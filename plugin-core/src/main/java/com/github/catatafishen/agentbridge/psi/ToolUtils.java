package com.github.catatafishen.agentbridge.psi;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shared utility methods and constants extracted from PsiBridgeService
 * for use by individual tool handler classes.
 */
public final class ToolUtils {

    // Error message constants — kept for backward compatibility.
    // New code should use ToolError.of(McpErrorCode, message) directly.
    public static final String ERROR_PREFIX = "Error: ";
    public static final String ERROR_FILE_NOT_FOUND = "File not found: ";
    public static final String ERROR_CANNOT_PARSE = "Cannot parse file: ";
    public static final String ERROR_PATH_REQUIRED = ToolError.of(McpErrorCode.MISSING_PARAM,
        "'path' parameter is required");
    public static final String JAVA_EXTENSION = ".java";
    public static final String BUILD_DIR = "build";
    public static final String JAR_URL_PREFIX = "jar://";
    public static final String JAR_SEPARATOR = ".jar!/";

    // Element type constants
    public static final String ELEMENT_TYPE_CLASS = "class";
    public static final String ELEMENT_TYPE_INTERFACE = "interface";
    public static final String ELEMENT_TYPE_ENUM = "enum";
    public static final String ELEMENT_TYPE_FIELD = "field";
    public static final String ELEMENT_TYPE_FUNCTION = "function";
    public static final String ELEMENT_TYPE_METHOD = "method";

    // PSI class name substrings used for generic multi-language classification
    private static final String PSI_PATTERN_CLASS = "Class";
    private static final String PSI_PATTERN_INTERFACE = "Interface";
    private static final String PSI_PATTERN_FUNCTION = "Function";
    private static final String PSI_PATTERN_METHOD = "Method";
    private static final String PSI_PATTERN_FIELD = "Field";

    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, java.util.Optional<java.lang.reflect.Method>> IS_INTERFACE_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, java.util.Optional<java.lang.reflect.Method>> IS_ENUM_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();

