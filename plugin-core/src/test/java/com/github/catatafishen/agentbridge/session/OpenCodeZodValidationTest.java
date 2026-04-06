package com.github.catatafishen.agentbridge.session;

import com.github.catatafishen.agentbridge.session.exporters.OpenCodeClientExporter;
import com.github.catatafishen.agentbridge.ui.EntryData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Validates exported OpenCode session data against OpenCode 1.2.27's actual Zod schemas.
 *
 * <p>Runs a Node.js validation script that defines the exact Zod schemas extracted from
 * the OpenCode binary and validates the exported message/part data against them. This
 * catches schema mismatches that would cause "schema validation failure" errors at runtime.</p>
 */
class OpenCodeZodValidationTest {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String PROJECT_DIR = "/tmp/test-project";

    private static Path validatorDir;
    private static String nodeCmd;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void ensureNodeAndZod() throws IOException, InterruptedException {
        // Resolve the validator directory (relative to project root)
        Path projectRoot = Path.of(System.getProperty("user.dir"));
        // Walk up from test working dir if needed (Gradle sets it to the module dir)
        validatorDir = projectRoot.resolve("opencode-zod-validator");
        if (!Files.exists(validatorDir)) {
            validatorDir = projectRoot.getParent().resolve("plugin-core/opencode-zod-validator");
        }
        if (!Files.exists(validatorDir.resolve("validate.mjs"))) {
            fail("Zod validator script not found at " + validatorDir
                + ". Ensure plugin-core/opencode-zod-validator/validate.mjs exists.");
        }

        nodeCmd = findNode();

        // Ensure node_modules/zod is installed
        if (!Files.exists(validatorDir.resolve("node_modules/zod"))) {
            String npmCmd = findNpm();
            ProcessBuilder pb = new ProcessBuilder(npmCmd, "install")
                .directory(validatorDir.toFile())
                .redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = proc.waitFor();
            if (code != 0) {
                fail("npm install failed in " + validatorDir + ": " + output);
            }
        }
    }

    @Test
    void exportedSessionPassesZodValidation() throws Exception {
        List<EntryData> entries = List.of(
            userPrompt("What is in test.txt?"),
            assistantThinking("Let me think..."),
            toolCall("read_file", "{\"path\":\"test.txt\"}", "file content"),
            assistantText("Here is the content."),
            userPrompt("Thanks!"),
            assistantText("You're welcome!")
        );

        Path dbPath = tempDir.resolve("opencode.db");
        String sessionId = OpenCodeClientExporter.exportSession(entries, dbPath, PROJECT_DIR);
        assertTrue(sessionId != null && !sessionId.isEmpty(), "Export should succeed");

        // Extract message and part rows from the exported DB
        JsonObject validatorInput = extractValidatorInput(dbPath, sessionId);

        // Run Zod validation
        String result = runValidator(validatorInput);
        JsonObject resultObj = JsonParser.parseString(result).getAsJsonObject();

        assertTrue(resultObj.get("valid").getAsBoolean(),
            "Exported session data should pass Zod validation but got errors:\n" + result);
    }

    @Test
    void exportedSubagentPartPassesZodValidation() throws Exception {
        List<EntryData> entries = List.of(
            userPrompt("Search"),
            subAgent("explore", "Exploring codebase", "Found 3 files")
        );

        Path dbPath = tempDir.resolve("opencode.db");
        String sessionId = OpenCodeClientExporter.exportSession(entries, dbPath, PROJECT_DIR);
        assertTrue(sessionId != null && !sessionId.isEmpty());

        JsonObject validatorInput = extractValidatorInput(dbPath, sessionId);
        String result = runValidator(validatorInput);
        JsonObject resultObj = JsonParser.parseString(result).getAsJsonObject();

        assertTrue(resultObj.get("valid").getAsBoolean(),
            "Subagent→text conversion should pass Zod validation:\n" + result);
    }

