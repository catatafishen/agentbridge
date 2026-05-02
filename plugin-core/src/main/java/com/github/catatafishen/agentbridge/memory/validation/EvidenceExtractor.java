package com.github.catatafishen.agentbridge.memory.validation;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts code evidence references from text — file paths, FQNs, and
 * file:line references. Stateless utility; all methods are static.
 *
 * <p>Evidence strings follow these formats:</p>
 * <ul>
 *   <li>FQN: {@code com.example.UserService}</li>
 *   <li>FQN with method: {@code com.example.UserService.authenticate}</li>
 *   <li>File path: {@code src/main/java/UserService.java}</li>
 *   <li>File:line: {@code UserService.java:42}</li>
 *   <li>File:range: {@code UserService.java:42-80}</li>
 * </ul>
 */
public final class EvidenceExtractor {

    private EvidenceExtractor() {
    }

    private static final Pattern FQN_PATTERN = Pattern.compile(
        "\\b([a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*){1,10}\\.[A-Z][A-Za-z0-9]+(?:\\.[a-zA-Z][A-Za-z0-9]*)?)\\b"
    );

    private static final Pattern FILE_LINE_PATTERN = Pattern.compile(
        "`?([A-Za-z][A-Za-z0-9_-]*\\.[a-zA-Z]{1,10}:\\d+(?:-\\d+)?)`?"
    );

    private static final Pattern FILE_PATH_PATTERN = Pattern.compile(
        "\\b((?:[A-Za-z0-9._-]+/){1,15}[A-Za-z][A-Za-z0-9_-]*\\.[a-zA-Z]{1,10})\\b"
    );

    private static final Set<String> CODE_EXTENSIONS = Set.of(
        "java", "kt", "kts", "groovy", "scala",
        "py", "rb", "js", "ts", "tsx", "jsx",
        "go", "rs", "c", "cpp", "h", "hpp",
        "xml", "json", "yaml", "yml", "toml",
        "gradle", "properties", "md", "sql"
    );

    private static final int MAX_EVIDENCE_REFS = 20;

    /**
     * Extract all evidence references from the given text.
     *
     * @param text conversation text, tool output, or markdown content
     * @return deduplicated list of evidence reference strings, max {@value MAX_EVIDENCE_REFS}
     */
    public static @NotNull List<String> extract(@NotNull String text) {
        Set<String> refs = new LinkedHashSet<>();

        extractFqns(text, refs);
        extractFileLineRefs(text, refs);
        extractFilePaths(text, refs);

        return new ArrayList<>(refs).subList(0, Math.min(refs.size(), MAX_EVIDENCE_REFS));
    }

    /**
     * Extract evidence references and return as a JSON array string.
     * Returns empty string if no evidence found.
     *
     * @param text conversation text, tool output, or markdown content
     * @return JSON array string (e.g. {@code ["com.example.Foo","Bar.java:42"]}) or empty string
     */
    public static @NotNull String extractAsJson(@NotNull String text) {
        List<String> refs = extract(text);
        if (refs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < refs.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escapeJsonString(refs.get(i))).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    private static @NotNull String escapeJsonString(@NotNull String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void extractFqns(@NotNull String text, @NotNull Set<String> refs) {
        Matcher m = FQN_PATTERN.matcher(text);
        while (m.find()) {
            String fqn = m.group(1);
            if (isLikelyFqn(fqn)) {
                refs.add(fqn);
            }
        }
    }

    private static void extractFileLineRefs(@NotNull String text, @NotNull Set<String> refs) {
        Matcher m = FILE_LINE_PATTERN.matcher(text);
        while (m.find()) {
            String ref = m.group(1);
            if (hasCodeExtension(ref)) {
                refs.add(ref);
            }
        }
    }

    private static void extractFilePaths(@NotNull String text, @NotNull Set<String> refs) {
        Matcher m = FILE_PATH_PATTERN.matcher(text);
        while (m.find()) {
            String path = m.group(1);
            if (hasCodeExtension(path)) {
                refs.add(path);
            }
        }
    }

    /**
     * Reject FQN-like strings that are actually URLs, version numbers, or config keys.
     */
    private static boolean isLikelyFqn(@NotNull String candidate) {
        if (candidate.contains("://")) return false;
        if (candidate.startsWith("www.")) return false;
        if (looksLikeVersionNumber(candidate)) return false;

        int dotCount = 0;
        for (int i = 0; i < candidate.length(); i++) {
            if (candidate.charAt(i) == '.') dotCount++;
        }
        return dotCount >= 2;
    }

    /**
     * Detects version-number-like strings (e.g. "1.0.3", "v2.5.1-beta") without regex,
     * avoiding ReDoS risk from patterns like {@code .*\d+\.\d+\.\d+.*}.
     */
    private static boolean looksLikeVersionNumber(@NotNull String s) {
        int dotDigitRuns = 0;
        boolean prevWasDot = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.') {
                prevWasDot = true;
            } else if (Character.isDigit(c) && prevWasDot) {
                dotDigitRuns++;
                if (dotDigitRuns >= 2) return true;
                prevWasDot = false;
            } else {
                prevWasDot = false;
            }
        }
        return false;
    }

    private static boolean hasCodeExtension(@NotNull String ref) {
        String name = ref.contains(":") ? ref.substring(0, ref.indexOf(':')) : ref;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        return CODE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase());
    }
}
