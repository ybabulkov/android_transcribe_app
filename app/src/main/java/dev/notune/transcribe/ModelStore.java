package dev.notune.transcribe;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which speech models the app can use: the bundled Canary model (absent in
 * F-Droid builds) and GGUF files imported into {@code files/models/}. The
 * selection is the {@code active_model} marker file read by the Rust engine
 * loader: the file name of an imported model, absent/empty = bundled model.
 */
public final class ModelStore {
    private static final String TAG = "ModelStore";
    private static final String ACTIVE_MODEL_FILE = "active_model";
    private static final String ACTIVE_LANGUAGE_FILE = "model_language";
    private static final String LAST_EXPLICIT_LANGUAGE_FILE = "last_explicit_language";

    /** Languages the quick switcher offers (BCP-47 tags the engine understands). */
    public static final String LANG_BG = "bg-BG";
    public static final String LANG_EN = "en-US";
    /** Asset directory of the bundled model (see src/assets.rs). */
    private static final String BUILTIN_ASSET_DIR = "builtin-model";

    /** Engine status when there is nothing to load (NO_MODEL_STATUS in src/engine.rs). */
    public static final String NO_MODEL_STATUS = "Error: no speech model installed";

    /** File name of the bundled model (modelPackFiles in app/build.gradle.kts). */
    static final String BUILTIN_MODEL_FILE = "canary-1b-v2-Q8_0.gguf";

    /** The bundled model, offered as the first download when it isn't bundled. */
    public static final String RECOMMENDED_NAME = "Canary 1B v2 Q8";
    public static final String RECOMMENDED_SIZE = "1.1 GB";
    public static final String RECOMMENDED_URL =
            "https://huggingface.co/handy-computer/canary-1b-v2-gguf/resolve/main/" + BUILTIN_MODEL_FILE;

    private static Boolean builtin;

    private ModelStore() {}

    /** True if this build bundles a model (F-Droid builds don't). */
    public static synchronized boolean hasBuiltinModel(Context ctx) {
        if (builtin == null) {
            boolean found = false;
            try {
                String[] names = ctx.getAssets().list(BUILTIN_ASSET_DIR);
                if (names != null) {
                    for (String name : names) {
                        if (isGguf(name)) found = true;
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to list bundled model assets", e);
            }
            builtin = found;
        }
        return builtin;
    }

    public static boolean hasAnyModel(Context ctx) {
        return hasBuiltinModel(ctx) || !importedModels(ctx).isEmpty();
    }

    public static File modelsDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "models");
        dir.mkdirs();
        return dir;
    }

    /** File names of the imported models, sorted for display. */
    public static List<String> importedModels(Context ctx) {
        List<String> names = new ArrayList<>();
        File[] files = modelsDir(ctx).listFiles((dir, name) -> isGguf(name));
        if (files != null) {
            for (File f : files) names.add(f.getName());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    static boolean isGguf(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".gguf");
    }

    /** The selected imported model, or "" for the bundled one. */
    public static String activeModel(Context ctx) {
        File f = new File(ctx.getFilesDir(), ACTIVE_MODEL_FILE);
        if (!f.exists()) return "";
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /** Selects an imported model; null or "" selects the bundled one. */
    public static void setActiveModel(Context ctx, String fileName) {
        File f = new File(ctx.getFilesDir(), ACTIVE_MODEL_FILE);
        if (fileName == null || fileName.isEmpty()) {
            f.delete();
            return;
        }
        try {
            Files.write(f.toPath(), fileName.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e(TAG, "Failed to write " + ACTIVE_MODEL_FILE, e);
        }
    }

    /**
     * Keeps the selection pointing at a model that exists: a deleted model
     * falls back to the bundled one, or without one to the first imported
     * model. Returns the (possibly updated) selection.
     */
    public static String ensureValidSelection(Context ctx) {
        String active = activeModel(ctx);
        List<String> names = importedModels(ctx);
        if (!active.isEmpty() && names.contains(active)) return active;
        if (active.isEmpty() && hasBuiltinModel(ctx)) return active;

        String fallback = hasBuiltinModel(ctx) || names.isEmpty() ? "" : names.get(0);
        if (!fallback.equals(active)) setActiveModel(ctx, fallback);
        return fallback;
    }

    // --- Language -------------------------------------------------------------

    /** The language hint passed to the model; "" = automatic. */
    public static String activeLanguage(Context ctx) {
        return activeLanguageInDir(ctx.getFilesDir());
    }

    static String activeLanguageInDir(File filesDir) {
        return readFile(new File(filesDir, ACTIVE_LANGUAGE_FILE));
    }

    /**
     * Stores the language hint ("" or null = automatic). An explicit language
     * is also remembered as the one to fall back to when a model needs one.
     */
    public static void setActiveLanguage(Context ctx, String tag) {
        setActiveLanguageInDir(ctx.getFilesDir(), tag);
    }

    static void setActiveLanguageInDir(File filesDir, String tag) {
        String value = tag == null ? "" : tag.trim();
        writeFile(new File(filesDir, ACTIVE_LANGUAGE_FILE), value);
        if (!value.isEmpty() && !value.equalsIgnoreCase("auto")) {
            writeFile(new File(filesDir, LAST_EXPLICIT_LANGUAGE_FILE), value);
        }
    }

    /** The last explicitly chosen language, {@link #LANG_BG} if none yet. */
    public static String lastExplicitLanguage(Context ctx) {
        return lastExplicitLanguageInDir(ctx.getFilesDir());
    }

    static String lastExplicitLanguageInDir(File filesDir) {
        String value = readFile(new File(filesDir, LAST_EXPLICIT_LANGUAGE_FILE));
        return value.isEmpty() ? LANG_BG : value;
    }

    /** True for Canary models, which cannot run without an explicit language. */
    public static boolean isCanaryModel(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).contains("canary");
    }

    /**
     * True if the model with this key ("" = bundled) needs an explicit
     * language, i.e. is a Canary model.
     */
    public static boolean needsExplicitLanguage(String modelKey) {
        return isCanaryModel(modelKey == null || modelKey.isEmpty() ? BUILTIN_MODEL_FILE : modelKey);
    }

    /**
     * Canary has no automatic mode: if it is the model and the language is
     * empty/auto, applies the last explicit language. Returns the language in
     * effect afterwards.
     */
    public static String ensureValidLanguageForModel(Context ctx, String modelFileName) {
        return ensureValidLanguageInDir(ctx.getFilesDir(), modelFileName);
    }

    static String ensureValidLanguageInDir(File filesDir, String modelFileName) {
        String lang = activeLanguageInDir(filesDir);
        if (needsExplicitLanguage(modelFileName) && (lang.isEmpty() || lang.equalsIgnoreCase("auto"))) {
            lang = lastExplicitLanguageInDir(filesDir);
            setActiveLanguageInDir(filesDir, lang);
        }
        return lang;
    }

    // --- Display names --------------------------------------------------------

    /**
     * A concise chip label for a model file, e.g. "canary-1b-v2-Q8_0.gguf" ->
     * "Canary 1B v2 Q8". An empty name is the bundled model: {@code builtinFallback}.
     */
    public static String shortModelLabel(String fileName, String builtinFallback) {
        if (fileName == null || fileName.isEmpty()) return builtinFallback;
        String base = fileName;
        if (base.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
            base = base.substring(0, base.length() - 5);
        }
        String[] tokens = base.replace('_', ' ').replace('-', ' ').trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String token : tokens) {
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.isEmpty()) continue;
            // Quantization sub-tokens and architecture noise.
            if (lower.equals("0") || lower.equals("k") || lower.equals("m") || lower.equals("s")
                    || lower.equals("tdt") || lower.equals("ctc")) {
                continue;
            }
            String word;
            if (lower.matches("\\d+(\\.\\d+)?[bm]") || lower.matches("q\\d+")) {
                word = lower.toUpperCase(Locale.ROOT);   // 1b -> 1B, q8 -> Q8
            } else if (lower.matches("[a-z]+")) {
                word = Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
            } else {
                word = lower;                            // v2, v3, ...
            }
            if (out.length() > 0) out.append(' ');
            out.append(word);
        }
        return out.length() == 0 ? fileName : out.toString();
    }

