package com.ghidra.i18n.extract.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Output manifest from the extraction stage.
 * Contains metadata about the extraction run and the list of all extracted
 * {@link TranslationUnit} objects to be passed to the translation stage.
 */
public class ExtractionManifest {

    private Metadata metadata;
    private List<TranslationUnit> units = new ArrayList<>();

    public ExtractionManifest() {}

    public Metadata getMetadata() { return metadata; }
    public ExtractionManifest setMetadata(Metadata metadata) { this.metadata = metadata; return this; }
    public List<TranslationUnit> getUnits() { return units; }
    public ExtractionManifest setUnits(List<TranslationUnit> units) { this.units = units; return this; }

    public void addUnit(TranslationUnit unit) {
        this.units.add(unit);
    }

    /**
     * Metadata about a single extraction run.
     */
    public static class Metadata {
        private String ghidraVersion;
        private Instant extractionDate;
        private int totalCandidates;
        private int aiFiltered;
        private int aiConfirmed;
        private int needsManualReview;

        public Metadata() {}

        public String getGhidraVersion() { return ghidraVersion; }
        public Metadata setGhidraVersion(String ghidraVersion) { this.ghidraVersion = ghidraVersion; return this; }
        public Instant getExtractionDate() { return extractionDate; }
        public Metadata setExtractionDate(Instant extractionDate) { this.extractionDate = extractionDate; return this; }
        public int getTotalCandidates() { return totalCandidates; }
        public Metadata setTotalCandidates(int totalCandidates) { this.totalCandidates = totalCandidates; return this; }
        public int getAiFiltered() { return aiFiltered; }
        public Metadata setAiFiltered(int aiFiltered) { this.aiFiltered = aiFiltered; return this; }
        public int getAiConfirmed() { return aiConfirmed; }
        public Metadata setAiConfirmed(int aiConfirmed) { this.aiConfirmed = aiConfirmed; return this; }
        public int getNeedsManualReview() { return needsManualReview; }
        public Metadata setNeedsManualReview(int needsManualReview) { this.needsManualReview = needsManualReview; return this; }
    }

    @Override
    public String toString() {
        return "ExtractionManifest{" +
            "version=" + (metadata != null ? metadata.ghidraVersion : "null") +
            ", units=" + units.size() +
            '}';
    }
}
