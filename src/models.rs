use crate::engine;
use jni::objects::{JClass, JObject};
use jni::JNIEnv;
use std::sync::Arc;

/// Reloads the engine for the current model/language selection in the
/// background, reporting progress via `target`'s `onStatusUpdate` callback.
/// If only the language changed, the loaded model is kept and updated in place.
fn reload_engine_async(env: &JNIEnv, target: &JObject) {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );
    let vm = match env.get_java_vm() {
        Ok(vm) => Arc::new(vm),
        Err(_) => return,
    };
    let target_ref = match env.new_global_ref(target) {
        Ok(r) => r,
        Err(_) => return,
    };
    std::thread::spawn(move || {
        engine::reset_if_stale();
        let _ = engine::ensure_loaded_from_thread(&vm, &target_ref);
    });
}

/// Called after the user changes the model selection (or language hint):
/// picks up the new selection, reporting progress via the activity's
/// `onStatusUpdate` callback.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_ModelsActivity_reloadModelNative(
    env: JNIEnv,
    _class: JClass,
    activity: JObject,
) {
    reload_engine_async(&env, &activity);
}

/// Same for the quick switcher on the voice keyboard and popup. `target` must
/// be a Context (the loader reads its files and assets) with
/// `onStatusUpdate(String)`: the keyboard service, or the `App`.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_EngineBridge_reloadEngineNative(
    env: JNIEnv,
    _class: JClass,
    target: JObject,
) {
    reload_engine_async(&env, &target);
}
