use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use jni::objects::{GlobalRef, JObject};
use jni::{JNIEnv, JavaVM};

use crate::endpoint::Endpointer;
use crate::engine;
use crate::last_recording;

// --- Optional auto-stop endpointing ------------------------------------------
/// Trailing silence after speech that triggers auto-stop.
const AUTO_STOP_SILENCE_MS: u64 = 2000;
/// If no speech is ever detected, auto-stop after this long.
const AUTO_STOP_NO_SPEECH_MS: u64 = 8000;

pub struct SendStream(#[allow(dead_code)] pub cpal::Stream);
unsafe impl Send for SendStream {}
unsafe impl Sync for SendStream {}

pub struct VoiceSessionState {
    pub stream: Option<SendStream>,
    pub audio_buffer: Arc<Mutex<Vec<f32>>>,
    pub jvm: Arc<jni::JavaVM>,
    pub target_ref: GlobalRef,
    pub last_level_sent: Arc<Mutex<std::time::Instant>>,
    /// True while the current recording runs; flipped off on stop/cancel so
    /// the auto-stop monitor (if any) exits.
    pub session_active: Arc<AtomicBool>,
}

pub(crate) fn notify_status(env: &mut JNIEnv, obj: &JObject, msg: &str) {
    if let Ok(jmsg) = env.new_string(msg) {
        let _ = env.call_method(
            obj,
            "onStatusUpdate",
            "(Ljava/lang/String;)V",
            &[(&jmsg).into()],
        );
    }
}

fn notify_level(env: &mut JNIEnv, obj: &JObject, level: f32) {
    let _ = env.call_method(obj, "onAudioLevel", "(F)V", &[level.into()]);
}

fn notify_text(env: &mut JNIEnv, obj: &JObject, text: &str) -> jni::errors::Result<()> {
    let jtxt = env.new_string(text)?;
    env.call_method(
        obj,
        "onTextTranscribed",
        "(Ljava/lang/String;)V",
        &[(&jtxt).into()],
    )?;
    Ok(())
}

pub fn init_session(env: JNIEnv, target: JObject) -> VoiceSessionState {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );

    let vm = env.get_java_vm().expect("Failed to get JavaVM");
    let vm_arc = Arc::new(vm);
    let target_ref = env.new_global_ref(&target).expect("Failed to ref target");

    let state = VoiceSessionState {
        stream: None,
        audio_buffer: Arc::new(Mutex::new(Vec::new())),
        jvm: vm_arc.clone(),
        target_ref: target_ref.clone(),
        last_level_sent: Arc::new(Mutex::new(std::time::Instant::now())),
        session_active: Arc::new(AtomicBool::new(false)),
    };

    // Load engine in background
    let vm_clone = vm_arc.clone();
    let target_ref_clone = target_ref.clone();

    std::thread::spawn(move || {
        let _ = engine::ensure_loaded_from_thread(&vm_clone, &target_ref_clone);
    });

    state
}

