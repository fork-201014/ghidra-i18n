package com.ghidra.i18n.extract.ai;

import com.ghidra.i18n.common.config.GlobalConfig;
import com.ghidra.i18n.extract.model.TranslationUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AiReviewer}.
 *
 * <p>Tests the prompt building, response parsing, and stat tracking logic.
 * Does NOT call real APIs — uses mock API key to trigger error handling.</p>
 */
class AiReviewerTest {

    private AiReviewer reviewer;
    private GlobalConfig config;

    @BeforeEach
    void setUp() {
        config = GlobalConfig.forTesting(Path.of("/tmp/ghidra-test"));
        // Set fake API keys so it won't throw immediately
        // but actual HTTP calls will fail gracefully
        reviewer = new AiReviewer(config);
    }

    // ========================================================================
    // Prompt construction
    // ========================================================================

    @Test
    @DisplayName("buildPrompt includes pattern and context for each unit")
    void promptContainsPatternAndContext() throws Exception {
        List<TranslationUnit> units = List.of(
            makeUnit("Cancel", TranslationUnit.ExtractionPattern.NEW_JBUTTON,
                TranslationUnit.UiContext.BUTTON),
            makeUnit("Unable to Launch Manual Viewer", TranslationUnit.ExtractionPattern.SET_TITLE,
                TranslationUnit.UiContext.DIALOG_TITLE)
        );

        // Use reflection to test private buildPrompt method
        java.lang.reflect.Method method = AiReviewer.class.getDeclaredMethod("buildPrompt", List.class);
        method.setAccessible(true);
        String prompt = (String) method.invoke(reviewer, units);

        assertNotNull(prompt);
        assertTrue(prompt.contains("[1]"));
        assertTrue(prompt.contains("[2]"));
        assertTrue(prompt.contains("Cancel"));
        assertTrue(prompt.contains("Unable to Launch Manual Viewer"));
        assertTrue(prompt.contains("NEW_JBUTTON"));
        assertTrue(prompt.contains("SET_TITLE"));
        assertTrue(prompt.contains("BUTTON"));
        assertTrue(prompt.contains("DIALOG_TITLE"));
        assertTrue(prompt.contains("APPROVED"));
        assertTrue(prompt.contains("REJECTED"));
    }

    @Test
    @DisplayName("buildPrompt handles empty batch")
    void promptEmpty() throws Exception {
        List<TranslationUnit> units = List.of();
        java.lang.reflect.Method method = AiReviewer.class.getDeclaredMethod("buildPrompt", List.class);
        method.setAccessible(true);
        String prompt = (String) method.invoke(reviewer, units);
        assertNotNull(prompt);
        assertFalse(prompt.contains("[1]"));
    }

    // ========================================================================
    // Response parsing
    // ========================================================================

    @Test
    @DisplayName("parseResponse correctly classifies APPROVED/REJECTED/UNCERTAIN")
    void parseApprovedRejectedUncertain() throws Exception {
        List<TranslationUnit> batch = new ArrayList<>();
        batch.add(makeUnit("Save", TranslationUnit.ExtractionPattern.NEW_JBUTTON,
            TranslationUnit.UiContext.BUTTON));
        batch.add(makeUnit("Error: failed", TranslationUnit.ExtractionPattern.SET_TITLE,
            TranslationUnit.UiContext.DIALOG_MESSAGE));
        batch.add(makeUnit("Some config", TranslationUnit.ExtractionPattern.OTHER,
            TranslationUnit.UiContext.OTHER));

        String response = """
            1 | APPROVED | 按钮文本，用户可见
            2 | REJECTED  | 错误消息，仅内部使用
            3 | UNCERTAIN | 上下文不明确
            """;

        java.lang.reflect.Method method = AiReviewer.class.getDeclaredMethod(
            "parseResponse", String.class, List.class);
        method.setAccessible(true);
        method.invoke(reviewer, response, batch);

        assertEquals(TranslationUnit.AiReviewStatus.APPROVED, batch.get(0).getAiReviewStatus());
        assertEquals(TranslationUnit.AiReviewStatus.REJECTED, batch.get(1).getAiReviewStatus());
        assertEquals(TranslationUnit.AiReviewStatus.NEEDS_REVIEW, batch.get(2).getAiReviewStatus());
    }

    @Test
    @DisplayName("parseResponse handles alternate format: N. APPROVED / N) REJECTED")
    void parseAlternateFormats() throws Exception {
        List<TranslationUnit> batch = new ArrayList<>();
        batch.add(makeUnit("Save", TranslationUnit.ExtractionPattern.NEW_JBUTTON,
            TranslationUnit.UiContext.BUTTON));
        batch.add(makeUnit("Load", TranslationUnit.ExtractionPattern.NEW_JBUTTON,
            TranslationUnit.UiContext.BUTTON));

        String response = """
            1. APPROVED
            2) REJECTED
            """;

        java.lang.reflect.Method method = AiReviewer.class.getDeclaredMethod(
            "parseResponse", String.class, List.class);
        method.setAccessible(true);
        method.invoke(reviewer, response, batch);

        assertEquals(TranslationUnit.AiReviewStatus.APPROVED, batch.get(0).getAiReviewStatus());
        assertEquals(TranslationUnit.AiReviewStatus.REJECTED, batch.get(1).getAiReviewStatus());
    }

