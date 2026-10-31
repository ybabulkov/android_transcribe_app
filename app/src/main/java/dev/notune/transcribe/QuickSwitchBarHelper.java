package dev.notune.transcribe;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives the quick model / language chip bar ({@code quick_switch_bar.xml}) on
 * the voice keyboard and the voice popup. One tap on a chip selects a model or
 * a language; while a recording or its transcription is running (the host's
 * {@link Host#shouldDeferSwitch()}), the tap is only queued in the host-owned
 * {@link PendingSwitchState} and applied by {@link #flushPending()} once the
 * utterance is done, so dictation is never interrupted.
 *
 * All views and colors come from the bar's own themed context (the IME and the
 * activity wrap their context in the Material 3 / dynamic-color theme; the
 * service context alone has no such attributes).
 */
public final class QuickSwitchBarHelper {

    /** What the bar needs from the keyboard service or popup activity. */
    public interface Host {
        /** True while recording or transcribing: taps are queued, not applied. */
        boolean shouldDeferSwitch();

        /** The selection on disk changed (or a queued switch was resolved); reload the engine if needed. */
        void onSelectionApplied(boolean modelChanged, boolean langChanged);

        /** The queued switch changed; update any "Next: …" hint. */
        void onPendingChanged();

        /** The settings button was tapped. */
        void onManageModelsClicked();
    }

    private final View barView;
    private final Context themedContext;
    private final PendingSwitchState pendingState;
    private final Host host;
    private final LinearLayout modelChips;
    private final TextView chipBg;
    private final TextView chipEn;

    public QuickSwitchBarHelper(View rootView, PendingSwitchState pendingState, Host host) {
        View bar = rootView.findViewById(R.id.quick_switch_bar);
        this.barView = bar != null ? bar : rootView;
        this.themedContext = barView.getContext();
        this.pendingState = pendingState;
        this.host = host;
        this.modelChips = barView.findViewById(R.id.quick_model_chips);
        this.chipBg = barView.findViewById(R.id.chip_lang_bg);
        this.chipEn = barView.findViewById(R.id.chip_lang_en);

        barView.findViewById(R.id.btn_manage_models)
                .setOnClickListener(v -> host.onManageModelsClicked());
        chipBg.setOnClickListener(v -> onLanguageTapped(ModelStore.LANG_BG));
        chipEn.setOnClickListener(v -> onLanguageTapped(ModelStore.LANG_EN));
        refresh();
    }

    public boolean hasPending() {
        return pendingState.hasPending();
    }

    /** "Model · Lang" of the selection that will be active after the queued switch, for the "Next: …" hint. */
    public String getPendingSummary() {
        String model = pendingState.effectiveModel(ModelStore.activeModel(themedContext));
        String lang = pendingState.effectiveLanguage(ModelStore.activeLanguage(themedContext));
        return ModelStore.shortModelLabel(model, themedContext.getString(R.string.quick_builtin_short))
                + " \u00B7 " + languageLabel(lang);
    }

    private String languageLabel(String lang) {
        if (ModelStore.LANG_BG.equals(lang)) return themedContext.getString(R.string.quick_lang_bg);
        if (ModelStore.LANG_EN.equals(lang)) return themedContext.getString(R.string.quick_lang_en);
        if (lang == null || lang.isEmpty() || lang.equalsIgnoreCase("auto")) {
            return themedContext.getString(R.string.models_language_auto);
        }
        return lang;
    }

    /**
     * Re-reads the selection and redraws the chips. While a recording or
     * transcription is running nothing is written to disk: validating the
     * selection could change files the running session depends on.
     */
    public void refresh() {
        String activeModel;
        if (!host.shouldDeferSwitch()) {
            activeModel = ModelStore.ensureValidSelection(themedContext);
            ModelStore.ensureValidLanguageForModel(themedContext, activeModel);
        } else {
            activeModel = ModelStore.activeModel(themedContext);
        }
        String activeLang = ModelStore.activeLanguage(themedContext);

        // Model chips: "" is the bundled model (absent in F-Droid builds).
        final List<String> keys = new ArrayList<>();
        if (ModelStore.hasBuiltinModel(themedContext)) keys.add("");
        keys.addAll(ModelStore.importedModels(themedContext));
        List<String> labels = ModelStore.buildDisambiguatedLabels(
                keys, themedContext.getString(R.string.quick_builtin_short));

        modelChips.removeAllViews();
        if (keys.isEmpty()) {
            TextView add = newChip(themedContext.getString(R.string.quick_add_model));
            styleChip(add, themedContext.getString(R.string.quick_add_model), ChipState.INACTIVE);
            add.setOnClickListener(v -> host.onManageModelsClicked());
            modelChips.addView(add);
        }
        String pendingModel = pendingState.getPendingModel();
        for (int i = 0; i < keys.size(); i++) {
            final String key = keys.get(i);
            String label = labels.get(i);
            ChipState state = key.equals(pendingModel) ? ChipState.PENDING
                    : (key.equals(activeModel) ? ChipState.ACTIVE : ChipState.INACTIVE);
            TextView chip = newChip(label);
            styleChip(chip, label, state);
            chip.setOnClickListener(v -> onModelTapped(key));
            modelChips.addView(chip);
        }

        styleLanguageChip(chipBg, R.string.quick_lang_bg, ModelStore.LANG_BG, activeLang);
        styleLanguageChip(chipEn, R.string.quick_lang_en, ModelStore.LANG_EN, activeLang);
    }

    private void styleLanguageChip(TextView chip, int labelRes, String tag, String activeLang) {
        ChipState state = tag.equals(pendingState.getPendingLanguage()) ? ChipState.PENDING
                : (tag.equals(activeLang) ? ChipState.ACTIVE : ChipState.INACTIVE);
        styleChip(chip, themedContext.getString(labelRes), state);
    }

    private enum ChipState { ACTIVE, PENDING, INACTIVE }

    private TextView newChip(String label) {
        TextView chip = new TextView(themedContext);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        chip.setTypeface(chip.getTypeface(), Typeface.BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setMinWidth(dp(44));
        chip.setPadding(dp(12), 0, dp(12), 0);
        chip.setBackgroundResource(R.drawable.bg_quick_chip);
        chip.setClickable(true);
        chip.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        lp.setMarginEnd(dp(4));
        chip.setLayoutParams(lp);
        return chip;
    }

    private void styleChip(TextView chip, String label, ChipState state) {
        int fill;
        int text;
        String shown = label;
        switch (state) {
            case ACTIVE:
                fill = color(com.google.android.material.R.attr.colorPrimary);
                text = color(com.google.android.material.R.attr.colorOnPrimary);
                break;
            case PENDING:
                fill = color(com.google.android.material.R.attr.colorSecondaryContainer);
                text = color(com.google.android.material.R.attr.colorOnSecondaryContainer);
                shown = label + " \u23F1";
                break;
            default:
                fill = Color.TRANSPARENT;
                text = color(com.google.android.material.R.attr.colorOnSurfaceVariant);
                break;
        }
        chip.setText(shown);
        chip.setTextColor(text);
        chip.setBackgroundTintList(ColorStateList.valueOf(fill));
        chip.setSelected(state == ChipState.ACTIVE);
    }

    private int color(int attr) {
        return MaterialColors.getColor(barView, attr);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                themedContext.getResources().getDisplayMetrics()));
    }

    // --- Taps ---------------------------------------------------------------

    private void onModelTapped(String key) {
        String activeModel = ModelStore.activeModel(themedContext);
        String activeLang = ModelStore.activeLanguage(themedContext);
        if (host.shouldDeferSwitch()) {
            pendingState.queueModel(key, activeModel, activeLang,
                    ModelStore.lastExplicitLanguage(themedContext));
            refresh();
            host.onPendingChanged();
            return;
        }
        pendingState.clear();
        boolean modelChanged = !key.equals(activeModel);
        if (modelChanged) ModelStore.setActiveModel(themedContext, key);
        String langAfter = ModelStore.ensureValidLanguageForModel(themedContext, key);
        boolean langChanged = !langAfter.equals(activeLang);
        refresh();
        host.onPendingChanged();
        if (modelChanged || langChanged) host.onSelectionApplied(modelChanged, langChanged);
    }

    private void onLanguageTapped(String tag) {
        String activeModel = ModelStore.activeModel(themedContext);
        String activeLang = ModelStore.activeLanguage(themedContext);
        if (host.shouldDeferSwitch()) {
            pendingState.queueLanguage(tag, activeModel, activeLang);
            refresh();
            host.onPendingChanged();
            return;
        }
        pendingState.clear();
        boolean langChanged = !tag.equals(activeLang);
        if (langChanged) ModelStore.setActiveLanguage(themedContext, tag);
        refresh();
        host.onPendingChanged();
        if (langChanged) host.onSelectionApplied(false, true);
    }

    /**
     * Applies the queued switch, if any: call when a recording and its
     * transcription are over (finished, canceled or failed). A queued model
     * that was deleted in the meantime falls back to a valid selection.
     * Returns false if nothing was queued.
     */
    public boolean flushPending() {
        if (!pendingState.hasPending()) return false;
        String pendingModel = pendingState.getPendingModel();
        String pendingLang = pendingState.getPendingLanguage();
        pendingState.clear();

        String modelBefore = ModelStore.activeModel(themedContext);
        String langBefore = ModelStore.activeLanguage(themedContext);

        if (pendingModel != null) {
            boolean exists = pendingModel.isEmpty()
                    ? ModelStore.hasBuiltinModel(themedContext)
                    : ModelStore.importedModels(themedContext).contains(pendingModel);
            if (exists) {
                if (!pendingModel.equals(modelBefore)) {
                    ModelStore.setActiveModel(themedContext, pendingModel);
                }
            } else {
                ModelStore.ensureValidSelection(themedContext);
            }
        }
        if (pendingLang != null && !pendingLang.equals(langBefore)) {
            ModelStore.setActiveLanguage(themedContext, pendingLang);
        }
        String modelAfter = ModelStore.activeModel(themedContext);
        String langAfter = ModelStore.ensureValidLanguageForModel(themedContext, modelAfter);

        boolean modelChanged = !modelAfter.equals(modelBefore);
        boolean langChanged = !langAfter.equals(langBefore);
        refresh();
        host.onSelectionApplied(modelChanged, langChanged);
        return true;
    }
}
