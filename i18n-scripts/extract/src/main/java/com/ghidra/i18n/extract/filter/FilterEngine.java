package com.ghidra.i18n.extract.filter;

import com.ghidra.i18n.common.util.StringClassifier;
import com.ghidra.i18n.extract.model.FilterConfig;
import com.ghidra.i18n.extract.model.TranslationUnit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Four-layer filtering engine for translatable string candidates.
 *
 * <p>Layer 1: API exclusion — checks if the string was used in a log/exception call</p>
 * <p>Layer 2: Context exclusion — verifies the source line has a UI pattern</p>
 * <p>Layer 3: Regex exclusion — matches string content against exclude patterns</p>
 * <p>Layer 4: Heuristic exclusion — single chars, punctuation, URLs, secrets, debug markers</p>
 *
 * <p>Manual approvals always override filter decisions.</p>
 */
public class FilterEngine {

    private final FilterConfig config;
    private final StringClassifier classifier;
    private final Set<String> excludeModules;
    private final Set<String> excludeDebugMarkers;
    private final List<Pattern> excludePatterns;

    // Stats
    private int layer1Excluded;
    private int layer2Excluded;
    private int layer3Excluded;
    private int layer4Excluded;
    private int manualOverridden;
    private int passed;

    public FilterEngine(FilterConfig config) {
        this.config = config;
        this.classifier = new StringClassifier();
        this.excludeModules = config.getExcludeModules();
        this.excludeDebugMarkers = config.getExcludeDebugMarkers();
        this.excludePatterns = config.getExcludePatterns();
    }

    /**
     * Filters a list of TranslationUnit candidates.
     *
     * <p>Returns a new list containing only the units that pass all four layers
     * (or have manual approval override).</p>
     */
    public List<TranslationUnit> filter(List<TranslationUnit> units) {
        List<TranslationUnit> result = new ArrayList<>();
        for (TranslationUnit unit : units) {
            if (shouldKeep(unit)) {
                result.add(unit);
            }
        }
        return result;
    }

    /**
     * Returns true if this unit should be kept for translation.
     */
    public boolean shouldKeep(TranslationUnit unit) {
        // Module-level exclusion
        if (excludeModules.contains(unit.getModuleName())) {
            layer1Excluded++;
            return false;
        }

        String text = unit.getSourceText();
        if (text == null) return false;
        String trimmed = text.trim();
        boolean isManuallyApproved = config.isManualApproved(unit.getId());

        // ============================================================
        // Layer 1: API exclusion
        // ============================================================
        // If the source file is a Java file and we have the source line context,
        // check if it's a log/exception API call.
        // Since TranslationUnit doesn't store source line context directly,
        // we use a heuristic: check StringClassifier.isLogOrError on the text.
        if (classifier.isLogOrError(trimmed)) {
            if (isManuallyApproved) {
                manualOverridden++;
            } else {
                unit.setAiReviewStatus(TranslationUnit.AiReviewStatus.REJECTED);
                layer1Excluded++;
                return false;
            }
        }

        // ============================================================
        // Layer 2: Context exclusion
        // ============================================================
        // If the pattern is OTHER (unrecognized), flag for review
        if (unit.getPattern() == TranslationUnit.ExtractionPattern.OTHER) {
            unit.setAiReviewStatus(TranslationUnit.AiReviewStatus.NEEDS_REVIEW);
            // Still keep it — let AI decide in later step
        }

        // ============================================================
        // Layer 3: Regex pattern exclusion
        // ============================================================
        for (Pattern p : excludePatterns) {
            if (p.matcher(trimmed).matches() || p.matcher(trimmed).find()) {
                if (isManuallyApproved) {
                    manualOverridden++;
                    break;
                } else {
                    unit.setAiReviewStatus(TranslationUnit.AiReviewStatus.REJECTED);
                    layer3Excluded++;
                    return false;
                }
            }
        }

        // ============================================================
        // Layer 4: Heuristic exclusion
        // ============================================================

        // If manually approved, skip all heuristic checks
        if (isManuallyApproved) { passed++; return true; }

        // 4a. Blank or single character
        if (trimmed.isEmpty()) { layer4Excluded++; return false; }
        if (config.isExcludeSingleChars() && trimmed.length() < config.getMinMeaningfulLength()) {
            if (trimmed.matches(".*[a-zA-Z].*")) {
                // "OK" is meaningful at 2 chars
            } else {
                layer4Excluded++;
                return false;
            }
        }

        // 4b. Punctuation-only
        if (classifier.isPunctuationOnly(trimmed)) { layer4Excluded++; return false; }

        // 4c. Code constants (except short acronyms like "OK", "ID", "TV")
        if (classifier.isCodeConstant(trimmed) && trimmed.length() > 3) { layer4Excluded++; return false; }

        // 4d. URLs
        if (classifier.isUrl(trimmed)) { layer4Excluded++; return false; }

        // 4e. Numeric-only
        if (classifier.isNumericOnly(trimmed)) { layer4Excluded++; return false; }

        // 4f. Secrets / API keys
        if (classifier.isSecret(trimmed)) { layer4Excluded++; return false; }

        // 4g. Debug markers
        if (excludeDebugMarkers.contains(trimmed.toUpperCase())) { layer4Excluded++; return false; }

        // ============================================================
        // Manual rejection override (last check)
        // ============================================================
        if (config.isManualRejected(unit.getId())) {
            unit.setAiReviewStatus(TranslationUnit.AiReviewStatus.REJECTED);
            layer4Excluded++;
            return false;
        }

        // Passed all filters
        // Don't override NEEDS_REVIEW set by Layer 2
        if (unit.getAiReviewStatus() != TranslationUnit.AiReviewStatus.NEEDS_REVIEW) {
            unit.setAiReviewStatus(TranslationUnit.AiReviewStatus.PENDING);
        }
        passed++;
        return true;
    }

    // -----------------------------------------------------------------------
    // Statistics
    // -----------------------------------------------------------------------

    public int getLayer1Excluded()  { return layer1Excluded; }
    public int getLayer2Excluded()  { return layer2Excluded; }
    public int getLayer3Excluded()  { return layer3Excluded; }
    public int getLayer4Excluded()  { return layer4Excluded; }
    public int getManualOverridden() { return manualOverridden; }
    public int getPassed()          { return passed; }
    public int getTotalExcluded()   { return layer1Excluded + layer2Excluded + layer3Excluded + layer4Excluded; }

    public void resetStats() {
        layer1Excluded = 0; layer2Excluded = 0; layer3Excluded = 0; layer4Excluded = 0;
        manualOverridden = 0; passed = 0;
    }

    public String summary(int totalInput) {
        return String.format(
            "Filter: in=%d, passed=%d (%.1f%%), excluded=%d\n" +
            "  Layer1 (API): %d, Layer2 (context): %d, Layer3 (regex): %d, Layer4 (heuristic): %d\n" +
            "  Manual overrides: %d",
            totalInput, passed, totalInput > 0 ? passed * 100.0 / totalInput : 0,
            getTotalExcluded(), layer1Excluded, layer2Excluded, layer3Excluded, layer4Excluded,
            manualOverridden
        );
    }
}
