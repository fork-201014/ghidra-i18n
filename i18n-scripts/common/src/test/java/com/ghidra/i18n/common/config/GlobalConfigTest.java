package com.ghidra.i18n.common.config;

import com.ghidra.i18n.common.config.GlobalConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Unit tests for {@link GlobalConfig}.
 */
class GlobalConfigTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("forTesting creates valid config")
    void forTesting() {
        GlobalConfig cfg = GlobalConfig.forTesting(tempDir);
        assertNotNull(cfg);
        assertEquals("0.0-TEST", cfg.getGhidraVersion());
        assertEquals("test-key-openai", cfg.getOpenAiApiKey());
        assertTrue(cfg.hasTranslationApiKey());
    }

    @Test
    @DisplayName("hasTranslationApiKey returns false when no keys set")
    void noKeys() {
        GlobalConfig cfg = new GlobalConfig.Builder()
            .ghidraRoot(tempDir)
            .ghidraVersion("1.0")
            .openAiApiKey("")
            .deepSeekApiKey("")
            .build();
        assertFalse(cfg.hasTranslationApiKey());
    }

    @Test
    @DisplayName("hasTranslationApiKey returns true when either key is set")
    void oneKey() {
        GlobalConfig cfg = new GlobalConfig.Builder()
            .ghidraRoot(tempDir)
            .openAiApiKey("sk-xxx")
            .deepSeekApiKey("")
            .build();
        assertTrue(cfg.hasTranslationApiKey());
    }

    @Test
    @DisplayName("summarize hides API keys")
    void summarizeHidesKeys() {
        GlobalConfig cfg = new GlobalConfig.Builder()
            .ghidraRoot(tempDir)
            .ghidraVersion("12.2")
            .openAiApiKey("sk-secret-key")
            .deepSeekApiKey("")
            .build();
        String summary = cfg.summarize();
        assertFalse(summary.contains("sk-secret-key"));
        assertTrue(summary.contains("***"));
        assertTrue(summary.contains("NOT SET"));
    }

    @Test
    @DisplayName("output subdirectories are resolved correctly")
    void outputDirs() {
        GlobalConfig cfg = GlobalConfig.forTesting(tempDir);
        Path expected = tempDir.resolve("i18n-scripts");
        assertEquals(expected.resolve("extract").resolve("output"), cfg.getExtractionOutputDir());
        assertEquals(expected.resolve("translate").resolve("output"), cfg.getTranslateOutputDir());
        assertEquals(expected.resolve("validate").resolve("output"), cfg.getValidateOutputDir());
    }

    @Test
    @DisplayName("glossary and filter file paths are correct")
    void glossaryPaths() {
        GlobalConfig cfg = GlobalConfig.forTesting(tempDir);
        Path glossary = tempDir.resolve("glossary").resolve("zh_CN");
        Path filters = tempDir.resolve("i18n-scripts").resolve("extract").resolve("filters");

        assertEquals(glossary.resolve("ghidra-terms.yml"), cfg.getGlossaryTermsFile());
        assertEquals(glossary.resolve("translation-memory.json"), cfg.getTranslationMemoryFile());
        assertEquals(filters.resolve("filter-config.yml"), cfg.getFilterConfigFile());
        assertEquals(filters.resolve("manual-approvals.yml"), cfg.getManualApprovalsFile());
    }

    @Test
    @DisplayName("builder throws when ghidraRoot is missing")
    void builderRequiresRoot() {
        assertThrows(IllegalStateException.class, () -> new GlobalConfig.Builder().build());
    }

    @Test
    @DisplayName("load from real Ghidra root reads version")
    void loadFromGhidraRoot() throws IOException {
        // Create minimal Ghidra dir structure
        Path ghidraDir = tempDir.resolve("Ghidra");
        Files.createDirectories(ghidraDir);
        Files.writeString(ghidraDir.resolve("application.properties"),
            "application.version=12.2\napplication.release.name=DEV\n");

        GlobalConfig cfg = GlobalConfig.load(tempDir);
        assertTrue(cfg.getGhidraVersion().contains("12.2"));
        assertTrue(cfg.getGhidraVersion().contains("DEV"));
    }

    @Test
    @DisplayName("load with missing properties file returns 'unknown'")
    void loadMissingProperties() throws IOException {
        GlobalConfig cfg = GlobalConfig.load(tempDir);
        assertEquals("unknown", cfg.getGhidraVersion());
    }
}
