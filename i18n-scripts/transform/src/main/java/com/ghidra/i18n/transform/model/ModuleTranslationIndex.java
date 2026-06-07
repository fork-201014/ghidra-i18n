package com.ghidra.i18n.transform.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregated translation statistics across all Ghidra submodules.
 * Used for reporting and to track translation coverage over time.
 */
public class ModuleTranslationIndex {

    private String ghidraVersion;
    private Instant generatedAt;
    private int totalUnits;
    private int translatedUnits;
    private double coveragePercent;
    private Map<String, ModuleStats> modules = new LinkedHashMap<>();

    public ModuleTranslationIndex() {}

    public String getGhidraVersion() { return ghidraVersion; }
    public ModuleTranslationIndex setGhidraVersion(String ghidraVersion) { this.ghidraVersion = ghidraVersion; return this; }
    public Instant getGeneratedAt() { return generatedAt; }
    public ModuleTranslationIndex setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; return this; }
    public int getTotalUnits() { return totalUnits; }
    public ModuleTranslationIndex setTotalUnits(int totalUnits) { this.totalUnits = totalUnits; return this; }
    public int getTranslatedUnits() { return translatedUnits; }
    public ModuleTranslationIndex setTranslatedUnits(int translatedUnits) { this.translatedUnits = translatedUnits; return this; }
    public double getCoveragePercent() { return coveragePercent; }
    public ModuleTranslationIndex setCoveragePercent(double coveragePercent) { this.coveragePercent = coveragePercent; return this; }
    public Map<String, ModuleStats> getModules() { return modules; }
    public ModuleTranslationIndex setModules(Map<String, ModuleStats> modules) { this.modules = modules; return this; }

    public void putModule(String name, ModuleStats stats) {
        modules.put(name, stats);
    }

    /**
     * Recomputes totals from module stats. Call after building the modules map.
     */
    public ModuleTranslationIndex recomputeTotals() {
        this.totalUnits = modules.values().stream().mapToInt(ModuleStats::getTotal).sum();
        this.translatedUnits = modules.values().stream().mapToInt(ModuleStats::getTranslated).sum();
        this.coveragePercent = totalUnits > 0 ? (translatedUnits * 100.0 / totalUnits) : 0.0;
        return this;
    }

    /**
     * Per-module statistics.
     */
    public static class ModuleStats {
        private int total;
        private int translated;
        private int attributeFiles;

        public ModuleStats() {}

        public ModuleStats(int total, int translated, int attributeFiles) {
            this.total = total;
            this.translated = translated;
            this.attributeFiles = attributeFiles;
        }

        public int getTotal() { return total; }
        public ModuleStats setTotal(int total) { this.total = total; return this; }
        public int getTranslated() { return translated; }
        public ModuleStats setTranslated(int translated) { this.translated = translated; return this; }
        public int getAttributeFiles() { return attributeFiles; }
        public ModuleStats setAttributeFiles(int attributeFiles) { this.attributeFiles = attributeFiles; return this; }

        public double coveragePercent() {
            return total > 0 ? (translated * 100.0 / total) : 0.0;
        }
    }

    @Override
    public String toString() {
        return "ModuleTranslationIndex{" +
            "version='" + ghidraVersion + '\'' +
            ", modules=" + modules.size() +
            ", coverage=" + String.format("%.1f%%", coveragePercent) +
            '}';
    }
}