    @Test
    @DisplayName("parseResponse ignores malformed lines")
    void parseMalformed() throws Exception {
        List<TranslationUnit> batch = new ArrayList<>();
        batch.add(makeUnit("Save", TranslationUnit.ExtractionPattern.NEW_JBUTTON,
            TranslationUnit.UiContext.BUTTON));

        String response = """
            This is not a valid response
            neither is this
            1 | APPROVED | valid line
            """;

        java.lang.reflect.Method method = AiReviewer.class.getDeclaredMethod(
            "parseResponse", String.class, List.class);
        method.setAccessible(true);
        method.invoke(reviewer, response, batch);

        assertEquals(TranslationUnit.AiReviewStatus.APPROVED, batch.get(0).getAiReviewStatus());
    }

    // ========================================================================
    // Review pipeline (no-op with no API key configured)
    // ========================================================================

    @Test
    @DisplayName("review with no matching units returns unchanged")
    void reviewSkipsNonPending() {
        List<TranslationUnit> units = new ArrayList<>();
        TranslationUnit u = makeUnit("Save", TranslationUnit.ExtractionPattern.NEW_JBUTTON,
            TranslationUnit.UiContext.BUTTON);
        u.setAiReviewStatus(TranslationUnit.AiReviewStatus.APPROVED); // already done
        units.add(u);

        List<TranslationUnit> result = reviewer.review(units);
        assertSame(units, result);
        // No API call made because no PENDING/NEEDS_REVIEW units
    }

    @Test
    @DisplayName("review with fake API key gracefully fails")
    void reviewGracefulFailure() {
        // Config with fake key — API call will fail
        GlobalConfig fakeConfig = new GlobalConfig.Builder()
            .ghidraRoot(Path.of("/tmp/test"))
            .openAiApiKey("sk-fake-key-that-does-not-work")
            .deepSeekApiKey("")
            .build();
        AiReviewer r = new AiReviewer(fakeConfig);

        List<TranslationUnit> batch = new ArrayList<>();
        batch.add(makeUnit("Save", TranslationUnit.ExtractionPattern.NEW_JBUTTON,
            TranslationUnit.UiContext.BUTTON));

        // Should not throw — gracefully handles API errors
        List<TranslationUnit> result = r.review(batch);
        assertNotNull(result);
        assertEquals(1, r.getApiErrors()); // API call failed
    }

    // ========================================================================
    // Stats
    // ========================================================================

    @Test
    @DisplayName("stats increment correctly")
    void statsIncrement() throws Exception {
        reviewer.resetStats();

        List<TranslationUnit> batch = new ArrayList<>();
        batch.add(makeUnit("Save", TranslationUnit.ExtractionPattern.NEW_JBUTTON,
            TranslationUnit.UiContext.BUTTON));
        batch.add(makeUnit("Error", TranslationUnit.ExtractionPattern.SET_TITLE,
            TranslationUnit.UiContext.DIALOG_TITLE));
        batch.add(makeUnit("Flags", TranslationUnit.ExtractionPattern.NEW_JBUTTON,
            TranslationUnit.UiContext.BUTTON));

        String response = """
            1 | APPROVED | ok
            2 | REJECTED | no
            3 | UNCERTAIN | maybe
            """;

        java.lang.reflect.Method method = AiReviewer.class.getDeclaredMethod(
            "parseResponse", String.class, List.class);
        method.setAccessible(true);
        method.invoke(reviewer, response, batch);

        assertEquals(3, reviewer.getReviewed());
        assertEquals(1, reviewer.getApproved());
        assertEquals(1, reviewer.getRejected());
        assertEquals(1, reviewer.getUncertain());

        String summary = reviewer.summary();
        assertTrue(summary.contains("approved=1"));
        assertTrue(summary.contains("rejected=1"));
        assertTrue(summary.contains("uncertain=1"));
    }

    @Test
    @DisplayName("resetStats zeros all counters")
    void statsReset() {
        reviewer.resetStats();
        assertEquals(0, reviewer.getReviewed());
        assertEquals(0, reviewer.getApproved());
        assertEquals(0, reviewer.getRejected());
        assertEquals(0, reviewer.getUncertain());
        assertEquals(0, reviewer.getApiErrors());
    }

    // ========================================================================
    // Helper
    // ========================================================================

    private TranslationUnit makeUnit(String text, TranslationUnit.ExtractionPattern pattern,
                                      TranslationUnit.UiContext context) {
        return new TranslationUnit()
            .setId("Test.Unit." + text.hashCode())
            .setModuleName("Test")
            .setSourceFilePath("Test.java")
            .setClassName("TestClass")
            .setFullClassName("com.TestClass")
            .setPattern(pattern)
            .setSourceText(text)
            .setPriority(TranslationUnit.Priority.P0)
            .setContext(context)
            .setAiReviewStatus(TranslationUnit.AiReviewStatus.PENDING);
    }
}
