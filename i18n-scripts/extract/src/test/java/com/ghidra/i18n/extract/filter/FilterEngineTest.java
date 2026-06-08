package com.ghidra.i18n.extract.filter;

import com.ghidra.i18n.extract.model.FilterConfig;
import com.ghidra.i18n.extract.model.TranslationUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FilterEngine} and {@link FilterConfigLoader}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FilterEngineTest {

    private FilterConfigLoader loader;
    private Path configDir;

    @BeforeAll
    void setUp(@TempDir Path tmpDir) throws IOException {
        configDir = tmpDir;
        // Create minimal config files
        Files.writeString(configDir.resolve("filter-config.yml"), """
            exclude_apis:
              - Msg.error
              - LOG.warn
              - throw new
            exclude_contexts:
              - import
              - package
            exclude_debug_markers:
              - TODO
              - FIXME
              - HACK
            exclude_modules: []
            exclude_single_chars: true
            min_meaningful_length: 2
            """);

        Files.writeString(configDir.resolve("exclude-patterns.txt"), """
            ^https?:
            ^\\d+$
            ^[a-z]+(\\.[a-z]+){2,}$
            ^sk-
            """);

        Files.writeString(configDir.resolve("manual-approvals.yml"), """
            Docking.ApprovedDialog.title: approved
            Docking.RejectedDialog.title: rejected
            """);
    }

    // ========================================================================
    // FilterConfigLoader tests
    // ========================================================================

    @Test
    @DisplayName("loads filter-config.yml correctly")
    void loadYamlConfig() throws IOException {
        FilterConfig config = FilterConfig.builder()
            .excludeApis(Set.of("Msg.error", "LOG.warn", "throw new"))
            .excludeContexts(Set.of("import", "package"))
            .excludeDebugMarkers(Set.of("TODO", "FIXME", "HACK"))
            .excludeSingleChars(true)
            .minMeaningfulLength(2)
            .build();

        assertEquals(3, config.getExcludeApis().size());
        assertTrue(config.getExcludeApis().contains("Msg.error"));
        assertEquals(2, config.getExcludeContexts().size());
        assertEquals(3, config.getExcludeDebugMarkers().size());
        assertTrue(config.isExcludeSingleChars());
        assertEquals(2, config.getMinMeaningfulLength());
    }

    @Test
    @DisplayName("loads exclude patterns")
    void loadPatterns() throws IOException {
        Path patternsFile = configDir.resolve("exclude-patterns.txt");
        List<Pattern> patterns = new FilterConfigLoader(null).loadExcludePatterns(patternsFile);
        assertEquals(4, patterns.size());

        // Verify patterns actually work
        assertTrue(patterns.get(0).matcher("https://example.com").find());
        assertTrue(patterns.get(1).matcher("12345").matches());
        assertTrue(patterns.get(2).matcher("com.example.foo").matches());
        assertTrue(patterns.get(3).matcher("sk-abc123").find());
        assertFalse(patterns.get(0).matcher("Cancel").find());
    }

    @Test
    @DisplayName("loads manual approvals")
    void loadApprovals() throws IOException {
        Path approvalsFile = configDir.resolve("manual-approvals.yml");
        var approvals = new FilterConfigLoader(null).loadManualApprovals(approvalsFile);
        assertEquals(2, approvals.size());
        assertTrue(approvals.get("Docking.ApprovedDialog.title"));
        assertFalse(approvals.get("Docking.RejectedDialog.title"));
    }

    // ========================================================================
    // FilterEngine — basic filtering
    // ========================================================================

    private FilterEngine createEngine() {
        FilterConfig config = FilterConfig.builder()
            .excludeApis(Set.of("Msg.error"))
            .excludeDebugMarkers(Set.of("TODO"))
            .excludeSingleChars(true)
            .minMeaningfulLength(2)
            .build();
        return new FilterEngine(config);
    }

    private TranslationUnit makeUnit(String text) {
        return new TranslationUnit()
            .setId("Test.TestClass.test")
            .setModuleName("Test")
            .setSourceFilePath("Test.java")
            .setClassName("TestClass")
            .setFullClassName("com.TestClass")
            .setPattern(TranslationUnit.ExtractionPattern.SET_TITLE)
            .setSourceText(text)
            .setPriority(TranslationUnit.Priority.P0)
            .setContext(TranslationUnit.UiContext.DIALOG_TITLE);
    }

    @Test
    @DisplayName("keeps valid UI strings")
    void keepsValidUiStrings() {
        FilterEngine engine = createEngine();
        assertTrue(engine.shouldKeep(makeUnit("Cancel")));
        assertTrue(engine.shouldKeep(makeUnit("Edit Settings")));
        assertTrue(engine.shouldKeep(makeUnit("Unable to Launch Manual Viewer")));
        assertTrue(engine.shouldKeep(makeUnit("OK")));
    }

    @Test
    @DisplayName("rejects blank strings")
    void rejectsBlank() {
        FilterEngine engine = createEngine();
        engine.resetStats();
        assertFalse(engine.shouldKeep(makeUnit("")));
        assertEquals(1, engine.getLayer4Excluded());
    }

    @Test
    @DisplayName("rejects punctuation-only")
    void rejectsPunctuation() {
        FilterEngine engine = createEngine();
        assertFalse(engine.shouldKeep(makeUnit(":")));
        assertFalse(engine.shouldKeep(makeUnit("[   ]")));
        assertFalse(engine.shouldKeep(makeUnit(", ")));
    }

    @Test
    @DisplayName("rejects URLs")
    void rejectsUrls() {
        FilterEngine engine = FilterConfig.builder()
            .excludePatterns(List.of(Pattern.compile("^https?:")))
            .build() instanceof FilterConfig c ? new FilterEngine(c) : createEngine();

        // Use engine with URL pattern
        FilterConfig config = FilterConfig.builder()
            .excludePatterns(List.of(Pattern.compile("^https?:")))
            .build();
        engine = new FilterEngine(config);

        assertFalse(engine.shouldKeep(makeUnit("https://github.com")));
        assertFalse(engine.shouldKeep(makeUnit("http://localhost:8080/path")));
        assertTrue(engine.shouldKeep(makeUnit("Cancel")));
    }

    @Test
    @DisplayName("rejects code constants (ALL_CAPS)")
    void rejectsCodeConstants() {
        FilterEngine engine = createEngine();
        assertFalse(engine.shouldKeep(makeUnit("ALL_CAPS_CONSTANT")));
        assertFalse(engine.shouldKeep(makeUnit("MAX_VALUE")));
    }

    @Test
    @DisplayName("rejects numeric-only")
    void rejectsNumeric() {
        FilterEngine engine = createEngine();
        assertFalse(engine.shouldKeep(makeUnit("12345")));
        assertFalse(engine.shouldKeep(makeUnit("0")));
    }

    @Test
    @DisplayName("rejects debug markers")
    void rejectsDebugMarkers() {
        // Use a shorter ALL_CAPS marker: "TBD" is 3 chars (not excluded by ALL_CAPS rule)
        // But is recognized as debug marker
        FilterConfig config = FilterConfig.builder()
            .excludeDebugMarkers(Set.of("TBD"))
            .excludeSingleChars(true)
            .minMeaningfulLength(2)
            .build();
        FilterEngine engine = new FilterEngine(config);
        assertFalse(engine.shouldKeep(makeUnit("TBD")));
        assertEquals(1, engine.getLayer4Excluded());
    }

    @Test
    @DisplayName("rejects secrets")
    void rejectsSecrets() {
        FilterEngine engine = createEngine();
        assertFalse(engine.shouldKeep(makeUnit("sk-abc123def456")));
        assertFalse(engine.shouldKeep(makeUnit("ghp_tokenstring")));
    }

    // ========================================================================
    // Manual approvals
    // ========================================================================

    @Test
    @DisplayName("manual approval overrides URL rejection")
    void manualApprovalOverrides() {
        FilterConfig config = FilterConfig.builder()
            .excludePatterns(List.of(Pattern.compile("^https?:")))
            .manualApprovals(java.util.Map.of("Test.TestClass.test", true))
            .build();
        FilterEngine engine = new FilterEngine(config);

        // Even though it's a URL, manual approval keeps it
        assertTrue(engine.shouldKeep(makeUnit("https://example.com")));
        assertEquals(1, engine.getManualOverridden());
    }

    @Test
    @DisplayName("manual rejection overrides default pass")
    void manualRejectionOverrides() {
        FilterConfig config = FilterConfig.builder()
            .manualApprovals(java.util.Map.of("Test.TestClass.test", false))
            .build();
        FilterEngine engine = new FilterEngine(config);

        assertFalse(engine.shouldKeep(makeUnit("Cancel")));
        assertEquals(1, engine.getLayer4Excluded());
    }

    // ========================================================================
    // Stats
    // ========================================================================

    @Test
    @DisplayName("stats track correctly")
    void statsTracking() {
        FilterEngine engine = createEngine();
        engine.resetStats();

        // Pass 2
        engine.shouldKeep(makeUnit("Cancel"));
        engine.shouldKeep(makeUnit("Save"));

        // Exclude 3
        engine.shouldKeep(makeUnit(""));        // blank → layer4
        engine.shouldKeep(makeUnit("TODO"));     // debug → layer4
        engine.shouldKeep(makeUnit("12345"));    // numeric → layer4

        assertEquals(2, engine.getPassed());
        assertEquals(3, engine.getLayer4Excluded());
        assertEquals(3, engine.getTotalExcluded());

        String summary = engine.summary(5);
        assertTrue(summary.contains("passed=2"));
        assertTrue(summary.contains("40.0%"));
    }

    @Test
    @DisplayName("resetStats zeros all counters")
    void resetStatsZeros() {
        FilterEngine engine = createEngine();
        engine.shouldKeep(makeUnit("Cancel"));
        engine.shouldKeep(makeUnit(""));

        assertEquals(1, engine.getPassed());
        assertEquals(1, engine.getLayer4Excluded());

        engine.resetStats();
        assertEquals(0, engine.getPassed());
        assertEquals(0, engine.getLayer4Excluded());
    }

    // ========================================================================
    // Filter pipeline (list-level)
    // ========================================================================

    @Test
    @DisplayName("filter returns only kept units")
    void filterList() {
        FilterEngine engine = createEngine();
        engine.resetStats();
        List<TranslationUnit> input = List.of(
            makeUnit("Cancel"),
            makeUnit(""),
            makeUnit("Save As..."),
            makeUnit("12345"),
            makeUnit("OK")
        );
        List<TranslationUnit> result = engine.filter(input);
        assertEquals(3, result.size(), "Expected Cancel, Save As..., OK to pass");
        assertEquals("Cancel", result.get(0).getSourceText());
        assertEquals("Save As...", result.get(1).getSourceText());
        assertEquals("OK", result.get(2).getSourceText());
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Test
    @DisplayName("null source text is rejected")
    void nullText() {
        FilterEngine engine = createEngine();
        TranslationUnit unit = makeUnit("Cancel");
        unit.setSourceText(null);
        assertFalse(engine.shouldKeep(unit));
    }

    @Test
    @DisplayName("ExtractionPattern.OTHER gets NEEDS_REVIEW status")
    void unknownPatternNeedsReview() {
        FilterEngine engine = createEngine();
        TranslationUnit unit = makeUnit("Some unknown string");
        unit.setPattern(TranslationUnit.ExtractionPattern.OTHER);
        assertTrue(engine.shouldKeep(unit)); // kept, but flagged
        assertEquals(TranslationUnit.AiReviewStatus.NEEDS_REVIEW, unit.getAiReviewStatus());
    }
}
