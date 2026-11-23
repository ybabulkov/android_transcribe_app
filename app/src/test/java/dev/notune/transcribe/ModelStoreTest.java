package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

public class ModelStoreTest {
    private static final String FALLBACK = "Parakeet 0.6B";
    private static final String CANARY = "canary-1b-v2-Q8_0.gguf";
    private static final String PARAKEET = "parakeet-ultra-Q8_0.gguf";

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private File dir;

    @Before
    public void setUp() {
        dir = tmp.getRoot();
    }

    // --- shortModelLabel ---------------------------------------------------

    @Test
    public void shortLabel_emptyOrNullIsBuiltinFallback() {
        assertEquals(FALLBACK, ModelStore.shortModelLabel("", FALLBACK));
        assertEquals(FALLBACK, ModelStore.shortModelLabel(null, FALLBACK));
    }

    @Test
    public void shortLabel_parakeetUltra() {
        assertEquals("Parakeet Ultra Q8", ModelStore.shortModelLabel(PARAKEET, FALLBACK));
    }

    @Test
    public void shortLabel_canary() {
        assertEquals("Canary 1B v2 Q8", ModelStore.shortModelLabel(CANARY, FALLBACK));
    }

    @Test
    public void shortLabel_dropsArchitectureAndQuantNoise() {
        assertEquals("Parakeet 0.6B v3 Q4",
                ModelStore.shortModelLabel("parakeet-tdt-0.6b-v3-Q4_K_M.gguf", FALLBACK));
    }

    @Test
    public void shortLabel_extensionIsCaseInsensitive() {
        assertEquals("Canary 1B v2 Q8", ModelStore.shortModelLabel("canary-1b-v2-Q8_0.GGUF", FALLBACK));
    }

    @Test
    public void shortLabel_whisperAndNemotron() {
        assertEquals("Whisper Small Q8", ModelStore.shortModelLabel("whisper-small-Q8_0.gguf", FALLBACK));
        assertEquals("Nemotron 3.5 Asr Streaming 0.6B Q4",
                ModelStore.shortModelLabel("nemotron-3.5-asr-streaming-0.6b-Q4_K_M.gguf", FALLBACK));
    }

    // --- buildDisambiguatedLabels -----------------------------------------

    @Test
    public void disambiguated_distinctLabelsAreUnchanged() {
        List<String> labels = ModelStore.buildDisambiguatedLabels(
                Arrays.asList("", PARAKEET, CANARY), FALLBACK);
        assertEquals(Arrays.asList(FALLBACK, "Parakeet Ultra Q8", "Canary 1B v2 Q8"), labels);
    }

    @Test
    public void disambiguated_identicalLabelsNeverLookAlike() {
        // Both collapse to "Parakeet Ultra Q8".
        List<String> labels = ModelStore.buildDisambiguatedLabels(
                Arrays.asList("parakeet-ultra-Q8_0.gguf", "parakeet_ultra_q8.gguf", CANARY), FALLBACK);
        assertEquals(3, labels.size());
        assertEquals(3, new HashSet<>(labels).size());
        assertEquals("Parakeet Ultra Q8 (1)", labels.get(0));
        assertEquals("Parakeet Ultra Q8 (2)", labels.get(1));
        assertEquals("Canary 1B v2 Q8", labels.get(2));
    }

    @Test
    public void disambiguated_builtinCollidingWithImportedGetsSuffix() {
        // The bundled model's fallback label equals an imported file's label.
        List<String> labels = ModelStore.buildDisambiguatedLabels(
                Arrays.asList("", "parakeet-tdt-0.6b-Q8_0.gguf"), "Parakeet 0.6B Q8");
        assertNotEquals(labels.get(0), labels.get(1));
    }

    // --- language persistence ---------------------------------------------

    @Test
    public void language_absentIsEmptyAndLastExplicitDefaultsToBulgarian() {
        assertEquals("", ModelStore.activeLanguageInDir(dir));
        assertEquals(ModelStore.LANG_BG, ModelStore.lastExplicitLanguageInDir(dir));
    }

    @Test
    public void language_setAndRead() {
        ModelStore.setActiveLanguageInDir(dir, "en-US");
        assertEquals("en-US", ModelStore.activeLanguageInDir(dir));
        assertEquals("en-US", ModelStore.lastExplicitLanguageInDir(dir));
    }

