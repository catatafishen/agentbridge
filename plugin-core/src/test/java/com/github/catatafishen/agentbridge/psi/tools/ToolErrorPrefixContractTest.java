package com.github.catatafishen.agentbridge.psi.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the {@code "Error: "} prefix contract of the String-returning tool API.
 *
 * <p>{@link com.github.catatafishen.agentbridge.services.ToolDefinition#execute(com.google.gson.JsonObject)}
 * has no success flag, so its result is classified by
 * {@link com.github.catatafishen.agentbridge.psi.ToolError#isError(String)} — which only looks for
 * an {@code "Error"} prefix. A failure phrased naturally ({@code "Failed to read terminal: …"})
 * therefore reaches the agent as a <em>successful</em> call, and the agent proceeds as if the
 * operation had worked. That is invisible to reviewers, so it is checked mechanically here.</p>
 */
class ToolErrorPrefixContractTest {

    private static final String TOOLS_RELATIVE =
        "src/main/java/com/github/catatafishen/agentbridge/psi/tools";

    /**
     * Openers that only ever introduce a failure. Deliberately narrow: a phrase earns a place here
     * only when no plausible success message could start with it, so the check has no false
     * positives to suppress.
     */
    private static final Pattern UNPREFIXED_FAILURE = Pattern.compile(
        "(?:return|complete\\()\\s*\"("
            + "Failed to|Refusing to|Unable to|Cannot |Could not |Timed out|Timeout"
            + "|Not found|File not found|No such |Invalid |Missing "
            + ")");

    @Test
    @DisplayName("err() adds the prefix that ToolError.isError looks for")
    void errAddsPrefix() {
        assertEquals("Error: Failed to read terminal: boom", Tool.err("Failed to read terminal: boom"));
    }

    @Test
    @DisplayName("err() leaves an existing Error prefix alone")
    void errIsIdempotent() {
        assertEquals("Error: already prefixed", Tool.err("Error: already prefixed"));
        assertEquals("Error (exit 1): boom", Tool.err("Error (exit 1): boom"));
        assertEquals("Error: Failed to X", Tool.err(Tool.err("Failed to X")));
    }

    @Test
    @DisplayName("no tool returns a failure message that would be reported as success")
    void noToolReturnsUnprefixedFailure() {
        List<String> violations = new ArrayList<>();
        Path toolsRoot = locateToolsSourceRoot();

        try (Stream<Path> sources = Files.walk(toolsRoot)) {
            sources.filter(p -> p.toString().endsWith(".java"))
                .sorted()
                .forEach(p -> collectViolations(toolsRoot, p, violations));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan " + toolsRoot, e);
        }

        assertTrue(violations.isEmpty(),
            "These tools return a failure message without an \"Error\" prefix, so ToolError.isError()"
                + " reports them to the agent as successful calls. Wrap them in Tool.err(...):\n"
                + String.join("\n", violations));
    }

    private static void collectViolations(Path root, Path file, List<String> violations) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = UNPREFIXED_FAILURE.matcher(lines.get(i));
            if (matcher.find()) {
                violations.add(root.relativize(file) + ":" + (i + 1) + "  " + lines.get(i).strip());
            }
        }
    }

    /**
     * Resolves the tool sources without assuming a working directory — Gradle runs tests from the
     * module directory while some IDE run configurations use the repository root.
     */
    private static Path locateToolsSourceRoot() {
        Path start = Path.of("").toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path direct = dir.resolve(TOOLS_RELATIVE);
            if (Files.isDirectory(direct)) return direct;
            Path nested = dir.resolve("plugin-core").resolve(TOOLS_RELATIVE);
            if (Files.isDirectory(nested)) return nested;
        }
        throw new IllegalStateException(
            "Could not locate " + TOOLS_RELATIVE + " from working directory " + start);
    }
}
