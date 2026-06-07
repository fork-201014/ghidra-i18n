package com.ghidra.i18n.common.module;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Unit tests for {@link ModuleScanner}.
 */
class ModuleScannerTest {

    @TempDir
    Path tempDir;

    // -----------------------------------------------------------------------
    // Helper: create a minimal Ghidra source tree
    // -----------------------------------------------------------------------

    private Path createModule(String relPath, int javaFileCount, boolean withResources,
                               int helpFileCount, boolean withToc, int pyFileCount) throws IOException {
        Path dir = tempDir.resolve(relPath);
        Files.createDirectories(dir);

        // build.gradle
        Files.createFile(dir.resolve("build.gradle"));

        // Java sources
        Path javaDir = dir.resolve("src/main/java");
        if (javaFileCount > 0) {
            Path pkg = javaDir.resolve("ghidra/app/plugin");
            Files.createDirectories(pkg);
            for (int i = 0; i < javaFileCount; i++) {
                Files.createFile(pkg.resolve("Class" + i + ".java"));
            }
        }

        // Resources
        if (withResources) {
            Files.createDirectories(dir.resolve("src/main/resources"));
        }

        // Help files
        if (helpFileCount > 0) {
            Path helpDir = dir.resolve("src/main/help/help/topics/Intro");
            Files.createDirectories(helpDir);
            for (int i = 0; i < helpFileCount; i++) {
                Files.createFile(helpDir.resolve("page" + i + ".html"));
            }
            if (withToc) {
                Files.createFile(dir.resolve("src/main/help/help/TOC_Source.xml"));
            }
        }

        // Python files
        if (pyFileCount > 0) {
            Path pyDir = dir.resolve("src/main/py/src");
            Files.createDirectories(pyDir);
            for (int i = 0; i < pyFileCount; i++) {
                Files.createFile(pyDir.resolve("script" + i + ".py"));
            }
        }

        return dir;
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("scans multiple modules correctly")
    void scanAll() throws IOException {
        createModule("Ghidra/Framework/Docking", 50, true, 10, true, 0);
        createModule("Ghidra/Features/Base", 100, true, 20, false, 0);
        createModule("Ghidra/Debug/Debugger", 30, false, 5, false, 0);
        createModule("Ghidra/Extensions/PyGhidra", 0, false, 0, false, 5);
        createModule("GPL/GnuDisassembler", 10, false, 0, false, 0);

        ModuleScanner scanner = new ModuleScanner(tempDir);
        List<ModuleScanner.ModuleInfo> modules = scanner.scanAll();

        assertEquals(5, modules.size());

        // Docking
        ModuleScanner.ModuleInfo docking = findModule(modules, "Docking");
        assertNotNull(docking);
        assertEquals(50, docking.javaFiles);
        assertTrue(docking.hasResourcesDir);
        assertEquals(10, docking.htmlHelpFiles);
        assertEquals(1, docking.tocFiles);
        assertNotNull(docking.bundleBasePath);
        assertEquals("DockingMessages", docking.bundleClassName);
        assertTrue(docking.hasTranslatableContent());

        // Base
        ModuleScanner.ModuleInfo base = findModule(modules, "Base");
        assertNotNull(base);
        assertEquals(100, base.javaFiles);
        assertTrue(base.hasResourcesDir);

        // Debugger (no resources dir → no bundle path)
        ModuleScanner.ModuleInfo debugger = findModule(modules, "Debugger");
        assertNotNull(debugger);
        assertEquals(30, debugger.javaFiles);
        assertFalse(debugger.hasResourcesDir);
        assertNull(debugger.bundleBasePath);

        // PyGhidra (Python only)
        ModuleScanner.ModuleInfo pyghidra = findModule(modules, "PyGhidra");
        assertNotNull(pyghidra);
        assertEquals(0, pyghidra.javaFiles);
        assertEquals(5, pyghidra.pyFiles);
        assertFalse(pyghidra.hasResourcesDir);
        assertTrue(pyghidra.hasTranslatableContent());
    }

    @Test
    @DisplayName("empty directory produces empty list")
    void emptyDir() throws IOException {
        ModuleScanner scanner = new ModuleScanner(tempDir);
        List<ModuleScanner.ModuleInfo> modules = scanner.scanAll();
        assertEquals(0, modules.size());
    }

    @Test
    @DisplayName("writes valid YAML registry")
    void writeRegistry() throws IOException {
        createModule("Ghidra/Framework/Docking", 5, true, 0, false, 0);

        ModuleScanner scanner = new ModuleScanner(tempDir);
        List<ModuleScanner.ModuleInfo> modules = scanner.scanAll();

        Path outputPath = tempDir.resolve("output").resolve("module-registry.yml");
        scanner.writeRegistry(modules, outputPath);

        assertTrue(Files.isRegularFile(outputPath));
        String content = Files.readString(outputPath);
        assertTrue(content.contains("modules:"));
        assertTrue(content.contains("name: Docking"));
        assertTrue(content.contains("javaFiles: 5"));
        assertTrue(content.contains("bundleClassName: DockingMessages"));
    }

    @Test
    @DisplayName("summary does not throw")
    void summary() throws IOException {
        createModule("Ghidra/Framework/Docking", 5, true, 0, false, 0);
        ModuleScanner scanner = new ModuleScanner(tempDir);
        List<ModuleScanner.ModuleInfo> modules = scanner.scanAll();
        scanner.printSummary(modules); // should not throw
    }

    @Test
    @DisplayName("skips directories without build.gradle")
    void skipsNonGradle() throws IOException {
        Files.createDirectories(tempDir.resolve("Ghidra/Framework/NotAModule/src/main/java"));
        createModule("Ghidra/Framework/Docking", 5, false, 0, false, 0);

        ModuleScanner scanner = new ModuleScanner(tempDir);
        List<ModuleScanner.ModuleInfo> modules = scanner.scanAll();

        assertEquals(1, modules.size());
        assertEquals("Docking", modules.get(0).name);
    }

    @Test
    @DisplayName("sort order is alphabetical by name")
    void sortOrder() throws IOException {
        createModule("Ghidra/Framework/Zulu", 1, false, 0, false, 0);
        createModule("Ghidra/Framework/Alpha", 1, false, 0, false, 0);
        createModule("Ghidra/Framework/Delta", 1, false, 0, false, 0);

        ModuleScanner scanner = new ModuleScanner(tempDir);
        List<ModuleScanner.ModuleInfo> modules = scanner.scanAll();
        assertEquals(3, modules.size());
        assertEquals("Alpha", modules.get(0).name);
        assertEquals("Delta", modules.get(1).name);
        assertEquals("Zulu", modules.get(2).name);
    }

    @Test
    @DisplayName("ModuleInfo.toString")
    void moduleInfoToString() {
        ModuleScanner.ModuleInfo m = new ModuleScanner.ModuleInfo();
        m.name = "Test";
        m.javaFiles = 42;
        String s = m.toString();
        assertTrue(s.contains("Test"));
        assertTrue(s.contains("42"));
    }

    private ModuleScanner.ModuleInfo findModule(List<ModuleScanner.ModuleInfo> modules, String name) {
        return modules.stream().filter(m -> m.name.equals(name)).findFirst().orElse(null);
    }
}
