package com.ghidra.i18n.common.cli;

import com.ghidra.i18n.common.config.GlobalConfig;
import com.ghidra.i18n.common.module.ModuleScanner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * CLI entry point for the ModuleScanner.
 *
 * <p>Usage:
 * <pre>java -cp ... com.ghidra.i18n.common.cli.ScanModules /path/to/ghidra [output.yml]</pre>
 */
public class ScanModules {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: ScanModules <ghidra-root> [output-path]");
            System.exit(1);
        }

        Path ghidraRoot = Path.of(args[0]).toRealPath();
        Path outputPath;
        if (args.length >= 2) {
            outputPath = Path.of(args[1]);
        } else {
            outputPath = ghidraRoot.getParent().resolve("glossary/module-registry.yml");
        }

        System.out.println("Scanning modules in: " + ghidraRoot);
        ModuleScanner scanner = new ModuleScanner(ghidraRoot);
        List<ModuleScanner.ModuleInfo> modules = scanner.scanAll();

        scanner.printSummary(modules);
        scanner.writeRegistry(modules, outputPath);
        System.out.println("Registry written to: " + outputPath);
    }
}
