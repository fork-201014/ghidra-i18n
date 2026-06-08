package com.ghidra.i18n.extract.model;

import java.util.List;
import java.util.Set;

/**
 * Immutable configuration for the extraction filter engine.
 *
 * <p>Loaded from {@code filter-config.yml}, {@code exclude-patterns.txt},
 * and {@code manual-approvals.yml} by {@link com.ghidra.i18n.extract.filter.FilterConfigLoader}.</p>
 */
public class FilterConfig {

    // API calls whose string arguments should be excluded
    private final Set<String> excludeApis;

    // Source line contexts that indicate non-UI strings
    private final Set<String> excludeContexts;

    // Regex patterns for string content exclusion
    private final List<java.util.regex.Pattern> excludePatterns;

    // Whether to exclude single-character strings
    private final boolean excludeSingleChars;

    // Minimum length for a meaningful translatable string
    private final int minMeaningfulLength;

    // File path globs to skip entirely
    private final List<String> excludeFilePaths;

    // Debug/todo marker strings to exclude
    private final Set<String> excludeDebugMarkers;

    // Module names to skip
    private final Set<String> excludeModules;

    // Manual approval overrides: unitId -> true=force-approve, false=force-reject
    private final java.util.Map<String, Boolean> manualApprovals;

    private FilterConfig(Builder builder) {
        this.excludeApis = Set.copyOf(builder.excludeApis);
        this.excludeContexts = Set.copyOf(builder.excludeContexts);
        this.excludePatterns = List.copyOf(builder.excludePatterns);
        this.excludeSingleChars = builder.excludeSingleChars;
        this.minMeaningfulLength = builder.minMeaningfulLength;
        this.excludeFilePaths = List.copyOf(builder.excludeFilePaths);
        this.excludeDebugMarkers = Set.copyOf(builder.excludeDebugMarkers);
        this.excludeModules = Set.copyOf(builder.excludeModules);
        this.manualApprovals = new java.util.LinkedHashMap<>(builder.manualApprovals);
    }

    public static Builder builder() { return new Builder(); }

    public Set<String> getExcludeApis()           { return excludeApis; }
    public Set<String> getExcludeContexts()        { return excludeContexts; }
    public List<java.util.regex.Pattern> getExcludePatterns() { return excludePatterns; }
    public boolean isExcludeSingleChars()          { return excludeSingleChars; }
    public int getMinMeaningfulLength()            { return minMeaningfulLength; }
    public List<String> getExcludeFilePaths()      { return excludeFilePaths; }
    public Set<String> getExcludeDebugMarkers()    { return excludeDebugMarkers; }
    public Set<String> getExcludeModules()         { return excludeModules; }
    public java.util.Map<String, Boolean> getManualApprovals() { return manualApprovals; }

    public boolean isManualApproved(String unitId)  { return Boolean.TRUE.equals(manualApprovals.get(unitId)); }
    public boolean isManualRejected(String unitId)  { return Boolean.FALSE.equals(manualApprovals.get(unitId)); }

    public static class Builder {
        Set<String> excludeApis = Set.of();
        Set<String> excludeContexts = Set.of();
        List<java.util.regex.Pattern> excludePatterns = List.of();
        boolean excludeSingleChars = true;
        int minMeaningfulLength = 2;
        List<String> excludeFilePaths = List.of();
        Set<String> excludeDebugMarkers = Set.of();
        Set<String> excludeModules = Set.of();
        java.util.Map<String, Boolean> manualApprovals = new java.util.LinkedHashMap<>();

        public Builder excludeApis(Set<String> v)              { this.excludeApis = v; return this; }
        public Builder excludeContexts(Set<String> v)           { this.excludeContexts = v; return this; }
        public Builder excludePatterns(List<java.util.regex.Pattern> v) { this.excludePatterns = v; return this; }
        public Builder excludeSingleChars(boolean v)            { this.excludeSingleChars = v; return this; }
        public Builder minMeaningfulLength(int v)               { this.minMeaningfulLength = v; return this; }
        public Builder excludeFilePaths(List<String> v)         { this.excludeFilePaths = v; return this; }
        public Builder excludeDebugMarkers(Set<String> v)       { this.excludeDebugMarkers = v; return this; }
        public Builder excludeModules(Set<String> v)            { this.excludeModules = v; return this; }
        public Builder manualApprovals(java.util.Map<String, Boolean> v) { this.manualApprovals = v; return this; }

        public FilterConfig build() { return new FilterConfig(this); }
    }
}
