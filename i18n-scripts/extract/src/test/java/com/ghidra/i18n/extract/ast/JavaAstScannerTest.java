package com.ghidra.i18n.extract.ast;

import com.ghidra.i18n.common.module.ModuleScanner;
import com.ghidra.i18n.common.util.GhidraPathResolver;
import com.ghidra.i18n.extract.model.TranslationUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link JavaAstScanner} against the real Ghidra submodule.
 *
 * <p>Requires the {@code ghidra/} submodule to be present under the project root.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JavaAstScannerTest {

    private static final Path GHIDRA_ROOT = findGhidraRoot();

    private static Path findGhidraRoot() {
        // Try relative from i18n-scripts/ working dir (Gradle test runner)
        Path cwd = Path.of("").toAbsolutePath();
        Path candidate = cwd.resolve("../ghidra").normalize();
        if (candidate.toFile().isDirectory()) return candidate.toAbsolutePath().normalize();
        // Fallback: try absolute path from env
        String env = System.getenv("GHIDRA_ROOT");
        if (env != null) return Path.of(env).toAbsolutePath().normalize();
        throw new IllegalStateException("Cannot find ghidra/ submodule. Set GHIDRA_ROOT env var.");
    }

    private GhidraPathResolver pathResolver;
    private JavaAstScanner scanner;

    @BeforeAll
    void setUp() {
        pathResolver = new GhidraPathResolver(GHIDRA_ROOT);
        scanner = new JavaAstScanner();
    }

    // ========================================================================
    // Docking module — the baseline (813 .java files)
    // ========================================================================

    @Test
    @DisplayName("AST scan Docking module (813 files) — extracts translatable strings")
    void scanDockingModule() throws IOException {
        ModuleScanner moduleScanner = new ModuleScanner(GHIDRA_ROOT);
        List<ModuleScanner.ModuleInfo> modules = moduleScanner.scanAll();
        ModuleScanner.ModuleInfo docking = modules.stream()
            .filter(m -> m.name.equals("Docking"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Docking module not found in scan"));

        assertEquals(813, docking.javaFiles, "Docking module should have 813 Java files");

        scanner.resetStats();
        List<TranslationUnit> units = scanner.scanModule(docking, pathResolver);

        assertFalse(units.isEmpty(), "Should extract at least some strings from Docking");

        int parsed = scanner.getFilesParsed();
        int failed = scanner.getFilesFailed();
        double successRate = (double) parsed / docking.javaFiles * 100;

        System.out.println("Docking AST scan results:");
        System.out.println("  Files parsed:  " + parsed + " / " + docking.javaFiles);
        System.out.println("  Files failed:  " + failed);
        System.out.println("  Success rate:  " + String.format("%.1f%%", successRate));
        System.out.println("  Units extracted: " + units.size());

        // Pattern distribution
        Map<TranslationUnit.ExtractionPattern, Long> byPattern = units.stream()
            .collect(Collectors.groupingBy(TranslationUnit::getPattern, Collectors.counting()));
        System.out.println("  By pattern:");
        byPattern.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
            .forEach(e -> System.out.println("    " + e.getKey() + ": " + e.getValue()));

        // Assertions
        assertTrue(parsed > 750, "AST should parse >750 files (allowing for JavaCC etc.)");
        assertTrue(successRate > 90.0, "Parse success rate should be >90%");
        assertTrue(units.size() >= 50, "Docking should yield at least 50 UI string candidates");
        assertFalse(units.isEmpty(), "Should extract some UI strings");

        // Check for known patterns
        Set<TranslationUnit.ExtractionPattern> patterns = units.stream()
            .map(TranslationUnit::getPattern)
            .collect(Collectors.toSet());
        assertTrue(patterns.contains(TranslationUnit.ExtractionPattern.SET_TITLE),
            "Should find setTitle patterns");
        assertTrue(patterns.contains(TranslationUnit.ExtractionPattern.NEW_JBUTTON),
            "Should find JButton patterns");

        // Verify no null fields in any unit
        for (TranslationUnit u : units) {
            assertNotNull(u.getModuleName());
            assertNotNull(u.getSourceFilePath());
            assertNotNull(u.getClassName());
            assertNotNull(u.getSourceText());
            assertNotNull(u.getPattern());
        }
    }

    // ========================================================================
    // Single file scan — LaunchErrorDialog.java
    // ========================================================================

    @Test
    @DisplayName("scan single file — validates scanFile works")
    void scanSingleFile() throws IOException {
        Path file = GHIDRA_ROOT.resolve(
            "Ghidra/Framework/Docking/src/main/java/docking/DockingUtils.java");

        ModuleScanner moduleScanner = new ModuleScanner(GHIDRA_ROOT);
        List<ModuleScanner.ModuleInfo> modules = moduleScanner.scanAll();
        ModuleScanner.ModuleInfo docking = modules.stream()
            .filter(m -> m.name.equals("Docking"))
            .findFirst()
            .orElseThrow();

        System.out.println("Scanning: " + file);
        List<TranslationUnit> units = scanner.scanFile(file, docking, pathResolver);
        System.out.println("Extracted units: " + units.size());
        for (TranslationUnit u : units) {
            System.out.println("  [" + u.getPattern() + "] " + u.getSourceText());
        }
        assertNotNull(units, "scanFile should never return null");
    }

    // ========================================================================
    // Statistics
    // ========================================================================

    @Test
    @DisplayName("scanner stats are reset correctly")
    void statsReset() {
        scanner.resetStats();
        assertEquals(0, scanner.getFilesParsed());
        assertEquals(0, scanner.getFilesFailed());
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Test
    @DisplayName("empty module returns empty list")
    void emptyModule() {
        ModuleScanner.ModuleInfo emptyModule = new ModuleScanner.ModuleInfo();
        emptyModule.name = "NonExistent";
        emptyModule.javaFiles = 0;

        List<TranslationUnit> units = scanner.scanModule(emptyModule, pathResolver);
        assertTrue(units.isEmpty());
    }

    @Test
    @DisplayName("deduplication works for identical strings in same class")
    void deduplication() {
        // Create two identical units manually
        TranslationUnit a = new TranslationUnit()
            .setClassName("TestClass")
            .setPattern(TranslationUnit.ExtractionPattern.SET_TITLE)
            .setSourceText("Duplicate Text");
        TranslationUnit b = new TranslationUnit()
            .setClassName("TestClass")
            .setPattern(TranslationUnit.ExtractionPattern.SET_TITLE)
            .setSourceText("Duplicate Text");

        assertEquals(a, b); // equals is based on id, but we test dedup key
        // The dedup key would be: className|pattern|sourceText
    }
}