    @Test
    public void language_clearingKeepsLastExplicit() {
        ModelStore.setActiveLanguageInDir(dir, "en-US");
        ModelStore.setActiveLanguageInDir(dir, "");
        assertEquals("", ModelStore.activeLanguageInDir(dir));
        assertEquals("en-US", ModelStore.lastExplicitLanguageInDir(dir));
        ModelStore.setActiveLanguageInDir(dir, null);
        assertEquals("", ModelStore.activeLanguageInDir(dir));
    }

    @Test
    public void language_autoIsNotRememberedAsExplicit() {
        ModelStore.setActiveLanguageInDir(dir, "en-US");
        ModelStore.setActiveLanguageInDir(dir, "auto");
        assertEquals("auto", ModelStore.activeLanguageInDir(dir));
        assertEquals("en-US", ModelStore.lastExplicitLanguageInDir(dir));
    }

    // --- Canary safeguard --------------------------------------------------

    @Test
    public void canary_detection() {
        assertTrue(ModelStore.isCanaryModel(CANARY));
        assertTrue(ModelStore.isCanaryModel("CANARY-big.gguf"));
        assertFalse(ModelStore.isCanaryModel(PARAKEET));
        assertFalse(ModelStore.isCanaryModel(""));
        assertFalse(ModelStore.isCanaryModel(null));
    }

    @Test
    public void canary_emptyLanguageBecomesBulgarian() {
        assertEquals("bg-BG", ModelStore.ensureValidLanguageInDir(dir, CANARY));
        assertEquals("bg-BG", ModelStore.activeLanguageInDir(dir));
    }

    @Test
    public void canary_autoLanguageBecomesLastExplicit() {
        ModelStore.setActiveLanguageInDir(dir, "en-US");
        ModelStore.setActiveLanguageInDir(dir, "auto");
        assertEquals("en-US", ModelStore.ensureValidLanguageInDir(dir, CANARY));
        assertEquals("en-US", ModelStore.activeLanguageInDir(dir));
    }

    @Test
    public void canary_restoresEnglishAfterClear() {
        ModelStore.setActiveLanguageInDir(dir, "en-US");
        ModelStore.setActiveLanguageInDir(dir, "");
        assertEquals("en-US", ModelStore.ensureValidLanguageInDir(dir, CANARY));
        assertEquals("en-US", ModelStore.activeLanguageInDir(dir));
    }

    @Test
    public void canary_explicitLanguageIsKept() {
        ModelStore.setActiveLanguageInDir(dir, "de-DE");
        assertEquals("de-DE", ModelStore.ensureValidLanguageInDir(dir, CANARY));
    }

    @Test
    public void builtinModel_isCanaryAndNeedsLanguage() {
        assertTrue(ModelStore.needsExplicitLanguage(""));
        assertTrue(ModelStore.needsExplicitLanguage(null));
        assertTrue(ModelStore.needsExplicitLanguage(CANARY));
        assertFalse(ModelStore.needsExplicitLanguage(PARAKEET));
        assertEquals("bg-BG", ModelStore.ensureValidLanguageInDir(dir, ""));
    }

    @Test
    public void pending_builtinWithAutoLanguageQueuesLastExplicit() {
        PendingSwitchState p = new PendingSwitchState();
        p.queueModel("", PARAKEET, "", "en-US");
        assertEquals("", p.getPendingModel());
        assertEquals("en-US", p.getPendingLanguage());
    }

    @Test
    public void nonCanary_languageIsLeftAlone() {
        assertEquals("", ModelStore.ensureValidLanguageInDir(dir, PARAKEET));
        assertEquals("", ModelStore.activeLanguageInDir(dir));
    }

    // --- isTerminalSessionStatus -----------------------------------------

    @Test
    public void terminal_warmUpErrorsDoNotEndRecording() {
        assertFalse(ModelStore.isTerminalSessionStatus(ModelStore.NO_MODEL_STATUS, true, false));
        assertFalse(ModelStore.isTerminalSessionStatus("Error loading x.gguf: bad file", true, false));
        assertFalse(ModelStore.isTerminalSessionStatus("Error: Model failed to load", true, false));
        assertFalse(ModelStore.isTerminalSessionStatus("Loading model x.gguf...", true, false));
    }