    // Precompiled patterns for abuse detection — using find() avoids leading/trailing .*
    // which Sonar flags for ReDoS (S5852). All patterns are anchored or use word boundaries.
    // Patterns that previously used .* between two literals are replaced with indexOf() chains
    // in helper methods (see isFindCommand, isGradlewTestCommand, isPythonPytestCommand).
    private static final java.util.regex.Pattern GRADLE_WORD_PATTERN =
        java.util.regex.Pattern.compile("\\bgradle\\s");
    private static final java.util.regex.Pattern COMPILE_TASK_PATTERN =
        java.util.regex.Pattern.compile("compile(test)?(kotlin|java)", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern TEST_RUNNER_PATTERN =
        java.util.regex.Pattern.compile("(gradlew|gradle|mvn|npm|yarn|pnpm|pytest|jest|mocha|go) test");
    private static final java.util.regex.Pattern NPX_RUNNER_PATTERN =
        java.util.regex.Pattern.compile("(npx|bunx|pnpx)\\s+(jest|vitest|mocha|ava|tap|jasmine)");
    private static final java.util.regex.Pattern NPM_RUN_TEST_PATTERN =
        java.util.regex.Pattern.compile("(npm|yarn|pnpm)\\s+run\\s+test");
    private static final java.util.regex.Pattern GRADLE_BUILD_PATTERN =
        java.util.regex.Pattern.compile("(gradlew|gradle)\\s+(build|check)(\\s|$)");
    private static final java.util.regex.Pattern GRADLEW_BUILD_PATTERN =
        java.util.regex.Pattern.compile("\\./gradlew\\s+(build|check)(\\s|$)");
    private static final java.util.regex.Pattern MVN_LIFECYCLE_PATTERN =
        java.util.regex.Pattern.compile("mvn\\s+(verify|package|install|deploy)(\\s|$)");

    private ToolUtils() {
    }

    public static String classifyElement(PsiElement element) {
        String cls = element.getClass().getSimpleName();

        // Java PSI
        if (cls.contains("PsiClass") && !cls.contains("Initializer")) {
            return classifyJavaClass(element);
        }
        if (cls.contains("PsiMethod")) return ELEMENT_TYPE_METHOD;
        if (cls.contains("PsiField")) return ELEMENT_TYPE_FIELD;
        if (cls.contains("PsiEnumConstant")) return ELEMENT_TYPE_FIELD;

        // Kotlin PSI
        String kotlinType = classifyKotlinElement(cls, element);
        if (kotlinType != null) return kotlinType;

        // Other languages (Python, JS/TS, Go, C/C++, PHP, Ruby, Rust, C#)
        return classifyGenericElement(cls);
    }

    static String classifyJavaClass(PsiElement element) {
        try {
            java.lang.reflect.Method isInterface = IS_INTERFACE_CACHE.computeIfAbsent(
                element.getClass(), c -> {
                    try {
                        return java.util.Optional.of(c.getMethod("isInterface"));
                    } catch (NoSuchMethodException e) {
                        return java.util.Optional.empty();
                    }
                }).orElse(null);
            if (isInterface != null && (boolean) isInterface.invoke(element)) return ELEMENT_TYPE_INTERFACE;
            java.lang.reflect.Method isEnum = IS_ENUM_CACHE.computeIfAbsent(
                element.getClass(), c -> {
                    try {
                        return java.util.Optional.of(c.getMethod("isEnum"));
                    } catch (NoSuchMethodException e) {
                        return java.util.Optional.empty();
                    }
                }).orElse(null);
            if (isEnum != null && (boolean) isEnum.invoke(element)) return ELEMENT_TYPE_ENUM;
        } catch (java.lang.reflect.InvocationTargetException | IllegalAccessException ignored) {
            // Reflection invocation failed for this PsiClass variant
        }
        return ELEMENT_TYPE_CLASS;
    }

    static String classifyKotlinElement(String cls, PsiElement element) {
        return switch (cls) {
            case "KtClass", "KtObjectDeclaration" -> classifyKotlinClass(element);
            case "KtNamedFunction" -> ELEMENT_TYPE_FUNCTION;
            case "KtProperty" -> ELEMENT_TYPE_FIELD;
            case "KtTypeAlias" -> ELEMENT_TYPE_CLASS;
            default -> null;
        };
    }

    static String classifyKotlinClass(PsiElement element) {
        // Kotlin PSI classes (KtClass, KtObjectDeclaration) support the same isInterface()/isEnum()
        // methods as Java's PsiClass — delegate to the shared reflection-based classifier
        return classifyJavaClass(element);
    }

    /**
     * Classifies PSI elements from non-Java/Kotlin languages by matching PSI class names.
     * Covers Python, JavaScript/TypeScript, Go, C/C++, PHP, Ruby, Rust, and C#.
     * PSI class simple names follow language-specific patterns (e.g. PyClass, JSFunction,
     * GoTypeSpec) — we match on known prefixes for efficiency and fall back to generic
     * patterns for unrecognized languages.
     */
    static String classifyGenericElement(String cls) {
        if (cls.startsWith("Py")) return classifyPythonElement(cls);
        if (cls.startsWith("JS") || cls.startsWith("TypeScript") || cls.startsWith("ES6"))
            return classifyJsElement(cls);
        if (cls.startsWith("Go")) return classifyGoElement(cls);
        if (cls.startsWith("OC")) return classifyCppElement(cls);
        if (cls.startsWith("Php") || cls.startsWith("php")) return classifyPhpElement(cls);
        if (cls.startsWith("RClass") || cls.startsWith("RModule") || cls.startsWith("RMethod"))
            return classifyRubyElement(cls);
        if (cls.startsWith("Rs")) return classifyRustElement(cls);
        if (cls.startsWith("CSharp")) return classifyCSharpElement(cls);

        // Generic fallback for unrecognized languages
        if (cls.contains(PSI_PATTERN_INTERFACE) && !cls.contains("Reference")) return ELEMENT_TYPE_INTERFACE;
        if (cls.contains("Enum") && cls.contains(PSI_PATTERN_CLASS)) return ELEMENT_TYPE_CLASS;

        return null;
    }

    static String classifyPythonElement(String cls) {
        if (cls.contains(PSI_PATTERN_CLASS)) return ELEMENT_TYPE_CLASS;
        if (cls.contains(PSI_PATTERN_FUNCTION)) return ELEMENT_TYPE_FUNCTION;
        if ("PyTargetExpressionImpl".equals(cls)) return ELEMENT_TYPE_FIELD;
        return null;
    }

    static String classifyJsElement(String cls) {
        if (cls.contains(PSI_PATTERN_CLASS)) return ELEMENT_TYPE_CLASS;
        if (cls.contains(PSI_PATTERN_INTERFACE)) return ELEMENT_TYPE_INTERFACE;
        if (cls.contains("Enum") && !cls.contains("Literal")) return ELEMENT_TYPE_ENUM;
        if (cls.contains(PSI_PATTERN_FUNCTION)) return ELEMENT_TYPE_FUNCTION;
        if (cls.contains("Variable") || cls.contains("Property") || cls.contains(PSI_PATTERN_FIELD))
            return ELEMENT_TYPE_FIELD;
        return null;
    }

    static String classifyGoElement(String cls) {
        if (cls.contains("TypeSpec")) return ELEMENT_TYPE_CLASS;
        if (cls.contains(PSI_PATTERN_FUNCTION) || cls.contains(PSI_PATTERN_METHOD)) return ELEMENT_TYPE_FUNCTION;
        if (cls.contains("VarDef") || cls.contains("ConstDef") || cls.contains("FieldDef"))
            return ELEMENT_TYPE_FIELD;
        return null;
    }

    static String classifyCppElement(String cls) {
        if (cls.contains("Structlike")) return ELEMENT_TYPE_CLASS;
        if (cls.contains("FunctionDef")) return ELEMENT_TYPE_FUNCTION;
        if (cls.contains("Declarator") || cls.contains("FieldDecl")) return ELEMENT_TYPE_FIELD;
        return null;
    }

    static String classifyPhpElement(String cls) {
        if (cls.contains(PSI_PATTERN_CLASS)) return ELEMENT_TYPE_CLASS;
        if (cls.contains(PSI_PATTERN_METHOD)) return ELEMENT_TYPE_METHOD;
        if (cls.contains(PSI_PATTERN_FUNCTION)) return ELEMENT_TYPE_FUNCTION;
        if (cls.contains(PSI_PATTERN_FIELD)) return ELEMENT_TYPE_FIELD;
        return null;
    }

    static String classifyRubyElement(String cls) {
        if (cls.startsWith("RClass")) return ELEMENT_TYPE_CLASS;
        if (cls.startsWith("RModule")) return ELEMENT_TYPE_CLASS;
        if (cls.startsWith("RMethod")) return ELEMENT_TYPE_METHOD;
        return null;
    }

    static String classifyRustElement(String cls) {
        if (cls.contains("StructItem") || cls.contains("ImplItem")) return ELEMENT_TYPE_CLASS;
        if (cls.contains("EnumItem")) return ELEMENT_TYPE_ENUM;
        if (cls.contains("TraitItem")) return ELEMENT_TYPE_INTERFACE;
        if (cls.contains(PSI_PATTERN_FUNCTION)) return ELEMENT_TYPE_FUNCTION;
        if (cls.contains("FieldDecl") || cls.contains("ConstItem")) return ELEMENT_TYPE_FIELD;
        return null;
    }

    static String classifyCSharpElement(String cls) {
        if (cls.contains(PSI_PATTERN_CLASS) || cls.contains("Struct")) return ELEMENT_TYPE_CLASS;
        if (cls.contains(PSI_PATTERN_INTERFACE)) return ELEMENT_TYPE_INTERFACE;
        if (cls.contains("Enum") && cls.contains("Decl")) return ELEMENT_TYPE_ENUM;
        if (cls.contains(PSI_PATTERN_METHOD)) return ELEMENT_TYPE_METHOD;
        if (cls.contains("Property") || cls.contains(PSI_PATTERN_FIELD)) return ELEMENT_TYPE_FIELD;
        return null;
    }

    /**
     * Resolves a JAR path (with or without {@code jar://} prefix) to a VirtualFile.
     * Returns {@code null} if the path is not a JAR path.
     */
    private static @Nullable VirtualFile resolveJarPath(String normalized) {
        if (normalized.startsWith(JAR_URL_PREFIX)) {
            String jarPath = normalized.substring(JAR_URL_PREFIX.length());
            return com.intellij.openapi.vfs.JarFileSystem.getInstance().findFileByPath(jarPath);
        }
        if (normalized.contains(JAR_SEPARATOR)) {
            return com.intellij.openapi.vfs.JarFileSystem.getInstance().findFileByPath(normalized);
        }
        return null;
    }

    public static VirtualFile resolveVirtualFile(Project project, String path) {
        String normalized = path.replace('\\', '/');

        VirtualFile jarFile = resolveJarPath(normalized);
        if (jarFile != null) return jarFile;

        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(normalized);
        if (vf != null) return vf;

        String basePath = project.getBasePath();
        if (basePath != null) {
            vf = LocalFileSystem.getInstance().findFileByPath(basePath + "/" + normalized);
        }
        return vf;
    }

    /**
     * Like {@link #resolveVirtualFile(Project, String)}, but falls back to a synchronous VFS refresh
     * when {@code findFileByPath} returns null. Use this when the VFS cache may be stale.
     * <p>
     * Must be called from a background thread outside any ReadAction.
     */
    public static VirtualFile refreshAndFindVirtualFile(Project project, String path) {
        String normalized = path.replace('\\', '/');

        VirtualFile jarFile = resolveJarPath(normalized);
        if (jarFile != null) return jarFile;

        String basePath = project.getBasePath();
        if (basePath != null) {
            VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath + "/" + normalized);
            if (vf != null) return vf;
        }
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(normalized);
    }

