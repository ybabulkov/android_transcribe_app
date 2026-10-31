# Quick Model & Language Switcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a 1-tap Model and Language (`BG`/`EN`) chip bar to Offline Voice Input's dedicated voice keyboard (`RustInputMethodService`) and voice popup (`RecognizeActivity`) with safe deferred switching during active recording and side-by-side installation for the owner's Samsung Galaxy S26.

**Architecture:** Extend `ModelStore` (pure Java, no static `System.loadLibrary`) with language persistence (`model_language`, `last_explicit_language`), concise GGUF display naming, and a Canary explicit-language safeguard (`bg-BG` / `en-US`). Add `EngineBridge` for JNI reload calls with a safe default status sink, `PendingSwitchState` for host-owned queued state across view rebuilds, and `QuickSwitchBarHelper` for rendering the 1-tap `48dp` chip bar using the view's themed `Context`. Update `src/engine.rs` so `reset_if_stale()` updates `Engine.language` in-place under the engine mutex without reloading the GGUF file when only the language changed.

**Tech Stack:** Android (Java 8, Material 3 / AppCompat, `minSdk = 26`, `targetSdk = 36`), Rust (`jni`, `transcribe-cpp` 0.4.0), JUnit 4 (local JVM unit tests with `isReturnDefaultValues = true`).

**Spec:** `docs/superpowers/specs/2026-10-31-quick-model-switcher-design.md`

## Global Constraints

- **Package & JNI Compatibility:** `applicationId = "dev.notune.transcribe.mod"` in `app/build.gradle.kts`, while `namespace = "dev.notune.transcribe"` and Java package `dev.notune.transcribe` MUST remain unchanged so JNI function names (`Java_dev_notune_transcribe_...`) do not change.
- **Side-by-Side Labels:** App, IME, and recognition service labels must be `"Offline Voice Input (Mod)"` so the modified app is clearly distinguishable from the official installation.
- **Offline Privacy:** Zero network permissions (`INTERNET` must not be added), zero telemetry, zero external runtime dependencies beyond existing Material 1.12.0.
- **Safe Dictation (Policy A):** Tapping a model or language chip during active recording (`isRecording == true`) or active transcription (`isTranscribing == true`) must never stop recording, discard captured audio, or write disk marker files before the current utterance finishes transcribing.
- **Canary Language Requirement:** Selecting a Canary model (`*canary*.gguf`) when `model_language` is empty or `"auto"` must automatically set `model_language` to the last explicit language (`bg-BG` by default, or `en-US` if previously chosen) so Canary never runs without a language hint.
- **Accessibility & Touch Targets:** Chips and the `⚙` manage-models button must have `48dp` minimum height (`48dp × 48dp` for `btn_manage_models`). In `ime_layout.xml`, reduce `ime_record_container` and `ime_mic_level` from `200dp` to `144dp` so adding the `48dp` chip bar + `8dp` bottom spacing keeps total keyboard height identical (`200dp` -> `144dp + 48dp + 8dp = 200dp`).

## Review Focus