    @Test
    public void terminal_microphoneFailureOrCancelEndsRecording() {
        assertTrue(ModelStore.isTerminalSessionStatus(
                "Error: no microphone available. Check permissions.", true, false));
        assertTrue(ModelStore.isTerminalSessionStatus(
                "Error: failed to open microphone: busy", true, false));
        assertTrue(ModelStore.isTerminalSessionStatus("Canceled", true, false));
    }

    @Test
    public void terminal_loadAndTranscriptionErrorsEndTranscribing() {
        assertTrue(ModelStore.isTerminalSessionStatus(ModelStore.NO_MODEL_STATUS, false, true));
        assertTrue(ModelStore.isTerminalSessionStatus("Error loading x.gguf: bad file", false, true));
        assertTrue(ModelStore.isTerminalSessionStatus("Error: Model failed to load", false, true));
        assertTrue(ModelStore.isTerminalSessionStatus("Error: model not loaded", false, true));
        assertTrue(ModelStore.isTerminalSessionStatus(
                "Error: no audio recorded. Check microphone permissions.", false, true));
    }

    @Test
    public void terminal_anyErrorEndsTranscription() {
        String unexpected = "Error: transcription failed unexpectedly, please try again";
        assertTrue(ModelStore.isTerminalSessionStatus(unexpected, false, true));
        assertTrue(ModelStore.isTerminalSessionStatus("Error: no saved recording", false, true));
        assertTrue(ModelStore.isTerminalSessionStatus("Error: CPU lacks dotprod", false, true));
        assertFalse(ModelStore.isTerminalSessionStatus(unexpected, true, false));
    }

    @Test
    public void terminal_builtinFallbackNoticeNeverEndsSession() {
        String fallback = "Error loading x.gguf: bad file — using built-in model";
        assertFalse(ModelStore.isTerminalSessionStatus(fallback, true, false));
        assertFalse(ModelStore.isTerminalSessionStatus(fallback, false, true));
        assertFalse(ModelStore.isTerminalSessionStatus(fallback, false, false));
    }

    @Test
    public void terminal_progressStatusesNeverEndSession() {
        assertFalse(ModelStore.isTerminalSessionStatus("Transcribing...", false, true));
        assertFalse(ModelStore.isTerminalSessionStatus("Ready", false, true));
        assertFalse(ModelStore.isTerminalSessionStatus(null, false, true));
        assertFalse(ModelStore.isTerminalSessionStatus(null, true, false));
    }

    // --- PendingSwitchState ------------------------------------------------

    @Test
    public void pending_startsEmpty() {
        PendingSwitchState p = new PendingSwitchState();
        assertFalse(p.hasPending());
        assertNull(p.getPendingModel());
        assertNull(p.getPendingLanguage());
    }

    @Test
    public void pending_queueDifferentModel() {
        PendingSwitchState p = new PendingSwitchState();
        p.queueModel(PARAKEET, "", "bg-BG", "bg-BG");
        assertEquals(PARAKEET, p.getPendingModel());
        assertTrue(p.hasPending());
        assertEquals(PARAKEET, p.effectiveModel(""));
    }

    @Test
    public void pending_tappingActiveModelAgainCancelsQueuedModel() {
        PendingSwitchState p = new PendingSwitchState();
        p.queueModel(CANARY, PARAKEET, "bg-BG", "bg-BG");
        assertEquals(CANARY, p.getPendingModel());
        p.queueModel(PARAKEET, PARAKEET, "bg-BG", "bg-BG");
        assertNull(p.getPendingModel());
        assertFalse(p.hasPending());
    }

    @Test
    public void pending_builtinModelIsEmptyString() {
        PendingSwitchState p = new PendingSwitchState();
        p.queueModel("", PARAKEET, "", "bg-BG");
        assertEquals("", p.getPendingModel());
        assertTrue(p.hasPending());
        p.queueModel(PARAKEET, PARAKEET, "", "bg-BG");
        assertFalse(p.hasPending());
    }

