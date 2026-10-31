# Quick Model & Language Switcher Design Spec

**Date:** 2026-10-31
**Target Device:** European Samsung Galaxy S26 (ARM64, Android 17)
**Upstream Baseline:** `notune/android_transcribe_app` `v0.2.3` (`7abe07a2125733d008a21eb5a039e8204d3c6572`)
**Fork & Branch:** `ybabulkov/android_transcribe_app` (`feat/quick-model-switcher`)

## 1. Intent & Why

The owner uses **Offline Voice Input**'s dedicated voice keyboard (`RustInputMethodService`) alongside **FUTO Keyboard** on a Samsung Galaxy S26, switching between two local GGUF speech models—**Parakeet** (e.g. Parakeet TDT 0.6B v3 Q8 / Parakeet Ultra Q8) and **Canary 1B v2 Q8**—and two languages—**Bulgarian (`bg-BG`)** and **English (`en-US`)**. Currently, switching either the active model or the language requires leaving the current app and opening `ModelsActivity`.

The goal is a minimal, privacy-preserving modification that lets the owner switch between their installed models and between Bulgarian and English in **a single tap** directly on the voice keyboard (and floating voice panel), without losing in-progress recordings or overwriting the official app installation.

## 2. Goals, Non-Goals, and Anti-Goals

### Goals
- **P0 — 1-Tap Model & Language Chips on Voice IME (`RustInputMethodService`) and Popup (`RecognizeActivity`):**
  - Display installed models as 1-tap pill chips on the left side of a compact chip bar (`48dp` height), plus a `48dp` `⚙` button to open `ModelsActivity` when model management/import is needed.
  - Display **BG** (`bg-BG`) and **EN** (`en-US`) as 1-tap pill chips (`48dp` height) on the right side of the chip bar.
- **P0 — Safe Dictation During Recording & Transcription (Policy A):**
  - Tapping a model or language chip while recording (`isRecording == true`) or transcribing (`isTranscribing == true`) must **never** stop capture, discard audio, or write disk marker files before the current utterance finishes transcribing.
  - Instead, the tapped model/language is held in a `PendingSwitchState` object owned by the host (`RustInputMethodService` / `RecognizeActivity` so it survives IME theme-change view rebuilds), shown visually on the queued chip (`colorSecondaryContainer` fill + `" ⏱"` suffix) and in the hint (`Next: <Model> · <Lang>`), and flushed automatically as soon as the current recording's transcription finishes or capture is canceled.
- **P0 — Fast In-Place Language Switching in Native Engine (`src/engine.rs`):**
  - When only `model_language` changes (same `active_model`, `model_stamp`, `translate`, and `threads`, and `LOADED_CONFIG` reflects a successfully loaded active model), `reset_if_stale()` updates `Engine.language` in-place under the engine mutex without unloading/reloading the multi-hundred-MB `.gguf` file from disk.
- **P0 — Canary Explicit Language Safeguard:**
  - Canary models require an explicit language hint (`bg-BG` / `en-US`) and do not support `auto`. Whenever a Canary model is active or selected (including in `ModelsActivity`) and `model_language` is empty/`auto`, automatically apply the last-used explicit language (defaulting to `bg-BG`).
- **P0 — Side-by-Side Installation (`dev.notune.transcribe.mod`):**
  - Use `applicationId = "dev.notune.transcribe.mod"` while keeping `namespace = "dev.notune.transcribe"` so JNI signatures remain unchanged.
  - Label the app, IME service, and recognition service **"Offline Voice Input (Mod)"** so they coexist cleanly alongside the official app and FUTO Keyboard.

### Non-Goals & Anti-Goals
- No internet permissions, telemetry, cloud services, or in-app downloader changes.
- No replacement of the `transcribe.cpp` / Rust singleton engine architecture.
- No silent translation to English (`model_translate` stays untouched and off by default).
- Never let a tap anywhere on the quick-switch bar in `RecognizeActivity` bubble up to `root`'s tap-to-stop listener.

## 3. Architecture & Component Design

### 3.1 `ModelStore.java`, `EngineBridge.java`, & `PendingSwitchState.java`
Extend `ModelStore.java` (keeping it free of `System.loadLibrary` so it is 100% pure-JVM testable) with:
- `ACTIVE_LANGUAGE_FILE = "model_language"` and `LAST_EXPLICIT_LANGUAGE_FILE = "last_explicit_language"`
- `LANG_BG = "bg-BG"` and `LANG_EN = "en-US"`
- `activeLanguage(Context ctx)` / `activeLanguageInDir(File filesDir)`: reads `model_language` (returns `""` if absent/empty).
- `setActiveLanguage(Context ctx, String tag)` / `setActiveLanguageInDir(File filesDir, String tag)`: writes `model_language`; if `tag` is non-empty and not `"auto"`, also writes it to `last_explicit_language`.
- `lastExplicitLanguage(Context ctx)` / `lastExplicitLanguageInDir(File filesDir)`: reads `last_explicit_language`, defaulting to `"bg-BG"` if absent.
- `isCanaryModel(String modelName)`: returns `true` if `modelName != null && modelName.toLowerCase(Locale.ROOT).contains("canary")`.
- `ensureValidLanguageForModel(Context ctx, String modelName)` / `ensureValidLanguageInDir(File filesDir, String modelName)`: if `isCanaryModel(modelName)` and `activeLanguage` is empty or `"auto"`, sets and returns `lastExplicitLanguage`; otherwise returns `activeLanguage`.
- `shortModelLabel(String fileName, String builtinFallback)`:
  - If `fileName == null || fileName.isEmpty()`, returns `builtinFallback` (e.g. `"Parakeet 0.6B"`).
  - Strips `.gguf` extension (case-insensitive), replaces `_` and `-` with spaces, removes redundant quantization sub-tokens (`_0`, `_K_M`, `_K_S`) and architecture noise (`tdt`, `ctc`), Title-Cases family names (`parakeet` -> `Parakeet`, `canary` -> `Canary`, `whisper` -> `Whisper`, `nemotron` -> `Nemotron`), and uppercases size/quant tokens (`1b` -> `1B`, `0.6b` -> `0.6B`, `q8` -> `Q8`, `q4` -> `Q4`).
  - Examples:
    - `""` -> `"Parakeet 0.6B"`
    - `"parakeet-ultra-Q8_0.gguf"` -> `"Parakeet Ultra Q8"`
    - `"canary-1b-v2-Q8_0.gguf"` -> `"Canary 1B v2 Q8"`
    - `"parakeet-tdt-0.6b-v3-Q4_K_M.gguf"` -> `"Parakeet 0.6B v3 Q4"`

