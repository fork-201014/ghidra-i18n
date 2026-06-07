package com.ghidra.i18n.common.util;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lightweight classifier for Java string literals extracted from Ghidra's source code.
 *
 * <p>Used by the extraction filter engine to decide which strings qualify as
 * user-facing UI strings vs. internal code constants, log messages, etc.</p>
 *
 * <p>This classifier performs purely syntactic checks. Semantic classification
 * (AI review) happens in a downstream pipeline step.</p>
 */
public class StringClassifier {

    // -----------------------------------------------------------------------
    // Recognised extraction patterns (from Java source lines)
    // -----------------------------------------------------------------------

    private static final Set<String> UI_METHODS = Set.of(
        ".setTitle(", ".setToolTipText(",
        ".setText(", ".setLabel(", ".setName(",
        ".setDescription(",
        "showMessageDialog(", "showConfirmDialog(",
        "showErrorDialog(", "showInputDialog(",
        "showOptionDialog("
    );

    private static final Set<String> UI_CONSTRUCTORS = Set.of(
        "new JButton(", "new JLabel(", "new JMenu(", "new JMenuItem(",
        "new JCheckBox(", "new JRadioButton(", "new JToggleButton(",
        "new JFrame(", "new JDialog(",
        "new GLabel(", "new GHtmlLabel(", "new GIconLabel(",
        "new TitledBorder(",
        "new DockingAction("
    );

    private static final Set<String> LOG_METHODS = Set.of(
        "Msg.error(", "Msg.warn(", "Msg.info(", "Msg.debug(", "Msg.trace(",
        "Msg.showError(", "Msg.showWarn(", "Msg.showInfo(",
        "LOG.error(", "LOG.warn(", "LOG.info(", "LOG.debug(",
        "logger.error(", "logger.warn(", "logger.info(", "logger.debug(",
        "System.out.print", "System.err.print"
    );

    private static final Set<String> EXCEPTION_CLASSES = Set.of(
        "IllegalArgumentException(", "IllegalStateException(",
        "NullPointerException(", "AssertException(",
        "UnsupportedOperationException(", "IOException(",
        "RuntimeException(", "Assert."
    );

    // -----------------------------------------------------------------------
    // Precompiled patterns for content classification
    // -----------------------------------------------------------------------

    /** Anything that is just punctuation, whitespace, or brackets. */
    private static final Pattern PUNCTUATION_ONLY = Pattern.compile("^[\\[\\]{}()/:,;.\\-_+=*&^%$#@!~`<>?\\\\|\\s'\"]+$");

    /** ALL_CAPS_AND_UNDERSCORES (code constants, enum names). */
    private static final Pattern ALL_CAPS = Pattern.compile("^[A-Z_][A-Z0-9_]*$");

    /** Numeric string. */
    private static final Pattern NUMERIC = Pattern.compile("^\\d+$");

    /** URL-like. */
    private static final Pattern URL_PATTERN = Pattern.compile("^(https?|ftp|file):", Pattern.CASE_INSENSITIVE);

    /** Java package/class path (at least 3 segments). */
    private static final Pattern PACKAGE_PATH = Pattern.compile("^[a-z]+(\\.[a-z]+){2,}$");

    /** HTML tags hint. */
    private static final Pattern HTML_TAG = Pattern.compile("</?[a-zA-Z]+[^>]*>");

    /** Format specifiers: %s %d %f {0} %n etc. */
    private static final Pattern FORMAT_SPEC = Pattern.compile("(%[sdifn%]|\\{[0-9]+\\})");

    /** Mnemonic: & character used for keyboard shortcuts. */
    private static final Pattern MNEMONIC = Pattern.compile("&[A-Za-z]");

    /** API key / token patterns. */
    private static final Pattern SECRET_PATTERN = Pattern.compile("^(sk-|ghp_|ghs_|github_pat_)");

    /** Debug/todo comment markers. */
    private static final Set<String> DEBUG_MARKERS = Set.of(
        "TODO", "FIXME", "HACK", "XXX", "TEMP", "DEBUG", "TEST"
    );

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Returns a pattern classification for the given source line content.
     */
    public String classifyPattern(String sourceLine) {
        if (sourceLine == null || sourceLine.isBlank()) return "unknown";

        String s = sourceLine.strip();

        // Check constructors (most distinctive)
        for (String ctor : UI_CONSTRUCTORS) {
            if (s.contains(ctor)) return ctor.replace("new ", "").replace("(", "");
        }

        // Check method calls
        for (String m : UI_METHODS) {
            if (s.contains(m)) return m.replace(".", "").replace("(", "");
        }

        // Check annotation patterns
        if (s.contains("shortDescription") || s.contains("@PluginInfo")) return "@PluginInfo";
        if (s.contains("text=\"") && (s.contains("tocdef") || s.contains("tocref"))) return "tocText";

        return "other";
    }