1. **Mid-recording chip tap cancellation / undo:** If the user is recording with `Parakeet Ultra`, taps `Canary 1B` (queuing `pendingModel`), and then taps `Parakeet Ultra` again before stopping recording, `pendingModel` must clear back to `null` so no unnecessary reload happens after transcription.
2. **Stop-to-transcribe race window (`isTranscribing`):** Right after `finishCapture()` / `finishRecording()`, `isRecording` becomes `false` before the background thread posts `"Transcribing..."`. An explicit `isTranscribing = true` flag must be set synchronously in `finishCapture()` / `finishRecording()` and cleared only on terminal session outcomes (`onTextTranscribed`, `"Canceled"`, `"Error: no audio recorded"`, `"Error: failed to open microphone"`, `"Error: model not loaded"`, or `NO_MODEL_STATUS`)—never on non-terminal fallback statuses like `"Error loading X: … — using built-in model"`.
3. **Canary selected when `model_language` is `"auto"` or empty:** Switching to `canary-1b-v2-Q8_0.gguf` (via chip bar or `ModelsActivity`) must automatically set `model_language` to `"bg-BG"` (or the stored `last_explicit_language`) instead of leaving `model_language` empty.
4. **Deleted GGUF file before or during recording:** When `shouldDeferSwitch()` is `true`, `refresh()` must NOT write to disk via `ensureValidSelection()`. When `flushPending()` runs after recording ends, it must verify that a non-empty `pendingModel` still exists in `ModelStore.importedModels(ctx)` before writing `active_model`, falling back via `ensureValidSelection(ctx)` if deleted.
5. **Popup (`RecognizeActivity`) root click, theme context, & JNI status target:** The chip bar root in `recognize_activity.xml` must have `android:clickable="true"` so taps on gaps never trigger `R.id.root`'s `finishRecording()`. `QuickSwitchBarHelper` must use `barView.getContext()` for all view creation and `MaterialColors.getColor(...)` calls. `EngineBridge.reloadEngine(null)` must pass a dedicated `StatusSink` object that implements `public void onStatusUpdate(String status)` (never `Application` which lacks `onStatusUpdate`).

---

### Task 1: Side-by-Side Application Identity & Unit Test Setup

**Files:**
- Modify: `app/build.gradle.kts:14-26`, `app/build.gradle.kts:63-68`, `app/build.gradle.kts:104-118`
- Modify: `app/src/main/AndroidManifest.xml:27-33`, `app/src/main/AndroidManifest.xml:127-132`
- Modify: `app/src/main/res/values/strings.xml:1-4`, `app/src/main/res/values/strings.xml:48-49`

**Interfaces:**
- Consumes: Existing Gradle configuration and manifest.
- Produces: `applicationId = "dev.notune.transcribe.mod"`, `testOptions.unitTests.isReturnDefaultValues = true`, `testImplementation("junit:junit:4.13.2")`, and string resources in `strings.xml`.

- [ ] **Step 1: Update `app/build.gradle.kts` with `applicationId = "dev.notune.transcribe.mod"`, `testOptions`, and JUnit 4**

In `app/build.gradle.kts`:
- Change `defaultConfig.applicationId` to `"dev.notune.transcribe.mod"` while keeping `namespace = "dev.notune.transcribe"`.
- Inside `android { ... }`, add:
  ```kotlin
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
  ```
- Inside `dependencies { ... }`, add:
  ```kotlin
    testImplementation("junit:junit:4.13.2")
  ```

- [ ] **Step 2: Update `AndroidManifest.xml` and `strings.xml` labels**

In `app/src/main/AndroidManifest.xml`:
- Set `<application android:label="@string/app_name" ...>` and `<service android:name=".RustInputMethodService" android:label="@string/app_name" ...>`.
In `app/src/main/res/values/strings.xml`:
- Update `app_name` and `recognition_service_label` to `"Offline Voice Input (Mod)"`, and add:
  ```xml
    <string name="quick_lang_bg" translatable="false">BG</string>
    <string name="quick_lang_en" translatable="false">EN</string>
    <string name="quick_lang_bg_desc">Bulgarian (bg-BG)</string>
    <string name="quick_lang_en_desc">English (en-US)</string>
    <string name="quick_manage_models_desc">Manage speech models</string>
    <string name="quick_add_model">Add model…</string>
    <string name="quick_next_prefix">Next: %1$s</string>
    <string name="quick_builtin_short" translatable="false">Parakeet 0.6B</string>
  ```

---

### Task 2: `ModelStore`, `PendingSwitchState`, `EngineBridge`, and Unit Tests

**Files:**
- Modify: `app/src/main/java/dev/notune/transcribe/ModelStore.java`
- Create: `app/src/main/java/dev/notune/transcribe/PendingSwitchState.java`
- Create: `app/src/main/java/dev/notune/transcribe/EngineBridge.java`
- Modify: `app/src/main/java/dev/notune/transcribe/ModelsActivity.java`
- Create: `app/src/test/java/dev/notune/transcribe/ModelStoreTest.java`

