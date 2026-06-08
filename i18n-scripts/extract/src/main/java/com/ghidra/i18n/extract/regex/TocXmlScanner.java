package com.ghidra.i18n.extract.regex;

import com.ghidra.i18n.common.module.ModuleScanner.ModuleInfo;
import com.ghidra.i18n.common.util.GhidraPathResolver;
import com.ghidra.i18n.common.util.StringClassifier;
import com.ghidra.i18n.extract.model.TranslationUnit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans Ghidra TOC_Source.xml files for translatable {@code text="..."} attributes.
 */
public class TocXmlScanner {

    private static final Pattern TEXT_ATTR = Pattern.compile("text=\"([^\"]+)\"");

    private final StringClassifier classifier;

    public TocXmlScanner(StringClassifier classifier) {
        this.classifier = classifier;
    }

    public List<TranslationUnit> scanModule(ModuleInfo module, GhidraPathResolver pathResolver) {
        List<TranslationUnit> results = new ArrayList<>();
        Path tocFile = pathResolver.getTocFile(module.name);
        if (!Files.isRegularFile(tocFile)) return results;

        try {
            String xml = Files.readString(tocFile);
            Matcher m = TEXT_ATTR.matcher(xml);
            while (m.find()) {
                String text = m.group(1).trim();
                if (isValid(text)) {
                    results.add(makeUnit(module, tocFile.toString(), text));
                }
            }
        } catch (IOException ignored) {}

        return results;
    }

    private boolean isValid(String text) {
        if (text == null || text.isBlank()) return false;
        if (classifier.isPunctuationOnly(text)) return false;
        if (text.matches("^\\d+$")) return false;
        return text.length() >= 2 && text.matches(".*[a-zA-Z].*");
    }

    private TranslationUnit makeUnit(ModuleInfo module, String filePath, String text) {
        return new TranslationUnit()
            .setId(module.name + ".TOC." + text.substring(0, Math.min(text.length(), 20)).replaceAll("[^a-zA-Z]", "_"))
            .setModuleName(module.name)
            .setSourceFilePath(filePath)
            .setClassName("TOC_Source")
            .setFullClassName("TOC_Source")
            .setPattern(TranslationUnit.ExtractionPattern.TOC_TEXT)
            .setSourceText(text)
            .setPriority(TranslationUnit.Priority.P1)
            .setContext(TranslationUnit.UiContext.TOC_ENTRY)
            .setHtml(false)
            .setHasFormatSpecifier(classifier.hasFormatSpecifiers(text))
            .setContainsMnemonic(false)
            .setAiReviewStatus(TranslationUnit.AiReviewStatus.PENDING)
            .setTranslationStatus(TranslationUnit.TranslationStatus.UNTRANSLATED);
    }
}