    public static String relativize(@Nullable String basePath, @NotNull String filePath) {
        String file = filePath.replace('\\', '/');
        // JAR-internal paths: produce a jar:// URL so agents can pass it back to file tools.
        // Must check before the base-path prefix strip to avoid producing broken relative JAR paths.
        if (file.contains(JAR_SEPARATOR)) return JAR_URL_PREFIX + file;
        if (basePath == null) return filePath;
        String base = basePath.replace('\\', '/');
        if (file.startsWith(base + "/")) return file.substring(base.length() + 1);
        return file;
    }

    /**
     * Appends {@code " (relative/path/to/file:lineNumber)"} to {@code sb} for the given PSI element.
     * For JAR-internal paths, produces a {@code jar://} URL so agents can pass it back to file tools.
     * Skips elements with null containing files or base paths.
     *
     * @param sb       string being built
     * @param element  PSI element whose source location to append
     * @param basePath project base path for relativizing the file path, or {@code null} to skip
     */
    public static void appendFileLocation(@NotNull StringBuilder sb, @NotNull PsiElement element,
                                          @Nullable String basePath) {
        com.intellij.psi.PsiFile file = element.getContainingFile();
        if (file == null || file.getVirtualFile() == null || basePath == null) return;
        String path = file.getVirtualFile().getPath();
        sb.append(" (").append(relativize(basePath, path));
        Document doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            .getDocument(file.getVirtualFile());
        if (doc != null) {
            int lineNum = doc.getLineNumber(element.getTextOffset()) + 1;
            sb.append(":").append(lineNum);
        }
        sb.append(")");
    }

