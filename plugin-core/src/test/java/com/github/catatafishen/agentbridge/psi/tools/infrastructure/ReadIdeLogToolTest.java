package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ReadIdeLogTool} — pure log parsing, filtering, and formatting.
 * <p>
 * The tool reads from a {@code idea.log} file resolved via the {@code idea.log.path}
 * system property, so no IntelliJ platform setup is required.
 */
@DisplayName("ReadIdeLogTool")
class ReadIdeLogToolTest {

    @TempDir
    Path tempDir;

    private Path logFile;
    private ReadIdeLogTool tool;

    /**
     * Template: "YYYY-MM-DD HH:mm:ss,SSS [THREAD]   LEVEL - #logger - message"
     */
    private static final String TODAY = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

    @BeforeEach
    void setUp() {
        logFile = tempDir.resolve("idea.log");
        // Pass tempDir as logDirOverride to bypass the system-property fallback chain,
        // which would otherwise find the real sandbox idea.log via PathManager.
        tool = new ReadIdeLogTool(null, tempDir);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String logLine(String time, String level, String logger, String message) {
        return TODAY + " " + time + ",000 [     1]   " + level + " - #" + logger + " - " + message;
    }

    private JsonObject args(String... pairs) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            obj.addProperty(pairs[i], pairs[i + 1]);
        }
        return obj;
    }

    private String execute(JsonObject args) throws IOException {
        return tool.execute(args);
    }

    // ── Basic output ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("returns 'no log entries' message when log file is empty")
    void emptyLogFileReturnsNoEntries() throws IOException {
        Files.writeString(logFile, "");
        String result = execute(new JsonObject());
        assertEquals("No matching log entries found.", result);
    }

    @Test
    @DisplayName("returns a prefixed 'could not locate' error when log file does not exist")
    void missingLogFileReturnsError() throws IOException {
        // Don't create the file
        String result = execute(new JsonObject());
        assertEquals("Error: Could not locate idea.log", result);
    }

    @Test
    @DisplayName("parses valid log lines into compact format HH:mm:ss.mmm  LEVEL  ShortClass: message")
    void parsesValidLogLines() throws IOException {
        Files.writeString(logFile,
            logLine("10:00:00", "INFO", "com.example.Foo", "Hello world") + "\n"
        );
        String result = execute(new JsonObject());
        assertTrue(result.contains("10:00:00.000"), "Should contain time: " + result);
        assertTrue(result.contains("INFO"), "Should contain level: " + result);
        assertTrue(result.contains("Foo"), "Should contain short class name: " + result);
        assertTrue(result.contains("Hello world"), "Should contain message: " + result);
    }

    @Test
    @DisplayName("shortens logger to simple class name (strips package prefix)")
    void shortensLoggerToSimpleClassName() throws IOException {
        Files.writeString(logFile,
            logLine("10:00:00", "INFO", "com.example.deeply.nested.MyService", "msg") + "\n"
        );
        String result = execute(new JsonObject());
        assertTrue(result.contains("MyService"), "Should use simple class name: " + result);
        assertFalse(result.contains("com.example"), "Should not contain full package: " + result);
    }

    // ── Level filtering ───────────────────────────────────────────────────────

    @Test
    @DisplayName("filter by level=INFO returns only INFO entries")
    void filterByLevelInfo() throws IOException {
        Files.writeString(logFile,
            logLine("10:00:00", "INFO", "com.Foo", "info message") + "\n" +
                logLine("10:00:01", "WARN", "com.Bar", "warn message") + "\n" +
                logLine("10:00:02", "ERROR", "com.Baz", "error message") + "\n"
        );
        String result = execute(args("level", "INFO"));
        assertTrue(result.contains("info message"), "Should include INFO: " + result);
        assertFalse(result.contains("warn message"), "Should exclude WARN: " + result);
        assertFalse(result.contains("error message"), "Should exclude ERROR: " + result);
    }

    @Test
    @DisplayName("filter by level=WARN,ERROR returns both WARN and ERROR")
    void filterByMultipleLevels() throws IOException {
        Files.writeString(logFile,
            logLine("10:00:00", "INFO", "com.Foo", "info message") + "\n" +
                logLine("10:00:01", "WARN", "com.Bar", "warn message") + "\n" +
                logLine("10:00:02", "ERROR", "com.Baz", "error message") + "\n"
        );
        String result = execute(args("level", "WARN,ERROR"));
        assertFalse(result.contains("info message"), "Should exclude INFO: " + result);
        assertTrue(result.contains("warn message"), "Should include WARN: " + result);
        assertTrue(result.contains("error message"), "Should include ERROR: " + result);
    }

    @Test
    @DisplayName("no level filter returns all entries")
    void noLevelFilterReturnsAll() throws IOException {
        Files.writeString(logFile,
            logLine("10:00:00", "INFO", "com.Foo", "info message") + "\n" +
                logLine("10:00:01", "WARN", "com.Bar", "warn message") + "\n"
        );
        String result = execute(new JsonObject());
        assertTrue(result.contains("info message"), "Should include INFO: " + result);
        assertTrue(result.contains("warn message"), "Should include WARN: " + result);
    }

    // ── Regex filter ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("filter regex matches against compact output text")
    void filterRegexMatchesMessage() throws IOException {
        Files.writeString(logFile,
            logLine("10:00:00", "INFO", "com.Foo", "connected to server") + "\n" +
                logLine("10:00:01", "INFO", "com.Bar", "disconnected from server") + "\n" +
                logLine("10:00:02", "INFO", "com.Baz", "unrelated message") + "\n"
        );
        String result = execute(args("filter", "connected"));
        assertTrue(result.contains("connected to server"), "Should match 'connected': " + result);
        assertTrue(result.contains("disconnected from server"), "Should match 'disconnected' (contains 'connected'): " + result);
        assertFalse(result.contains("unrelated"), "Should not include unrelated: " + result);
    }

    @Test
    @DisplayName("filter regex is case-insensitive")
    void filterIsCaseInsensitive() throws IOException {
        Files.writeString(logFile,
            logLine("10:00:00", "INFO", "com.Foo", "Authentication FAILED") + "\n"
        );
        String result = execute(args("filter", "authentication failed"));
        assertTrue(result.contains("Authentication FAILED"), "Case-insensitive match: " + result);
    }

    @Test
    @DisplayName("filter with pipe (|) matches either pattern")
    void filterPipeAlternation() throws IOException {
        Files.writeString(logFile,
            logLine("10:00:00", "INFO", "com.Foo", "server started") + "\n" +
                logLine("10:00:01", "INFO", "com.Bar", "client connected") + "\n" +
                logLine("10:00:02", "INFO", "com.Baz", "routine operation") + "\n"
        );
        String result = execute(args("filter", "server|client"));
        assertTrue(result.contains("server started"), "Should match 'server': " + result);
        assertTrue(result.contains("client connected"), "Should match 'client': " + result);
        assertFalse(result.contains("routine"), "Should exclude non-matching: " + result);
    }

    @Test
    @DisplayName("invalid regex returns a prefixed error message")
    void invalidRegexReturnsError() throws IOException {
        Files.writeString(logFile, logLine("10:00:00", "INFO", "Foo", "msg") + "\n");
        String result = execute(args("filter", "[unclosed bracket"));
        assertEquals("Error: Invalid filter regex - check syntax", result);
    }

    // ── Time filtering ────────────────────────────────────────────────────────

    @Test
    @DisplayName("since filter excludes entries before the cutoff")
    void sinceFilterExcludesOldEntries() throws IOException {
        Files.writeString(logFile,
            logLine("09:00:00", "INFO", "com.Foo", "early message") + "\n" +
                logLine("11:00:00", "INFO", "com.Bar", "late message") + "\n"
        );
        String result = execute(args("since", "10:00:00"));
        assertFalse(result.contains("early message"), "Should exclude early entries: " + result);
        assertTrue(result.contains("late message"), "Should include late entries: " + result);
    }

    @Test
    @DisplayName("lines parameter limits the number of returned entries")
    void linesParameterLimitsResults() throws IOException {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            content.append(logLine("10:00:0" + i, "INFO", "com.Foo", "message " + i)).append("\n");
        }
        Files.writeString(logFile, content.toString());

        JsonObject argsObj = new JsonObject();
        argsObj.addProperty("lines", 3);
        String result = execute(argsObj);

        // Should return at most 3 lines (the last 3)
        String[] lines = result.split("\n");
        assertTrue(lines.length <= 3, "Should return at most 3 lines, got: " + lines.length);
        // Last entries should be present
        assertTrue(result.contains("message 9"), "Should contain last message: " + result);
    }

    // ── Multi-line entries ────────────────────────────────────────────────────

    @Test
    @DisplayName("continuation lines (stack traces) are appended to the previous entry")
    void continuationLinesAppendedToPrevious() throws IOException {
        Files.writeString(logFile,
            logLine("10:00:00", "ERROR", "com.Foo", "NullPointerException") + "\n" +
                "\tat com.example.Foo.bar(Foo.java:42)\n" +
                "\tat com.example.Main.main(Main.java:10)\n"
        );
        String result = execute(new JsonObject());
        assertTrue(result.contains("NullPointerException"), "Main error line: " + result);
        assertTrue(result.contains("Foo.java:42"), "Stack trace line 1: " + result);
        assertTrue(result.contains("Main.java:10"), "Stack trace line 2: " + result);
    }

    // ── No-prefix logger ─────────────────────────────────────────────────────

    @Test
    @DisplayName("logger without package prefix uses the full name")
    void loggerWithoutPackagePrefix() throws IOException {
        // Log line without the # prefix before the logger name
        String line = TODAY + " 10:00:00,000 [     1]   INFO - SimpleLogger - simple message\n";
        Files.writeString(logFile, line);
        String result = execute(new JsonObject());
        assertTrue(result.contains("SimpleLogger"), "Should contain logger name: " + result);
        assertTrue(result.contains("simple message"), "Should contain message: " + result);
    }
}
