# Keyboard Transcript History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A history button on the voice keyboard listing the last 5 transcripts (tap to insert) plus "Retry last recording", and a fix for the keyboard getting stuck on "Processing".

**Architecture:** Rust saves each keyboard recording to `filesDir/last_recording.wav` from the transcription worker thread before transcribing, and gains a JNI entry that re-transcribes that file through the same worker path. Java keeps the transcript history in a small plain-text file via a pure-Java store class (JVM unit-tested), and the keyboard shows it in a panel that temporarily replaces the chip bar + record area (same height).

**Tech Stack:** Java 8 Android (InputMethodService, Material 3 views), Rust (`jni` 0.21, cargo-ndk), JUnit 4 local tests.

**Spec:** `docs/superpowers/specs/2026-11-23-transcript-history-design.md`

## Global Constraints

- Work in the main checkout on branch `feat/quick-model-switcher`, on top of the existing uncommitted changes. Do not revert, stash or reformat them. **Do not commit** (the user has not asked for commits); skip every "Commit" step.
- History: max **5** entries, newest first, saved **before** the text is committed to the field.
- Recording file: `last_recording.wav`, 16 kHz, mono, 16-bit PCM WAV, in the app's `filesDir`, replaced by each new keyboard recording; canceled/discarded recordings never touch it. Keyboard (`RustInputMethodService`) only — `RecognizeActivity` must not write it.
- Java 8 source level; no new dependencies. Do not use `org.json` in classes under unit test (`unitTests.isReturnDefaultValues = true` makes Android stubs return null).
- Every new user-visible string goes into `values/strings.xml` and all 6 locales: `values-de`, `values-es`, `values-fr`, `values-it`, `values-pt`, `values-ru`.
- Status strings sent from Rust stay English, like the existing ones.
- SSD rule for agents: avoid unnecessary rebuilds or cache deletion; reuse `target/` and Gradle caches.

## Review Focus

1. Transcription fails with an error status not in today's list (e.g. `Error: transcription failed unexpectedly, please try again`) → keyboard must return to idle (mic tappable, queued chip switch applied). Test in Task 3.
2. Retry tapped when the file is missing or corrupt → a clear error status, keyboard returns to idle, never stuck. Tests in Task 1 (decode rejects garbage) + Task 2 (status is `Error: no saved recording`, which Task 3 makes terminal).
3. Transcript text containing newlines, tabs or backslashes → stored and restored unchanged. Test in Task 4.
4. Same text transcribed twice (e.g. a retry) → one entry, moved to the top, not a duplicate. Test in Task 4.
5. Tapping a history entry with no text field focused → nothing is lost or sent to a dead connection; a hint tells the user to focus a field. Covered in Task 5 manual check.

---

### Task 1: Rust WAV save/load for the last recording

**Files:**
- Create: `src/last_recording.rs`
- Modify: `src/lib.rs` (add `pub mod last_recording;`)

**Interfaces:**
- Produces:
  - `pub const FILE_NAME: &str = "last_recording.wav";`
  - `pub fn encode_wav(samples: &[f32]) -> Vec<u8>` — 44-byte canonical header (RIFF/WAVE, fmt PCM=1, channels 1, 16000 Hz, 16 bits) + s16le data; samples clamped to [-1, 1] and scaled by 32767.
  - `pub fn decode_wav(bytes: &[u8]) -> Option<Vec<f32>>` — accepts only that exact format (walk chunks to find `fmt ` and `data`); s16 / 32768.0 → f32. `None` for anything else.
  - `pub fn save(path: &std::path::Path, samples: &[f32]) -> std::io::Result<()>` — write to `<path>.tmp`, then `rename` over `path`.
  - `pub fn load(path: &std::path::Path) -> Option<Vec<f32>>` — read + `decode_wav`; `None` if missing, unreadable or empty.

- [ ] **Step 1: Write tests** in a `#[cfg(test)] mod tests` in `src/last_recording.rs`:
  - `roundtrip_preserves_samples`: `[0.0, 0.5, -0.5, 1.0, -1.0]` → encode → decode; each within `1.0/32767.0 + 1e-6`.
  - `clamps_out_of_range`: `[2.0, -3.0]` decodes to values ≈ `1.0` and ≈ `-1.0` (within 1e-3).
  - `header_is_16k_mono_16bit`: bytes 0..4 = `b"RIFF"`, 8..12 = `b"WAVE"`, u16 LE at 22 = 1, u32 LE at 24 = 16000, u16 LE at 34 = 16, encoded length = 44 + 2 * n.
  - `rejects_garbage`: `decode_wav(b"hello")`, `decode_wav(&[])` and a valid header with sample rate patched to 44100 all return `None`.
  - `save_then_load`: in `std::env::temp_dir()` + unique name; `load` equals decoded samples; no `.tmp` file left; `load` of a missing path is `None`.