/// Begin microphone capture. With `auto_stop` set, a monitor thread watches
/// for trailing silence after speech (or a no-speech timeout) and invokes the
/// Java-side `onAutoStop()` callback, which is expected to stop the recording
/// the same way a manual tap would.
pub fn start_recording(mut env: JNIEnv, state: &mut VoiceSessionState, auto_stop: bool) {
    let host = cpal::default_host();
    let device = match host.default_input_device() {
        Some(d) => d,
        None => {
            notify_status(
                &mut env,
                state.target_ref.as_obj(),
                "Error: no microphone available. Check permissions.",
            );
            return;
        }
    };

    let config = cpal::StreamConfig {
        channels: 1,
        sample_rate: cpal::SampleRate(16000),
        buffer_size: cpal::BufferSize::Default,
    };

    state.audio_buffer.lock().unwrap().clear();
    let buffer_clone = state.audio_buffer.clone();

    // End any previous session's monitor, then arm a fresh flag.
    state.session_active.store(false, Ordering::SeqCst);
    let session_active = Arc::new(AtomicBool::new(true));
    state.session_active = session_active.clone();

    let endpoint = if auto_stop {
        Some(Arc::new(Mutex::new(Endpointer::new())))
    } else {
        None
    };

    let jvm = state.jvm.clone();
    let target_ref = state.target_ref.clone();
    let last_sent = state.last_level_sent.clone();
    let endpoint_cb = endpoint.clone();

    let stream = device.build_input_stream(
        &config,
        move |data: &[f32], _: &_| {
            buffer_clone.lock().unwrap().extend_from_slice(data);

            // compute RMS
            let mut sum = 0.0f32;
            for &x in data {
                sum += x * x;
            }
            let rms = (sum / (data.len().max(1) as f32)).sqrt();
            let level = (rms * 6.0).clamp(0.0, 1.0);

            if let Some(ep) = &endpoint_cb {
                ep.lock().unwrap().push(data);
            }

            // throttle updates
            let mut last = last_sent.lock().unwrap();
            if last.elapsed() >= std::time::Duration::from_millis(50) {
                *last = std::time::Instant::now();

                if let Ok(mut env) = jvm.attach_current_thread() {
                    let obj = target_ref.as_obj();
                    notify_level(&mut env, obj, level);
                }
            }
        },
        |e| log::error!("Stream err: {}", e),
        None,
    );

    match stream {
        Ok(s) => {
            s.play().ok();
            state.stream = Some(SendStream(s));
            notify_status(&mut env, state.target_ref.as_obj(), "Listening...");

            // Settings changed in the app since this process loaded the
            // model: reload while the user speaks rather than after.
            if engine::is_engine_loaded() && !engine::is_current() {
                let jvm = state.jvm.clone();
                let target_ref = state.target_ref.clone();
                std::thread::spawn(move || {
                    let _ = engine::ensure_loaded_from_thread(&jvm, &target_ref);
                });
            }

            if let Some(ep) = endpoint {
                let jvm = state.jvm.clone();
                let target_ref = state.target_ref.clone();
                let started_at = Instant::now();
                std::thread::spawn(move || loop {
                    std::thread::sleep(Duration::from_millis(100));
                    if !session_active.load(Ordering::SeqCst) {
                        return;
                    }
                    let (speech, silence_ms) = {
                        let ep = ep.lock().unwrap();
                        (ep.speech_started(), ep.silence_ms())
                    };
                    let done = (speech && silence_ms >= AUTO_STOP_SILENCE_MS)
                        || (!speech
                            && started_at.elapsed()
                                >= Duration::from_millis(AUTO_STOP_NO_SPEECH_MS));
                    if done {
                        // Claim the session so a simultaneous manual stop and
                        // this monitor can't both fire.
                        if session_active.swap(false, Ordering::SeqCst) {
                            if let Ok(mut env) = jvm.attach_current_thread() {
                                let _ = env.call_method(
                                    target_ref.as_obj(),
                                    "onAutoStop",
                                    "()V",
                                    &[],
                                );
                            }
                        }
                        return;
                    }
                });
            }
        }
        Err(e) => {
            notify_status(
                &mut env,
                state.target_ref.as_obj(),
                &format!("Error: failed to open microphone: {}", e),
            );
        }
    }
}

pub fn stop_recording(mut env: JNIEnv, state: &mut VoiceSessionState, save_to: Option<PathBuf>) {
    // Drop the stream to stop recording; end the auto-stop monitor if running.
    state.session_active.store(false, Ordering::SeqCst);
    state.stream = None;

    let buffer = std::mem::take(&mut *state.audio_buffer.lock().unwrap());

    // Guard against empty buffer (mic permission denied, instant stop, etc.)
    if buffer.is_empty() {
        notify_status(
            &mut env,
            state.target_ref.as_obj(),
            "Error: no audio recorded. Check microphone permissions.",
        );
        return;
    }

    start_transcription(&mut env, state, AudioSource::Buffer(buffer, save_to));
}

pub fn transcribe_saved(mut env: JNIEnv, state: &mut VoiceSessionState, path: PathBuf) {
    start_transcription(&mut env, state, AudioSource::Saved(path));
}

enum AudioSource {
    Buffer(Vec<f32>, Option<PathBuf>),
    Saved(PathBuf),
}