Create `EngineBridge.java`:
- Loads native libraries (`c++_shared`, `android_transcribe_app`), defines a lightweight `StatusSink` instance with `public void onStatusUpdate(String status)` (logged via `Log.d`), and exposes `public static void reloadEngine(Object statusTarget)` which passes `statusTarget != null ? statusTarget : DEFAULT_SINK` to `private static native void reloadEngineNative(Object target)`.

Create `PendingSwitchState.java`:
- Pure-Java state holder owned by `RustInputMethodService` and `RecognizeActivity` (survives view recreation on theme change):
  - Holds `String pendingModel` (`null` = no model switch queued; `""` = built-in model queued; non-empty = imported GGUF filename queued) and `String pendingLanguage` (`null` = no language switch queued; `"bg-BG"` or `"en-US"`).
  - `queueModel(String targetModel, String currentActiveModel, String currentEffectiveLang, String lastExplicitLang)`: if `targetModel.equals(currentActiveModel)`, clears `pendingModel = null`; otherwise sets `pendingModel = targetModel`. If `ModelStore.isCanaryModel(effectiveModel)` and `effectiveLang` is empty/`"auto"`, also ensures `pendingLanguage` is set to `lastExplicitLang`.
  - `queueLanguage(String targetLang, String currentActiveLang)`: if `targetLang.equals(currentActiveLang)`, clears `pendingLanguage = null`; otherwise sets `pendingLanguage = targetLang`.

Create `QuickSwitchBarHelper.java`:
- Binds `@id/quick_switch_bar` using `barView.getContext()` (which carries the wrapped Material 3 + DynamicColors theme in both the IME and the Activity).
- When `host.shouldDeferSwitch()` is true, `refresh()` reads current state without mutating disk files (`ensureValidSelection` disk write is skipped while recording/transcribing).
- When `flushPending()` is called on a terminal session outcome, re-validates that any queued model still exists via `ModelStore.ensureValidSelection(ctx)` before persisting, updates the UI, and triggers `host.onSelectionApplied(modelChanged, langChanged)`.

### 3.2 Native Engine In-Place Language Sync (`src/engine.rs` & `src/models.rs`)
- In `src/engine.rs`:
  - In `notify_status`, call `let _ = env.exception_clear();` after `env.call_method(...)` so a missing or throwing callback can never leave a pending JNI exception.
  - Add `EngineConfig::needs_full_reload(&self, other: &EngineConfig) -> bool` comparing `active_model`, `model_stamp`, `translate`, and `threads`.
  - In `do_load`, when an imported model fails to load and falls back to the built-in model, record `config_stored.active_model = None; config_stored.model_stamp = None;` in `LOADED_CONFIG` so `LOADED_CONFIG` accurately reflects that the built-in model is loaded (and retrying the failed imported model still sees a config diff).
  - Make `pub fn reset_if_stale()` perform the in-place language update when possible:
    - Wait for any `LoadState::Loading` under `LOAD_STATE`.
    - Inspect `LOADED_CONFIG` (clone `(dir, loaded)` and drop the `LOADED_CONFIG` mutex before locking the engine).
    - Read `current = EngineConfig::read(&dir)`. If `current == loaded`, return.
    - If `!loaded.needs_full_reload(&current)`:
      - If `let Some(eng_arc) = get_engine()`:
        - Lock `eng_arc` (recovering from poison), set `guard.language = current.language.clone()`, drop the engine guard, update `*LOADED_CONFIG.lock().unwrap() = Some((dir, current))`, and return without dropping `GLOBAL_ENGINE`.
    - Otherwise, drop `GLOBAL_ENGINE` and set `*state = LoadState::Idle`.
  - In `src/models.rs`, expose `Java_dev_notune_transcribe_EngineBridge_reloadEngineNative` (calling `engine::reset_if_stale()` + `engine::ensure_loaded_from_thread`), while keeping `Java_dev_notune_transcribe_ModelsActivity_reloadModelNative` calling `engine::reset_if_stale()` + `engine::ensure_loaded_from_thread`.