    @Test
    void exportedRunningToolPartPassesZodValidation() throws Exception {
        // Tool invocation in "running" state (no result yet)
        var tc = new EntryData.ToolCall("bash", "{\"command\":\"ls\"}");
        tc.setStatus("running");

        List<EntryData> entries = List.of(
            userPrompt("Run ls"),
            tc
        );

        Path dbPath = tempDir.resolve("opencode.db");
        String sessionId = OpenCodeClientExporter.exportSession(entries, dbPath, PROJECT_DIR);
        assertTrue(sessionId != null && !sessionId.isEmpty());

        JsonObject validatorInput = extractValidatorInput(dbPath, sessionId);
        String result = runValidator(validatorInput);
        JsonObject resultObj = JsonParser.parseString(result).getAsJsonObject();

        assertTrue(resultObj.get("valid").getAsBoolean(),
            "Running tool part should pass Zod validation:\n" + result);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Verifies that tool invocations with empty args (common when restoring tool calls
     * from an external agent like OpenCode) produce valid state.input objects rather than
     * being dropped by GSON serialization.
     * <p>
     * Root cause: {@code JsonParser.parseString("")} returns {@code JsonNull} which GSON's
     * default serializer drops, leaving {@code state.input} absent and failing Zod validation.
     */
    @Test
    void exportedToolPartWithEmptyArgsPassesZodValidation() throws Exception {
        List<EntryData> entries = List.of(
            userPrompt("test empty args"),
            toolCall("read_file", "", "file content")
        );

        Path dbPath = tempDir.resolve("opencode.db");
        String sessionId = OpenCodeClientExporter.exportSession(entries, dbPath, PROJECT_DIR);
        assertNotNull(sessionId);

        JsonObject validatorInput = extractValidatorInput(dbPath, sessionId);
        String result = runValidator(validatorInput);
        JsonObject resultObj = JsonParser.parseString(result).getAsJsonObject();

        assertTrue(resultObj.get("valid").getAsBoolean(),
            "Tool part with empty args should pass Zod validation:\n" + result);
    }

    /**
     * Verifies that tool invocations with null args produce valid state.input objects.
     */
    @Test
    void exportedToolPartWithNullArgsPassesZodValidation() throws Exception {
        List<EntryData> entries = List.of(
            userPrompt("test null args"),
            toolCall("search_text", null, "results")
        );

        Path dbPath = tempDir.resolve("opencode.db");
        String sessionId = OpenCodeClientExporter.exportSession(entries, dbPath, PROJECT_DIR);
        assertNotNull(sessionId);

        JsonObject validatorInput = extractValidatorInput(dbPath, sessionId);
        String result = runValidator(validatorInput);
        JsonObject resultObj = JsonParser.parseString(result).getAsJsonObject();

        assertTrue(resultObj.get("valid").getAsBoolean(),
            "Tool part with null args should pass Zod validation:\n" + result);
    }

    /**
     * Verifies that consecutive assistant entries (e.g. main response + sub-agent result
     * + continuation) are merged into a single assistant message during export, maintaining
     * the linear user→assistant chain that OpenCode expects.
     */
    @Test
    void consecutiveAssistantMessagesAreMergedDuringExport() throws Exception {
        List<EntryData> entries = List.of(
            userPrompt("Check permissions"),
            assistantText("Let me investigate...", "build", "claude-sonnet-4.6"),
            subAgent("explore", "Exploring codebase", "Found the bug"),
            assistantText("Based on the exploration, here's the fix...", "build", "claude-sonnet-4.6"),
            userPrompt("Thanks!"),
            assistantText("You're welcome!", "build", "claude-sonnet-4.6")
        );

        Path dbPath = tempDir.resolve("opencode.db");
        String sessionId = OpenCodeClientExporter.exportSession(entries, dbPath, PROJECT_DIR);
        assertNotNull(sessionId, "Export should succeed");

        // Validate structure: should have exactly 4 messages (2 user + 2 assistant)
        // because the 3 consecutive assistants (text, subAgent, text) are merged into 1
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            Class.forName("org.sqlite.JDBC");

            // Count messages
            try (var ps = conn.prepareStatement(
                "SELECT json_extract(data, '$.role') as role, COUNT(*) as cnt "
                    + "FROM message WHERE session_id = ? GROUP BY role ORDER BY role")) {
                ps.setString(1, sessionId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String role = rs.getString("role");
                        int count = rs.getInt("cnt");
                        if ("assistant".equals(role)) {
                            assertEquals(2, count,
                                "Expected 2 assistant messages (3 merged into 1 + 1 standalone)");
                        } else if ("user".equals(role)) {
                            assertEquals(2, count, "Expected 2 user messages");
                        }
                    }
                }
            }

            // Verify each assistant has exactly one unique parentID (no branching)
            try (var ps = conn.prepareStatement(
                "SELECT json_extract(data, '$.parentID') as parent, COUNT(*) as cnt "
                    + "FROM message WHERE session_id = ? "
                    + "AND json_extract(data, '$.role') = 'assistant' "
                    + "GROUP BY parent HAVING cnt > 1")) {
                ps.setString(1, sessionId);
                try (var rs = ps.executeQuery()) {
                    assertFalse(rs.next(),
                        "No parent user message should have multiple assistant children");
                }
            }

            // Verify timestamps are monotonically increasing
            try (var ps = conn.prepareStatement(
                "SELECT time_created FROM message WHERE session_id = ? ORDER BY rowid")) {
                ps.setString(1, sessionId);
                try (var rs = ps.executeQuery()) {
                    long prev = -1;
                    while (rs.next()) {
                        long ts = rs.getLong("time_created");
                        assertTrue(ts > prev,
                            "Timestamps must be strictly increasing, but " + ts + " <= " + prev);
                        prev = ts;
                    }
                }
            }
        }

