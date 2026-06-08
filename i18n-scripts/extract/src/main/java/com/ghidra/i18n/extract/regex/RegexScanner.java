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
 * Regex-based fallback scanner for Java AST failures and non-Java source files.
 *
 * <p>Handles four file categories:</p>
 * <ul>
 *   <li>Java files that JavaParser could not parse (AST fallback)</li>
 *   <li>Python scripts (print strings, UI labels)</li>
 *   <li>HTML help files (h1-h6, title, p, li text content)</li>
 *   <li>TOC XML files (text attributes)</li>
 * </ul>
 *
 * <p>Delegates HTML and TOC scanning to {@link HtmlHelpScanner} and {@link TocXmlScanner}.</p>
 */
public class RegexScanner {

    private final StringClassifier classifier;
    private final Pattern[] javaPatterns;
    private final HtmlHelpScanner htmlScanner;
    private final TocXmlScanner tocScanner;

    // ---- Java regex patterns (ordered: most specific first) ----
    private static final String[][] JAVA_PATTERN_DEFS = {
        // { regex, patternName, context, priority }
        { "\\.setTitle\\s*\\(\\s*\"([^\"]*)\"",            "SET_TITLE",          "DIALOG_TITLE",      "P0" },
        { "\\.setToolTipText\\s*\\(\\s*\"([^\"]*)\"",      "SET_TOOL_TIP_TEXT",  "TOOLTIP",           "P0" },
        { "new\\s+JButton\\s*\\(\\s*\"([^\"]*)\"",          "NEW_JBUTTON",        "BUTTON",            "P0" },
        { "new\\s+JLabel\\s*\\(\\s*\"([^\"]*)\"",           "NEW_JLABEL",         "LABEL",             "P0" },
        { "new\\s+GLabel\\s*\\(\\s*\"([^\"]*)\"",           "NEW_GLABEL",         "LABEL",             "P0" },
        { "new\\s+GHtmlLabel\\s*\\(\\s*\"([^\"]*)\"",       "NEW_GHTML_LABEL",    "LABEL",             "P0" },
        { "new\\s+GIconLabel\\s*\\(\\s*\"([^\"]*)\"",       "NEW_GICON_LABEL",    "LABEL",             "P1" },
        { "new\\s+JMenuItem\\s*\\(\\s*\"([^\"]*)\"",        "NEW_JMENU_ITEM",     "MENU_ITEM",         "P0" },
        { "new\\s+JCheckBoxMenuItem\\s*\\(\\s*\"([^\"]*)\"", "NEW_JMENU_ITEM",    "MENU_ITEM",         "P0" },
        { "new\\s+JMenu\\s*\\(\\s*\"([^\"]*)\"",            "NEW_JMENU",          "MENU_LABEL",        "P0" },
        { "new\\s+JCheckBox\\s*\\(\\s*\"([^\"]*)\"",        "NEW_JCHECK_BOX",     "CHECKBOX",          "P0" },
        { "new\\s+JRadioButton\\s*\\(\\s*\"([^\"]*)\"",     "NEW_JRADIO_BUTTON",  "RADIO",             "P0" },
        { "new\\s+JToggleButton\\s*\\(\\s*\"([^\"]*)\"",    "NEW_JTOGGLE_BUTTON", "BUTTON",            "P1" },
        { "new\\s+TitledBorder\\s*\\(\\s*\"([^\"]*)\"",     "NEW_TITLED_BORDER",  "BORDER_TITLE",      "P0" },
        { "new\\s+JFrame\\s*\\(\\s*\"([^\"]*)\"",           "NEW_JFRAME",         "DIALOG_TITLE",      "P1" },
        { "new\\s+JDialog\\s*\\([^)]*\"([^\"]*)\"",         "NEW_JDIALOG",        "DIALOG_TITLE",      "P1" },
        { "new\\s+DockingAction[If]?\\s*\\(\\s*\"([^\"]*)\"","DOCKING_ACTION_NAME","ACTION_NAME",       "P1" },
        { "(shortDescription|description)\\s*=\\s*\"([^\"]*)\"","PLUGIN_SHORT_DESC","PLUGIN_SHORT_DESC","P1" },
        { "\\.setText\\s*\\(\\s*\"([^\"]*)\"",              "SET_TEXT",           "LABEL",             "P0" },
        { "\\.setLabel\\s*\\(\\s*\"([^\"]*)\"",             "SET_LABEL",          "LABEL",             "P0" },
        { "\\.setDescription\\s*\\(\\s*\"([^\"]*)\"",       "SET_DESCRIPTION",    "ACTION_DESCRIPTION","P1" },
        { "\\.setName\\s*\\(\\s*\"([^\"]*)\"",              "SET_NAME",           "ACTION_NAME",       "P1" },
    };

