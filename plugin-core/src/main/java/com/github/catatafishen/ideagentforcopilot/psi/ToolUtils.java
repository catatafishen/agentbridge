package com.github.catatafishen.ideagentforcopilot.psi;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;

/**
 * Shared utility methods and constants extracted from PsiBridgeService
 * for use by individual tool handler classes.
 */
public final class ToolUtils {

    // Error message constants
    public static final String ERROR_PREFIX = "Error: ";
    public static final String ERROR_FILE_NOT_FOUND = "File not found: ";
    public static final String ERROR_CANNOT_PARSE = "Cannot parse file: ";
    public static final String ERROR_PATH_REQUIRED = "Error: 'path' parameter is required";
    public static final String JAVA_EXTENSION = ".java";
    static final String BUILD_DIR = "build";

    // Element type constants
    static final String ELEMENT_TYPE_CLASS = "class";
    static final String ELEMENT_TYPE_INTERFACE = "interface";
    static final String ELEMENT_TYPE_ENUM = "enum";
    public static final String ELEMENT_TYPE_FIELD = "field";
    static final String ELEMENT_TYPE_FUNCTION = "function";
    static final String ELEMENT_TYPE_METHOD = "method";

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

        // Generic patterns
        if (cls.contains("Interface") && !cls.contains("Reference")) return ELEMENT_TYPE_INTERFACE;
        if (cls.contains("Enum") && cls.contains("Class")) return ELEMENT_TYPE_CLASS;