- [ ] **Step 2: Run** (the crate itself can't build on the host: `cpal` needs ALSA dev files that aren't installed, so test the std-only file standalone): `rustc --edition 2021 --test src/last_recording.rs -o "$SCRATCH/lr_test" && "$SCRATCH/lr_test"` where `$SCRATCH` is a temp dir outside the repo → FAIL (functions missing).
- [ ] **Step 3: Implement** the functions above, **std only, no `crate::` or `log` imports** (so the standalone test compiles). `save` uses a unique tmp name (`<path>.<pid>-<counter>.tmp`, counter from a static `AtomicU64`) so two workers never write the same tmp file.
- [ ] **Step 4: Run** the same command → PASS.

### Task 2: Rust session — save on stop, retry entry, always-terminal worker

**Files:**
- Modify: `src/voice_session.rs` (`stop_recording`, new `transcribe_saved`, shared worker)
- Modify: `src/ime.rs` (`stopRecording` passes the save path; new `retryLastRecording`)
- Modify: `src/recognize.rs` (pass `None`)

**Interfaces:**
- Consumes: Task 1 `last_recording::{FILE_NAME, save, load}`; `crate::assets::files_dir(&mut JNIEnv, &JObject) -> anyhow::Result<PathBuf>`.
- Produces:
  - `pub fn stop_recording(env: JNIEnv, state: &mut VoiceSessionState, save_to: Option<PathBuf>)`
  - `pub fn transcribe_saved(env: JNIEnv, state: &mut VoiceSessionState, path: PathBuf)`
  - JNI: `Java_dev_notune_transcribe_RustInputMethodService_retryLastRecording(env, _class)` ↔ Java `private native void retryLastRecording();`
  - Rust status `"Error: no saved recording"` when the file is missing/invalid.

- [ ] **Step 1:** Extract the body of the thread spawned in `stop_recording` into `fn spawn_transcription(jvm: Arc<JavaVM>, target_ref: GlobalRef, source: AudioSource)` where `enum AudioSource { Buffer(Vec<f32>, Option<PathBuf>), Saved(PathBuf) }`. In the thread, first: `Buffer(b, Some(p))` → `last_recording::save(&p, &b)` (log a warning on error, continue); `Saved(p)` → `load(&p)`, on `None` notify `"Error: no saved recording"` and return. Then the existing ensure-loaded + transcribe logic.
- [ ] **Step 2:** Make every exit of the worker end in a status or text:
  - Call `engine::ensure_loaded_from_thread(&jvm, &target_ref)` directly in the worker (it already reports its own errors via `do_load`; do **not** add a second `"Error: …"`, it would read "Error: Error loading…"). On `Err`, return.
  - Wrap the whole worker body (save/load, ensure-loaded, `get_engine`, transcribe, notify) in `std::panic::catch_unwind(AssertUnwindSafe(..))`; on panic `log::error!` and notify `"Error: transcription failed unexpectedly, please try again"`.
  - If `attach_current_thread` fails, `log::error!` (nothing else possible).
- [ ] **Step 2b (engine.rs):** In `ensure_loaded_from_thread`'s owning branch, make the `LoadState::Loading` → `Done/Failed` transition panic-safe: a small drop guard struct that, if dropped without being disarmed, sets the state to `Failed("load panicked")` and `notify_all`s. Today a panic in `do_load` leaves `Loading` forever and every later load waits forever (engine.rs owning branch ~572-588).
- [ ] **Step 2c (running-worker counter):** `static RUNNING: AtomicUsize` in `voice_session.rs`; increment on the calling (JNI) thread **before** `thread::spawn` (so Java never sees "not running" right after `stopRecording()` returns; if spawn fails, decrement), decrement via a drop guard at worker end (covers panics). `pub fn transcription_running() -> bool`. JNI `Java_dev_notune_transcribe_RustInputMethodService_isTranscriptionRunning(env, _this) -> jboolean` ↔ Java `private native boolean isTranscriptionRunning();`.
- [ ] **Step 3:** `stop_recording(env, state, save_to)`: unchanged empty-buffer guard (no save); then notify `"Transcribing..."` and `spawn_transcription(.., AudioSource::Buffer(buffer, save_to))`. `transcribe_saved(env, state, path)`: notify `"Transcribing..."`, then `spawn_transcription(.., AudioSource::Saved(path))`. `recognize.rs` passes `None`.
- [ ] **Step 4:** In `ime.rs` (the JNI second param is the Java `this` object even though it is named `_class`; rename it `this: JObject`), `stopRecording` computes `crate::assets::files_dir(&mut env, state.target_ref.as_obj()).ok().map(|d| d.join(last_recording::FILE_NAME))` (needs `mut env`; returns an owned PathBuf so `env` can be passed on) and passes it. New `retryLastRecording(env, this)` does the same lookup and calls `transcribe_saved`; if the path can't be resolved, call `onStatusUpdate("Error: no saved recording")` on `this`. In `stopRecording` and `retryLastRecording`, when `IME_STATE` is `None`, call `onStatusUpdate("Error: keyboard engine not initialized")` on `this` (clear any pending exception) instead of silently doing nothing.
- [ ] **Step 5: Verify:** `./gradlew :app:compileFdroidDebugJavaWithJavac -PtargetAbis=arm64-v8a` (runs `cargoNdkBuild` via `preBuild`; fdroid avoids re-hashing the 1.1 GB model) succeeds with no new Rust warnings; Task 1 tests still pass.

