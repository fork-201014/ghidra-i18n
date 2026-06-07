package com.ghidra.i18n.common.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Properties;

/**
 * Global configuration loaded from environment variables and the Ghidra
 * submodule's application.properties.
 *
 * <p>Usage:
 * <pre>
 *   GlobalConfig config = GlobalConfig.load(Path.of("/path/to/ghidra"));
 *   String apiKey = config.getOpenAiApiKey();
 * </pre>
 */
public class GlobalConfig {

    private final Path ghidraRoot;
    private final String ghidraVersion;
    private final String openAiApiKey;
    private final String deepSeekApiKey;
    private final Path outputDir;
    private final Path glossaryDir;
    private final Path filterConfigDir;
    private final Path extractionOutputDir;
    private final Path translateOutputDir;
    private final Path validateOutputDir;

    private GlobalConfig(Builder builder) {
        this.ghidraRoot = builder.ghidraRoot;
        this.ghidraVersion = builder.ghidraVersion;
        this.openAiApiKey = builder.openAiApiKey;
        this.deepSeekApiKey = builder.deepSeekApiKey;
        this.outputDir = builder.outputDir;
        this.glossaryDir = builder.glossaryDir;
        this.filterConfigDir = builder.filterConfigDir;
        this.extractionOutputDir = outputDir.resolve("extract").resolve("output");
        this.translateOutputDir = outputDir.resolve("translate").resolve("output");
        this.validateOutputDir = outputDir.resolve("validate").resolve("output");
    }

    // -----------------------------------------------------------------------
    // Factory
    // -----------------------------------------------------------------------

    /**
     * Loads configuration from the given Ghidra root directory.
     * Reads {@code Ghidra/application.properties} for version info.
     * Reads environment variables for API keys and optional overrides.
     */
    public static GlobalConfig load(Path ghidraRoot) throws IOException {
        ghidraRoot = ghidraRoot.toRealPath();

        String version = readGhidraVersion(ghidraRoot);
        String openAiKey = envOrEmpty("OPENAI_API_KEY");
        String deepSeekKey = envOrEmpty("DEEPSEEK_API_KEY");

        // Output dir: env override or default under i18n-scripts/
        Path outputDir;
        String envOutput = System.getenv("I18N_OUTPUT_DIR");
        if (envOutput != null && !envOutput.isBlank()) {
            outputDir = Path.of(envOutput);
        } else {
            outputDir = ghidraRoot.getParent().resolve("i18n-scripts");
        }

        // Glossary and filter paths
        String envGlossary = System.getenv("I18N_GLOSSARY_DIR");
        Path glossaryDir = (envGlossary != null && !envGlossary.isBlank())
            ? Path.of(envGlossary)
            : ghidraRoot.getParent().resolve("glossary").resolve("zh_CN");

        String envFilter = System.getenv("I18N_FILTER_DIR");
        Path filterDir = (envFilter != null && !envFilter.isBlank())
            ? Path.of(envFilter)
            : ghidraRoot.getParent().resolve("i18n-scripts").resolve("extract").resolve("filters");

        return new Builder()
            .ghidraRoot(ghidraRoot)
            .ghidraVersion(version)
            .openAiApiKey(openAiKey)
            .deepSeekApiKey(deepSeekKey)
            .outputDir(outputDir)
            .glossaryDir(glossaryDir)
            .filterConfigDir(filterDir)
            .build();
    }

    /**
     * Creates a minimal config for testing purposes.
     * The given ghidraRoot is treated as the repository root (not the ghidra/ submodule).
     */
    public static GlobalConfig forTesting(Path ghidraRoot) {
        return new Builder()
            .ghidraRoot(ghidraRoot)
            .ghidraVersion("0.0-TEST")
            .openAiApiKey("test-key-openai")
            .deepSeekApiKey("test-key-deepseek")
            .outputDir(ghidraRoot.resolve("i18n-scripts"))
            .glossaryDir(ghidraRoot.resolve("glossary").resolve("zh_CN"))
            .filterConfigDir(ghidraRoot.resolve("i18n-scripts").resolve("extract").resolve("filters"))
            .build();
    }

