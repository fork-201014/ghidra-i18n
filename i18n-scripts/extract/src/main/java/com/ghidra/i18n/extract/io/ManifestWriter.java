package com.ghidra.i18n.extract.io;

import com.ghidra.i18n.common.config.GlobalConfig;
import com.ghidra.i18n.extract.model.ExtractionManifest;
import com.ghidra.i18n.extract.model.TranslationUnit;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Writes the extraction manifest to JSON and prints a human-readable summary.
 */
public class ManifestWriter {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private final GlobalConfig config;

    public ManifestWriter(GlobalConfig config) {
        this.config = config;
    }

    /**
     * Writes the manifest to the configured output path and prints summary to stdout.
     */
    public ExtractionManifest write(ExtractionManifest manifest) throws IOException {
        Path outputFile = config.getExtractionOutputDir().resolve("extraction-manifest.json");
        Files.createDirectories(outputFile.getParent());

        String json = GSON.toJson(manifest);
        Files.writeString(outputFile, json);

        System.out.println("Extraction manifest written to: " + outputFile);
        printSummary(manifest);

        return manifest;
    }

    /**
     * Prints a human-readable summary of the extraction results.
     */
    public void printSummary(ExtractionManifest manifest) {
        List<TranslationUnit> units = manifest.getUnits();
        ExtractionManifest.Metadata meta = manifest.getMetadata();

        System.out.println();
        System.out.println("Extraction Pipeline Summary");
        System.out.println("===========================");
        System.out.println("Ghidra version:  " + (meta != null ? meta.getGhidraVersion() : "unknown"));

        // Per module stats
        Map<String, Long> byModule = units.stream()
            .collect(Collectors.groupingBy(TranslationUnit::getModuleName, Collectors.counting()));

        // Per pattern stats
        Map<TranslationUnit.ExtractionPattern, Long> byPattern = units.stream()
            .collect(Collectors.groupingBy(TranslationUnit::getPattern, Collectors.counting()));

        // Per review status
        Map<TranslationUnit.AiReviewStatus, Long> byStatus = units.stream()
            .collect(Collectors.groupingBy(TranslationUnit::getAiReviewStatus, Collectors.counting()));

        // Totals
        int total = units.size();
        long approved = byStatus.getOrDefault(TranslationUnit.AiReviewStatus.APPROVED, 0L);
        long rejected = byStatus.getOrDefault(TranslationUnit.AiReviewStatus.REJECTED, 0L);
        long needsReview = byStatus.getOrDefault(TranslationUnit.AiReviewStatus.NEEDS_REVIEW, 0L);
        long pending = byStatus.getOrDefault(TranslationUnit.AiReviewStatus.PENDING, 0L);

        System.out.println("Total units:           " + total);
        System.out.println("  Approved:            " + approved + " (" + pct(approved, total) + ")");
        System.out.println("  Rejected:            " + rejected + " (" + pct(rejected, total) + ")");
        System.out.println("  Needs review:        " + needsReview + " (" + pct(needsReview, total) + ")");
        System.out.println("  Pending (unreviewed) " + pending + " (" + pct(pending, total) + ")");
        System.out.println();

        if (meta != null) {
            System.out.println("Raw candidates:        " + meta.getTotalCandidates());
            System.out.println("Filtered out:          " + meta.getAiFiltered());
            System.out.println();
        }

        // Top 10 modules
        System.out.println("Top modules by unit count:");
        byModule.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(10)
            .forEach(e -> System.out.println("  " + e.getKey() + ": " + e.getValue()));

        // Top patterns
        System.out.println("\nPattern distribution:");
        byPattern.entrySet().stream()
            .sorted(Map.Entry.<TranslationUnit.ExtractionPattern, Long>comparingByValue().reversed())
            .limit(15)
            .forEach(e -> System.out.println("  " + e.getKey() + ": " + e.getValue()));

        // HTML/Help coverage
        long htmlUnits = units.stream()
            .filter(TranslationUnit::isHtml)
            .count();
        System.out.println("\nHTML strings: " + htmlUnits + " (" + pct(htmlUnits, total) + ")");

        System.out.println("===========================");
    }

    private static String pct(long part, int total) {
        if (total == 0) return "0.0%";
        return String.format("%.1f%%", part * 100.0 / total);
    }
}
