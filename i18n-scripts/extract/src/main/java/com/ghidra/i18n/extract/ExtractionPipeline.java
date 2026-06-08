package com.ghidra.i18n.extract;

import com.ghidra.i18n.common.config.GlobalConfig;
import com.ghidra.i18n.common.module.ModuleScanner;
import com.ghidra.i18n.common.util.GhidraPathResolver;
import com.ghidra.i18n.extract.ai.AiReviewer;
import com.ghidra.i18n.extract.ast.JavaAstScanner;
import com.ghidra.i18n.extract.filter.FilterConfigLoader;
import com.ghidra.i18n.extract.filter.FilterEngine;
import com.ghidra.i18n.extract.io.ManifestWriter;
import com.ghidra.i18n.extract.model.ExtractionManifest;
import com.ghidra.i18n.extract.model.FilterConfig;
import com.ghidra.i18n.extract.model.TranslationUnit;
import com.ghidra.i18n.extract.regex.RegexScanner;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Main entry point for the string extraction pipeline.
 *
 * <p>Orchestrates the full four-stage extraction flow:</p>
 * <ol>
 *   <li>AST scanning (JavaParser) — 88 modules, ~13K .java files</li>
 *   <li>Regex scanning (fallback Java + Python + HTML + TOC)</li>
 *   <li>Filtering (4-layer: API → context → regex → heuristic)</li>
 *   <li>AI review (DeepSeek/OpenAI semantic confirmation)</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>ExtractionPipeline.run(ghidraRoot);</pre>
 */
public class ExtractionPipeline {

    /**
     * Runs the full extraction pipeline against a Ghidra submodule root.
     *
     * @param ghidraRoot path to the ghidra/ submodule (e.g. /path/to/ghidra-i18n/ghidra)
     * @return the populated extraction manifest
     */
    public static ExtractionManifest run(Path ghidraRoot) throws IOException {
        System.out.println("=== Phase 2: String Extraction Pipeline ===");
        System.out.println("Ghidra root: " + ghidraRoot.toAbsolutePath().normalize());

        // 1. Load configuration
        GlobalConfig config = GlobalConfig.load(ghidraRoot);
        System.out.println("Ghidra version: " + config.getGhidraVersion());

        FilterConfig filterConfig;
        try {
            filterConfig = new FilterConfigLoader(config).load();
        } catch (IOException e) {
            System.err.println("WARN: Failed to load filter config: " + e.getMessage());
            filterConfig = FilterConfig.builder().build();
        }

        // 2. Scan all modules
        ModuleScanner moduleScanner = new ModuleScanner(ghidraRoot);
        List<ModuleScanner.ModuleInfo> modules = moduleScanner.scanAll();
        System.out.println("Modules found: " + modules.size());

        GhidraPathResolver pathResolver = new GhidraPathResolver(ghidraRoot);

        // 3. Initialize pipeline components
        JavaAstScanner astScanner = new JavaAstScanner();
        RegexScanner regexScanner = new RegexScanner();
        FilterEngine filterEngine = new FilterEngine(filterConfig);
        AiReviewer aiReviewer = new AiReviewer(config);
        ManifestWriter writer = new ManifestWriter(config);

        // 4. Process each module
        int totalCandidates = 0;
        int modulesProcessed = 0;
        List<TranslationUnit> allUnits = new ArrayList<>();

        for (ModuleScanner.ModuleInfo module : modules) {
            if (!module.hasTranslatableContent()) continue;

            System.out.print("  [" + module.name + "] ");
            List<TranslationUnit> moduleUnits = new ArrayList<>();

            // 4a. AST scan (Java files)
            if (module.javaFiles > 0) {
                astScanner.resetStats();
                List<TranslationUnit> astUnits = astScanner.scanModule(module, pathResolver);
                moduleUnits.addAll(astUnits);
            }

            // 4b. Regex scan (Python + HTML + TOC)
            List<TranslationUnit> regexUnits = regexScanner.scanModule(module, pathResolver);
            moduleUnits.addAll(regexUnits);

            totalCandidates += moduleUnits.size();

            // 4c. Filter
            filterEngine.resetStats();
            List<TranslationUnit> filtered = filterEngine.filter(moduleUnits);

            // 4d. AI review
            aiReviewer.resetStats();
            List<TranslationUnit> reviewed = aiReviewer.review(filtered);

            allUnits.addAll(reviewed);
            modulesProcessed++;

            System.out.println("candidates=" + moduleUnits.size()
                + " → filtered=" + filtered.size()
                + " → reviewed=" + reviewed.size());
        }

        // 5. Build manifest
        ExtractionManifest manifest = new ExtractionManifest();
        manifest.setMetadata(
            new ExtractionManifest.Metadata()
                .setGhidraVersion(config.getGhidraVersion())
                .setExtractionDate(Instant.now())
                .setTotalCandidates(totalCandidates)
        );
        manifest.setUnits(allUnits);

        // 6. Write output + summary
        writer.write(manifest);

        System.out.println();
        System.out.println("Pipeline complete: " + modulesProcessed + " modules, "
            + allUnits.size() + " final units");

        return manifest;
    }
}
