package dev.notune.transcribe;

import android.inputmethodservice.InputMethodService;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.TypedValue;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.content.res.ColorStateList;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import java.io.File;
import java.io.IOException;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;

public class RustInputMethodService extends InputMethodService {
    
    private static final String TAG = "OfflineVoiceInput";

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("android_transcribe_app");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    private TextView statusView;
    private TextView hintView;
    private View recordContainer;
    private android.widget.ImageView micIcon;
    private ProgressBar progressBar;
    private View backspaceButton;
    private View spaceButton;
    private View enterButton;
    private View switchKeyboardButton;
    private View inputView;
    private MicLevelView micLevelView;
    private View recordCircle;
    private TranscriptHistory history;
    private View mainArea;
    private View historyButton;
    private View historyPanel;
    private LinearLayout historyList;
    private TextView historyRetry;
    private TextView historyClear;
    private View historyEmpty;
    private boolean historyOpen;
    private boolean clearArmed;
    private final Runnable checkHistoryWorker = () -> {
        if (historyOpen) {
            recoverIfWorkerGone();
            updateHistoryActions();
        }
    };
    // Night flag the current input view was inflated with, so it can be rebuilt
    // if the theme preference changes while this process stays alive.
    private boolean viewIsNight = false;
    private Handler mainHandler;
    private boolean isRecording = false;
    // True from the moment capture is stopped until the session ends (text
    // delivered, canceled or failed). Set synchronously in finishCapture():
    // the "Transcribing..." status only arrives later from the worker thread,
    // and a model/language switch must not slip through that gap.
    private boolean isTranscribing = false;
    // A model/language switch tapped while recording or transcribing. Lives on
    // the service (not the view) so it survives onCreateInputView rebuilds.
    private final PendingSwitchState pendingSwitch = new PendingSwitchState();
    private QuickSwitchBarHelper quickSwitchBar;
    private final QuickSwitchBarHelper.Host quickSwitchHost = new QuickSwitchBarHelper.Host() {
        @Override
        public boolean shouldDeferSwitch() {
            return isRecording || isTranscribing;
        }

        @Override
        public boolean discardRecordingForSwitch() {
            return discardCapture();
        }

        @Override
        public void onSelectionApplied(boolean modelChanged, boolean langChanged) {
            // Reload (or just update the language of) the engine; progress
            // comes back through onStatusUpdate.
            if (modelChanged || langChanged) {
                try {
                    EngineBridge.reloadEngine(RustInputMethodService.this);
                } catch (Throwable t) {
                    Log.e(TAG, "reloadEngine failed", t);
                }
            }
            updatePendingHint();
        }

        @Override
        public void onSwitchTapped() {
            // Like opening the keyboard: with auto-record on, a chip switch
            // starts listening in the new language. Posted so it runs after
            // the "Canceled" status of a just-discarded recording, which would
            // otherwise mark the new recording as stopped.
            if (!isAutoRecordEnabled()) return;
            mainHandler.post(() -> {
                if (windowVisible
                        && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                                == PackageManager.PERMISSION_GRANTED
                        && ModelStore.hasAnyModel(RustInputMethodService.this)) {
                    startListening();
                }
            });
        }

        @Override
        public void onPendingChanged() {
            updatePendingHint();
        }

        @Override
        public void onManageModelsClicked() {
            startActivity(new Intent(RustInputMethodService.this, ModelsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    };
    private boolean pendingSwitchBack = false;
    private String lastStatus = "Initializing...";
    // Key repeat settings
    private static final long REPEAT_INITIAL_DELAY = 400; // ms before repeat starts
    private static final long REPEAT_INTERVAL = 50; // ms between repeats
    private Runnable backspaceRepeatRunnable;
    private Runnable spaceRepeatRunnable;
    private final AudioFocusPauser audioPauser = new AudioFocusPauser();
    private boolean pauseAudioActive = false;
    // Whether an editor is currently focused/started for input. Tracked via
    // onStartInput/onFinishInput because getCurrentInputConnection() returns a
    // non-null no-op connection when nothing is focused, so commitText would be
    // silently dropped.
    private boolean inputActive = false;
    // Whether the keyboard window is currently on screen. Some frameworks
    // (notably OEM builds) call onWindowShown again for events that don't
    // follow an onWindowHidden, e.g. tapping the text area to move the
    // cursor while the keyboard stays visible. Auto-record must only fire on
    // a genuine hidden -> shown transition, or a cursor tap starts a
    // recording the user never asked for.
    private boolean windowVisible = false;
    // Transcribed text waiting to be committed because no editor was focused
    // when transcription finished. This happens on long transcribes where the
    // target field (e.g. a web field in Firefox/Gemini) drops focus while we
    // process audio. Flushed from onStartInputView once a field is focused
    // again so the text is never lost.
    private String pendingCommitText = null;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        history = new TranscriptHistory(new File(getFilesDir(), TranscriptHistory.FILE_NAME));
        Log.d(TAG, "Service onCreate");
        try {
            initNative(this);
        } catch (Throwable t) {
            // Native may be unavailable (e.g. wrong-ABI emulator); don't crash the IME.
            Log.e(TAG, "Error in initNative", t);
        }
    }

    @Override
    public View onCreateInputView() {
        Log.d(TAG, "onCreateInputView");
        try {
            // The IME is a non-AppCompat Service, so AppCompat's delegate can't
            // theme it. Build a context that is night-aware (per the saved
            // preference), wears the Material 3 theme, and picks up Material
            // You dynamic color — matching the app.
            Context night = ThemePrefs.wrapForNight(this, ThemePrefs.getMode(this));
            viewIsNight = ThemePrefs.isNight(night);
            Context themed = DynamicColors.wrapContextIfAvailable(
                    new ContextThemeWrapper(night, R.style.AppTheme));
            View view = LayoutInflater.from(themed).inflate(R.layout.ime_layout, null);
            inputView = view;

            // Keep the keys above the navigation bar: pad the bottom by its
            // height. When the keyboard starts cold, Android 16 can size the
            // window from a layout pass that ran before these insets arrived and
            // keep that size, cutting off the bottom row; lay out again once the
            // padding changes so the window grows to fit it.
            int basePaddingBottom = view.getPaddingBottom();
            view.setOnApplyWindowInsetsListener((v, insets) -> {
                int paddingBottom = basePaddingBottom + insets.getSystemWindowInsetBottom();
                if (v.getPaddingBottom() != paddingBottom) {
                    v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), paddingBottom);
                    v.post(v::requestLayout);
                }
                return insets;
            });

            statusView = view.findViewById(R.id.ime_status_text);
            progressBar = view.findViewById(R.id.ime_progress);
            recordContainer = view.findViewById(R.id.ime_record_container);
            micIcon = view.findViewById(R.id.ime_mic_icon);
            micLevelView = view.findViewById(R.id.ime_mic_level);
            recordCircle = view.findViewById(R.id.ime_record_circle);
            hintView = view.findViewById(R.id.ime_hint);
            backspaceButton = view.findViewById(R.id.ime_backspace);
            spaceButton = view.findViewById(R.id.ime_space);
            enterButton = view.findViewById(R.id.ime_enter);
            switchKeyboardButton = view.findViewById(R.id.ime_switch_keyboard);
            quickSwitchBar = new QuickSwitchBarHelper(view, pendingSwitch, quickSwitchHost);
            mainArea = view.findViewById(R.id.ime_main_area);
            historyButton = view.findViewById(R.id.ime_history_button);
            historyPanel = view.findViewById(R.id.ime_history_panel);
            historyList = view.findViewById(R.id.history_list);
            historyRetry = view.findViewById(R.id.history_retry);
            historyClear = view.findViewById(R.id.history_clear);
            historyEmpty = view.findViewById(R.id.history_empty);
            historyButton.setOnClickListener(v -> setHistoryOpen(!historyOpen));
            view.findViewById(R.id.history_close).setOnClickListener(v -> setHistoryOpen(false));
            historyRetry.setOnClickListener(v -> retryFromHistory());
            historyClear.setOnClickListener(v -> clearHistory());
            setHistoryOpen(false);
            historyButton.setEnabled(!isRecording);

            // Tap: leave right away; a running recording is thrown away, not
            // transcribed. Long press: transcribe it first, then leave.
            switchKeyboardButton.setOnClickListener(v -> {
                discardCapture();
                switchAwayFromVoiceKeyboard();
            });
            switchKeyboardButton.setOnLongClickListener(v -> {
                if (isRecording) {
                    pendingSwitchBack = true;
                    finishCapture();
                } else {
                    switchAwayFromVoiceKeyboard();
                }
                return true;
            });

            // Key repeat runnable for backspace
            backspaceRepeatRunnable = new Runnable() {
                @Override
                public void run() {
                    deleteBackward();
                    mainHandler.postDelayed(this, REPEAT_INTERVAL);
                }
            };

            // Key repeat runnable for space
            spaceRepeatRunnable = new Runnable() {
                @Override
                public void run() {
                    insertSpace();
                    mainHandler.postDelayed(this, REPEAT_INTERVAL);
                }
            };

            // Touch drives the key repeat; the click listeners serve
            // activation without touch (TalkBack, Switch Access, Voice Access),
            // which calls performClick and never sends touch events.
            backspaceButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        deleteBackward();
                        mainHandler.postDelayed(backspaceRepeatRunnable, REPEAT_INITIAL_DELAY);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mainHandler.removeCallbacks(backspaceRepeatRunnable);
                        return true;
                }
                return false;
            });
            backspaceButton.setOnClickListener(v -> deleteBackward());

            spaceButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        insertSpace();
                        mainHandler.postDelayed(spaceRepeatRunnable, REPEAT_INITIAL_DELAY);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mainHandler.removeCallbacks(spaceRepeatRunnable);
                        return true;
                }
                return false;
            });
            spaceButton.setOnClickListener(v -> insertSpace());

            enterButton.setOnClickListener(v -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    android.view.inputmethod.EditorInfo editorInfo = getCurrentInputEditorInfo();
                    if (editorInfo == null) {
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER));
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER));
                        return;
                    }
                    int imeOptions = editorInfo.imeOptions;
                    int action = imeOptions & android.view.inputmethod.EditorInfo.IME_MASK_ACTION;
                    boolean noEnterAction = (imeOptions & android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;

                    // If the editor flags IME_FLAG_NO_ENTER_ACTION (e.g. multi-line fields in
                    // messaging apps like Signal), or if there's no meaningful action, insert a
                    // newline. Otherwise perform the editor action (Go, Search, Send, etc.).
                    if (!noEnterAction && (
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT)) {
                        ic.performEditorAction(action);
                    } else {
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER));
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER));
                    }
                }
            });

            recordContainer.setOnClickListener(v -> {
                // Stuck on "Processing" with no worker left: reset, and offer
                // Retry instead of recording. Never start a recording in this
                // same tap, or a new take would replace the stuck one's audio.
                if (recoverIfWorkerGone()) {
                    if (hasSavedRecording()) setHistoryOpen(true);
                    return;
                }
                if (isTranscribing || (!isRecording && lastStatus.contains("Waiting"))) return;

                // Check microphone permission
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    if (statusView != null) statusView.setText(R.string.ime_need_mic);
                    if (hintView != null) hintView.setText(R.string.ime_need_mic_hint);
                    return;
                }

                if (!isRecording && !ModelStore.hasAnyModel(this)) {
                    updateUiState();
                    return;
                }

                if (isRecording) {
                    finishCapture();
                } else {
                    startListening();
                }
            });

            tintRecordButton(false);
            updateUiState();
            return view;
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreateInputView", e);
            TextView errorView = new TextView(this);
            errorView.setText("Error loading keyboard: " + e.getMessage());
            return errorView;
        }
    }

    private void deleteBackward() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL));
            ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL));
        }
    }

    private void insertSpace() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.commitText(" ", 1);
        }
    }

    /**
     * Stops capture and hands the audio to transcription. Audio focus is
     * released right away, so paused media can resume while the (possibly
     * long) transcription runs.
     */
    private void finishCapture() {
        isTranscribing = true;
        stopRecording();
        releaseAudioFocus();
        updateRecordButtonUI(false);
    }

    /**
     * Stops a running recording without transcribing it. Returns false if
     * nothing was recording (e.g. the audio is already being transcribed,
     * which can't be aborted).
     */
    private boolean discardCapture() {
        if (!isRecording) return false;
        try {
            cancelRecording();
        } catch (Throwable t) {
            Log.w(TAG, "cancelRecording failed", t);
            return false;
        }
        isTranscribing = false;
        releaseAudioFocus();
        updateRecordButtonUI(false);
        if (statusView != null) statusView.setText(R.string.ime_recording_discarded);
        return true;
    }

    /** Starts a recording (pausing media if that setting is on). */
    private void startListening() {
        if (isRecording || isTranscribing) return;
        setHistoryOpen(false);
        if (isPauseAudioEnabled()) {
            audioPauser.request(this);
            pauseAudioActive = true;
        }
        startRecording();
        updateRecordButtonUI(true);
    }

    /** Auto-starts listening when the keyboard opens (and after a chip switch). */
    private boolean isAutoRecordEnabled() {
        return new File(getFilesDir(), "auto_record").exists();
    }

    private void releaseAudioFocus() {
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    /**
     * Leaves the voice keyboard. There is no previous keyboard when ours was
     * picked in the system settings (common on Android TV) or after the input
     * method history was reset; then fall back to the next enabled keyboard,
     * and finally to the system picker, so the button never does nothing.
     */
    private void switchAwayFromVoiceKeyboard() {
        if (switchToLastKeyboard() || switchToNextKeyboard()) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showInputMethodPicker();
    }

    // InputMethodService#switchToPreviousInputMethod / #switchToNextInputMethod
    // only exist since API 28; older versions take the window token.
    @SuppressWarnings("deprecation")
    private boolean switchToLastKeyboard() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return switchToPreviousInputMethod();
            }
            IBinder token = imeWindowToken();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            return token != null && imm != null && imm.switchToLastInputMethod(token);
        } catch (RuntimeException e) {
            Log.w(TAG, "switching to the previous keyboard failed", e);
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private boolean switchToNextKeyboard() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return switchToNextInputMethod(false);
            }
            IBinder token = imeWindowToken();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            return token != null && imm != null && imm.switchToNextInputMethod(token, false);
        } catch (RuntimeException e) {
            Log.w(TAG, "switching to the next keyboard failed", e);
            return false;
        }
    }

    private IBinder imeWindowToken() {
        android.app.Dialog window = getWindow();
        if (window == null || window.getWindow() == null) return null;
        return window.getWindow().getAttributes().token;
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        boolean wasVisible = windowVisible;
        windowVisible = true;
        if (quickSwitchBar != null) quickSwitchBar.refresh();
        if (isRecording) {
            // A background recording is still running (record-in-background
            // setting): restore the recording UI.
            updateRecordButtonUI(true);
            return;
        }
        if (wasVisible) {
            // Not a real hidden -> shown transition (e.g. a cursor tap in the
            // text area); never auto-start a recording from here.
            return;
        }
        if (isAutoRecordEnabled()
                && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) {
            startListening();
        }
    }

    @Override
    public void onWindowHidden() {
        setHistoryOpen(false);
        super.onWindowHidden();
        windowVisible = false;
        if (isRecording) {
            if (isStopOnHideEnabled()) {
                // Opt-in behavior: discard the recording when the keyboard hides.
                // If canceling fails and the fallback stop hands the audio to
                // transcription, that session is still running.
                boolean stillTranscribing = false;
                try {
                    cancelRecording();
                } catch (Throwable t) {
                    Log.w(TAG, "cancelRecording failed, falling back to stopRecording", t);
                    try {
                        stopRecording();
                        stillTranscribing = true;
                    } catch (Throwable ignored) { }
                }
                isTranscribing = stillTranscribing;
                updateRecordButtonUI(false);
                if (!stillTranscribing && quickSwitchBar != null) quickSwitchBar.flushPending();
            } else {
                // Default: keep recording in the background. The transcription
                // is committed on return (or held in pendingCommitText).
                return;
            }
        }
        releaseAudioFocus();
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        inputActive = isTextEditor(attribute);
    }

    /**
     * Since Android 11 the keyboard also gets onStartInput for windows without
     * any text field (e.g. the launcher after pressing Home, or a browser
     * between page focus changes), with a placeholder EditorInfo. Text
     * committed there is silently lost, so it doesn't count as an editor.
     * Terminal-style views that really use TYPE_NULL still have a view id.
     */
    private static boolean isTextEditor(EditorInfo info) {
        return info != null && !(info.inputType == android.text.InputType.TYPE_NULL
                && info.initialSelStart < 0 && info.fieldId == 0);
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        inputActive = isTextEditor(info);
        // Rebuild the keyboard if the theme preference changed while this
        // (long-lived) keyboard service stayed alive, so it matches the app
        // setting.
        if (inputView != null
                && ThemePrefs.isNight(ThemePrefs.wrapForNight(this, ThemePrefs.getMode(this))) != viewIsNight) {
            View rebuilt = onCreateInputView();
            setInputView(rebuilt);
            // The window already dispatched its insets to the old view; ask
            // for a new pass so the replacement gets its navigation-bar
            // padding (otherwise the bottom key row is cut off).
            rebuilt.requestApplyInsets();
        }
        if (quickSwitchBar != null) quickSwitchBar.refresh();
        // A field is focused and the input connection is live again — commit any
        // text that finished transcribing while nothing was focused.
        if (inputActive) flushPendingText();
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        inputActive = false;
    }

    private void updateRecordButtonUI(boolean recording) {
        isRecording = recording;
        if (recording) setHistoryOpen(false);
        if (historyButton != null) historyButton.setEnabled(!recording);
        // Keep the screen awake while recording so it never sleeps mid-capture
        // and cuts the recording short. Cleared automatically once we stop.
        if (inputView != null) {
            inputView.setKeepScreenOn(recording);
        }
        tintRecordButton(recording);
        if (recording) {
            statusView.setText(R.string.ime_listening);
            hintView.setText(R.string.ime_tap_to_stop);
        } else {
            statusView.setText(R.string.voice_processing);
            hintView.setText(R.string.ime_tap_to_record);
            if (micLevelView != null) micLevelView.setLevel(0f);
        }
        // Never let the generic hint overwrite the "Next: ..." one.
        applyPendingHint();
        if (historyOpen) updateHistoryActions();
    }

    private void setHistoryOpen(boolean open) {
        if (historyPanel == null || (open && isRecording)) return;
        historyOpen = open;
        mainArea.setVisibility(open ? View.GONE : View.VISIBLE);
        historyPanel.setVisibility(open ? View.VISIBLE : View.GONE);
        clearArmed = false;
        historyClear.setText(R.string.history_clear);
        mainHandler.removeCallbacks(checkHistoryWorker);
        if (open) refreshHistoryPanel();
    }

    /**
     * Resets a "Processing" state whose transcription worker is gone (it died
     * without a final callback). Returns true if it reset. The worker posts
     * all its callbacks before it stops counting as running, so by the time
     * this sees "not running" they are already queued ahead of any later tap.
     */
    private boolean recoverIfWorkerGone() {
        if (!isTranscribing || isRecording) return false;
        try {
            if (isTranscriptionRunning()) return false;
        } catch (Throwable t) {
            // If the native check is unavailable, keep the current session state.
            return false;
        }
        Log.w(TAG, "Resetting processing state with no transcription worker");
        isTranscribing = false;
        pendingSwitchBack = false;
        lastStatus = "Ready";
        releaseAudioFocus();
        if (quickSwitchBar != null) quickSwitchBar.flushPending();
        updateUiState();
        return true;
    }

    /** A saved recording that can be retried (more than a bare 44-byte WAV header). */
    private boolean hasSavedRecording() {
        File f = lastRecordingFile();
        return f.isFile() && f.length() > 44;
    }

    private void refreshHistoryPanel() {
        recoverIfWorkerGone();
        historyList.removeAllViews();
        long now = System.currentTimeMillis();
        for (TranscriptHistory.Entry entry : history.load()) {
            addHistoryRow(entry, now);
        }
        historyEmpty.setVisibility(historyList.getChildCount() == 0 ? View.VISIBLE : View.GONE);
        updateHistoryActions();
    }

    private void updateHistoryActions() {
        boolean busy = isRecording || isTranscribing;
        boolean hasRecording = hasSavedRecording();
        boolean canRetry = !busy && hasRecording;
        historyRetry.setEnabled(canRetry);
        historyRetry.setAlpha(canRetry ? 1f : 0.4f);
        boolean canClear = !busy && (hasRecording || historyList.getChildCount() > 0);
        historyClear.setEnabled(canClear);
        historyClear.setAlpha(canClear ? 1f : 0.4f);
        mainHandler.removeCallbacks(checkHistoryWorker);
        if (historyOpen && isTranscribing) {
            // JNI attachment can fail before it can send a terminal callback.
            mainHandler.postDelayed(checkHistoryWorker, 500);
        }
    }

    private void addHistoryRow(TranscriptHistory.Entry entry, long now) {
        Context context = historyList.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        int horizontalPadding = Math.round(8 * density);
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setMinimumHeight(Math.round(48 * density));
        row.setPaddingRelative(horizontalPadding, 0, horizontalPadding, 0);
        row.setFocusable(true);
        row.setContentDescription(entry.text);
        TypedValue background = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground,
                background, true)) {
            row.setBackgroundResource(background.resourceId);
        }
        row.setOnClickListener(v -> insertFromHistory(entry.text));

        TextView transcript = new TextView(context);
        transcript.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        transcript.setTextColor(MaterialColors.getColor(row, com.google.android.material.R.attr.colorOnSurface));
        transcript.setSingleLine();
        transcript.setEllipsize(TextUtils.TruncateAt.END);
        transcript.setText(entry.text);
        row.addView(transcript, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView time = new TextView(context);
        time.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        time.setTextColor(MaterialColors.getColor(row, com.google.android.material.R.attr.colorOnSurfaceVariant));
        time.setPaddingRelative(horizontalPadding, 0, 0, 0);
        time.setText(DateUtils.getRelativeTimeSpanString(entry.timeMillis, now, DateUtils.MINUTE_IN_MILLIS));
        row.addView(time);
        historyList.addView(row);
    }

    private void insertFromHistory(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (!inputActive || ic == null) {
            statusView.setText(R.string.history_no_field);
            return;
        }
        commitTranscribedText(ic, text + " ");
        setHistoryOpen(false);
    }

    private File lastRecordingFile() {
        return new File(getFilesDir(), TranscriptHistory.LAST_RECORDING_FILE);
    }

    private void retryFromHistory() {
        recoverIfWorkerGone();
        if (isRecording || isTranscribing || !hasSavedRecording()) {
            updateHistoryActions();
            return;
        }
        setHistoryOpen(false);
        isTranscribing = true;
        lastStatus = "Transcribing...";
        updateRecordButtonUI(false);
        updateUiState();
        try {
            retryLastRecording();
        } catch (Throwable t) {
            Log.e(TAG, "retryLastRecording failed", t);
            isTranscribing = false;
            lastStatus = "Ready";
            updateUiState();
        }
    }

    private void clearHistory() {
        if (isRecording || isTranscribing) return;
        if (!clearArmed) {
            clearArmed = true;
            historyClear.setText(R.string.history_clear_confirm);
            return;
        }
        history.clear();
        File recording = lastRecordingFile();
        if (recording.exists() && !recording.delete()) {
            Log.w(TAG, "Could not delete last recording");
        }
        File partial = new File(getFilesDir(), TranscriptHistory.LAST_RECORDING_FILE + ".tmp");
        if (partial.exists() && !partial.delete()) {
            Log.w(TAG, "Could not delete partial recording");
        }
        clearArmed = false;
        historyClear.setText(R.string.history_clear);
        refreshHistoryPanel();
    }

    /** Shows "Next: <Model> · <Lang>" in the hint while a switch is queued during a session. */
    private boolean applyPendingHint() {
        if ((isRecording || isTranscribing) && hintView != null
                && quickSwitchBar != null && quickSwitchBar.hasPending()) {
            hintView.setText(getString(R.string.quick_next_prefix, quickSwitchBar.getPendingSummary()));
            return true;
        }
        return false;
    }

    /** Re-evaluates the hint after the queued switch changed or was applied. */
    private void updatePendingHint() {
        if (hintView == null || applyPendingHint()) return;
        if (isRecording) {
            hintView.setText(R.string.ime_tap_to_stop);
        } else {
            // Only the hint: updateUiState() would re-derive the status line
            // from the last engine status and undo what the caller just set.
            hintView.setText(ModelStore.hasAnyModel(this)
                    ? R.string.ime_tap_to_record : R.string.ime_no_model_hint);
        }
    }

    /** Tints the round record button + mic: idle = primary, recording = error. */
    private void tintRecordButton(boolean recording) {
        int circleAttr = recording
                ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorPrimaryContainer;
        int iconAttr = recording
                ? com.google.android.material.R.attr.colorOnPrimary
                : com.google.android.material.R.attr.colorOnPrimaryContainer;
        if (recordCircle != null) {
            recordCircle.setBackgroundTintList(ColorStateList.valueOf(
                    MaterialColors.getColor(recordCircle, circleAttr)));
        }
        if (micIcon != null) {
            micIcon.setColorFilter(MaterialColors.getColor(micIcon, iconAttr));
        }
    }

    @Override
    public void onDestroy() {
        setHistoryOpen(false);
        super.onDestroy();
        cleanupNative(this);
        releaseAudioFocus();
    }

    // Native methods
    private native void initNative(RustInputMethodService service);
    private native void cleanupNative(RustInputMethodService self);
    private native void startRecording();
    private native void stopRecording();
    private native void retryLastRecording();
    private native boolean isTranscriptionRunning();
    private native void cancelRecording();

    // Called from Rust
    public void onStatusUpdate(String status) {
        mainHandler.post(() -> {
            if (status == null) return;
            Log.d(TAG, "Status: " + status);
            lastStatus = status;
            if (ModelStore.isTerminalSessionStatus(status, isRecording, isTranscribing)) {
                if (isRecording) updateRecordButtonUI(false);
                isTranscribing = false;
                if (quickSwitchBar != null) quickSwitchBar.flushPending();
                if (pendingSwitchBack && status.startsWith("Error")) {
                    pendingSwitchBack = false;
                    switchAwayFromVoiceKeyboard();
                }
            }
            updateUiState();
            if (historyOpen) updateHistoryActions();
            // A warm-up error mid-recording must not resume the paused media.
            if (status.startsWith("Error") && !isRecording) {
                releaseAudioFocus();
            }
        });
    }

    private void updateUiState() {
        boolean isWaiting = lastStatus.contains("Waiting");
        boolean statusTranscribing = isTranscribing || lastStatus.contains("Transcribing")
                || lastStatus.contains("Processing");
        boolean isError = lastStatus.startsWith("Error");
        boolean noModel = !isRecording && !ModelStore.hasAnyModel(this);

        // Don't show internal loading states to the user
        if (statusView != null && !isRecording) {
            if (isError && !lastStatus.equals(ModelStore.NO_MODEL_STATUS)) {
                statusView.setText(lastStatus);
            } else if (statusTranscribing || isWaiting) {
                statusView.setText(R.string.voice_processing);
            } else if (noModel) {
                statusView.setText(R.string.no_model_title);
            } else if (lastStatus.equals("Canceled")) {
                statusView.setText(R.string.ime_recording_discarded);
            } else {
                statusView.setText(R.string.ime_tap_to_record);
            }
        }

        // Hide progress bar - don't expose model loading to user
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
        }

        // Keep taps available so a dead worker can be detected from Processing.
        if (recordContainer != null) {
            boolean busy = !isRecording && (statusTranscribing || isWaiting);
            recordContainer.setAlpha(busy ? 0.5f : 1.0f);
        }

        if (hintView != null && !applyPendingHint() && !isRecording) {
            hintView.setText(noModel ? R.string.ime_no_model_hint : R.string.ime_tap_to_record);
        }
    }

    // Called from Rust
    public void onTextTranscribed(String text) {
        mainHandler.post(() -> {
            isTranscribing = false;
            lastStatus = "Ready";
            if (text == null || text.trim().isEmpty()) {
                // Nothing recognized — don't insert a stray space.
                updateRecordButtonUI(false);
                updateUiState();
                if (statusView != null) statusView.setText(R.string.voice_no_speech);
                releaseAudioFocus();
                if (quickSwitchBar != null) quickSwitchBar.flushPending();
                if (pendingSwitchBack) {
                    pendingSwitchBack = false;
                    switchAwayFromVoiceKeyboard();
                }
                return;
            }
            try {
                history.add(text.trim(), System.currentTimeMillis());
            } catch (IOException e) {
                Log.w(TAG, "Could not save transcript history", e);
            }
            if (historyOpen) refreshHistoryPanel();
            String committed = text + " ";
            InputConnection ic = getCurrentInputConnection();
            if (inputActive && ic != null) {
                commitTranscribedText(ic, committed);
            } else {
                // No editor is focused right now (common on long transcribes where
                // a web field in Firefox/Gemini dropped focus while we processed
                // audio). Committing now would be silently dropped, so defer the
                // text until a field is focused again instead of losing it.
                pendingCommitText = committed;
            }
            releaseAudioFocus();
            updateRecordButtonUI(false);
            updateUiState();
            if (statusView != null) statusView.setText(R.string.ime_tap_to_record);
            if (quickSwitchBar != null) quickSwitchBar.flushPending();
            if (pendingSwitchBack) {
                pendingSwitchBack = false;
                switchAwayFromVoiceKeyboard();
            }
        });
    }

    // Commits transcribed text into the active input connection, optionally
    // selecting it afterwards (select_transcription setting).
    private void commitTranscribedText(InputConnection ic, String committed) {
        // Keep the dictation apart from a word right before the cursor
        // ("hello wor" + "Weather" must not become "hello worWeather").
        CharSequence before = ic.getTextBeforeCursor(1, 0);
        if (before != null && before.length() > 0
                && !Character.isWhitespace(before.charAt(before.length() - 1))) {
            committed = " " + committed;
        }
        ic.commitText(committed, 1);

        if (!pendingSwitchBack && new File(getFilesDir(), "select_transcription").exists()) {
            android.view.inputmethod.ExtractedText et = ic.getExtractedText(
                new android.view.inputmethod.ExtractedTextRequest(), 0);
            if (et != null) {
                int end = et.startOffset + et.selectionStart;
                int start = end - committed.length();
                if (start >= 0) {
                    ic.setSelection(start, end);
                }
            }
        }
    }

    // Commits text that finished transcribing while no field was focused. Called
    // from onStartInputView when an editor (and a live input connection) is
    // available again.
    private void flushPendingText() {
        if (pendingCommitText == null) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            commitTranscribedText(ic, pendingCommitText);
            pendingCommitText = null;
        }
    }
    public void onAudioLevel(float level) {
        if (micLevelView != null) {
            mainHandler.post(() -> micLevelView.setLevel(level));
        }
    }

    private boolean isPauseAudioEnabled() {
        return new File(getFilesDir(), "pause_audio").exists();
    }

    /** "Record in background" is default ON; the marker file is the opt-out. */
    private boolean isStopOnHideEnabled() {
        return new File(getFilesDir(), "stop_on_hide").exists();
    }
}
