package dev.notune.transcribe;

/**
 * A model/language switch the user asked for while a recording or its
 * transcription was still running. Owned by the host (the keyboard service or
 * the voice popup) rather than the chip bar's view, so it survives view
 * rebuilds such as an IME theme change. Pure Java: no Android dependencies.
 *
 * {@code pendingModel}: null = nothing queued, "" = bundled model, otherwise
 * an imported GGUF file name. {@code pendingLanguage}: null = nothing queued,
 * otherwise a language tag.
 */
public final class PendingSwitchState {
    private String pendingModel;
    private String pendingLanguage;
    /** True if the user tapped a language chip (as opposed to Canary auto-queueing one). */
    private boolean languageExplicitlyQueued;

    public String getPendingModel() {
        return pendingModel;
    }

    public String getPendingLanguage() {
        return pendingLanguage;
    }

    public boolean hasPending() {
        return pendingModel != null || pendingLanguage != null;
    }

    public void clear() {
        pendingModel = null;
        pendingLanguage = null;
        languageExplicitlyQueued = false;
    }

    /**
     * Queues {@code targetModel} ("" = bundled). Tapping the already-active
     * model cancels the queued switch. Canary needs an explicit language, so
     * if the language in effect is empty/auto, {@code lastExplicitLang} is
     * queued along with it; that auto-queued language is dropped again if the
     * model switch is reverted to a non-Canary model.
     */
    public void queueModel(String targetModel, String activeModel, String activeLang,
                           String lastExplicitLang) {
        String target = targetModel == null ? "" : targetModel;
        String active = activeModel == null ? "" : activeModel;

        pendingModel = target.equals(active) ? null : target;

        String effectiveModel = effectiveModel(active);
        // An auto-queued language must not count as "the language is set", or
        // re-tapping Canary would drop it again.
        String effectiveLang = languageExplicitlyQueued ? effectiveLanguage(activeLang) : activeLang;
        boolean needsLanguage = ModelStore.needsExplicitLanguage(effectiveModel) && isAutoLanguage(effectiveLang);

        if (needsLanguage) {
            if (pendingLanguage == null) {
                pendingLanguage = lastExplicitLang;
                languageExplicitlyQueued = false;
            }
        } else if (pendingLanguage != null && !languageExplicitlyQueued) {
            pendingLanguage = null;
        }
    }

    /** Queues {@code targetLang}; tapping the language already in effect cancels it. */
    public void queueLanguage(String targetLang, String activeModel, String activeLang) {
        String target = targetLang == null ? "" : targetLang;
        String active = activeLang == null ? "" : activeLang;
        if (target.equals(active)) {
            pendingLanguage = null;
        } else {
            pendingLanguage = target;
        }
        languageExplicitlyQueued = pendingLanguage != null;
    }

    /** The model that will be active once the queued switch is applied. */
    public String effectiveModel(String activeModel) {
        return pendingModel != null ? pendingModel : (activeModel == null ? "" : activeModel);
    }

    /** The language that will be in effect once the queued switch is applied. */
    public String effectiveLanguage(String activeLang) {
        return pendingLanguage != null ? pendingLanguage : (activeLang == null ? "" : activeLang);
    }

    private static boolean isAutoLanguage(String lang) {
        return lang == null || lang.isEmpty() || lang.equalsIgnoreCase("auto");
    }
}