    private static String readGhidraVersion(Path ghidraRoot) throws IOException {
        Path propsFile = ghidraRoot.resolve("Ghidra").resolve("application.properties");
        if (!Files.isRegularFile(propsFile)) {
            return "unknown";
        }
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(propsFile)) {
            p.load(reader);
        }
        return p.getProperty("application.version", "unknown") +
            (p.getProperty("application.release.name", "DEV").equals("DEV") ? " DEV" : "");
    }

    private static String envOrEmpty(String key) {
        String val = System.getenv(key);
        return val != null ? val : "";
    }

    // -----------------------------------------------------------------------
    // Getters
    // -----------------------------------------------------------------------

    public Path getGhidraRoot()               { return ghidraRoot; }
    public String getGhidraVersion()           { return ghidraVersion; }
    public String getOpenAiApiKey()            { return openAiApiKey; }
    public String getDeepSeekApiKey()          { return deepSeekApiKey; }
    public Path getOutputDir()                 { return outputDir; }
    public Path getGlossaryDir()               { return glossaryDir; }
    public Path getFilterConfigDir()           { return filterConfigDir; }
    public Path getExtractionOutputDir()        { return extractionOutputDir; }
    public Path getTranslateOutputDir()         { return translateOutputDir; }
    public Path getValidateOutputDir()          { return validateOutputDir; }

    public Path getGlossaryTermsFile() {
        return glossaryDir.resolve("ghidra-terms.yml");
    }

    public Path getTranslationMemoryFile() {
        return glossaryDir.resolve("translation-memory.json");
    }

    public Path getFilterConfigFile() {
        return filterConfigDir.resolve("filter-config.yml");
    }

    public Path getManualApprovalsFile() {
        return filterConfigDir.resolve("manual-approvals.yml");
    }

    public Path getExcludePatternsFile() {
        return filterConfigDir.resolve("exclude-patterns.txt");
    }

    public Path getModuleRegistryFile() {
        return glossaryDir.resolve("..").resolve("module-registry.yml").normalize();
    }

    /**
     * Returns true if at least one AI translation API key is configured.
     */
    public boolean hasTranslationApiKey() {
        return !openAiApiKey.isBlank() || !deepSeekApiKey.isBlank();
    }

    // -----------------------------------------------------------------------
    // Convenience
    // -----------------------------------------------------------------------

    public String summarize() {
        return "GlobalConfig{" +
            "version='" + ghidraVersion + '\'' +
            ", ghidraRoot=" + ghidraRoot +
            ", openAiKey=" + (openAiApiKey.isBlank() ? "NOT SET" : "***") +
            ", deepSeekKey=" + (deepSeekApiKey.isBlank() ? "NOT SET" : "***") +
            ", glossaryDir=" + glossaryDir +
            '}';
    }

    // -----------------------------------------------------------------------
    // Builder
    // -----------------------------------------------------------------------

    public static class Builder {
        private Path ghidraRoot;
        private String ghidraVersion;
        private String openAiApiKey = "";
        private String deepSeekApiKey = "";
        private Path outputDir;
        private Path glossaryDir;
        private Path filterConfigDir;

        public Builder ghidraRoot(Path v)          { this.ghidraRoot = v; return this; }
        public Builder ghidraVersion(String v)      { this.ghidraVersion = v; return this; }
        public Builder openAiApiKey(String v)        { this.openAiApiKey = v; return this; }
        public Builder deepSeekApiKey(String v)      { this.deepSeekApiKey = v; return this; }
        public Builder outputDir(Path v)             { this.outputDir = v; return this; }
        public Builder glossaryDir(Path v)           { this.glossaryDir = v; return this; }
        public Builder filterConfigDir(Path v)       { this.filterConfigDir = v; return this; }

        public GlobalConfig build() {
            if (ghidraRoot == null) throw new IllegalStateException("ghidraRoot is required");
            if (ghidraVersion == null) ghidraVersion = "unknown";
            if (outputDir == null) outputDir = ghidraRoot.getParent().resolve("i18n-scripts");
            if (glossaryDir == null) glossaryDir = ghidraRoot.getParent().resolve("glossary").resolve("zh_CN");
            if (filterConfigDir == null) filterConfigDir = outputDir.resolve("extract").resolve("filters");
            return new GlobalConfig(this);
        }
    }
}
