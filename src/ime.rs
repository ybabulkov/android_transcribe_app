use jni::objects::{JClass, JObject};
use jni::sys::jboolean;
use jni::JNIEnv;
use once_cell::sync::Lazy;
use std::sync::Mutex;

use crate::voice_session::{self, notify_status, VoiceSessionState};
use crate::{assets, last_recording};

static IME_STATE: Lazy<Mutex<Option<VoiceSessionState>>> = Lazy::new(|| Mutex::new(None));

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_initNative(
    env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    let state = voice_session::init_session(env, service);
    *IME_STATE.lock().unwrap() = Some(state);
}

/// Only clears the session if it belongs to `service` (see recognize.rs).
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_cleanupNative(
    env: JNIEnv,
    _class: JClass,
    service: JObject,
) {
    let mut guard = IME_STATE.lock().unwrap();
    if guard
        .as_ref()
        .is_some_and(|s| env.is_same_object(&s.target_ref, &service).unwrap_or(false))
    {
        *guard = None;
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_startRecording(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = IME_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        // The IME keyboard is manual tap-to-stop; no silence auto-stop.
        voice_session::start_recording(env, state, false);
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_stopRecording(
    mut env: JNIEnv,
    this: JObject,
) {
    let mut guard = IME_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        let path = assets::files_dir(&mut env, state.target_ref.as_obj())
            .ok()
            .map(|dir| dir.join(last_recording::FILE_NAME));
        voice_session::stop_recording(env, state, path);
    } else {
        let _ = env.exception_clear();
        notify_status(&mut env, &this, "Error: keyboard engine not initialized");
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_retryLastRecording(
    mut env: JNIEnv,
    this: JObject,
) {
    let mut guard = IME_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        match assets::files_dir(&mut env, state.target_ref.as_obj()) {
            Ok(dir) => {
                voice_session::transcribe_saved(env, state, dir.join(last_recording::FILE_NAME))
            }
            Err(_) => notify_status(&mut env, &this, "Error: no saved recording"),
        }
    } else {
        let _ = env.exception_clear();
        notify_status(&mut env, &this, "Error: keyboard engine not initialized");
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_isTranscriptionRunning(
    _env: JNIEnv,
    _this: JObject,
) -> jboolean {
    jboolean::from(voice_session::transcription_running())
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_RustInputMethodService_cancelRecording(
    env: JNIEnv,
    _class: JClass,
) {
    let mut guard = IME_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        voice_session::cancel_recording(env, state);
    }
}
