package com.ghidra.i18n.extract.regex;

import com.ghidra.i18n.common.module.ModuleScanner.ModuleInfo;
import com.ghidra.i18n.common.util.GhidraPathResolver;
import com.ghidra.i18n.common.util.StringClassifier;
import com.ghidra.i18n.extract.model.TranslationUnit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Scans Ghidra HTML help files (.htm/.html) for translatable text content.
 *
 * <p>Targets visible text in heading, paragraph, list-item, table-cell, and
 * anchor tags. Skips meta, script, style, code, and comments.</p>
 */
public class HtmlHelpScanner {

    private static final Pattern TITLE = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADING = Pattern.compile("<h([1-6])[^>]*>([^<]+)</h\\1>", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARAGRAPH = Pattern.compile("<p[^>]*>([^<]{10,})</p>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIST_ITEM = Pattern.compile("<li[^>]*>([^<]{5,})</li>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANCHOR = Pattern.compile("<a[^>]*>([^<]{3,})</a>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_CELL = Pattern.compile("<t[hd][^>]*>([^<]{3,})</t[hd]>", Pattern.CASE_INSENSITIVE);

    private final StringClassifier classifier;

    public HtmlHelpScanner(StringClassifier classifier) {
        this.classifier = classifier;
    }

    public List<TranslationUnit> scanModule(ModuleInfo module, GhidraPathResolver pathResolver) {
        List<TranslationUnit> results = new ArrayList<>();
        Path helpDir = pathResolver.getHelpDir(module.name);
        if (!Files.isDirectory(helpDir)) return results;

        try (Stream<Path> files = Files.walk(helpDir)) {
            files.filter(Files::isRegularFile)
                .filter(f -> {
                    String name = f.getFileName().toString().toLowerCase();
                    return name.endsWith(".htm") || name.endsWith(".html");
                })
                .forEach(f -> results.addAll(scanFile(f, module)));
        } catch (IOException e) {
            System.err.println("WARN: Failed to walk help dir " + helpDir + ": " + e.getMessage());
        }
        return results;
    }

    private List<TranslationUnit> scanFile(Path file, ModuleInfo module) {
        List<TranslationUnit> results = new ArrayList<>();
        try {
            String html = Files.readString(file);
            String baseName = file.getFileName().toString();

            // 1. Title
            Matcher tm = TITLE.matcher(html);
            while (tm.find()) {
                String text = tm.group(1).trim();
                if (isValid(text)) {
                    results.add(makeUnit(module, file.toString(), baseName, text,
                        TranslationUnit.ExtractionPattern.HTML_TEXT, TranslationUnit.UiContext.HTML_HEADING));
                }
            }

            // 2. Headings
            Matcher hm = HEADING.matcher(html);
            while (hm.find()) {
                String text = hm.group(2).trim();
                if (isValid(text)) {
                    results.add(makeUnit(module, file.toString(), baseName, text,
                        TranslationUnit.ExtractionPattern.HTML_TEXT, TranslationUnit.UiContext.HTML_HEADING));
                }
            }

            // 3. Paragraphs (longer text, likely translatable)
            Matcher pm = PARAGRAPH.matcher(html);
            while (pm.find()) {
                String text = pm.group(1).trim();
                // Strip inner HTML tags for clean text
                text = text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                if (isValid(text) && text.length() > 10) {
                    results.add(makeUnit(module, file.toString(), baseName, text,
                        TranslationUnit.ExtractionPattern.HTML_TEXT, TranslationUnit.UiContext.HTML_CONTENT));
                }
            }

            // 4. List items
            Matcher lm = LIST_ITEM.matcher(html);
            while (lm.find()) {
                String text = lm.group(1).trim();
                text = text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                if (isValid(text)) {
                    results.add(makeUnit(module, file.toString(), baseName, text,
                        TranslationUnit.ExtractionPattern.HTML_TEXT, TranslationUnit.UiContext.HTML_CONTENT));
                }
            }

            // 5. Anchors (skip URLs, keep descriptive text)
            Matcher am = ANCHOR.matcher(html);
            while (am.find()) {
                String text = am.group(1).trim();
                text = text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                if (isValid(text) && !text.matches("^https?://.*")) {
                    results.add(makeUnit(module, file.toString(), baseName, text,
                        TranslationUnit.ExtractionPattern.HTML_TEXT, TranslationUnit.UiContext.HTML_CONTENT));
                }
            }

            // 6. Table cells
            Matcher tcm = TABLE_CELL.matcher(html);
            while (tcm.find()) {
                String text = tcm.group(1).trim();
                text = text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                if (isValid(text)) {
                    results.add(makeUnit(module, file.toString(), baseName, text,
                        TranslationUnit.ExtractionPattern.HTML_TEXT, TranslationUnit.UiContext.HTML_CONTENT));
                }
            }

        } catch (IOException ignored) {}

        return results;
    }

    private boolean isValid(String text) {
        if (text == null || text.isBlank()) return false;
        if (classifier.isPunctuationOnly(text)) return false;
        if (text.matches("^\\d+$")) return false;
        if (text.matches("^[A-Z_]+$")) return false;
        return text.length() >= 2 && text.matches(".*[a-zA-Z].*");
    }

    private TranslationUnit makeUnit(ModuleInfo module, String filePath, String baseName,
                                      String text, TranslationUnit.ExtractionPattern pattern,
                                      TranslationUnit.UiContext context) {
        return new TranslationUnit()
            .setId(module.name + "." + baseName + "." + text.substring(0, Math.min(text.length(), 20)).replaceAll("[^a-zA-Z]", "_"))
            .setModuleName(module.name)
            .setSourceFilePath(filePath)
            .setClassName(baseName)
            .setFullClassName(baseName)
            .setPattern(pattern)
            .setSourceText(text)
            .setPriority(TranslationUnit.Priority.P2)
            .setContext(context)
            .setHtml(true)
            .setHasFormatSpecifier(classifier.hasFormatSpecifiers(text))
            .setContainsMnemonic(false)
            .setAiReviewStatus(TranslationUnit.AiReviewStatus.PENDING)
            .setTranslationStatus(TranslationUnit.TranslationStatus.UNTRANSLATED);
    }
}
