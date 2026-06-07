package com.ghidra.i18n.common.module;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Scans all Gradle submodules in a Ghidra repository and generates
 * {@code glossary/module-registry.yml}.
 *
 * <p>A "module" is any directory under {@code Ghidra/} (or {@code GPL/})
 * that contains a {@code build.gradle} file and optionally a
 * {@code src/main/java} or {@code src/main/py} source tree.</p>
 *
 * <p>Usage:
 * <pre>
 *   ModuleScanner scanner = new ModuleScanner(ghidraRoot);
 *   List<ModuleInfo> modules = scanner.scanAll();
 *   scanner.writeRegistry(modules, outputPath);
 * </pre>
 */
public class ModuleScanner {

    private final Path ghidraRoot;

    /** Top-level directories under ghidraRoot that contain submodules. */
    private static final String[] MODULE_ROOTS = {
        "Ghidra/Framework",
        "Ghidra/Features",
        "Ghidra/Debug",
        "Ghidra/Extensions",
        "Ghidra/Processors",
        "Ghidra/Configurations",
        "Ghidra/RuntimeScripts",
        "Ghidra/Test",
        "GPL"
    };

    public ModuleScanner(Path ghidraRoot) {
        this.ghidraRoot = ghidraRoot.toAbsolutePath().normalize();
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Scans all module directories and returns an ordered list.
     */
    public List<ModuleInfo> scanAll() throws IOException {
        List<ModuleInfo> results = new ArrayList<>();
        for (String root : MODULE_ROOTS) {
            Path dir = ghidraRoot.resolve(root);
            if (!Files.isDirectory(dir)) continue;

            try (Stream<Path> entries = Files.list(dir)) {
                entries.filter(Files::isDirectory)
                    .filter(this::isGradleModule)
                    .forEach(sub -> {
                        try {
                            results.add(scanModule(sub, root));
                        } catch (IOException e) {
                            System.err.println("WARN: Failed to scan module " + sub + ": " + e.getMessage());
                        }
                    });
            }
        }
        results.sort(Comparator.comparing(m -> m.name));
        return results;
    }

    /**
     * Writes the module registry to a YAML file.
     */
    public void writeRegistry(List<ModuleInfo> modules, Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());
        try (Writer w = Files.newBufferedWriter(outputPath)) {
            w.write("# 模块注册表 — 由 ModuleScanner 自动生成\n");
            w.write("# 包含 Ghidra 所有 Gradle 子模块及其 i18n 元数据\n");
            w.write("# 生成时间: " + java.time.Instant.now() + "\n\n");
            w.write("modules:\n");
            for (ModuleInfo m : modules) {
                writeModuleYaml(w, m, 2);
            }
        }
    }

