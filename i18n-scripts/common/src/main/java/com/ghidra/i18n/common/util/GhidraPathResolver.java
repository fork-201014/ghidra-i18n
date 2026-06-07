package com.ghidra.i18n.common.util;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Resolves file paths within the Ghidra submodule structure.
 *
 * <p>Ghidra follows a convention where each Gradle submodule lives under
 * a path like {@code Ghidra/Framework/Docking/} and its Java sources live
 * under {@code src/main/java/}. This class encapsulates that convention.</p>
 *
 * <p>Also handles the mapping from module name to i18n resource paths:
 * <ul>
 *   <li>Bundle properties path: {@code src/main/resources/{package}/Messages[_locale].properties}</li>
 *   <li>I18n bootstrap class: {@code src/main/java/{package}/I18n{Module}.java}</li>
 * </ul>
 */
public class GhidraPathResolver {

    private final Path ghidraRoot;

    public GhidraPathResolver(Path ghidraRoot) {
        this.ghidraRoot = ghidraRoot.toAbsolutePath().normalize();
    }

    // -----------------------------------------------------------------------
    // Module-level paths
    // -----------------------------------------------------------------------

    /**
     * Returns the module root directory for the given module name.
     * Scans under {@code Ghidra/} looking for a directory matching the name.
     */
    public Path getModulePath(String moduleName) {
        // Modules are under Ghidra/Framework/, Ghidra/Features/, Ghidra/Debug/, Ghidra/Extensions/, GPL/
        String[] prefixes = {"Ghidra/Framework", "Ghidra/Features", "Ghidra/Debug",
                             "Ghidra/Extensions", "GPL"};
        for (String prefix : prefixes) {
            Path candidate = ghidraRoot.resolve(prefix).resolve(moduleName);
            if (candidate.toFile().isDirectory()) {
                return candidate;
            }
        }
        // Fallback: assume Ghidra/Features
        return ghidraRoot.resolve("Ghidra/Features").resolve(moduleName);
    }

    /**
     * Returns the main Java source directory for the module.
     */
    public Path getSourceMainJava(String moduleName) {
        return getModulePath(moduleName).resolve("src/main/java");
    }

    /**
     * Returns the resources directory for the module.
     */
    public Path getResourcesDir(String moduleName) {
        return getModulePath(moduleName).resolve("src/main/resources");
    }

    /**
     * Returns the help directory for the module.
     */
    public Path getHelpDir(String moduleName) {
        return getModulePath(moduleName).resolve("src/main/help/help");
    }

    /**
     * Returns the TOC_Source.xml path for the module.
     */
    public Path getTocFile(String moduleName) {
        return getHelpDir(moduleName).resolve("TOC_Source.xml");
    }

    // -----------------------------------------------------------------------
    // i18n-specific paths
    // -----------------------------------------------------------------------

    /**
     * Computes the .properties file path within the module's resources.
     *
     * @param moduleName  e.g. "Docking"
     * @param locale      target locale, e.g. Locale.SIMPLIFIED_CHINESE
     * @return Path like Ghidra/Framework/Docking/src/main/resources/ghidra/framework/docking/DockingMessages_zh_CN.properties
     */
    public Path computeBundlePath(String moduleName, Locale locale) {
        Path resDir = getResourcesDir(moduleName);
        String bundleBasePath = computeBundleBasePath(moduleName);
        String suffix = locale.toString().isEmpty() ? "" : "_" + locale;
        String bundleClass = bundleClassName(moduleName);

        return resDir.resolve(bundleBasePath)
            .resolve(bundleClass + suffix + ".properties");
    }

    /**
     * Computes the English base .properties path.
     */
    public Path computeBundlePathBase(String moduleName) {
        return computeBundlePath(moduleName, Locale.ROOT);
    }

    /**
     * Computes the path for the generated I18n bootstrap class.
     *
     * @return Path like Ghidra/Framework/Docking/src/main/java/ghidra/framework/docking/I18nDocking.java
     */
    public Path computeI18nClassPath(String moduleName) {
        Path srcDir = getSourceMainJava(moduleName);
        String pkgPath = computeBundleBasePath(moduleName);
        String className = i18nClassName(moduleName);
        return srcDir.resolve(pkgPath).resolve(className + ".java");
    }

    // -----------------------------------------------------------------------
    // Naming conventions
    // -----------------------------------------------------------------------

    /**
     * Returns the bundle class name for a module.
     * e.g. "Docking" → "DockingMessages"
     */
    public String bundleClassName(String moduleName) {
        return moduleName.replaceAll("[^a-zA-Z0-9]", "") + "Messages";
    }

    /**
     * Returns the I18n bootstrap class name.
     * e.g. "Docking" → "I18nDocking"
     */
    public String i18nClassName(String moduleName) {
        return "I18n" + moduleName.replaceAll("[^a-zA-Z0-9]", "");
    }

    /**
     * Returns the fully qualified class name of the I18n bootstrap class.
     * e.g. "Docking" → "ghidra.framework.docking.I18nDocking"
     */
    public String i18nClassFQN(String moduleName) {
        return computeBundleBasePath(moduleName).replace('/', '.')
            + "." + i18nClassName(moduleName);
    }

    /**
     * Returns the Java package path (with / separators) for the bundle resources.
     * Derived from the module's primary Java package.
     * e.g. "Docking" → "ghidra/framework/docking"
     */
    public String computeBundleBasePath(String moduleName) {
        Path srcDir = getSourceMainJava(moduleName);
        // Walk the first java package directory
        try (var stream = java.nio.file.Files.walk(srcDir, 2)) {
            return stream
                .filter(p -> java.nio.file.Files.isDirectory(p) && !p.equals(srcDir))
                .filter(p -> p.getParent().getParent().equals(srcDir)) // depth 2 under src/main/java
                .findFirst()
                .map(p -> srcDir.relativize(p).toString())
                .orElse(moduleName.toLowerCase());
        } catch (Exception e) {
            return moduleName.toLowerCase();
        }
    }

    // -----------------------------------------------------------------------
    // Ghidra-wide paths
    // -----------------------------------------------------------------------

    /**
     * Returns the Ghidra application.properties path.
     */
    public Path getApplicationProperties() {
        return ghidraRoot.resolve("Ghidra").resolve("application.properties");
    }

    /**
     * Returns the Ghidra settings.gradle path.
     */
    public Path getSettingsGradle() {
        return ghidraRoot.resolve("settings.gradle");
    }

    @Override
    public String toString() {
        return "GhidraPathResolver{root=" + ghidraRoot + "}";
    }
}