**Interfaces:**
- Consumes: `Context.getFilesDir()`
- Produces:
  - `ModelStore`:
    - `public static final String LANG_BG = "bg-BG";`
    - `public static final String LANG_EN = "en-US";`
    - `public static String activeLanguage(Context ctx)` / `static String activeLanguageInDir(File filesDir)`
    - `public static void setActiveLanguage(Context ctx, String tag)` / `static void setActiveLanguageInDir(File filesDir, String tag)`
    - `public static String lastExplicitLanguage(Context ctx)` / `static String lastExplicitLanguageInDir(File filesDir)`
    - `public static boolean isCanaryModel(String fileName)`
    - `public static String ensureValidLanguageForModel(Context ctx, String modelFileName)` / `static String ensureValidLanguageInDir(File filesDir, String modelFileName)`
    - `public static String shortModelLabel(String fileName, String builtinFallback)`
    - `public static List<String> buildDisambiguatedLabels(List<String> modelKeys, String builtinFallback)`
  - `PendingSwitchState`:
    - `public String getPendingModel()` / `public String getPendingLanguage()`
    - `public boolean hasPending()`
    - `public void clear()`
    - `public void queueModel(String targetModel, String activeModel, String activeLang, String lastExplicitLang)`
    - `public void queueLanguage(String targetLang, String activeModel, String activeLang)`
    - `public String effectiveModel(String activeModel)`
    - `public String effectiveLanguage(String activeLang)`
  - `EngineBridge`:
    - `public static void reloadEngine(Object statusTargetOrNull)`

- [ ] **Step 1: Write the failing unit tests in `app/src/test/java/dev/notune/transcribe/ModelStoreTest.java`**

Write JUnit 4 tests in `ModelStoreTest.java` testing:
1. `ModelStore.shortModelLabel`:
   - `("", "Parakeet 0.6B")` -> `"Parakeet 0.6B"`
   - `("parakeet-ultra-Q8_0.gguf", "Parakeet 0.6B")` -> `"Parakeet Ultra Q8"`
   - `("canary-1b-v2-Q8_0.gguf", "Parakeet 0.6B")` -> `"Canary 1B v2 Q8"`
   - `("parakeet-tdt-0.6b-v3-Q4_K_M.gguf", "Parakeet 0.6B")` -> `"Parakeet 0.6B v3 Q4"`
2. `ModelStore.buildDisambiguatedLabels`:
   - If two model keys produce the exact same short label, appends `(1)`, `(2)` or keeps distinguishing suffix so two chips never look identical.
3. `ModelStore.ensureValidLanguageInDir` & `setActiveLanguageInDir`:
   - When `model_language` is absent/empty and `ensureValidLanguageInDir(dir, "canary-1b-v2-Q8_0.gguf")` is called, `activeLanguageInDir(dir)` becomes `"bg-BG"`.
   - After `setActiveLanguageInDir(dir, "en-US")` and `setActiveLanguageInDir(dir, "")`, calling `ensureValidLanguageInDir(dir, "canary-1b-v2-Q8_0.gguf")` restores `"en-US"`.