    /**
     * Writes a summary report to stdout.
     */
    public void printSummary(List<ModuleInfo> modules) {
        long withJava = modules.stream().filter(m -> m.javaFiles > 0).count();
        long withPy = modules.stream().filter(m -> m.pyFiles > 0).count();
        long withHelp = modules.stream().filter(m -> m.htmlHelpFiles > 0).count();
        long withResources = modules.stream().filter(m -> m.hasResourcesDir).count();
        long withToc = modules.stream().filter(m -> m.tocFiles > 0).count();
        long totalJava = modules.stream().mapToInt(m -> m.javaFiles).sum();
        long totalPy = modules.stream().mapToInt(m -> m.pyFiles).sum();

        System.out.println("Module Scanner Summary");
        System.out.println("======================");
        System.out.println("Total modules:          " + modules.size());
        System.out.println("With Java sources:      " + withJava + "  (total " + totalJava + " files)");
        System.out.println("With Python sources:    " + withPy + "  (total " + totalPy + " files)");
        System.out.println("With HTML help:         " + withHelp);
        System.out.println("With TOC_Source.xml:    " + withToc);
        System.out.println("With resources dir:     " + withResources);
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private boolean isGradleModule(Path dir) {
        return Files.isRegularFile(dir.resolve("build.gradle"));
    }

    private ModuleInfo scanModule(Path moduleDir, String rootPrefix) throws IOException {
        ModuleInfo m = new ModuleInfo();
        m.name = moduleDir.getFileName().toString();
        m.path = ghidraRoot.relativize(moduleDir).toString();

        // Gradle project name (same as module name for simplicity)
        m.gradleProject = ":" + m.path;

        // Source counts
        Path javaDir = moduleDir.resolve("src/main/java");
        Path pyDir = moduleDir.resolve("src/main/py");
        Path pySrcDir = moduleDir.resolve("src/main/py/src");  // PyGhidra convention

        if (Files.isDirectory(javaDir)) {
            m.javaFiles = countFiles(javaDir, ".java");
        }
        Path resourcesDir = moduleDir.resolve("src/main/resources");
        m.hasResourcesDir = Files.isDirectory(resourcesDir);

        Path helpDir = moduleDir.resolve("src/main/help/help");
        if (Files.isDirectory(helpDir)) {
            m.htmlHelpFiles = countFiles(helpDir, ".html", ".htm");
            m.tocFiles = Files.isRegularFile(helpDir.resolve("TOC_Source.xml")) ? 1 : 0;
        }

        if (Files.isDirectory(pyDir)) {
            m.pyFiles = countFiles(pyDir, ".py");
        } else if (Files.isDirectory(pySrcDir)) {
            m.pyFiles = countFiles(pySrcDir, ".py");
        }

        // Bundle path computation
        if (m.javaFiles > 0 && m.hasResourcesDir) {
            computeBundlePaths(moduleDir, m);
        }

        // Module.manifest check
        m.hasManifest = Files.isRegularFile(moduleDir.resolve("Module.manifest"));

        return m;
    }

    private void computeBundlePaths(Path moduleDir, ModuleInfo m) {
        Path srcDir = moduleDir.resolve("src/main/java");
        // Walk down to find the first Java package directory
        try (Stream<Path> stream = Files.walk(srcDir, 3)) {
            stream
                .filter(Files::isDirectory)
                .filter(p -> {
                    Path rel = srcDir.relativize(p);
                    return rel.getNameCount() >= 1 && rel.getNameCount() <= 3;
                })
                .filter(p -> !p.equals(srcDir))
                .findFirst()
                .ifPresent(p -> {
                    String rel = srcDir.relativize(p).toString();
                    // Verify this looks like a package path (contains lowercase)
                    if (rel.matches("^[a-z].*") && !rel.contains("META-INF")) {
                        m.bundleBasePath = rel;
                    }
                });
        } catch (IOException ignored) {
            // fall through to defaults
        }

        if (m.bundleBasePath == null) {
            m.bundleBasePath = m.name.toLowerCase();
        }
        m.bundleClassName = toBundleClassName(m.name);
        m.i18nClassFQN = m.bundleBasePath.replace('/', '.') + ".I18n" + toAlphaNum(m.name);
    }

    private static String toBundleClassName(String moduleName) {
        return toAlphaNum(moduleName) + "Messages";
    }

    private static String toAlphaNum(String s) {
        return s.replaceAll("[^a-zA-Z0-9]", "");
    }

    private static int countFiles(Path dir, String... extensions) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            return (int) stream
                .filter(Files::isRegularFile)
                .filter(f -> {
                    String name = f.getFileName().toString().toLowerCase();
                    for (String ext : extensions) {
                        if (name.endsWith(ext)) return true;
                    }
                    return false;
                })
                .count();
        }
    }

    // -----------------------------------------------------------------------
    // YAML output
    // -----------------------------------------------------------------------

    private void writeModuleYaml(Writer w, ModuleInfo m, int indent) throws IOException {
        String pad = " ".repeat(indent);
        w.write(pad + "- name: " + yamlValue(m.name) + "\n");
        w.write(pad + "  path: " + yamlValue(m.path) + "\n");
        w.write(pad + "  gradleProject: \":" + m.path.replace('/', ':') + "\"\n");
        w.write(pad + "  hasResourcesDir: " + m.hasResourcesDir + "\n");
        w.write(pad + "  javaFiles: " + m.javaFiles + "\n");
        w.write(pad + "  pyFiles: " + m.pyFiles + "\n");
        w.write(pad + "  htmlHelpFiles: " + m.htmlHelpFiles + "\n");
        w.write(pad + "  tocFiles: " + m.tocFiles + "\n");
        w.write(pad + "  hasManifest: " + m.hasManifest + "\n");
        if (m.bundleBasePath != null) {
            w.write(pad + "  bundleBasePath: " + yamlValue(m.bundleBasePath) + "\n");
            w.write(pad + "  bundleClassName: " + yamlValue(m.bundleClassName) + "\n");
            w.write(pad + "  i18nClassFQN: " + yamlValue(m.i18nClassFQN) + "\n");
        }
        if (m.note != null && !m.note.isBlank()) {
            w.write(pad + "  note: " + yamlValue(m.note) + "\n");
        }
        w.write("\n");
    }

    private static String yamlValue(String s) {
        if (s == null || s.isEmpty()) return "\"\"";
        if (s.contains(":") || s.contains("#") || s.contains("\"") || s.startsWith(" ")) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return s;
    }

    // -----------------------------------------------------------------------
    // Data class
    // -----------------------------------------------------------------------

    /**
     * Metadata for a single Ghidra Gradle submodule.
     */
    public static class ModuleInfo {
        public String name;
        public String path;
        public String gradleProject;
        public boolean hasResourcesDir;
        public int javaFiles;
        public int pyFiles;
        public int htmlHelpFiles;
        public int tocFiles;
        public boolean hasManifest;
        public String bundleBasePath;
        public String bundleClassName;
        public String i18nClassFQN;
        public String note;

        /** Returns true if this module has any source files that may contain translatable strings. */
        public boolean hasTranslatableContent() {
            return javaFiles > 0 || pyFiles > 0 || htmlHelpFiles > 0 || tocFiles > 0;
        }

        @Override
        public String toString() {
            return "ModuleInfo{name='" + name + "', java=" + javaFiles + ", py=" + pyFiles + "}";
        }
    }
}