    /**
     * Short labels for the given model keys ("" = bundled), made unique: if
     * two models collapse to the same label, the repeats get " (2)", " (3)", ...
     */
    public static List<String> buildDisambiguatedLabels(List<String> modelKeys, String builtinFallback) {
        List<String> labels = new ArrayList<>(modelKeys.size());
        for (String key : modelKeys) labels.add(shortModelLabel(key, builtinFallback));

        Map<String, Integer> total = new HashMap<>();
        for (String label : labels) {
            Integer n = total.get(label);
            total.put(label, n == null ? 1 : n + 1);
        }
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            if (total.get(label) > 1) {
                Integer n = seen.get(label);
                int index = n == null ? 1 : n + 1;
                seen.put(label, index);
                labels.set(i, label + " (" + index + ")");
            }
        }
        return labels;
    }

    // --- File helpers ---------------------------------------------------------

    private static String readFile(File f) {
        if (!f.exists()) return "";
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeFile(File f, String value) {
        if (value == null || value.isEmpty()) {
            f.delete();
            return;
        }
        try {
            Files.write(f.toPath(), value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e(TAG, "Failed to write " + f.getName(), e);
        }
    }

    /**
     * True if {@code status} ends the current session phase without a result.
     * While recording, only a microphone that never opened (or a cancel) does:
     * a model-load error then comes from the background warm-up, and the
     * recording goes on (the load is retried when capture stops). Once capture
     * has stopped, model-load and transcription failures end the session too.
     */
    public static boolean isTerminalSessionStatus(String status, boolean recording, boolean transcribing) {
        if (recording) return isRecordingFailureStatus(status);
        if (transcribing) return isTranscriptionFailureStatus(status);
        return isRecordingFailureStatus(status) || isTranscriptionFailureStatus(status);
    }

    /** Statuses meaning the microphone is not (or no longer) recording. */
    static boolean isRecordingFailureStatus(String status) {
        return status != null && (
                status.equals("Canceled")
                || status.startsWith("Error: no microphone available")
                || status.startsWith("Error: failed to open microphone"));
    }

    /**
     * Any error ends the attempt so the keyboard cannot stay busy. The
     * built-in-model fallback notice continues the current attempt.
     */
    static boolean isTranscriptionFailureStatus(String status) {
        return status != null && (
                status.equals("Canceled")
                || status.equals(NO_MODEL_STATUS)
                || (status.startsWith("Error") && !status.contains("using built-in model")));
    }

    /** An engine status for display, with the no-model error translated. */
    public static String displayStatus(Context ctx, String status) {
        return NO_MODEL_STATUS.equals(status) ? ctx.getString(R.string.no_model_short) : status;
    }
}