    @Test
    public void pending_canaryWithAutoLanguageQueuesLastExplicit() {
        PendingSwitchState p = new PendingSwitchState();
        p.queueModel(CANARY, PARAKEET, "", "en-US");
        assertEquals(CANARY, p.getPendingModel());
        assertEquals("en-US", p.getPendingLanguage());
        assertEquals("en-US", p.effectiveLanguage(""));
    }

    @Test
    public void pending_canaryWithExplicitLanguageQueuesNoLanguage() {
        PendingSwitchState p = new PendingSwitchState();
        p.queueModel(CANARY, PARAKEET, "bg-BG", "bg-BG");
        assertNull(p.getPendingLanguage());
    }

    @Test
    public void pending_revertingFromCanaryClearsAutoQueuedLanguage() {
        PendingSwitchState p = new PendingSwitchState();
        p.queueModel(CANARY, PARAKEET, "", "bg-BG");
        assertEquals("bg-BG", p.getPendingLanguage());
        p.queueModel(PARAKEET, PARAKEET, "", "bg-BG");
        assertNull(p.getPendingModel());
        assertNull(p.getPendingLanguage());
        assertFalse(p.hasPending());
    }

    @Test
    public void pending_retappingCanaryKeepsAutoQueuedLanguage() {
        PendingSwitchState p = new PendingSwitchState();
        p.queueModel(CANARY, PARAKEET, "", "bg-BG");
        p.queueModel(CANARY, PARAKEET, "", "bg-BG");
        assertEquals(CANARY, p.getPendingModel());
        assertEquals("bg-BG", p.getPendingLanguage());
    }

    @Test
    public void pending_revertingFromCanaryKeepsExplicitlyTappedLanguage() {
        PendingSwitchState p = new PendingSwitchState();
        p.queueLanguage("en-US", PARAKEET, "bg-BG");
        p.queueModel(CANARY, PARAKEET, "bg-BG", "bg-BG");
        p.queueModel(PARAKEET, PARAKEET, "bg-BG", "bg-BG");
        assertNull(p.getPendingModel());
        assertEquals("en-US", p.getPendingLanguage());
    }

    @Test
    public void pending_explicitLanguageTapOverridesAutoQueuedOne() {
        PendingSwitchState p = new PendingSwitchState();
        p.queueModel(CANARY, PARAKEET, "", "bg-BG");
        assertEquals("bg-BG", p.getPendingLanguage());
        p.queueLanguage("en-US", PARAKEET, "");
        assertEquals("en-US", p.getPendingLanguage());
        // Reverting the model no longer drops the language the user chose.
        p.queueModel(PARAKEET, PARAKEET, "", "bg-BG");
        assertEquals("en-US", p.getPendingLanguage());
    }

    @Test
    public void pending_queueLanguageAndCancel() {
        PendingSwitchState p = new PendingSwitchState();
        p.queueLanguage("en-US", PARAKEET, "bg-BG");
        assertEquals("en-US", p.getPendingLanguage());
        assertTrue(p.hasPending());
        p.queueLanguage("bg-BG", PARAKEET, "bg-BG");
        assertNull(p.getPendingLanguage());
        assertFalse(p.hasPending());
    }

    @Test
    public void pending_effectiveValuesFallBackToActive() {
        PendingSwitchState p = new PendingSwitchState();
        assertEquals(PARAKEET, p.effectiveModel(PARAKEET));
        assertEquals("bg-BG", p.effectiveLanguage("bg-BG"));
        assertEquals("", p.effectiveModel(null));
        assertEquals("", p.effectiveLanguage(null));
    }

    @Test
    public void pending_clearResetsEverything() {
        PendingSwitchState p = new PendingSwitchState();
        p.queueModel(CANARY, PARAKEET, "", "bg-BG");
        p.queueLanguage("en-US", PARAKEET, "");
        p.clear();
        assertFalse(p.hasPending());
        assertNull(p.getPendingModel());
        assertNull(p.getPendingLanguage());
        // After clear, an auto-queued language is auto again (not sticky-explicit).
        p.queueModel(CANARY, PARAKEET, "", "bg-BG");
        p.queueModel(PARAKEET, PARAKEET, "", "bg-BG");
        assertNull(p.getPendingLanguage());
    }
}
