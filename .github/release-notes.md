First release of **Offline Voice Input (Mod)**, a fork of [notune/android_transcribe_app](https://github.com/notune/android_transcribe_app) v0.2.3.

**New**
- One-tap model and language (BG/EN) chips on the voice keyboard and the voice popup. Language switches don't reload the model.
- Transcript history: the last 5 transcripts, tap to insert. Retry the last recording with the current model and language. Two-tap Clear.
- Canary 1B v2 (Q8) is the built-in model.
- Installs next to the official app (`dev.notune.transcribe.mod`).

**Fixed**
- Keyboard could stay stuck on "Processing" after certain errors.
- Worker or model-load crashes now report an error instead of hanging.
- Long-press "transcribe, then switch keyboard" no longer leaves before the text arrives.

**Downloads**
- `offline_voice_input_mod_v0.2.3-mod.1.apk`: with Canary 1B v2 (~1.1 GB).
- `offline_voice_input_mod_v0.2.3-mod.1_no_model.apk`: no bundled model; import one on first start.