4. `PendingSwitchState`:
   - Queuing a different model while recording sets `pendingModel`; queuing the already-active model clears `pendingModel` back to `null` (Review Focus #1).
   - Queuing Canary while active language is `""` automatically queues `pendingLanguage = lastExplicitLang`; if the user then cancels the model switch back to Parakeet before stopping recording, the auto-queued language is also cleared unless the user explicitly tapped a language chip.

- [ ] **Step 2: Implement `ModelStore.java`, `PendingSwitchState.java`, `EngineBridge.java`, and update `ModelsActivity.java`**

- Implement the methods in `ModelStore.java` without loading native libraries in `ModelStore`.
- Create `PendingSwitchState.java` with a `boolean languageExplicitlyQueued` flag so auto-queued Canary language clears if the user reverts `pendingModel` back to a non-Canary model, while an explicitly tapped language chip stays queued.
- Create `EngineBridge.java` with `static { System.loadLibrary("c++_shared"); System.loadLibrary("android_transcribe_app"); }`, a static `final StatusSink DEFAULT_SINK = new StatusSink()`, and `private static native void reloadEngineNative(Object target);`.
- In `ModelsActivity.java`:
  - Replace local `readConfig("model_language")` / `writeConfig("model_language", ...)` with `ModelStore.activeLanguage(this)` / `ModelStore.setActiveLanguage(this, ...)`.
  - In `selectModel(String fileNameOrNull)` and in `languageSpinner.onItemSelected`, call `ModelStore.ensureValidLanguageForModel(this, ModelStore.activeModel(this))` so Canary cannot be left on `"auto"` (Review Focus #3), updating `languageSpinner` selection if adjusted.

---

### Task 3: Native Engine In-Place Language Sync & JNI (`src/engine.rs` & `src/models.rs`)

**Files:**
- Modify: `src/engine.rs:227-288`, `src/engine.rs:410-468`, `src/engine.rs:631-660`
- Modify: `src/models.rs:1-31`

**Interfaces:**
- Consumes: `active_model`, `model_language`, `model_translate`, `model_threads` in `filesDir`.
- Produces:
  - In-place language update inside `engine::reset_if_stale()`
  - `Java_dev_notune_transcribe_EngineBridge_reloadEngineNative` in `src/models.rs`

- [ ] **Step 1: Update `notify_status`, `do_load`, and `reset_if_stale` in `src/engine.rs`**

In `src/engine.rs`:
1. In `notify_status`:
   ```rust
   fn notify_status(env: &mut JNIEnv, obj: &JObject, msg: &str) {
       if let Ok(jmsg) = env.new_string(msg) {
           let _ = env.call_method(
               obj,
               "onStatusUpdate",
               "(Ljava/lang/String;)V",
               &[(&jmsg).into()],
           );
           let _ = env.exception_clear();
       }
   }
   ```
2. On `EngineConfig`, add:
   ```rust
   fn needs_full_reload(&self, other: &EngineConfig) -> bool {
       self.active_model != other.active_model
           || self.model_stamp != other.model_stamp
           || self.translate != other.translate
           || self.threads != other.threads
   }
   ```
3. In `do_load`: when an imported model fails to load and falls through to the built-in model (line ~654), store a `builtin_config` in `LOADED_CONFIG` with `active_model: None, model_stamp: None` on built-in load success so `LOADED_CONFIG` reflects what is actually loaded.
4. Make `pub fn reset_if_stale()` perform in-place language updates without holding `LOADED_CONFIG` across the engine lock:
   ```rust
   pub fn reset_if_stale() {
       let (lock, cvar) = &*LOAD_STATE;
       let mut state = lock.lock().unwrap();
       while *state == LoadState::Loading {
           state = cvar.wait(state).unwrap();
       }
       let loaded_snapshot = LOADED_CONFIG.lock().unwrap().clone();
       if let Some((dir, loaded)) = loaded_snapshot {
           let current = EngineConfig::read(&dir);
           if current != loaded {
               if !loaded.needs_full_reload(&current) {
                   if let Some(eng_arc) = get_engine() {
                       let mut guard = eng_arc.lock().unwrap_or_else(|p| p.into_inner());
                       guard.language = current.language.clone();
                       drop(guard);
                       *LOADED_CONFIG.lock().unwrap() = Some((dir, current));
                       log::info!("model language updated in-place");
                       return;
                   }
               }
               log::info!("model settings changed; reloading the engine");
               *GLOBAL_ENGINE.lock().unwrap() = None;
               *state = LoadState::Idle;
           }
       }
   }
   ```

- [ ] **Step 2: Add `Java_dev_notune_transcribe_EngineBridge_reloadEngineNative` in `src/models.rs`**

In `src/models.rs`:
- Keep `Java_dev_notune_transcribe_ModelsActivity_reloadModelNative` and add `Java_dev_notune_transcribe_EngineBridge_reloadEngineNative`.
- Both call a shared helper `reload_engine_async(env, target)` that spawns a thread calling `engine::reset_if_stale()` followed by `let _ = engine::ensure_loaded_from_thread(&vm, &target_ref);`.

---

### Task 4: Reusable 1-Tap Chip Bar Layout & Controller (`QuickSwitchBarHelper`)

**Files:**
- Create: `app/src/main/res/drawable/bg_quick_chip_group.xml`
- Create: `app/src/main/res/drawable/bg_quick_chip.xml`
- Create: `app/src/main/res/layout/quick_switch_bar.xml`
- Create: `app/src/main/java/dev/notune/transcribe/QuickSwitchBarHelper.java`

**Interfaces:**
- Consumes: `ModelStore`, `PendingSwitchState`, `EngineBridge`.
- Produces: `@layout/quick_switch_bar` and `QuickSwitchBarHelper`.

- [ ] **Step 1: Create `bg_quick_chip_group.xml`, `bg_quick_chip.xml`, and `quick_switch_bar.xml`**

- `bg_quick_chip_group.xml`: rounded `12dp` rectangle with `<solid android:color="?attr/colorSurfaceContainerHigh" />` and `<stroke android:width="1dp" android:color="?attr/colorOutlineVariant" />`.
- `bg_quick_chip.xml`: ripple with `?attr/colorControlHighlight` over a rounded `9dp` rectangle (`<solid android:color="@android:color/white" />` so `backgroundTintList` tints it cleanly).
- `quick_switch_bar.xml`:
  - Root `LinearLayout` (`@+id/quick_switch_bar`), `layout_width="match_parent"`, `layout_height="48dp"`, `orientation="horizontal"`, `gravity="center_vertical"`, `clickable="true"`, `focusable="true"` (so taps on spacing in `RecognizeActivity` never fall through to `R.id.root`).
  - Left container (`LinearLayout`, `layout_width="0dp"`, `layout_height="48dp"`, `layout_weight="1"`, `background="@drawable/bg_quick_chip_group"`, `paddingStart="4dp"`, `paddingEnd="2dp"`, `gravity="center_vertical"`):
    - `HorizontalScrollView` (`layout_width="0dp"`, `layout_height="match_parent"`, `layout_weight="1"`, `scrollbars="none"`, `fillViewport="true"`) containing `LinearLayout` `@+id/quick_model_chips` (`orientation="horizontal"`, `gravity="center_vertical"`).
    - `ImageButton` `@+id/btn_manage_models` (`layout_width="44dp"`, `layout_height="44dp"`, `src="@drawable/ic_settings"`, `background="?attr/selectableItemBackgroundBorderless"`, `contentDescription="@string/quick_manage_models_desc"`, `app:tint="?attr/colorOnSurfaceVariant"`).
  - Right container (`LinearLayout`, `layout_width="wrap_content"`, `layout_height="48dp"`, `layout_marginStart="6dp"`, `background="@drawable/bg_quick_chip_group"`, `padding="4dp"`, `gravity="center_vertical"`):
    - `TextView` `@+id/chip_lang_bg` (`minWidth="44dp"`, `layout_height="40dp"`, `gravity="center"`, `paddingHorizontal="10dp"`, `text="@string/quick_lang_bg"`, `contentDescription="@string/quick_lang_bg_desc"`, `background="@drawable/bg_quick_chip"`, `textStyle="bold"`, `textSize="12sp"`, `clickable="true"`, `focusable="true"`).
    - `TextView` `@+id/chip_lang_en` (`minWidth="44dp"`, `layout_height="40dp"`, `layout_marginStart="4dp"`, `gravity="center"`, `paddingHorizontal="10dp"`, `text="@string/quick_lang_en"`, `contentDescription="@string/quick_lang_en_desc"`, `background="@drawable/bg_quick_chip"`, `textStyle="bold"`, `textSize="12sp"`, `clickable="true"`, `focusable="true"`).

- [ ] **Step 2: Implement `QuickSwitchBarHelper.java`**

In `QuickSwitchBarHelper.java`:
- Constructor takes `(View rootView, PendingSwitchState pendingState, Host host)`:
  - Stores `themedContext = barView.getContext()` and uses `themedContext` for all `new TextView(themedContext)` and `MaterialColors.getColor(barView, attr)` calls (Review Focus #5).
- `refresh()`:
  - When `!host.shouldDeferSwitch()`, calls `ModelStore.ensureValidSelection(themedContext)` and `ModelStore.ensureValidLanguageForModel(themedContext, activeModel)`. When `host.shouldDeferSwitch()` is `true`, reads `ModelStore.activeModel(themedContext)` and `ModelStore.activeLanguage(themedContext)` without writing to disk.
  - Builds model chips from `(hasBuiltin ? "" : null)` + `ModelStore.importedModels(themedContext)` using `ModelStore.buildDisambiguatedLabels(...)`.
  - Styles each chip (Model and `BG`/`EN`):
    - **Active:** `backgroundTintList = colorPrimary`, `textColor = colorOnPrimary`.
    - **Pending (queued in `pendingState`):** `backgroundTintList = colorSecondaryContainer`, `textColor = colorOnSecondaryContainer`, label suffixed with `" ⏱"`.
    - **Inactive:** `backgroundTintList = transparent`, `textColor = colorOnSurfaceVariant`. (If stored language is neither `bg-BG` nor `en-US`, both `BG` and `EN` render as inactive until one is tapped.)
- `flushPending()`:
  - If `!pendingState.hasPending()`, returns `false`.
  - Reads `pendingModel` and `pendingLanguage`, clears `pendingState`, validates model existence via `ModelStore.importedModels(themedContext)`, writes `ModelStore.setActiveModel` / `ModelStore.setActiveLanguage` + `ModelStore.ensureValidLanguageForModel`, refreshes chips, and calls `host.onSelectionApplied(modelChanged, langChanged)`.

---

### Task 5: Wire `RustInputMethodService` and `RecognizeActivity` with Explicit `isTranscribing` Guard

**Files:**
- Modify: `app/src/main/res/layout/ime_layout.xml`
- Modify: `app/src/main/java/dev/notune/transcribe/RustInputMethodService.java`
- Modify: `app/src/main/res/layout/recognize_activity.xml`
- Modify: `app/src/main/java/dev/notune/transcribe/RecognizeActivity.java`

**Interfaces:**
- Consumes: `QuickSwitchBarHelper`, `PendingSwitchState`, `EngineBridge`.
- Produces: Full 1-tap switching + Policy A in both IME and Popup.

- [ ] **Step 1: Update `ime_layout.xml` and `RustInputMethodService.java`**

In `app/src/main/res/layout/ime_layout.xml`:
- Insert `<include layout="@layout/quick_switch_bar" />` with `android:layout_marginBottom="8dp"` right below the status row.
- Change `@id/ime_record_container` `android:layout_height` from `200dp` to `144dp`, and `@id/ime_mic_level` `android:layout_width` / `android:layout_height` from `200dp` to `144dp` so the total keyboard height is unchanged (`144 + 48 + 8 = 200dp`).

In `RustInputMethodService.java`:
- Add fields on the service (so they survive `onCreateInputView` theme rebuilds):
  - `private final PendingSwitchState pendingSwitch = new PendingSwitchState();`
  - `private QuickSwitchBarHelper quickSwitchBar;`
  - `private boolean isTranscribing = false;`
- In `finishCapture()`: set `isTranscribing = true;` before calling `stopRecording()`.
- In `onTextTranscribed(String text)` (both the empty-text branch and the committed-text branch):
  - Set `isTranscribing = false;`.
  - Call `if (quickSwitchBar != null) quickSwitchBar.flushPending();`.
- In `onStatusUpdate(String status)`:
  - Treat only terminal failure/cancel statuses as ending transcription:
    ```java
    boolean isTerminalStatus = status != null && (
            status.equals("Canceled")
            || status.equals(ModelStore.NO_MODEL_STATUS)
            || status.startsWith("Error: no audio recorded")
            || status.startsWith("Error: failed to open microphone")
            || status.startsWith("Error: model not loaded")
            || status.startsWith("Error: Model failed to load")
            || (status.startsWith("Error loading ") && !status.contains("using built-in model")));
    if (isTerminalStatus) {
        isTranscribing = false;
        if (quickSwitchBar != null) quickSwitchBar.flushPending();
    }
    ```
- In `updateRecordButtonUI(boolean recording)` and `updateUiState()`:
  - When `isRecording` or `isTranscribing`, if `quickSwitchBar != null && quickSwitchBar.hasPending()`, set `hintView.setText(getString(R.string.quick_next_prefix, quickSwitchBar.getPendingSummary()));` so `updateRecordButtonUI(true)` never overwrites the `"Next: …"` hint.
- In `onWindowShown()` and `onStartInputView(...)`: call `if (quickSwitchBar != null) quickSwitchBar.refresh();`.
- In `onWindowHidden()`: when `isStopOnHideEnabled()` cancels recording, set `isTranscribing = false;` and call `if (quickSwitchBar != null) quickSwitchBar.flushPending();`.

- [ ] **Step 2: Update `recognize_activity.xml` and `RecognizeActivity.java`**

In `app/src/main/res/layout/recognize_activity.xml`:
- Change the floating panel container (`@drawable/bg_voice_panel`) to `android:orientation="vertical"`, `android:paddingStart="12dp"`, `android:paddingEnd="12dp"`, `android:paddingTop="10dp"`, `android:paddingBottom="10dp"`.
- Top child inside the panel: `<include layout="@layout/quick_switch_bar" />` with `android:layout_marginBottom="6dp"`.
- Bottom child inside the panel: `LinearLayout` (`orientation="horizontal"`, `gravity="center_vertical"`) containing the existing `64dp` mic frame, `txt_status`, and `btn_close`.

In `RecognizeActivity.java`:
- Add fields:
  - `private final PendingSwitchState pendingSwitch = new PendingSwitchState();`
  - `private QuickSwitchBarHelper quickSwitchBar;`
  - `private boolean isTranscribing = false;`
- Initialize `quickSwitchBar = new QuickSwitchBarHelper(findViewById(android.R.id.content), pendingSwitch, ...)` right after `setContentView(R.layout.recognize_activity)` (before the permission and `hasAnyModel` early returns!).
- In `finishRecording()`: set `isTranscribing = true;` when `stopRecording()` is called.
- In `onTextTranscribed(String text)` (both empty and non-empty branches), `btn_close` click listener, and `onStop()` cancellation:
  - Set `isTranscribing = false;` and call `quickSwitchBar.flushPending();` (whose `onSelectionApplied` calls `EngineBridge.reloadEngine(null)` so the safe static `StatusSink` is used and the finishing `RecognizeActivity` is never leaked).
- In `onManageModelsClicked()`:
  - If `isRecording`: set `isRecording = false; isTranscribing = false; cancelRecording();` and release audio focus.
  - Call `quickSwitchBar.flushPending();`.
  - Launch `ModelsActivity` with `FLAG_ACTIVITY_NEW_TASK`, `setResult(Activity.RESULT_CANCELED)`, and `finish()`.

---

### Task 6: Toolchain Setup, Build (`arm64-v8a` & `x86_64`), and Verification

**Files:**
- Create/Update: `local.properties` (gitignored)

- [ ] **Step 1: Verify Rust toolchain & run JVM unit tests**

Ensure `rustup`, `cargo`, `cargo-ndk`, and Android targets (`aarch64-linux-android`, `x86_64-linux-android`) are installed, and `local.properties` points to `sdk.dir=/home/yoan/Android/Sdk` and `ndk.dir=/home/yoan/Android/Sdk/ndk/27.1.12297006`.
Run `./gradlew testFdroidDebugUnitTest` and verify all `ModelStoreTest` tests pass.

- [ ] **Step 2: Build the ARM64 debug APK (`assembleFdroidDebug`)**

Run `./gradlew assembleFdroidDebug` and verify `app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk` builds cleanly.
