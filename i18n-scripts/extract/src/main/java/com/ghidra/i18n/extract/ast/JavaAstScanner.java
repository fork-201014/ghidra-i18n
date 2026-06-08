package com.ghidra.i18n.extract.ast;

import com.ghidra.i18n.common.module.ModuleScanner.ModuleInfo;
import com.ghidra.i18n.common.util.GhidraPathResolver;
import com.ghidra.i18n.common.util.StringClassifier;
import com.ghidra.i18n.extract.model.TranslationUnit;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * AST-based Java source scanner that extracts translatable UI strings
 * by matching exact API call patterns in the parsed Abstract Syntax Tree.
 *
 * <p>Uses {@code com.github.javaparser:javaparser-core:3.26.4}.</p>
 *
 * <p>Entry point: {@link #scanModule(ModuleInfo, GhidraPathResolver)}</p>
 */
public class JavaAstScanner {

    private final JavaParser parser;
    private final StringClassifier classifier;
    private int filesParsed;
    private int filesFailed;

    public JavaAstScanner() {
        this.parser = new JavaParser();
        this.classifier = new StringClassifier();
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Scans all Java files in a module and returns extracted translatable units.
     *
     * @param module     module metadata (from ModuleScanner)
     * @param pathResolver  path resolver for this Ghidra root
     * @return list of TranslationUnit candidates (not yet filtered)
     */
    public List<TranslationUnit> scanModule(ModuleInfo module, GhidraPathResolver pathResolver) {
        List<TranslationUnit> results = new ArrayList<>();
        if (module.javaFiles == 0) return results;

        Path javaDir = pathResolver.getSourceMainJava(module.name);
        if (!Files.isDirectory(javaDir)) return results;

        try (Stream<Path> files = Files.walk(javaDir)) {
            files.filter(Files::isRegularFile)
                .filter(f -> f.getFileName().toString().endsWith(".java"))
                .forEach(f -> {
                    try {
                        List<TranslationUnit> fromFile = scanFile(f, module, pathResolver);
                        results.addAll(fromFile);
                        filesParsed++;
                    } catch (Exception e) {
                        filesFailed++;
                        // Silently skip: this file will be handled by RegexScanner fallback
                    }
                });
        } catch (IOException e) {
            System.err.println("WARN: Failed to walk " + javaDir + ": " + e.getMessage());
        }

        // Deduplicate by (className, pattern, sourceText)
        return deduplicate(results);
    }

    /**
     * Scans a single Java file and returns extracted units.
     */
    public List<TranslationUnit> scanFile(Path file, ModuleInfo module, GhidraPathResolver pathResolver) throws IOException {
        List<TranslationUnit> results = new ArrayList<>();
        String source = Files.readString(file);

        ParseResult<CompilationUnit> result = parser.parse(source);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            throw new IOException("Parse failed: " + result.getProblems());
        }

        CompilationUnit cu = result.getResult().get();
        String className = extractClassName(cu);
        String fullClassName = extractFullClassName(cu, file, pathResolver);

        // Collect all string literal usages via AST visitor
        new StringLiteralVisitor(results, module.name, file, className, fullClassName, classifier).visit(cu, null);

        return results;
    }

    // -----------------------------------------------------------------------
    // Deduplication
    // -----------------------------------------------------------------------

    private List<TranslationUnit> deduplicate(List<TranslationUnit> units) {
        Set<String> seen = new HashSet<>();
        List<TranslationUnit> unique = new ArrayList<>();
        for (TranslationUnit u : units) {
            String key = u.getClassName() + "|" + u.getPattern() + "|" + u.getSourceText();
            if (seen.add(key)) {
                unique.add(u);
            }
        }
        return unique;
    }

    // -----------------------------------------------------------------------
    // Class name extraction
    // -----------------------------------------------------------------------

    private String extractClassName(CompilationUnit cu) {
        return cu.findAll(ClassOrInterfaceDeclaration.class).stream()
            .findFirst()
            .map(ClassOrInterfaceDeclaration::getNameAsString)
            .orElse("UnknownClass");
    }

    private String extractFullClassName(CompilationUnit cu, Path file, GhidraPathResolver pathResolver) {
        String pkg = cu.getPackageDeclaration()
            .map(pd -> pd.getNameAsString())
            .orElse("");
        String cls = extractClassName(cu);
        return pkg.isEmpty() ? cls : pkg + "." + cls;
    }

    // -----------------------------------------------------------------------
    // Statistics
    // -----------------------------------------------------------------------

    public int getFilesParsed() { return filesParsed; }
    public int getFilesFailed()  { return filesFailed; }
    public void resetStats()     { filesParsed = 0; filesFailed = 0; }

    // ========================================================================
    // AST Visitor - the core extraction logic
    // ========================================================================

    private static class StringLiteralVisitor extends VoidVisitorAdapter<Void> {

        private final List<TranslationUnit> results;
        private final String moduleName;
        private final Path sourceFile;
        private final String className;
        private final String fullClassName;
        private final StringClassifier classifier;

        StringLiteralVisitor(List<TranslationUnit> results, String moduleName,
                             Path sourceFile, String className, String fullClassName,
                             StringClassifier classifier) {
            this.results = results;
            this.moduleName = moduleName;
            this.sourceFile = sourceFile;
            this.className = className;
            this.fullClassName = fullClassName;
            this.classifier = classifier;
        }

        // ============================================================
        // Method call expressions: setTitle("..."), setToolTipText("..."), etc.
        // ============================================================

        @Override
        public void visit(MethodCallExpr call, Void arg) {
            String name = call.getNameAsString();
            String sourceText = extractFirstStringLiteralArg(call);

            if (sourceText == null) { super.visit(call, arg); return; }

            switch (name) {
                case "setTitle":
                    addUnit(sourceText, TranslationUnit.ExtractionPattern.SET_TITLE,
                        TranslationUnit.UiContext.DIALOG_TITLE, TranslationUnit.Priority.P0);
                    break;
                case "setToolTipText":
                    addUnit(sourceText, TranslationUnit.ExtractionPattern.SET_TOOL_TIP_TEXT,
                        TranslationUnit.UiContext.TOOLTIP, TranslationUnit.Priority.P0);
                    break;
                case "setText":
                    addUnit(sourceText, TranslationUnit.ExtractionPattern.SET_TEXT,
                        TranslationUnit.UiContext.LABEL, TranslationUnit.Priority.P0);
                    break;
                case "setLabel":
                    addUnit(sourceText, TranslationUnit.ExtractionPattern.SET_LABEL,
                        TranslationUnit.UiContext.LABEL, TranslationUnit.Priority.P0);
                    break;
                case "setDescription":
                    addUnit(sourceText, TranslationUnit.ExtractionPattern.SET_DESCRIPTION,
                        TranslationUnit.UiContext.ACTION_DESCRIPTION, TranslationUnit.Priority.P1);
                    break;
                case "setName":
                    addUnit(sourceText, TranslationUnit.ExtractionPattern.SET_NAME,
                        TranslationUnit.UiContext.ACTION_NAME, TranslationUnit.Priority.P1);
                    break;
                default:
                    break;
            }

            super.visit(call, arg);
        }

        // ============================================================
        // Object creation: new JButton("..."), new GLabel("..."), etc.
        // ============================================================

        @Override
        public void visit(ObjectCreationExpr creation, Void arg) {
            String typeName = creation.getTypeAsString();
            String sourceText = extractFirstStringLiteralArg(creation);

            if (sourceText == null) { super.visit(creation, arg); return; }

            // Normalize type name (strip generics, handle FQN)
            String shortName = typeName.replaceAll("<.*>", "");
            int lastDot = shortName.lastIndexOf('.');
            if (lastDot >= 0) shortName = shortName.substring(lastDot + 1);

            switch (shortName) {
                case "JButton":
                    addUnit(sourceText, TranslationUnit.ExtractionPattern.NEW_JBUTTON,
                        TranslationUnit.UiContext.BUTTON, TranslationUnit.Priority.P0);
                    break;
                case "JLabel":
                    addUnit(sourceText, TranslationUnit.ExtractionPattern.NEW_JLABEL,
                        TranslationUnit.UiContext.LABEL, TranslationUnit.Priority.P0);
                    break;
                case "GLabel":
                    addUnit(sourceText, TranslationUnit.ExtractionPattern.NEW_GLABEL,
                        TranslationUnit.UiContext.LABEL, TranslationUnit.Priority.P0);
                    break;
                case "GHtmlLabel":
                    addUnit(sourceText, TranslationUnit.ExtractionPattern.NEW_GHTML_LABEL,
                        TranslationUnit.UiContext.LABEL, TranslationUnit.Priority.P0);
                    break;
                case "GIconLabel":
                    addUnit(sourceText, TranslationUnit.ExtractionPattern.NEW_GICON_LABEL,
                        TranslationUnit.UiContext.LABEL, TranslationUnit.Priority.P0);
                    break;
                case "JMenuItem":
                case "JCheckBoxMenuItem":
                case "JRadioButtonMenuItem":
                    addUnit(sourceText, TranslationUnit.ExtractionPattern.NEW_JMENU_ITEM,
                        TranslationUnit.UiContext.MENU_ITEM, TranslationUnit.Priority.P0);
                    break;
                case "JMenu":
                    addUnit(sourceText, TranslationUnit.ExtractionPattern.NEW_JMENU,
                        TranslationUnit.UiContext.MENU_LABEL, TranslationUnit.Priority.P0);
                    break;
                case "JCheckBox":
                    addUnit(sourceText, TranslationUnit.ExtractionPattern.NEW_JCHECK_BOX,
                        TranslationUnit.UiContext.CHECKBOX, TranslationUnit.Priority.P0);
                    break;
                case "JRadioButton":
                    addUnit(sourceText, TranslationUnit.ExtractionPattern.NEW_JRADIO_BUTTON,
                        TranslationUnit.UiContext.RADIO, TranslationUnit.Priority.P0);
                    break;
                case "JToggleButton":
                    addUnit(sourceText, TranslationUnit.ExtractionPattern.NEW_JTOGGLE_BUTTON,
                        TranslationUnit.UiContext.BUTTON, TranslationUnit.Priority.P1);
                    break;
                case "TitledBorder":
                    addUnit(sourceText, TranslationUnit.ExtractionPattern.NEW_TITLED_BORDER,
                        TranslationUnit.UiContext.BORDER_TITLE, TranslationUnit.Priority.P0);
                    break;
                case "JFrame":
                    addUnit(sourceText, TranslationUnit.ExtractionPattern.NEW_JFRAME,
                        TranslationUnit.UiContext.DIALOG_TITLE, TranslationUnit.Priority.P1);
                    break;
                case "JDialog":
                    addUnit(sourceText, TranslationUnit.ExtractionPattern.NEW_JDIALOG,
                        TranslationUnit.UiContext.DIALOG_TITLE, TranslationUnit.Priority.P1);
                    break;
                case "DockingAction":
                case "DockingActionIf":
                    addUnit(sourceText, TranslationUnit.ExtractionPattern.DOCKING_ACTION_NAME,
                        TranslationUnit.UiContext.ACTION_NAME, TranslationUnit.Priority.P1);
                    break;
                default:
                    break;
            }

            super.visit(creation, arg);
        }

        // ============================================================
        // Annotation members: @PluginInfo(shortDescription="...")
        // ============================================================

        @Override
        public void visit(MemberValuePair pair, Void arg) {
            String memberName = pair.getNameAsString();
            Expression value = pair.getValue();

            if (value instanceof StringLiteralExpr sl) {
                String text = sl.asString();
                switch (memberName) {
                    case "shortDescription":
                        addUnit(text, TranslationUnit.ExtractionPattern.PLUGIN_SHORT_DESC,
                            TranslationUnit.UiContext.PLUGIN_SHORT_DESC, TranslationUnit.Priority.P1);
                        break;
                    case "description":
                        addUnit(text, TranslationUnit.ExtractionPattern.PLUGIN_DESCRIPTION,
                            TranslationUnit.UiContext.PLUGIN_DESC, TranslationUnit.Priority.P1);
                        break;
                    case "category":
                        addUnit(text, TranslationUnit.ExtractionPattern.PLUGIN_CATEGORY,
                            TranslationUnit.UiContext.PLUGIN_DESC, TranslationUnit.Priority.P2);
                        break;
                }
            }

            super.visit(pair, arg);
        }

        // ============================================================
        // Variable declarations: String title = "...";
        // (captured if used later in UI context, or heuristic-based)
        // ============================================================

        @Override
        public void visit(FieldDeclaration field, Void arg) {
            // Skip: fields are not directly UI-facing; they are resolved at usage site
            super.visit(field, arg);
        }

        // ============================================================
        // Helper methods
        // ============================================================

        private String extractFirstStringLiteralArg(Expression expr) {
            if (expr instanceof MethodCallExpr call) {
                return extractFirstStringLiteralArg(call);
            }
            if (expr instanceof ObjectCreationExpr creation) {
                return extractFirstStringLiteralArg(creation);
            }
            return null;
        }

        private String extractFirstStringLiteralArg(MethodCallExpr call) {
            NodeList<Expression> args = call.getArguments();
            if (args.isEmpty()) return null;
            Expression first = args.get(0);
            if (first instanceof StringLiteralExpr sl) {
                return sl.asString();
            }
            // Try string concatenation: "a" + "b" + ...
            if (first instanceof BinaryExpr bin && bin.getOperator() == BinaryExpr.Operator.PLUS) {
                String resolved = resolveStringConcat(bin);
                if (resolved != null) return resolved;
            }
            return null;
        }

        private String extractFirstStringLiteralArg(ObjectCreationExpr creation) {
            NodeList<Expression> args = creation.getArguments();
            if (args.isEmpty()) return null;
            Expression first = args.get(0);
            if (first instanceof StringLiteralExpr sl) {
                return sl.asString();
            }
            if (first instanceof BinaryExpr bin && bin.getOperator() == BinaryExpr.Operator.PLUS) {
                String resolved = resolveStringConcat(bin);
                if (resolved != null) return resolved;
            }
            return null;
        }

        /**
         * Recursively resolve compile-time string concatenation in AST.
         * Only handles pure StringLiteralExpr + StringLiteralExpr chains.
         */
        private String resolveStringConcat(BinaryExpr bin) {
            if (bin.getOperator() != BinaryExpr.Operator.PLUS) return null;
            StringBuilder sb = new StringBuilder();
            if (!appendStringPart(sb, bin.getLeft())) return null;
            if (!appendStringPart(sb, bin.getRight())) return null;
            return sb.toString();
        }

        private boolean appendStringPart(StringBuilder sb, Expression expr) {
            if (expr instanceof StringLiteralExpr sl) {
                sb.append(sl.asString());
                return true;
            }
            if (expr instanceof BinaryExpr bin && bin.getOperator() == BinaryExpr.Operator.PLUS) {
                return appendStringPart(sb, bin.getLeft())
                    && appendStringPart(sb, bin.getRight());
            }
            return false; // variable reference, method call, etc. → can't resolve at compile time
        }

        // ============================================================
        // Unit creation
        // ============================================================

        private void addUnit(String sourceText, TranslationUnit.ExtractionPattern pattern,
                             TranslationUnit.UiContext context, TranslationUnit.Priority priority) {
            if (sourceText == null || sourceText.isBlank()) return;

            // Quick syntactic filter: skip obviously non-UI strings
            if (classifier.isPunctuationOnly(sourceText)) return;
            if (classifier.isUrl(sourceText)) return;
            if (classifier.isSecret(sourceText)) return;

            String id = moduleName + "." + className + "." + pattern.name().toLowerCase();

            TranslationUnit unit = new TranslationUnit()
                .setId(id)
                .setModuleName(moduleName)
                .setSourceFilePath(sourceFile.toString())
                .setClassName(className)
                .setFullClassName(fullClassName)
                .setPattern(pattern)
                .setSourceText(sourceText)
                .setPriority(priority)
                .setContext(context)
                .setHtml(classifier.containsHtml(sourceText))
                .setHasFormatSpecifier(classifier.hasFormatSpecifiers(sourceText))
                .setContainsMnemonic(classifier.hasMnemonic(sourceText))
                .setAiReviewStatus(TranslationUnit.AiReviewStatus.PENDING)
                .setTranslationStatus(TranslationUnit.TranslationStatus.UNTRANSLATED);

            results.add(unit);
        }
    }
}
