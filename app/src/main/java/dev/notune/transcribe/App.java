package dev.notune.transcribe;

import android.app.Application;
import android.util.Log;

import com.google.android.material.color.DynamicColors;

/**
 * Applies the saved dark-mode choice before any activity is created, and enables
 * Material You dynamic color on Android 12+. Runs in every process (including the
 * keyboard service).
 */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ThemePrefs.apply(this);
        DynamicColors.applyToActivitiesIfAvailable(this);
    }

    /**
     * Status sink for engine reloads started by callers that may be gone by
     * the time the load reports back (see {@link EngineBridge}). Called from Rust.
     */
    public void onStatusUpdate(String status) {
        Log.d("App", "engine status: " + status);
    }
}
