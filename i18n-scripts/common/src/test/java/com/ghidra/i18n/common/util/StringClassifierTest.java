package com.ghidra.i18n.common.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StringClassifier}.
 */
class StringClassifierTest {

    private StringClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new StringClassifier();
    }

    // -----------------------------------------------------------------------
    // classifyPattern
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("classifyPattern recognises JButton constructor pattern")
    void classifyPattern_JButton() {
        assertEquals("JButton", classifier.classifyPattern("new JButton(\"Cancel\")"));
    }

    @Test
    @DisplayName("classifyPattern recognises setTitle pattern")
    void classifyPattern_setTitle() {
        assertEquals("setTitle", classifier.classifyPattern(".setTitle(\"My Dialog\")"));
    }

    @Test
    @DisplayName("classifyPattern recognises GLabel pattern")
    void classifyPattern_GLabel() {
        assertEquals("GLabel", classifier.classifyPattern("new GLabel(\"URL: \")"));
    }

    @Test
    @DisplayName("classifyPattern recognises TitledBorder pattern")
    void classifyPattern_TitledBorder() {
        assertEquals("TitledBorder", classifier.classifyPattern("new TitledBorder(\"Options\")"));
    }

    @Test
    @DisplayName("classifyPattern returns 'other' for unknown pattern")
    void classifyPattern_other() {
        assertEquals("other", classifier.classifyPattern("int x = 42;"));
    }

    @Test
    @DisplayName("classifyPattern returns 'unknown' for null/blank")
    void classifyPattern_null() {
        assertEquals("unknown", classifier.classifyPattern(null));
        assertEquals("unknown", classifier.classifyPattern("  "));
    }

    // -----------------------------------------------------------------------
    // isUserFacing
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("isUserFacing returns true for typical UI strings")
    void isUserFacing_typicalUi() {
        assertTrue(classifier.isUserFacing("Cancel"));
        assertTrue(classifier.isUserFacing("Edit Settings"));
        assertTrue(classifier.isUserFacing("Unable to Launch Manual Viewer"));
    }

    @Test
    @DisplayName("isUserFacing returns false for null/blank")
    void isUserFacing_null() {
        assertFalse(classifier.isUserFacing(null));
        assertFalse(classifier.isUserFacing(""));
        assertFalse(classifier.isUserFacing("   "));
    }

    @Test
    @DisplayName("isUserFacing returns false for punctuation-only")
    void isUserFacing_punctuation() {
        assertFalse(classifier.isUserFacing(":"));
        assertFalse(classifier.isUserFacing(", "));
        assertFalse(classifier.isUserFacing("["));
    }

    @Test
    @DisplayName("isUserFacing returns false for code constants")
    void isUserFacing_codeConstants() {
        assertFalse(classifier.isUserFacing("ALL_CAPS_CONSTANT"));
        assertFalse(classifier.isUserFacing("ghidra.app.plugin.core.debug"));
    }

    @Test
    @DisplayName("isUserFacing returns false for URLs")
    void isUserFacing_urls() {
        assertFalse(classifier.isUserFacing("https://example.com/api"));
        assertFalse(classifier.isUserFacing("file:///tmp/test"));
    }

    @Test
    @DisplayName("isUserFacing returns false for numeric strings")
    void isUserFacing_numeric() {
        assertFalse(classifier.isUserFacing("12345"));
        assertFalse(classifier.isUserFacing("0"));
    }

    @Test
    @DisplayName("isUserFacing returns false for secrets")
    void isUserFacing_secrets() {
        assertFalse(classifier.isUserFacing("sk-abc123def456"));
        assertFalse(classifier.isUserFacing("ghp_tokenstring"));
    }

    @Test
    @DisplayName("isUserFacing returns false for debug markers")
    void isUserFacing_debugMarkers() {
        assertFalse(classifier.isUserFacing("TODO"));
        assertFalse(classifier.isUserFacing("FIXME"));
        assertFalse(classifier.isUserFacing("XXX"));
    }

    // -----------------------------------------------------------------------
    // Content classification
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("containsHtml detects HTML tags")
    void containsHtml() {
        assertTrue(classifier.containsHtml("<html>Click <b>here</b></html>"));
        assertTrue(classifier.containsHtml("before <br> after"));
        assertFalse(classifier.containsHtml("plain text without tags"));
        assertFalse(classifier.containsHtml(null));
    }

    @Test
    @DisplayName("hasFormatSpecifiers detects printf/MF patterns")
    void hasFormatSpecifiers() {
        assertTrue(classifier.hasFormatSpecifiers("Value: %s"));
        assertTrue(classifier.hasFormatSpecifiers("Count: %d"));
        assertTrue(classifier.hasFormatSpecifiers("Item {0} of {1}"));
        assertFalse(classifier.hasFormatSpecifiers("No specifiers here"));
        assertFalse(classifier.hasFormatSpecifiers(null));
    }

    @Test
    @DisplayName("hasMnemonic detects Swing mnemonics")
    void hasMnemonic() {
        assertTrue(classifier.hasMnemonic("&File"));
        assertTrue(classifier.hasMnemonic("E&xit"));
        assertFalse(classifier.hasMnemonic("No mnemonic"));
        assertFalse(classifier.hasMnemonic(null));
    }

    @Test
    @DisplayName("isPunctuationOnly")
    void  isPunctuationOnly() {
        assertTrue(classifier.isPunctuationOnly(":"));
        assertTrue(classifier.isPunctuationOnly(", "));
        assertTrue(classifier.isPunctuationOnly("["));
        assertTrue(classifier.isPunctuationOnly("...."));
        assertFalse(classifier.isPunctuationOnly("OK"));
        assertFalse(classifier.isPunctuationOnly(null));
    }

    @Test
    @DisplayName("isUrl")
    void isUrl() {
        assertTrue(classifier.isUrl("https://github.com"));
        assertTrue(classifier.isUrl("http://localhost:8080/path"));
        assertTrue(classifier.isUrl("ftp://files.example.com"));
        assertFalse(classifier.isUrl("Cancel"));
        assertFalse(classifier.isUrl(null));
    }

    @Test
    @DisplayName("hasMeaningfulContent requires 2+ chars and letters")
    void hasMeaningfulContent() {
        assertTrue(classifier.hasMeaningfulContent("OK"));
        assertTrue(classifier.hasMeaningfulContent("File"));
        assertFalse(classifier.hasMeaningfulContent("A"));      // too short
        assertFalse(classifier.hasMeaningfulContent("123"));    // no letters
        assertFalse(classifier.hasMeaningfulContent(null));
    }

    @Test
    @DisplayName("isNumericOnly")
    void isNumericOnly() {
        assertTrue(classifier.isNumericOnly("123"));
        assertTrue(classifier.isNumericOnly("0"));
        assertFalse(classifier.isNumericOnly("12A"));
        assertFalse(classifier.isNumericOnly("File"));
        assertFalse(classifier.isNumericOnly(null));
    }

    // -----------------------------------------------------------------------
    // Source line context checks
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("containsLogOrExceptionMethod detects log calls")
    void containsLogOrExceptionMethod() {
        assertTrue(classifier.containsLogOrExceptionMethod("Msg.error(this, \"Something failed\")"));
        assertTrue(classifier.containsLogOrExceptionMethod("LOG.warn(\"unexpected\")"));
        assertTrue(classifier.containsLogOrExceptionMethod("throw new IllegalArgumentException(\"bad\")"));
        assertFalse(classifier.containsLogOrExceptionMethod("setTitle(\"My Dialog\")"));
        assertFalse(classifier.containsLogOrExceptionMethod(null));
    }

    @Test
    @DisplayName("containsUiPattern detects UI method calls")
    void containsUiPattern() {
        assertTrue(classifier.containsUiPattern(".setTitle(\"My Title\")"));
        assertTrue(classifier.containsUiPattern("new JButton(\"OK\")"));
        assertTrue(classifier.containsUiPattern("new TitledBorder(\"Panel\")"));
        assertFalse(classifier.containsUiPattern("int x = 0;"));
        assertFalse(classifier.containsUiPattern(null));
    }
}