static RUNNING: AtomicUsize = AtomicUsize::new(0);
/// Recordings shorter than this (0.5 s at 16 kHz) are not saved for retry.
const MIN_SAVED_SAMPLES: usize = 8_000;
const WORKER_ERROR: &str = "Error: transcription failed unexpectedly, please try again";

struct RunningTranscription;

impl Drop for RunningTranscription {
    fn drop(&mut self) {
        RUNNING.fetch_sub(1, Ordering::SeqCst);
    }
}

pub fn transcription_running() -> bool {
    RUNNING.load(Ordering::SeqCst) != 0
}

fn start_transcription(env: &mut JNIEnv, state: &VoiceSessionState, source: AudioSource) {
    let obj = state.target_ref.as_obj();
    notify_status(env, obj, "Transcribing...");
    if let Err(e) = spawn_transcription(state.jvm.clone(), state.target_ref.clone(), source) {
        log::error!("Could not start transcription worker: {e}");
        notify_status(env, obj, WORKER_ERROR);
    }
}

fn spawn_transcription(
    jvm: Arc<JavaVM>,
    target_ref: GlobalRef,
    source: AudioSource,
) -> std::io::Result<()> {
    RUNNING.fetch_add(1, Ordering::SeqCst);
    // Moving the guard into the closure also drops it if thread creation fails.
    let running = RunningTranscription;
    std::thread::Builder::new()
        .spawn(move || {
            let _running = running;
            let obj = target_ref.as_obj();
            let result = catch_unwind(AssertUnwindSafe(|| {
                let buffer = match source {
                    AudioSource::Buffer(buffer, save_to) => {
                        // Keep only takes worth retrying, so an accidental
                        // tap or pure silence doesn't replace the recording
                        // the user may still want to retry.
                        let worth_keeping = buffer.len() >= MIN_SAVED_SAMPLES
                            && !crate::endpoint::speech_range(&buffer).is_empty();
                        if let (Some(path), true) = (save_to, worth_keeping) {
                            if let Err(e) = last_recording::save(&path, &buffer) {
                                log::warn!("Could not save last recording: {e}");
                            }
                        }
                        Some(buffer)
                    }
                    AudioSource::Saved(path) => {
                        let samples = last_recording::load(&path);
                        if samples.is_none() && path.exists() {
                            // Unreadable: remove it so Retry stops offering it.
                            let _ = std::fs::remove_file(&path);
                        }
                        samples
                    }
                };

                let mut env = match jvm.attach_current_thread() {
                    Ok(env) => env,
                    Err(e) => {
                        log::error!("Could not attach transcription worker to JNI: {e}");
                        return;
                    }
                };
                let Some(buffer) = buffer else {
                    notify_status(&mut env, obj, "Error: no saved recording");
                    return;
                };

                // ensure_loaded_from_thread reports model-load failures itself.
                if !engine::is_current()
                    && engine::ensure_loaded_from_thread(&jvm, &target_ref).is_err()
                {
                    return;
                }

                let Some(engine) = engine::get_engine() else {
                    notify_status(&mut env, obj, "Error: model not loaded");
                    return;
                };
                match engine::transcribe_speech(&engine, buffer) {
                    Ok(text) => {
                        notify_status(&mut env, obj, "Ready");
                        if let Err(e) = notify_text(&mut env, obj, &text) {
                            log::error!("Could not deliver transcription: {e}");
                            let _ = env.exception_clear();
                            notify_status(&mut env, obj, WORKER_ERROR);
                        }
                    }
                    Err(e) => notify_status(&mut env, obj, &format!("Error: {e}")),
                }
            }));
            if result.is_err() {
                log::error!("Transcription worker panicked");
                if let Ok(mut env) = jvm.attach_current_thread() {
                    let _ = env.exception_clear();
                    notify_status(&mut env, obj, WORKER_ERROR);
                }
            }
        })
        .map(|_| ())
}

pub fn cancel_recording(mut env: JNIEnv, state: &mut VoiceSessionState) {
    state.session_active.store(false, Ordering::SeqCst);
    state.stream = None;
    state.audio_buffer.lock().unwrap().clear();
    notify_status(&mut env, state.target_ref.as_obj(), "Canceled");
}
