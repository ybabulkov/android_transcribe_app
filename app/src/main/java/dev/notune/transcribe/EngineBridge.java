package dev.notune.transcribe;

import android.content.Context;
import android.util.Log;

/**
 * JNI entry point for reloading the speech engine after the model or language
 * changed, from places other than {@link ModelsActivity} (the voice keyboard
 * and popup chip bars). Kept separate from {@link ModelStore} so that class
 * stays free of native libraries and unit-testable on the JVM.
 */
public final class EngineBridge {
    private static final String TAG = "EngineBridge";

    static {
        try {
            System.loadLibrary("c++_shared");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Failed to load c++_shared", e);
        }
        System.loadLibrary("android_transcribe_app");
    }

    private EngineBridge() {}

    /**
     * Reloads the engine with the stored selection, in the background. The
     * native loader resolves files and assets through {@code context} and
     * reports progress to its {@code onStatusUpdate(String)}, so it must have
     * one: the keyboard service, or the {@link App} when the caller may be
     * finishing and must not be kept alive by the native side.
     */
    public static void reloadEngine(Context context) {
        reloadEngineNative(context);
    }

    private static native void reloadEngineNative(Context context);
}
