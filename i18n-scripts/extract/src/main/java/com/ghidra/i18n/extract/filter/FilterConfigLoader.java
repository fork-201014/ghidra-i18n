package com.ghidra.i18n.extract.filter;

import com.ghidra.i18n.common.config.GlobalConfig;
import com.ghidra.i18n.extract.model.FilterConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Loads filter configuration from the three config files set up in Phase 1:
 *
 * <ul>
 *   <li>{@code filter-config.yml} — YAML file with API excludes, contexts, patterns, modules</li>
 *   <li>{@code exclude-patterns.txt} — one regex per line for string content exclusion</li>
 *   <li>{@code manual-approvals.yml} — unit ID → approved | rejected</li>
 * </ul>
 */
public class FilterConfigLoader {

    private final GlobalConfig config;

    public FilterConfigLoader(GlobalConfig config) {
        this.config = config;
    }

    /**
     * Loads and merges all filter configuration into a single {@link FilterConfig}.
     */
    public FilterConfig load() throws IOException {
        FilterConfig.Builder builder = FilterConfig.builder();

        // 1. Load filter-config.yml
        Path ymlFile = config.getFilterConfigFile();
        if (Files.isRegularFile(ymlFile)) {
            loadYamlConfig(builder, ymlFile);
        }

        // 2. Load exclude-patterns.txt
        Path patternsFile = config.getExcludePatternsFile();
        if (Files.isRegularFile(patternsFile)) {
            builder.excludePatterns(loadExcludePatterns(patternsFile));
        }

        // 3. Load manual-approvals.yml
        Path approvalsFile = config.getManualApprovalsFile();
        if (Files.isRegularFile(approvalsFile)) {
            builder.manualApprovals(loadManualApprovals(approvalsFile));
        }

        return builder.build();
    }

    // -----------------------------------------------------------------------
    // YAML config parser (lightweight, no SnakeYAML dependency for model)
    // -----------------------------------------------------------------------

    private void loadYamlConfig(FilterConfig.Builder builder, Path ymlFile) throws IOException {
        List<String> lines = Files.readAllLines(ymlFile);
        String currentSection = null;
        List<String> excludeApis = new ArrayList<>();
        List<String> excludeContexts = new ArrayList<>();
        Set<String> excludeDebugMarkers = new HashSet<>();
        Set<String> excludeModules = new HashSet<>();

        for (String line : lines) {
            String trimmed = line.strip();

            // Section headers
            if (trimmed.equals("exclude_apis:"))  { currentSection = "apis"; continue; }
            if (trimmed.equals("exclude_contexts:")) { currentSection = "contexts"; continue; }
            if (trimmed.equals("exclude_debug_markers:")) { currentSection = "debug"; continue; }
            if (trimmed.equals("exclude_modules:")) { currentSection = "modules"; continue; }
            if (trimmed.startsWith("exclude_patterns:")) { currentSection = "patterns_raw"; continue; }
            if (trimmed.equals("exclude_single_chars:")) {
                // next line should be true/false
                continue;
            }
            if (trimmed.equals("min_meaningful_length:")) { continue; }
            if (trimmed.startsWith("exclude_file_paths:")) { currentSection = "filepaths"; continue; }

            // List items: "- value" or "- "value""
            if (trimmed.startsWith("- ") || trimmed.startsWith("- \"")) {
                String value = trimmed.replaceFirst("^-\\s*\"?", "").replaceFirst("\"$", "").trim();
                if (value.isEmpty()) continue;

                switch (currentSection != null ? currentSection : "") {
                    case "apis":     excludeApis.add(value); break;
                    case "contexts": excludeContexts.add(value); break;
                    case "debug":    excludeDebugMarkers.add(value); break;
                    case "modules":  excludeModules.add(value); break;
                }
                continue;
            }

            // Boolean values
            if (trimmed.startsWith("exclude_single_chars:")) {
                builder.excludeSingleChars(trimmed.contains("true"));
            }
            if (trimmed.startsWith("min_meaningful_length:")) {
                try {
                    int val = Integer.parseInt(trimmed.replaceAll("[^0-9]", ""));
                    builder.minMeaningfulLength(val);
                } catch (NumberFormatException ignored) {}
            }
        }

        builder.excludeApis(Set.copyOf(excludeApis));
        builder.excludeContexts(Set.copyOf(excludeContexts));
        builder.excludeDebugMarkers(Set.copyOf(excludeDebugMarkers));
        builder.excludeModules(Set.copyOf(excludeModules));
    }

    // -----------------------------------------------------------------------
    // Regex pattern loader
    // -----------------------------------------------------------------------

    public List<Pattern> loadExcludePatterns(Path patternsFile) throws IOException {
        List<String> lines = Files.readAllLines(patternsFile);
        List<Pattern> patterns = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.strip();
            // Skip comments and blank lines
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            try {
                patterns.add(Pattern.compile(trimmed));
            } catch (PatternSyntaxException e) {
                System.err.println("WARN: Invalid regex in exclude patterns: " + trimmed + " — " + e.getMessage());
            }
        }

        return patterns;
    }

    // -----------------------------------------------------------------------
    // Manual approvals loader
    // -----------------------------------------------------------------------

    public Map<String, Boolean> loadManualApprovals(Path approvalsFile) throws IOException {
        Map<String, Boolean> approvals = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(approvalsFile);

        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            String[] parts = trimmed.split(":", 2);
            if (parts.length < 2) continue;

            String unitId = parts[0].trim();
            String value = parts[1].trim().replaceAll("#.*", "").trim().toLowerCase();

            if (value.equals("approved") || value.equals("true")) {
                approvals.put(unitId, true);
            } else if (value.equals("rejected") || value.equals("false")) {
                approvals.put(unitId, false);
            }
        }

        return approvals;
    }
}
