package com.ghidra.i18n.extract.model;

import java.time.Instant;
import java.util.Objects;

/**
 * A single translatable UI string extracted from Ghidra source code.
 * This is the core data model shared across extract, translate, and transform stages.
 *
 * <p>Each TranslationUnit represents one hard-coded English string found in Ghidra's
 * Java/Python/HTML/XML source files, along with its metadata and translation state.</p>
 */
public class TranslationUnit {

    // ExtractionPattern and Context enums for type safety
    public enum ExtractionPattern {
        SET_TITLE, SET_TOOL_TIP_TEXT, NEW_JBUTTON, NEW_JLABEL, NEW_GLABEL,
        NEW_GHTML_LABEL, NEW_GICON_LABEL, NEW_JMENU_ITEM, NEW_JMENU, NEW_JCHECK_BOX, NEW_JRADIO_BUTTON,
        NEW_JTOGGLE_BUTTON, NEW_TITLED_BORDER, NEW_JFRAME, NEW_JDIALOG,
        PLUGIN_SHORT_DESC, PLUGIN_DESCRIPTION, PLUGIN_CATEGORY,
        SET_TEXT, SET_LABEL, SET_DESCRIPTION, SET_NAME,
        SHOW_DIALOG, SHOW_CONFIRM_DIALOG, SHOW_ERROR_DIALOG, SHOW_MESSAGE_DIALOG,
        DOCKING_ACTION_NAME, DOCKING_ACTION_DESCRIPTION,
        TOC_TEXT, HTML_TEXT, PY_STRING, OTHER
    }

    public enum UiContext {
        DIALOG_TITLE, BUTTON, LABEL, MENU_ITEM, MENU_LABEL,
        CHECKBOX, RADIO, TOOLTIP, BORDER_TITLE,
        PLUGIN_DESC, PLUGIN_SHORT_DESC, DIALOG_MESSAGE,
        ACTION_NAME, ACTION_DESCRIPTION,
        TOC_ENTRY, HTML_CONTENT, HTML_HEADING, PYTHON_STRING, OTHER
    }

    public enum Priority {
        P0, P1, P2
    }

    public enum AiReviewStatus {
        PENDING, APPROVED, REJECTED, NEEDS_REVIEW
    }

    public enum TranslationStatus {
        UNTRANSLATED, MACHINE_TRANSLATED, VERIFIED, NEEDS_UPDATE
    }

    // --- Required fields ---
    private String id;
    private String moduleName;
    private String sourceFilePath;
    private String className;
    private String fullClassName;
    private ExtractionPattern pattern;
    private String sourceText;
    private Priority priority;

    // --- Optional descriptive fields ---
    private UiContext context = UiContext.OTHER;
    private boolean isHtml = false;
    private boolean hasFormatSpecifier = false;
    private boolean containsMnemonic = false;

    // --- Review / Approval ---
    private AiReviewStatus aiReviewStatus = AiReviewStatus.PENDING;
    private boolean manualApproval = false;

    // --- Translation ---
    private String translationZhCN = "";
    private TranslationStatus translationStatus = TranslationStatus.UNTRANSLATED;

    // --- Metadata ---
    private Instant lastModified;

    public TranslationUnit() {}

    // Builder-style setters for fluent construction

    public TranslationUnit setId(String id) { this.id = id; return this; }
    public TranslationUnit setModuleName(String moduleName) { this.moduleName = moduleName; return this; }
    public TranslationUnit setSourceFilePath(String sourceFilePath) { this.sourceFilePath = sourceFilePath; return this; }
    public TranslationUnit setClassName(String className) { this.className = className; return this; }
    public TranslationUnit setFullClassName(String fullClassName) { this.fullClassName = fullClassName; return this; }
    public TranslationUnit setPattern(ExtractionPattern pattern) { this.pattern = pattern; return this; }
    public TranslationUnit setSourceText(String sourceText) { this.sourceText = sourceText; return this; }
    public TranslationUnit setPriority(Priority priority) { this.priority = priority; return this; }
    public TranslationUnit setContext(UiContext context) { this.context = context; return this; }
    public TranslationUnit setHtml(boolean html) { isHtml = html; return this; }
    public TranslationUnit setHasFormatSpecifier(boolean hasFormatSpecifier) { this.hasFormatSpecifier = hasFormatSpecifier; return this; }
    public TranslationUnit setContainsMnemonic(boolean containsMnemonic) { this.containsMnemonic = containsMnemonic; return this; }
    public TranslationUnit setAiReviewStatus(AiReviewStatus aiReviewStatus) { this.aiReviewStatus = aiReviewStatus; return this; }
    public TranslationUnit setManualApproval(boolean manualApproval) { this.manualApproval = manualApproval; return this; }
    public TranslationUnit setTranslationZhCN(String translationZhCN) { this.translationZhCN = translationZhCN; return this; }
    public TranslationUnit setTranslationStatus(TranslationStatus translationStatus) { this.translationStatus = translationStatus; return this; }
    public TranslationUnit setLastModified(Instant lastModified) { this.lastModified = lastModified; return this; }

    // Getters

    public String getId() { return id; }
    public String getModuleName() { return moduleName; }
    public String getSourceFilePath() { return sourceFilePath; }
    public String getClassName() { return className; }
    public String getFullClassName() { return fullClassName; }
    public ExtractionPattern getPattern() { return pattern; }
    public String getSourceText() { return sourceText; }
    public Priority getPriority() { return priority; }
    public UiContext getContext() { return context; }
    public boolean isHtml() { return isHtml; }
    public boolean isHasFormatSpecifier() { return hasFormatSpecifier; }
    public boolean isContainsMnemonic() { return containsMnemonic; }
    public AiReviewStatus getAiReviewStatus() { return aiReviewStatus; }
    public boolean isManualApproval() { return manualApproval; }
    public String getTranslationZhCN() { return translationZhCN; }
    public TranslationStatus getTranslationStatus() { return translationStatus; }
    public Instant getLastModified() { return lastModified; }

    /**
     * Returns true if this unit has a completed translation.
     */
    public boolean isTranslated() {
        return translationStatus == TranslationStatus.MACHINE_TRANSLATED
            || translationStatus == TranslationStatus.VERIFIED;
    }

    /**
     * Returns the properties-file-compatible key, with dots replaced where needed.
     */
    public String getPropertiesKey() {
        // The id is already in dot format: "Docking.LaunchErrorDialog.title"
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TranslationUnit that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "TranslationUnit{" + "id='" + id + '\'' + ", sourceText='" + truncate(sourceText, 50) + '\'' + '}';
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