        return null;
    }

    static String classifyJavaClass(PsiElement element) {
        try {
            if ((boolean) element.getClass().getMethod("isInterface").invoke(element))
                return ELEMENT_TYPE_INTERFACE;
            if ((boolean) element.getClass().getMethod("isEnum").invoke(element)) return ELEMENT_TYPE_ENUM;
        } catch (NoSuchMethodException | java.lang.reflect.InvocationTargetException
                 | IllegalAccessException ignored) {
            // Reflection unavailable for this PsiClass variant
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
        try {
            var isInterface = element.getClass().getMethod("isInterface");
            if ((boolean) isInterface.invoke(element)) return ELEMENT_TYPE_INTERFACE;
            var isEnum = element.getClass().getMethod("isEnum");
            if ((boolean) isEnum.invoke(element)) return ELEMENT_TYPE_ENUM;
        } catch (NoSuchMethodException | java.lang.reflect.InvocationTargetException
                 | IllegalAccessException ignored) {
            // Reflection unavailable for this Kotlin class variant
        }
        return ELEMENT_TYPE_CLASS;
    }

    public static VirtualFile resolveVirtualFile(Project project, String path) {
        String normalized = path.replace('\\', '/');
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(normalized);
        if (vf != null) return vf;

        String basePath = project.getBasePath();
        if (basePath != null) {
            vf = LocalFileSystem.getInstance().findFileByPath(basePath + "/" + normalized);
        }
        return vf;
    }

    public static String relativize(String basePath, String filePath) {
        String base = basePath.replace('\\', '/');
        String file = filePath.replace('\\', '/');
        return file.startsWith(base + "/") ? file.substring(base.length() + 1) : file;
    }

    public static String getLineText(Document doc, int lineIndex) {
        if (lineIndex < 0 || lineIndex >= doc.getLineCount()) return "";
        int start = doc.getLineStartOffset(lineIndex);
        int end = doc.getLineEndOffset(lineIndex);
        return doc.getText().substring(start, end).trim();
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
        if (pattern.isEmpty()) return false;
        String normalizedPath = path.replace('\\', '/');
        boolean isPathPattern = pattern.contains("/") || pattern.contains("**");
        String target = isPathPattern ? normalizedPath : lastSegment(normalizedPath);
        return !globToRegex(pattern).matcher(target).matches();
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
    static String normalizeForMatch(String s) {
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
    static int findOriginalLength(String original, int startIdx, int normalizedLen) {
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
    static String truncateOutput(String output, int maxChars, int offset) {
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
     * flow (InfrastructureTools) to ensure consistent blocking regardless of call path.
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
        if (cmd.matches("find \\S+.*-name.*") || cmd.matches("find \\S+.*-type.*") ||
            cmd.startsWith("find .") || cmd.startsWith("find /")) {
            return "find";
        }

        // Block direct Gradle compile tasks — should use build_project (IntelliJ incremental compiler)
        if (isGradleCompileCommand(cmd)) return "compile";

        // Block test commands — should use run_tests
        // Explicit test tasks
        if (cmd.matches(".*(gradlew|gradle|mvn|npm|yarn|pnpm|pytest|jest|mocha|go) test.*") ||
            cmd.matches(".*\\./gradlew.*test.*") ||
            cmd.matches(".*python.*-m.*pytest.*") ||
            cmd.matches(".*cargo test.*") ||
            cmd.matches(".*dotnet test.*") ||
            // Bare pytest with args (pytest alone is caught by the first pattern via "pytest test")
            cmd.matches("pytest\\s+.*") ||
            // npx/bunx/pnpx wrappers for test runners
            cmd.matches(".*(npx|bunx|pnpx)\\s+(jest|vitest|mocha|ava|tap|jasmine).*") ||
            // Gradle build/check tasks (implicitly run tests)
            cmd.matches(".*(gradlew|gradle)\\s+(build|check)(\\s.*|$)") ||
            cmd.matches(".*\\./gradlew\\s+(build|check)(\\s.*|$)") ||
            // Maven lifecycle phases that include tests
            cmd.matches(".*mvn\\s+(verify|package|install|deploy)(\\s.*|$)") ||
            // npm/yarn/pnpm run test
            cmd.matches(".*(npm|yarn|pnpm)\\s+run\\s+test.*")) {
            return "test";
        }

        return null;
    }

    private static boolean isGradleCompileCommand(String cmd) {
        boolean isGradleCmd = cmd.contains("gradlew") || cmd.matches(".*\\bgradle\\s.*");
        boolean hasCompileTask = cmd.matches(".*compile(test)?(kotlin|java).*");
        return isGradleCmd && hasCompileTask;
    }

    /**
     * Map abuse type to a human-readable error message for MCP tool responses.
     */
    public static String getCommandAbuseMessage(String abuseType) {
        return switch (abuseType) {
            case "git" -> "Error: git commands are not allowed via run_command (causes IntelliJ buffer desync). "
                + "Use the dedicated git tools instead: git_status, git_diff, git_log, git_commit, "
                + "git_stage, git_unstage, git_branch, git_stash, git_show, git_blame, git_push, git_remote, "
                + "git_fetch, git_pull, git_merge, git_rebase, git_cherry_pick, git_tag, git_reset.";
            case "cat" -> "Error: cat/head/tail/less/more are not allowed via run_command (reads stale disk files). "
                + "Use intellij_read_file to read live editor buffers instead.";
            case "sed" -> "Error: sed is not allowed via run_command (bypasses IntelliJ editor buffers). "
                + "Use edit_text with old_str/new_str for file editing instead.";
            case "grep" -> "Error: grep/rg commands are not allowed via run_command (searches stale disk files). "
                + "Use search_text or search_symbols to search live editor buffers instead.";
            case "find" -> "Error: find commands are not allowed via run_command. "
                + "Use list_project_files to find files instead.";
            case "compile" -> "Error: Gradle compile tasks are not allowed via run_command. "
                + "Use build_project to compile via IntelliJ's incremental compiler instead.";
            case "test" -> "Error: test commands are not allowed via run_command (including build/check/verify " +
                "which implicitly run tests). Use run_tests to run tests with proper IntelliJ integration instead.";
            default -> "Error: this command is not allowed via run_command. Use dedicated IntelliJ tools instead.";
        };
    }
}