    public RegexScanner() {
        this.classifier = new StringClassifier();
        this.javaPatterns = new Pattern[JAVA_PATTERN_DEFS.length];
        for (int i = 0; i < JAVA_PATTERN_DEFS.length; i++) {
            javaPatterns[i] = Pattern.compile(JAVA_PATTERN_DEFS[i][0]);
        }
        this.htmlScanner = new HtmlHelpScanner(classifier);
        this.tocScanner = new TocXmlScanner(classifier);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Scans all non-Java and AST-failed files in a module.
     * @param module module metadata
     * @param pathResolver path resolver
     * @param astFailedFiles set of Java file paths that AST failed on
     */
    public List<TranslationUnit> scanModule(ModuleInfo module, GhidraPathResolver pathResolver,
                                             Set<Path> astFailedFiles) {
        List<TranslationUnit> results = new ArrayList<>();

        // 1. Java AST fallback files
        if (astFailedFiles != null && !astFailedFiles.isEmpty()) {
            for (Path f : astFailedFiles) {
                results.addAll(scanJavaFileWithRegex(f, module, pathResolver));
            }
        }

        // 2. Python files
        results.addAll(scanPythonFiles(module, pathResolver));

        // 3. HTML help files
        results.addAll(htmlScanner.scanModule(module, pathResolver));

        // 4. TOC XML files
        results.addAll(tocScanner.scanModule(module, pathResolver));

        return deduplicate(results);
    }

    /**
     * Scans all non-Java files only (Python/HTML/TOC). No AST fallback.
     */
    public List<TranslationUnit> scanModule(ModuleInfo module, GhidraPathResolver pathResolver) {
        return scanModule(module, pathResolver, Collections.emptySet());
    }

    // -----------------------------------------------------------------------
    // Java regex fallback
    // -----------------------------------------------------------------------

    List<TranslationUnit> scanJavaFileWithRegex(Path file, ModuleInfo module, GhidraPathResolver pathResolver) {
        List<TranslationUnit> results = new ArrayList<>();
        try {
            String source = Files.readString(file);
            String className = extractClassName(file);

            for (int i = 0; i < javaPatterns.length; i++) {
                Matcher m = javaPatterns[i].matcher(source);
                while (m.find()) {
                    String text = m.group(1);
                    if (text == null || text.isBlank()) continue;
                    if (classifier.isPunctuationOnly(text)) continue;
                    if (classifier.isUrl(text)) continue;

                    results.add(buildUnit(text, JAVA_PATTERN_DEFS[i][1],
                        JAVA_PATTERN_DEFS[i][2], JAVA_PATTERN_DEFS[i][3],
                        module.name, file.toString(), className, className));
                }
            }
        } catch (IOException ignored) {
            // File unreadable — skip
        }
        return results;
    }

    // -----------------------------------------------------------------------
    // Python files
    // -----------------------------------------------------------------------

    private List<TranslationUnit> scanPythonFiles(ModuleInfo module, GhidraPathResolver pathResolver) {
        List<TranslationUnit> results = new ArrayList<>();
        Path pyDir = pathResolver.getModulePath(module.name).resolve("src/main/py");
        if (!Files.isDirectory(pyDir)) {
            // Try PyGhidra convention: src/main/py/src
            pyDir = pathResolver.getModulePath(module.name).resolve("src/main/py/src");
        }
        if (!Files.isDirectory(pyDir)) return results;

        try (Stream<Path> files = Files.walk(pyDir)) {
            files.filter(Files::isRegularFile)
                .filter(f -> f.getFileName().toString().endsWith(".py"))
                .forEach(f -> results.addAll(scanPythonFile(f, module)));
        } catch (IOException e) {
            System.err.println("WARN: Failed to walk Python dir " + pyDir + ": " + e.getMessage());
        }
        return results;
    }

    private List<TranslationUnit> scanPythonFile(Path file, ModuleInfo module) {
        List<TranslationUnit> results = new ArrayList<>();
        try {
            String source = Files.readString(file);
            String scriptName = file.getFileName().toString().replace(".py", "");

            // Pattern for string literals in Python: print("..."), label="...", description="..."
            // Simple heuristic: match double-quoted strings that look like UI text
            Pattern pyString = Pattern.compile("\"([A-Z][a-zA-Z0-9 ,.:;!?/()'&-]{5,})\"");
            Matcher m = pyString.matcher(source);
            int counter = 0;
            while (m.find()) {
                String text = m.group(1);
                if (classifier.isPunctuationOnly(text)) continue;
                if (classifier.isCodeConstant(text)) continue;
                if (text.toLowerCase().contains("http")) continue;
                counter++;
                results.add(buildUnit(text, "PY_STRING", "PYTHON_STRING", "P2",
                    module.name, file.toString(), scriptName, scriptName));
            }
        } catch (IOException ignored) {}
        return results;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private TranslationUnit buildUnit(String text, String pattern, String context, String priority,
                                       String moduleName, String filePath, String className, String fullClassName) {
        TranslationUnit.ExtractionPattern ep = parsePattern(pattern);
        TranslationUnit.UiContext uc = parseContext(context);
        TranslationUnit.Priority pr = parsePriority(priority);

        return new TranslationUnit()
            .setId(moduleName + "." + className + "." + ep.name().toLowerCase())
            .setModuleName(moduleName)
            .setSourceFilePath(filePath)
            .setClassName(className)
            .setFullClassName(fullClassName)
            .setPattern(ep)
            .setSourceText(text)
            .setPriority(pr)
            .setContext(uc)
            .setHtml(classifier.containsHtml(text))
            .setHasFormatSpecifier(classifier.hasFormatSpecifiers(text))
            .setContainsMnemonic(classifier.hasMnemonic(text))
            .setAiReviewStatus(TranslationUnit.AiReviewStatus.PENDING)
            .setTranslationStatus(TranslationUnit.TranslationStatus.UNTRANSLATED);
    }

    private String extractClassName(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
    }

    private TranslationUnit.ExtractionPattern parsePattern(String p) {
        try { return TranslationUnit.ExtractionPattern.valueOf(p); }
        catch (IllegalArgumentException e) { return TranslationUnit.ExtractionPattern.OTHER; }
    }

    private TranslationUnit.UiContext parseContext(String c) {
        try { return TranslationUnit.UiContext.valueOf(c); }
        catch (IllegalArgumentException e) { return TranslationUnit.UiContext.OTHER; }
    }

    private TranslationUnit.Priority parsePriority(String p) {
        try { return TranslationUnit.Priority.valueOf(p); }
        catch (IllegalArgumentException e) { return TranslationUnit.Priority.P2; }
    }

    private List<TranslationUnit> deduplicate(List<TranslationUnit> units) {
        Set<String> seen = new HashSet<>();
        List<TranslationUnit> unique = new ArrayList<>();
        for (TranslationUnit u : units) {
            String key = u.getClassName() + "|" + u.getPattern() + "|" + u.getSourceText();
            if (seen.add(key)) unique.add(u);
        }
        return unique;
    }
}