### Task 3: Any transcription error ends the session (stuck-processing fix)

**Files:**
- Modify: `app/src/main/java/dev/notune/transcribe/ModelStore.java:309-322` (`isTranscriptionFailureStatus`)
- Test: `app/src/test/java/dev/notune/transcribe/ModelStoreTest.java`

**Interfaces:**
- Produces: `isTranscriptionFailureStatus(status)` = `status != null && (status.equals("Canceled") || status.equals(NO_MODEL_STATUS) || (status.startsWith("Error") && !status.contains("using built-in model")))`. `isRecordingFailureStatus` unchanged (warm-up errors while recording must still not stop it).

- [ ] **Step 1: Add test** `terminal_anyErrorEndsTranscription`:
  ```java
  assertTrue(ModelStore.isTerminalSessionStatus("Error: transcription failed unexpectedly, please try again", false, true));
  assertTrue(ModelStore.isTerminalSessionStatus("Error: no saved recording", false, true));
  assertTrue(ModelStore.isTerminalSessionStatus("Error: CPU lacks dotprod", false, true));
  assertFalse(ModelStore.isTerminalSessionStatus("Error: transcription failed unexpectedly, please try again", true, false));
  ```
- [ ] **Step 2: Run** `./gradlew :app:testFdroidDebugUnitTest --tests dev.notune.transcribe.ModelStoreTest -PtargetAbis=arm64-v8a` → new test FAILS, others pass.
- [ ] **Step 3: Implement** the rule above; update the Javadoc to say why (any error ends the attempt, so the keyboard can't stay "busy" forever).
- [ ] **Step 4: Run** the same command → all PASS (existing `terminal_builtinFallbackNoticeNeverEndsSession` and `terminal_warmUpErrorsDoNotEndRecording` must still pass unchanged).

### Task 4: `TranscriptHistory` store

**Files:**
- Create: `app/src/main/java/dev/notune/transcribe/TranscriptHistory.java`
- Test: `app/src/test/java/dev/notune/transcribe/TranscriptHistoryTest.java`

**Interfaces:**
- Produces:
  ```java
  public final class TranscriptHistory {
      public static final String FILE_NAME = "transcript_history.txt";
      public static final String LAST_RECORDING_FILE = "last_recording.wav"; // must equal Rust last_recording::FILE_NAME
      public static final int MAX_ENTRIES = 5;
      public static final class Entry { public final String text; public final long timeMillis; }
      public TranscriptHistory(java.io.File file);
      public List<Entry> load();                   // newest first; missing file or bad lines -> skipped
      public void add(String text, long timeMillis) throws IOException; // trims; ignores null/blank; removes an identical existing entry, inserts at top, keeps MAX_ENTRIES
      public void clear();                          // deletes the file
  }
  ```
  File format: UTF-8, one entry per line, `<timeMillis>\t<escaped text>`; escape `\` → `\\`, newline → `\n`, CR → `\r`, tab → `\t`. Write to `<file>.tmp` then rename.

- [ ] **Step 1: Write tests** (JUnit 4 + `TemporaryFolder`, like `ModelStoreTest`):
  - `empty_whenNoFile`: `load()` is empty.
  - `add_newestFirst`: add "a"@1, "b"@2 → texts `[b, a]`, times `[2, 1]`.
  - `cap_keepsFiveNewest`: add "1".."7" → `[7,6,5,4,3]`.
  - `ignoresBlank`: add `null`, `""`, `"  \n"` → empty; add `"  hi  "` → `"hi"`.
  - `duplicate_movesToTop`: add a, b, a → `[a, b]`, size 2, top time = the latest.
  - `specialCharacters_roundTrip`: `"line1\nline2\ttab \\ back\r"` (after trim rules: put specials in the middle, e.g. `"a\nb\tc\\d\re"`) loads back identical.
  - `persistsAcrossInstances`: add with one instance, load with a new instance on the same file.
  - `skipsMalformedLines`: file content `"garbage\n5\tok\n"` → one entry "ok"@5.
  - `clear_removesAll`: after `clear()`, `load()` empty and file gone.
- [ ] **Step 2: Run** `./gradlew :app:testFdroidDebugUnitTest --tests dev.notune.transcribe.TranscriptHistoryTest -PtargetAbis=arm64-v8a` → FAIL (class missing).
- [ ] **Step 3: Implement** as specified (java.io only).
- [ ] **Step 4: Run** → PASS.

### Task 5: Keyboard UI — history button, panel, retry, clear

**Files:**
- Create: `app/src/main/res/drawable/ic_history.xml` (Material "history" vector, 24dp, white fill like `ic_close.xml`), `app/src/main/res/drawable/ic_refresh.xml` (Material "refresh")
- Create: `app/src/main/res/layout/ime_history_panel.xml`
- Modify: `app/src/main/res/layout/ime_layout.xml`
- Modify: `app/src/main/java/dev/notune/transcribe/RustInputMethodService.java`
- Modify: `app/src/main/res/values*/strings.xml` (7 files)

**Interfaces:**
- Consumes: Task 2 `native void retryLastRecording()`, `native boolean isTranscriptionRunning()`; Task 4 `TranscriptHistory`.

**Layout (my calls, keep):**
- Status row: after `ime_progress`, an `ImageView` `@+id/ime_history_button`, width 40dp, **height 40dp with `layout_marginTop="-8dp"` and `layout_marginBottom="-8dp"`** (net 24dp, the same as the gone ProgressBar's slot, so the status row — and the keyboard — does not get taller while the touch target stays 40dp; check the row height before/after in the layout, adjust the negative margins so the measured row height is unchanged), `ic_history` with `scaleType=centerInside` and 8dp padding, background `?attr/selectableItemBackgroundBorderless`, tint `?attr/colorOnSurfaceVariant`, clickable/focusable, `contentDescription=@string/history_button_desc`.
- Wrap the quick-switch `<include>` and `ime_record_container` in a vertical `LinearLayout` `@+id/ime_main_area`; add sibling `<include layout="@layout/ime_history_panel">` with `visibility="gone"`, overriding **both** `layout_width="match_parent"` and `layout_height="200dp"` on the include (Android ignores layout_* on an include otherwise) and also `200dp` on the panel root (= 48 + 8 + 144, so the keyboard height never changes). Showing the panel hides `ime_main_area`, and vice versa.
- `ime_history_panel.xml` (root `@+id/ime_history_panel`, vertical, clickable so taps never fall through): header row 40dp — `@+id/history_retry` (TextView chip using `bg_quick_chip`, `ic_refresh` start drawable, text `@string/history_retry`), flexible space, `@+id/history_clear` (text button `@string/history_clear`), `@+id/history_close` (ImageButton `ic_close`, desc `@string/history_close_desc`); below, a `ScrollView` filling the rest with `LinearLayout` `@+id/history_list`, and `@+id/history_empty` TextView (`@string/history_empty`, centered, gone when the list has entries).
- Each row (built in code, min height 48dp, `selectableItemBackground`): text single line, `ellipsize=end`, `textAppearanceBodyMedium`, `colorOnSurface`; at the end a relative time from `DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS)` in `textAppearanceBodySmall`, `colorOnSurfaceVariant`. Row contentDescription = the text.

**Strings (en; translate the same keys into de/es/fr/it/pt/ru):**
- `history_button_desc` "Transcript history"
- `history_retry` "Retry last recording"
- `history_clear` "Clear"
- `history_clear_confirm` "Tap again to clear"
- `history_close_desc` "Close history"
- `history_empty` "No transcripts yet"
- `history_no_field` "Tap a text field first"

**Service behavior:**
- [ ] **Step 1:** Fields: `TranscriptHistory history` (created in `onCreate` on `new File(getFilesDir(), TranscriptHistory.FILE_NAME)`), view refs for the button/panel/list/retry/clear, `boolean historyOpen`, `boolean clearArmed`. Find views in `onCreateInputView`; a rebuilt view starts closed (`historyOpen = false`).
- [ ] **Step 2:** `onTextTranscribed`: right after the blank-text early return, `history.add(text.trim(), System.currentTimeMillis())` in try/catch (log on `IOException`), before any commit; then `refreshHistoryPanel()` if open.
- [ ] **Step 3:** `setHistoryOpen(boolean open)`: toggles `ime_main_area` / panel visibility, resets `clearArmed` (label back to `history_clear`), and calls `refreshHistoryPanel()` when opening. History button toggles it; `history_close` closes it. `onWindowHidden` closes it **at the very top**, before the early return for a background recording. Starting a recording (`startListening`, and `updateRecordButtonUI(true)`) closes it, so the record button is always reachable while listening.
- [ ] **Step 4:** `recoverIfWorkerGone()`: if `isTranscribing && !isRecording && !isTranscriptionRunning()` (native, try/catch Throwable → treat as running), log a warning, set `isTranscribing = false`, `lastStatus = "Ready"`, `quickSwitchBar.flushPending()`, `updateUiState()`. Call it at the start of the record-button click handler (and treat the button as enabled for this check even if `updateUiState` disabled it while "Processing": move the `isEnabled()` early return after the recovery), at the start of the retry handler, and in `refreshHistoryPanel()`. This is the escape hatch when processing is stuck with no worker alive.
- [ ] **Step 4b:** `refreshHistoryPanel()`: rebuild rows from `history.load()`; show/hide `history_empty`; retry `setEnabled(..)` + alpha 1.0 / 0.4, enabled only when `!isRecording && !isTranscribing && new File(getFilesDir(), TranscriptHistory.LAST_RECORDING_FILE).exists()`. Call it from `onStatusUpdate` (after the terminal handling) and `updateRecordButtonUI` when the panel is open, so retry re-enables once the session ends.
- [ ] **Step 5:** Row tap → `insertFromHistory(String text)`: if `inputActive && getCurrentInputConnection() != null`, `commitTranscribedText(ic, text + " ")` and close the panel; otherwise set the status line to `@string/history_no_field` and keep the panel open.
- [ ] **Step 6:** Retry tap: `recoverIfWorkerGone()`, then re-check `!isRecording && !isTranscribing && file exists` inside the handler (return if not). Close the panel, `isTranscribing = true`, `updateRecordButtonUI(false)` (shows "Processing…"), `retryLastRecording()` in try/catch(Throwable → `isTranscribing = false`, log, `updateUiState()`). Result arrives through the existing `onTextTranscribed` / `onStatusUpdate` paths unchanged.
- [ ] **Step 7:** Clear tap: first tap sets `clearArmed = true` and label `history_clear_confirm`; second tap `history.clear()`, deletes `LAST_RECORDING_FILE`, `clearArmed = false`, refreshes.
- [ ] **Step 7b (pre-existing bug, same file):** in `onStatusUpdate`, the `pendingSwitchBack && status.startsWith("Error")` check fires on the "… - using built-in model" fallback notice and leaves the keyboard before the text arrives. Move it inside the `isTerminalSessionStatus(...)` branch.
- [ ] **Step 7c (privacy, backup):** add `app/src/main/res/xml/backup_rules.xml` (`<full-backup-content>`) and `app/src/main/res/xml/data_extraction_rules.xml` (`<data-extraction-rules>` with `<cloud-backup>` and `<device-transfer>`), each only **excluding** `domain="file" path="last_recording.wav"`, `path="transcript_history.txt"` (plus the `.tmp` variants are short-lived, ignore). Reference them from `<application>` in `AndroidManifest.xml` with `android:fullBackupContent="@xml/backup_rules"` and `android:dataExtractionRules="@xml/data_extraction_rules"`. Everything else keeps its current (default) backup behavior.
- [ ] **Step 8: Verify:** `./gradlew :app:testFdroidDebugUnitTest :app:assembleFdroidDebug :app:lintFdroidDebug -PtargetAbis=arm64-v8a,x86_64` succeeds (adding x86_64 means a from-scratch ggml build for that ABI; expected, a few minutes), lint has no new errors (MissingTranslation in particular).

### Task 6: Docs

**Files:**
- Modify: `README.md` (feature list: one bullet "History button on the keyboard: last 5 transcripts, tap to insert, retry the last recording"), `fastlane/metadata/android/en-US/full_description.txt` (same, one line, matching its style).

- [ ] **Step 1:** Add the lines; keep wording consistent with neighboring bullets.

### Task 7 (orchestrator): Emulator check

- [ ] Install the x86_64 debug build on AVD `medium_phone` (started with `-no-snapshot`), enable the keyboard, dictate (or retry), confirm: entry appears in history, tap inserts into a text field, retry produces text, clear empties the list, keyboard height unchanged with the panel open; then close the app, `device_close`, and verify `qemu-system` is gone.