    /**
     * Returns true if the string is likely a user-facing UI element
     * (present in a dialog title, button text, label, menu, etc.).
     */
    public boolean isUserFacing(String text) {
        if (text == null || text.isBlank()) return false;
        if (isPunctuationOnly(text)) return false;
        if (isCodeConstant(text)) return false;
        if (isNumericOnly(text)) return false;
        if (isUrl(text)) return false;
        if (isSecret(text)) return false;
        if (isDebugMarker(text)) return false;

        // Single character strings are rarely user-facing
        if (text.trim().length() == 1) return false;

        // Must contain at least one letter
        return text.matches(".*[a-zA-Z].*");
    }

    /**
     * Returns true if the string appears to be a log or error message
     * (typically passed to Msg.error/warn/info/debug or logger methods).
     */
    public boolean isLogOrError(String text) {
        // If the source line context is available, check the log methods.
        // Without context, heuristic: strings containing "error", "failed", etc. +
        // technical-looking patterns are more likely logs.
        if (text == null) return false;
        String lower = text.toLowerCase();
        return lower.contains("exception") || lower.contains("stack trace") ||
            lower.contains("cause:") || lower.contains("at ");
    }

    /**
     * Returns true if the string looks like a code constant rather than
     * a user-facing label.
     */
    public boolean isCodeConstant(String text) {
        if (text == null) return false;
        String t = text.trim();
        return ALL_CAPS.matcher(t).matches() || PACKAGE_PATH.matcher(t).matches();
    }

    /**
     * Returns true if the string contains HTML tags.
     */
    public boolean containsHtml(String text) {
        return text != null && HTML_TAG.matcher(text).find();
    }

    /**
     * Returns true if the string contains printf-style or MessageFormat format specifiers.
     */
    public boolean hasFormatSpecifiers(String text) {
        return text != null && FORMAT_SPEC.matcher(text).find();
    }

    /**
     * Returns true if the string contains Swing keyboard mnemonics (&amp;).
     */
    public boolean hasMnemonic(String text) {
        return text != null && MNEMONIC.matcher(text).find();
    }

    /**
     * Returns true if the string is composed entirely of punctuation/whitespace.
     */
    public boolean isPunctuationOnly(String text) {
        return text != null && PUNCTUATION_ONLY.matcher(text.trim()).matches();
    }

    /**
     * Returns true if the text looks like a URL.
     */
    public boolean isUrl(String text) {
        return text != null && URL_PATTERN.matcher(text.trim()).find();
    }

    /**
     * Returns true if the text looks like an API key or secret token.
     */
    public boolean isSecret(String text) {
        return text != null && SECRET_PATTERN.matcher(text).find();
    }

    /**
     * Returns true if the text is a debug/todo marker.
     */
    public boolean isDebugMarker(String text) {
        return text != null && DEBUG_MARKERS.contains(text.strip().toUpperCase());
    }

    /**
     * Returns true if the text is purely numeric.
     */
    public boolean isNumericOnly(String text) {
        return text != null && NUMERIC.matcher(text.trim()).matches();
    }

    /**
     * Returns true if the text has meaningful alphabetic content.
     */
    public boolean hasMeaningfulContent(String text) {
        return text != null && text.trim().length() >= 2 && text.matches(".*[a-zA-Z].*");
    }

    /**
     * Returns true if the given Java source line contains any log/exception method call.
     */
    public boolean containsLogOrExceptionMethod(String sourceLine) {
        if (sourceLine == null) return false;
        for (String m : LOG_METHODS) {
            if (sourceLine.contains(m)) return true;
        }
        for (String ex : EXCEPTION_CLASSES) {
            if (sourceLine.contains(ex)) return true;
        }
        return false;
    }

    /**
     * Returns true if the given Java source line contains a UI-pattern method or constructor.
     */
    public boolean containsUiPattern(String sourceLine) {
        if (sourceLine == null) return false;
        for (String m : UI_METHODS) {
            if (sourceLine.contains(m)) return true;
        }
        for (String c : UI_CONSTRUCTORS) {
            if (sourceLine.contains(c)) return true;
        }
        return false;
    }
}