        // Also verify Zod schema validation passes
        JsonObject validatorInput = extractValidatorInput(dbPath, sessionId);
        String result = runValidator(validatorInput);
        JsonObject resultObj = JsonParser.parseString(result).getAsJsonObject();
        assertTrue(resultObj.get("valid").getAsBoolean(),
            "Merged session should pass Zod validation:\n" + result);
    }

    private static EntryData.Prompt userPrompt(String text) {
        return new EntryData.Prompt(text, Instant.now().toString());
    }

    private static EntryData.Text assistantText(String text) {
        return new EntryData.Text(text, Instant.now().toString());
    }

    private static EntryData.Text assistantText(String text, String agent, String model) {
        return new EntryData.Text(text, Instant.now().toString(), agent, model);
    }

    private static EntryData.Thinking assistantThinking(String text) {
        return new EntryData.Thinking(text, Instant.now().toString());
    }

    private static EntryData.ToolCall toolCall(String toolName, String args, String result) {
        return new EntryData.ToolCall(toolName, args, "other", result, "completed");
    }

    private static EntryData.SubAgent subAgent(String agentType, String description, String result) {
        return new EntryData.SubAgent(agentType, description, null, result, "completed");
    }

    private JsonObject extractValidatorInput(Path dbPath, String sessionId) throws SQLException {
        JsonArray messagesArr = new JsonArray();
        JsonArray partsArr = new JsonArray();

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            // Load SQLite JDBC driver (IntelliJ classloader workaround)
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                throw new SQLException("SQLite JDBC driver not found", e);
            }

            try (var ps = conn.prepareStatement(
                "SELECT id, session_id, data FROM message WHERE session_id = ? ORDER BY time_created")) {
                ps.setString(1, sessionId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject row = new JsonObject();
                        row.addProperty("id", rs.getString("id"));
                        row.addProperty("session_id", rs.getString("session_id"));
                        row.add("data", JsonParser.parseString(rs.getString("data")));
                        messagesArr.add(row);
                    }
                }
            }

            try (var ps = conn.prepareStatement(
                "SELECT id, session_id, message_id, data FROM part WHERE session_id = ? ORDER BY id")) {
                ps.setString(1, sessionId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject row = new JsonObject();
                        row.addProperty("id", rs.getString("id"));
                        row.addProperty("session_id", rs.getString("session_id"));
                        row.addProperty("message_id", rs.getString("message_id"));
                        row.add("data", JsonParser.parseString(rs.getString("data")));
                        partsArr.add(row);
                    }
                }
            }
        }

        JsonObject input = new JsonObject();
        input.add("messages", messagesArr);
        input.add("parts", partsArr);
        return input;
    }

    private String runValidator(JsonObject input) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(nodeCmd, "validate.mjs")
            .directory(validatorDir.toFile())
            .redirectErrorStream(true);
        Process proc = pb.start();

        try (OutputStream os = proc.getOutputStream()) {
            os.write(GSON.toJson(input).getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = proc.waitFor();

        if (exitCode != 0 && exitCode != 1) {
            fail("Validator script crashed (exit " + exitCode + "): " + output);
        }

        return output;
    }

    private static String findNode() {
        return findBinary("node");
    }

    private static String findNpm() {
        return findBinary("npm");
    }

    /**
     * Finds a binary on PATH or in common nvm locations.
     */
    private static String findBinary(String name) {
        // Check nvm
        Path nvmDir = Path.of(System.getProperty("user.home"), ".nvm", "versions", "node");
        if (Files.isDirectory(nvmDir)) {
            try (var versions = Files.list(nvmDir)) {
                Path latest = versions
                    .filter(Files::isDirectory)
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .findFirst().orElse(null);
                if (latest != null) {
                    Path bin = latest.resolve("bin").resolve(name);
                    if (Files.isExecutable(bin)) return bin.toString();
                }
            } catch (IOException ignored) {
                // fall through to PATH
            }
        }
        return name;
    }
}
