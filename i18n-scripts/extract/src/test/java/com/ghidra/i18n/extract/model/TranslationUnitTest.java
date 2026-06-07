package com.ghidra.i18n.extract.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;

/**
 * Unit tests for {@link TranslationUnit}.
 */
class TranslationUnitTest {

    @Test
    @DisplayName("fluent builder correctly sets all fields")
    void builder() {
        Instant now = Instant.now();
        TranslationUnit unit = new TranslationUnit()
            .setId("Docking.LaunchErrorDialog.title")
            .setModuleName("Docking")
            .setSourceFilePath("Ghidra/Framework/Docking/src/main/java/ghidra/util/LaunchErrorDialog.java")
            .setClassName("LaunchErrorDialog")
            .setFullClassName("ghidra.util.LaunchErrorDialog")
            .setPattern(TranslationUnit.ExtractionPattern.SET_TITLE)
            .setSourceText("Unable to Launch Manual Viewer")
            .setPriority(TranslationUnit.Priority.P0)
            .setContext(TranslationUnit.UiContext.DIALOG_TITLE)
            .setHtml(false)
            .setHasFormatSpecifier(false)
            .setContainsMnemonic(false)
            .setAiReviewStatus(TranslationUnit.AiReviewStatus.APPROVED)
            .setManualApproval(true)
            .setTranslationZhCN("无法启动手册查看器")
            .setTranslationStatus(TranslationUnit.TranslationStatus.MACHINE_TRANSLATED)
            .setLastModified(now);

        assertEquals("Docking.LaunchErrorDialog.title", unit.getId());
        assertEquals("Docking", unit.getModuleName());
        assertEquals("LaunchErrorDialog", unit.getClassName());
        assertEquals(TranslationUnit.ExtractionPattern.SET_TITLE, unit.getPattern());
        assertEquals("Unable to Launch Manual Viewer", unit.getSourceText());
        assertEquals(TranslationUnit.Priority.P0, unit.getPriority());
        assertEquals(TranslationUnit.UiContext.DIALOG_TITLE, unit.getContext());
        assertFalse(unit.isHtml());
        assertFalse(unit.isHasFormatSpecifier());
        assertFalse(unit.isContainsMnemonic());
        assertEquals(TranslationUnit.AiReviewStatus.APPROVED, unit.getAiReviewStatus());
        assertTrue(unit.isManualApproval());
        assertEquals("无法启动手册查看器", unit.getTranslationZhCN());
        assertEquals(TranslationUnit.TranslationStatus.MACHINE_TRANSLATED, unit.getTranslationStatus());
        assertEquals(now, unit.getLastModified());
    }

    @Test
    @DisplayName("isTranslated returns true for MACHINE_TRANSLATED and VERIFIED")
    void isTranslated() {
        TranslationUnit unit = new TranslationUnit()
            .setId("test.id")
            .setModuleName("Test")
            .setSourceFilePath("test.java")
            .setClassName("TestClass")
            .setFullClassName("com.TestClass")
            .setPattern(TranslationUnit.ExtractionPattern.SET_TITLE)
            .setSourceText("Test")
            .setPriority(TranslationUnit.Priority.P0);

        unit.setTranslationStatus(TranslationUnit.TranslationStatus.UNTRANSLATED);
        assertFalse(unit.isTranslated());

        unit.setTranslationStatus(TranslationUnit.TranslationStatus.MACHINE_TRANSLATED);
        assertTrue(unit.isTranslated());

        unit.setTranslationStatus(TranslationUnit.TranslationStatus.VERIFIED);
        assertTrue(unit.isTranslated());

        unit.setTranslationStatus(TranslationUnit.TranslationStatus.NEEDS_UPDATE);
        assertFalse(unit.isTranslated());
    }

    @Test
    @DisplayName("equals and hashCode are based on id")
    void equality() {
        TranslationUnit a = new TranslationUnit().setId("Docking.LaunchErrorDialog.title");
        TranslationUnit b = new TranslationUnit().setId("Docking.LaunchErrorDialog.title");
        TranslationUnit c = new TranslationUnit().setId("Docking.LaunchErrorDialog.button.edit");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, null);
        assertNotEquals(a, "string");
    }

    @Test
    @DisplayName("toString contains id and truncated sourceText")
    void toString_containsId() {
        TranslationUnit unit = new TranslationUnit()
            .setId("Test.id")
            .setSourceText("A very long source text that should be truncated");
        String s = unit.toString();
        assertTrue(s.contains("Test.id"));
        assertTrue(s.contains("A very long source text that should be trunca..."));
    }

    @Test
    @DisplayName("default values are correct")
    void defaults() {
        TranslationUnit unit = new TranslationUnit();
        assertEquals(TranslationUnit.UiContext.OTHER, unit.getContext());
        assertFalse(unit.isHtml());
        assertEquals(TranslationUnit.AiReviewStatus.PENDING, unit.getAiReviewStatus());
        assertFalse(unit.isManualApproval());
        assertEquals("", unit.getTranslationZhCN());
        assertEquals(TranslationUnit.TranslationStatus.UNTRANSLATED, unit.getTranslationStatus());
    }

    @Test
    @DisplayName("getPropertiesKey returns id as-is")
    void getPropertiesKey() {
        TranslationUnit unit = new TranslationUnit()
            .setId("Docking.LaunchErrorDialog.title");
        assertEquals("Docking.LaunchErrorDialog.title", unit.getPropertiesKey());
    }

    @Test
    @DisplayName("all enums have expected values")
    void enumValues() {
        // ExtractionPattern
        assertEquals(24, TranslationUnit.ExtractionPattern.values().length);
        // UiContext
        assertEquals(17, TranslationUnit.UiContext.values().length);
        // Priority
        assertEquals(3, TranslationUnit.Priority.values().length);
        // AiReviewStatus
        assertEquals(4, TranslationUnit.AiReviewStatus.values().length);
        // TranslationStatus
        assertEquals(4, TranslationUnit.TranslationStatus.values().length);
    }
}