    /**
     * Resolves the {@link com.intellij.psi.PsiFile} and line offset range for the given file path + line number.
     * Returns {@code null} if the file cannot be found, parsed, or the line is out of bounds.
     * <p>
     * Used to locate PSI elements at a specific source location in a language-agnostic way,
     * without requiring Java-specific APIs.
     *
     * @param project  current project
     * @param filePath absolute or project-relative path to the file
     * @param line     1-based line number
     * @return a {@link LineContext} with the PSI file and offset range, or {@code null}
     */
    @Nullable
    public static LineContext resolveLineContext(@NotNull Project project,
                                                 @NotNull String filePath,
                                                 int line) {
        VirtualFile vf = resolveVirtualFile(project, filePath);
        if (vf == null) return null;
        com.intellij.psi.PsiFile psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return null;
        Document document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
        if (document == null || line < 1 || line > document.getLineCount()) return null;
        return new LineContext(psiFile, document.getLineStartOffset(line - 1), document.getLineEndOffset(line - 1));
    }

    /**
     * Walks the PSI tree looking for a {@link com.intellij.psi.PsiNameIdentifierOwner} whose
     * {@link com.intellij.psi.PsiNameIdentifierOwner#getName() name} equals {@code name} and whose
     * text range overlaps the line bounds described by {@code ctx}.
     * <p>
     * Uses the element's full text range (not just the name identifier offset) so that
     * methods with annotations or multi-line signatures are found regardless of which line
     * the caller specifies — the annotation line, the signature line, or any line in the body.
     * <p>
     * Shared between {@link CallHierarchySupport} and {@code TypeHierarchySupport} to avoid
     * duplicating the PSI visitor pattern.
     *
     * @param ctx  line context from {@link #resolveLineContext}
     * @param name symbol name to match
     * @return the first matching element, or {@code null}
     */
    @Nullable
    public static com.intellij.psi.PsiNameIdentifierOwner findNamedElement(
        @NotNull LineContext ctx,
        @NotNull String name) {
        com.intellij.psi.PsiNameIdentifierOwner[] found = {null};
        ctx.psiFile().accept(new com.intellij.psi.PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull com.intellij.psi.PsiElement element) {
                if (element instanceof com.intellij.psi.PsiNameIdentifierOwner owner
                    && name.equals(owner.getName())) {
                    com.intellij.openapi.util.TextRange range = owner.getTextRange();
                    if (range.getStartOffset() <= ctx.lineEnd() && range.getEndOffset() >= ctx.lineStart()) {
                        found[0] = owner;
                        stopWalking();
                        return;
                    }
                }
                super.visitElement(element);
            }
        });
        return found[0];
    }

    /**
     * Holds a resolved PSI file and the character offsets bounding a specific source line.
     *
     * @param psiFile   the PSI file
     * @param lineStart offset of the first character on the line (inclusive)
     * @param lineEnd   offset of the last character on the line (inclusive)
     */
    public record LineContext(@NotNull com.intellij.psi.PsiFile psiFile, int lineStart, int lineEnd) {
    }

    public static String getLineText(Document doc, int lineIndex) {
        if (lineIndex < 0 || lineIndex >= doc.getLineCount()) return "";
        int start = doc.getLineStartOffset(lineIndex);
        int end = doc.getLineEndOffset(lineIndex);
        return doc.getText(new com.intellij.openapi.util.TextRange(start, end)).trim();
    }

    /**
     * Returns true if the given path does NOT match the glob pattern.
     * <p>
     * Simple patterns (no {@code /} and no {@code **}) are matched against the
     * <em>filename</em> only for backward compatibility (e.g. {@code *.java}, {@code *Test}).
     * Path patterns (containing {@code /} or {@code **}) are matched against the full
     * relative path using standard glob semantics:
     * <ul>
     *   <li>{@code **} — matches zero or more path segments (crosses {@code /})</li>
     *   <li>{@code *}  — matches any characters within a single path segment (no {@code /})</li>
     *   <li>{@code ?}  — matches exactly one non-separator character</li>
     * </ul>
     * Examples: {@code src/**}{@code /*.java} matches {@code src/main/Foo.java};
     * {@code *.java} matches {@code Foo.java} (filename only).
     */
    public static boolean doesNotMatchGlob(String path, String pattern) {
        return doesNotMatchGlob(path, pattern, globToRegex(pattern));
    }

    public static boolean doesNotMatchGlob(String path, String pattern, java.util.regex.Pattern compiled) {
        if (pattern.isEmpty()) return false;
        java.util.regex.Pattern effectivePattern = compiled != null ? compiled : globToRegex(pattern);
        String normalizedPath = path.replace('\\', '/');
        boolean isPathPattern = pattern.contains("/") || pattern.contains("**");
        String target = isPathPattern ? normalizedPath : lastSegment(normalizedPath);
        return !effectivePattern.matcher(target).matches();
    }

    public static java.util.regex.Pattern compileGlob(String pattern) {
        return globToRegex(pattern);
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static java.util.regex.Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        int len = glob.length();
        int i = 0;
        while (i < len) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < len && glob.charAt(i + 1) == '*') {
                sb.append(".*");
                i += 2; // consume **
                if (i < len && glob.charAt(i) == '/') {
                    i++; // consume the slash after **
                }
            } else if (c == '*') {
                sb.append("[^/]*");
                i++;
            } else if (c == '?') {
                sb.append("[^/]");
                i++;
            } else if (".+^${}[]()|\\".indexOf(c) >= 0) {
                sb.append('\\').append(c);
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        sb.append("$");
        return java.util.regex.Pattern.compile(sb.toString());
    }

    public static String fileType(String name) {
        String l = name.toLowerCase();
        if (l.endsWith(JAVA_EXTENSION)) return "Java";
        if (l.endsWith(".gradle") || l.endsWith(".gradle.kts")) return "Gradle";
        if (l.endsWith(".kt") || l.endsWith(".kts")) return "Kotlin";
        if (l.endsWith(".py")) return "Python";
        if (l.endsWith(".js") || l.endsWith(".jsx")) return "JavaScript";
        if (l.endsWith(".ts") || l.endsWith(".tsx")) return "TypeScript";
        if (l.endsWith(".go")) return "Go";
        if (l.endsWith(".xml")) return "XML";
        if (l.endsWith(".json")) return "JSON";
        if (l.endsWith(".yaml") || l.endsWith(".yml")) return "YAML";
        return "Other";
    }

    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    public static String formatFileTimestamp(long epochMs) {
        if (epochMs == 0) return "unknown";
        return new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(epochMs));
    }

    /**
     * Parses a date string like "2026-01-15" into epoch milliseconds (start of day UTC).
     * Returns -1 if blank or unparseable.
     */
    public static long parseDateParam(String date) {
        if (date == null || date.isBlank()) return -1;
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return sdf.parse(date).getTime();
        } catch (java.text.ParseException e) {
            return -1;
        }
    }

    /**
     * Normalize text for fuzzy matching: replace common Unicode variants with ASCII equivalents.
     * This handles em-dashes, smart quotes, non-breaking spaces, emoji, etc. that LLMs often can't reproduce exactly.
     * Uses codepoint iteration to correctly handle surrogate pairs (e.g. 4-byte emoji).
     */
    public static String normalizeForMatch(String s) {
        // First normalize line endings.
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        // Replace ALL non-ASCII codepoints with '?' for fuzzy matching.
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (cp > 127) {
                sb.append('?');
            } else {
                sb.append((char) cp);
            }
        });
        return sb.toString();
    }

    /**
     * Finds the length in the original text that corresponds to a given length in the normalized text,
     * starting from the given position. This accounts for multibyte/surrogate-pair chars that normalize
     * to a single '?' character.
     */
    public static int findOriginalLength(String original, int startIdx, int normalizedLen) {
        int origPos = startIdx;
        int normCount = 0;
        while (normCount < normalizedLen && origPos < original.length()) {
            char c = original.charAt(origPos);
            if (c == '\r' && origPos + 1 < original.length() && original.charAt(origPos + 1) == '\n') {
                // CRLF counts as 1 normalized char
                origPos += 2;
            } else if (Character.isHighSurrogate(c) && origPos + 1 < original.length()
                && Character.isLowSurrogate(original.charAt(origPos + 1))) {
                // Surrogate pair (e.g. emoji) counts as 1 normalized char
                origPos += 2;
            } else {
                origPos++;
            }
            normCount++;
        }
        return origPos - startIdx;
    }

    public static String truncateOutput(String output) {
        return truncateOutput(output, 8000, 0);
    }

    /**
     * Truncates output with pagination support.
     *
     * @param output   full output text
     * @param maxChars maximum characters to return per page
     * @param offset   character offset to start from (0 = beginning)
     * @return the page of output, with pagination hint if more data exists
     */
    public static String truncateOutput(String output, int maxChars, int offset) {
        if (output == null || output.isEmpty()) return output;
        if (offset >= output.length()) return "(offset beyond end of output, total length: " + output.length() + ")";
        String remaining = output.substring(offset);
        if (remaining.length() <= maxChars) {
            return offset > 0
                ? remaining + "\n\n(showing chars " + offset + "-" + output.length() + " of " + output.length() + ")"
                : remaining;
        }
        String page = remaining.substring(0, maxChars);
        int shown = offset + maxChars;
        return page + "\n\n...(truncated, showing chars " + offset + "-" + shown + " of " + output.length()
            + ". Use offset=" + shown + " to see more)";
    }

    /**
     * Detect if a shell command is an abuse pattern that should use a dedicated IntelliJ tool.
     * Shared between the ACP permission flow (AcpClient) and the MCP tool execution
     * flow (RunCommandTool) to ensure consistent blocking regardless of call path.
     *
     * @param command the shell command string (will be lowercased and trimmed)
     * @return the abuse type ("git", "sed", "grep", "find", "test") or null if allowed
     */
    public static String detectCommandAbuseType(String command) {
        String cmd = command.toLowerCase().trim();

        // Block git — causes IntelliJ editor buffer desync
        // Also catches env-prefixed (VAR=val git ...), sudo/env/command wrappers
        if (cmd.startsWith("git ") || cmd.equals("git") ||
            cmd.contains("&& git ") || cmd.contains("; git ") || cmd.contains("| git ") ||
            cmd.matches("(\\w+=\\S*+\\s++)++git(\\s.*|$)") ||
            cmd.matches("(sudo|env|command|nohup)\\s+git(\\s.*|$)") ||
            cmd.matches("(\\w+=\\S*+\\s++)++(?:sudo|env|command)\\s+git(\\s.*|$)") ||
            // env with VAR=val arguments before git (e.g. env GIT_DIR=/tmp git status)
            cmd.matches("env\\s+(\\S+=\\S*+\\s++)*+git(\\s.*|$)")) {
            return "git";
        }

        // Block cat/head/tail/less/more — should use intellij_read_file for live buffer access
        if (cmd.matches("(cat|head|tail|less|more) .*") ||
            cmd.contains("| cat ") || cmd.contains("&& cat ") || cmd.contains("; cat ")) {
            return "cat";
        }

        // Block sed — should use edit_text for proper undo/redo and live buffer access
        if (cmd.startsWith("sed ") || cmd.contains("| sed") ||
            cmd.contains("&& sed") || cmd.contains("; sed")) {
            return "sed";
        }

        // Block grep/rg — should use search_text or search_symbols for live buffer search
        if (cmd.startsWith("grep ") || cmd.startsWith("rg ") ||
            cmd.contains("| grep") || cmd.contains("&& grep") || cmd.contains("; grep") ||
            cmd.contains("| rg ") || cmd.contains("&& rg ") || cmd.contains("; rg ")) {
            return "grep";
        }

        // Block find — should use list_project_files
        if (isFindCommand(cmd) ||
            cmd.startsWith("find .") || cmd.startsWith("find /")) {
            return "find";
        }

        // Block direct Gradle compile tasks — should use build_project (IntelliJ incremental compiler)
        if (isGradleCompileCommand(cmd)) return "compile";

        // Block build/check tasks that implicitly run tests — should use build_project or run_tests
        if (isBuildCommand(cmd)) return "test";

        // Block test commands — should use run_tests
        if (isTestCommand(cmd)) return "test";

        return null;
    }

    public static boolean grepTargetsOnlyOutsideSourceRoots(@Nullable Project project, @NotNull String command) {
        if (project == null) return false;
        java.util.List<String> paths = extractGrepPaths(command);
        if (paths.isEmpty()) return false;

        String basePath = project.getBasePath();
        java.nio.file.Path base = basePath != null ? java.nio.file.Path.of(basePath) : null;
        com.intellij.openapi.roots.ProjectFileIndex index =
            com.intellij.openapi.roots.ProjectFileIndex.getInstance(project);

        for (String pathStr : paths) {
            if (!isPathOutsideSourceRoots(pathStr, base, index)) return false;
        }
        return true;
    }

    private static boolean isPathOutsideSourceRoots(
        @NotNull String pathStr,
        @Nullable java.nio.file.Path base,
        @NotNull com.intellij.openapi.roots.ProjectFileIndex index
    ) {
        java.nio.file.Path resolved = resolveCommandPath(pathStr, base);
        if (resolved == null) return false;

        VirtualFile vf = LocalFileSystem.getInstance().findFileByNioFile(resolved);
        if (vf == null) {
            // File doesn't exist (yet?). Allow only when clearly outside the project root.
            return base == null || !resolved.startsWith(base);
        }
        return com.intellij.openapi.application.ReadAction.compute(() ->
            !index.isInSource(vf) || index.isInGeneratedSources(vf));
    }

    @Nullable
    private static java.nio.file.Path resolveCommandPath(@NotNull String pathStr, @Nullable java.nio.file.Path base) {
        try {
            String expanded = pathStr.startsWith("~")
                ? System.getProperty("user.home") + pathStr.substring(1)
                : pathStr;
            java.nio.file.Path p = java.nio.file.Path.of(expanded);
            java.nio.file.Path candidate = p.isAbsolute() || base == null ? p : base.resolve(p);
            return candidate.normalize();
        } catch (Exception e) {
            return null;
        }
    }

    static java.util.List<String> extractGrepPaths(@NotNull String command) {
        java.util.List<String> tokens = tokenizeShellCommand(command);
        int idx = findGrepCommandIndex(tokens);
        if (idx < 0) return java.util.List.of();
        return collectPathArgs(tokens, idx + 1);
    }

    private static int findGrepCommandIndex(@NotNull java.util.List<String> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equalsIgnoreCase("grep") || t.equalsIgnoreCase("rg")) return i;
        }
        return -1;
    }

    private static final java.util.Set<String> GREP_TWO_ARG_FLAGS = java.util.Set.of(
        "-e", "-f", "--regexp", "--file",
        "--include", "--exclude", "--exclude-dir", "--include-dir",
        "-A", "-B", "-C", "--after-context", "--before-context", "--context",
        "-m", "--max-count", "--max-depth", "-t", "-T", "--type", "--type-not",
        "-g", "--glob", "--iglob"
    );

    private static final java.util.Set<String> GREP_PATTERN_FLAGS = java.util.Set.of(
        "-e", "-f", "--regexp", "--file"
    );

    private static java.util.List<String> collectPathArgs(@NotNull java.util.List<String> tokens, int from) {
        boolean patternConsumed = false;
        boolean skipNext = false;
        java.util.List<String> paths = new java.util.ArrayList<>();
        for (int i = from; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (skipNext) {
                skipNext = false;
                continue;
            }
            if (t.startsWith("-") && !t.equals("-")) {
                if (GREP_TWO_ARG_FLAGS.contains(t)) {
                    if (GREP_PATTERN_FLAGS.contains(t)) patternConsumed = true;
                    skipNext = true;
                }
            } else if (!patternConsumed) {
                patternConsumed = true;
            } else if (containsGlob(t)) {
                return java.util.List.of();
            } else {
                paths.add(t);
            }
        }
        return paths;
    }

    private static boolean containsGlob(@NotNull String s) {
        return s.indexOf('*') >= 0 || s.indexOf('?') >= 0 || s.indexOf('[') >= 0;
    }

    /**
     * Tokenize a shell command string respecting single and double quotes.
     * Backslash escapes inside quotes are NOT handled — adequate for the simple paths we care about.
     */
    @NotNull
    static java.util.List<String> tokenizeShellCommand(@NotNull String command) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else cur.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (!cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    private static boolean isGradleCompileCommand(String cmd) {
        boolean isGradleCmd = cmd.contains("gradlew") || GRADLE_WORD_PATTERN.matcher(cmd).find();
        boolean hasCompileTask = COMPILE_TASK_PATTERN.matcher(cmd).find();
        return isGradleCmd && hasCompileTask;
    }

    private static boolean isTestCommand(String cmd) {
        return TEST_RUNNER_PATTERN.matcher(cmd).find() ||
            isGradlewTestCommand(cmd) ||
            isPythonPytestCommand(cmd) ||
            cmd.contains("cargo test") ||
            cmd.contains("dotnet test") ||
            cmd.matches("pytest\\s+.*") ||
            NPX_RUNNER_PATTERN.matcher(cmd).find() ||
            NPM_RUN_TEST_PATTERN.matcher(cmd).find() ||
            // Bare test runner invocations (jest, vitest, mocha, etc.)
            cmd.matches("(jest|vitest|mocha|ava|tap|jasmine)(\\s.*|$)");
    }

    private static boolean isBuildCommand(String cmd) {
        return GRADLE_BUILD_PATTERN.matcher(cmd).find() ||
            GRADLEW_BUILD_PATTERN.matcher(cmd).find() ||
            MVN_LIFECYCLE_PATTERN.matcher(cmd).find();
    }

    /**
     * Checks if a command is a {@code find} command with {@code -name} or {@code -type} flags.
     * Uses indexOf chains instead of regex to avoid Sonar S5852 (ReDoS) hotspots.
     */
    private static boolean isFindCommand(String cmd) {
        if (!cmd.startsWith("find ")) return false;
        return cmd.contains("-name") || cmd.contains("-type");
    }

    /**
     * Checks if a command is a {@code ./gradlew} invocation that runs tests.
     * Uses indexOf instead of regex to avoid Sonar S5852.
     */
    private static boolean isGradlewTestCommand(String cmd) {
        int idx = cmd.indexOf("./gradlew");
        return idx >= 0 && cmd.indexOf("test", idx + 9) >= 0;
    }

    /**
     * Checks if a command is a {@code python -m pytest} invocation.
     * Uses indexOf chain instead of regex to avoid Sonar S5852.
     */
    private static boolean isPythonPytestCommand(String cmd) {
        int pyIdx = cmd.indexOf("python");
        if (pyIdx < 0) return false;
        int mIdx = cmd.indexOf("-m", pyIdx + 6);
        if (mIdx < 0) return false;
        return cmd.indexOf("pytest", mIdx + 2) >= 0;
    }

    /**
     * Detects hard-block abuse patterns for {@code run_in_terminal}.
     * Only blocks commands that cause IDE state desync (git, sed).
     * For commands with better MCP alternatives (grep, cat, find), use
     * {@link #getTerminalReprimand(String)} to append a warning instead.
     */
    public static @Nullable String detectTerminalAbuseType(@NotNull String command) {
        String cmd = command.toLowerCase().trim();
        if (cmd.startsWith("git ") || cmd.equals("git")) return "git";
        if (cmd.startsWith("sed ")) return "sed";
        return null;
    }

    /**
     * Returns a warning message for terminal commands that have better MCP tool alternatives.
     * Unlike {@link #detectTerminalAbuseType(String)}, this does NOT block execution — the
     * command runs normally but the warning is prepended to the output to nudge the agent
     * toward the purpose-built tool.
     *
     * @return a warning string to prepend to output, or null if no reprimand needed
     */
    public static @Nullable String getTerminalReprimand(@NotNull String command) {
        String cmd = command.toLowerCase().trim();

        // grep/rg/ag — prefer search_text or search_symbols
        if (cmd.startsWith("grep ") || cmd.startsWith("rg ") || cmd.startsWith("ag ") ||
            cmd.contains("| grep ") || cmd.contains("| rg ") || cmd.contains("| ag ")) {
            return "⚠️ Prefer search_text or search_symbols over shell grep — "
                + "they search live editor buffers and support semantic lookup.";
        }

        // cat/head/tail/less/more — prefer read_file
        if (cmd.matches("(cat|head|tail|less|more) .*")) {
            return "⚠️ Prefer read_file over shell cat/head/tail — "
                + "it reads live editor buffers, not stale disk content.";
        }

        // find — prefer list_project_files / list_directory_tree
        if (isFindCommand(cmd) ||
            cmd.startsWith("find .") || cmd.startsWith("find /")) {
            return "⚠️ Prefer list_project_files or list_directory_tree over shell find — "
                + "they respect project structure and exclusions.";
        }

        // ls/dir/tree — prefer list_project_files / list_directory_tree
        if (cmd.startsWith("ls ") || cmd.equals("ls") ||
            cmd.startsWith("dir ") || cmd.equals("dir") ||
            cmd.startsWith("tree ") || cmd.equals("tree")) {
            return "⚠️ Prefer list_project_files or list_directory_tree over shell ls/tree — "
                + "they respect project structure and exclusions.";
        }

        // test commands — prefer run_tests
        if (isTestCommand(cmd)) {
            return "⚠️ Prefer run_tests over shell test commands — "
                + "it provides structured pass/fail results with IntelliJ test runner integration.";
        }

        // compile/build commands — prefer build_project
        if (isGradleCompileCommand(cmd) || isBuildCommand(cmd)) {
            return "⚠️ Prefer build_project over shell compile/build commands — "
                + "it uses IntelliJ's incremental compiler with structured error reporting.";
        }

        return null;
    }

    /**
     * Map abuse type to a human-readable error message for MCP tool responses.
     *
     * @param abuseType the detected abuse category
     * @param toolName  the tool that detected the abuse (e.g. "run_command", "run_in_terminal")
     */
    public static String getCommandAbuseMessage(String abuseType, String toolName) {
        return switch (abuseType) {
            case "git" -> "Error: git commands are not allowed via " + toolName + " (causes IntelliJ buffer desync). "
                + "Use the dedicated git tools instead: git_status, git_diff, git_log, git_commit, "
                + "git_stage, git_unstage, git_branch, git_stash, git_show, git_blame, git_push, git_remote, "
                + "git_fetch, git_pull, git_merge, git_rebase, git_cherry_pick, git_tag, git_reset.";
            case "cat" ->
                "Error: cat/head/tail/less/more are not allowed via " + toolName + " (reads stale disk files). "
                    + "Use intellij_read_file to read live editor buffers instead.";
            case "sed" -> "Error: sed is not allowed via " + toolName + " (bypasses IntelliJ editor buffers). "
                + "Use edit_text with old_str/new_str for file editing instead.";
            case "grep" -> "Error: grep/rg on project source files is not allowed via " + toolName + " (searches "
                + "stale disk files). Use search_text or search_symbols to search live editor buffers. "
                + "Note: grep IS allowed when targeting paths outside source roots (e.g. log files, downloaded "
                + "CI artifacts, build/ output) — pass an explicit file/dir argument to use it.";
            case "find" -> "Error: find commands are not allowed via " + toolName + ". "
                + "Use list_project_files to find files instead.";
            case "compile" -> "Error: Gradle compile tasks are not allowed via " + toolName + ". "
                + "Use build_project to compile via IntelliJ's incremental compiler instead.";
            case "test" -> "Error: test commands are not allowed via " + toolName + " (including build/check/verify " +
                "which implicitly run tests). Use run_tests to run tests with proper IntelliJ integration instead.";
            default -> "Error: this command is not allowed via " + toolName + ". Use dedicated IntelliJ tools instead.";
        };
    }

    /**
     * Map abuse type to a human-readable error message for MCP tool responses.
     * Uses "run_command" as the default tool name for backward compatibility.
     */
    public static String getCommandAbuseMessage(String abuseType) {
        return getCommandAbuseMessage(abuseType, "run_command");
    }
}
