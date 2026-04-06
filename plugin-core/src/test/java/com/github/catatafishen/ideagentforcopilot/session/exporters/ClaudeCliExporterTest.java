package com.github.catatafishen.ideagentforcopilot.session.exporters;

import com.github.catatafishen.ideagentforcopilot.ui.EntryData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ClaudeCliExporter}.
 * Validates that v2 session entries are correctly converted to Claude CLI's
 * native event-sourced JSONL format (queue events, user/assistant message events,
 * last-prompt event, parentUuid chaining, version detection, and git branch detection).
 */
class ClaudeCliExporterTest {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String SESSION_ID = "test-session-id";

    // ── Event structure tests ────────────────────────────────────────

    @Test
    void exportProducesQueueEventsAtStart(@TempDir Path tmpDir) throws IOException {
        List<JsonObject> events = exportAndParse(List.of(prompt("Hello"), text("Hi there!")), tmpDir);

        assertTrue(events.size() >= 2, "Should have at least 2 queue events");

        JsonObject enqueue = events.getFirst();
        assertEquals("queue-operation", enqueue.get("type").getAsString());
        assertEquals("enqueue", enqueue.get("operation").getAsString());
        assertEquals(SESSION_ID, enqueue.get("sessionId").getAsString());
        assertTrue(enqueue.has("timestamp"));

        JsonObject dequeue = events.get(1);
        assertEquals("queue-operation", dequeue.get("type").getAsString());
        assertEquals("dequeue", dequeue.get("operation").getAsString());
        assertEquals(SESSION_ID, dequeue.get("sessionId").getAsString());
        assertTrue(dequeue.has("timestamp"));
    }

    @Test
    void exportProducesLastPromptAtEnd(@TempDir Path tmpDir) throws IOException {
        List<JsonObject> events = exportAndParse(
            List.of(prompt("What is 2+2?"), text("It is 4.")), tmpDir);

        JsonObject lastEvent = events.getLast();
        assertEquals("last-prompt", lastEvent.get("type").getAsString());
        assertEquals("What is 2+2?", lastEvent.get("lastPrompt").getAsString());
        assertEquals(SESSION_ID, lastEvent.get("sessionId").getAsString());
    }

    @Test
    void exportUserMessageHasCorrectFields(@TempDir Path tmpDir) throws IOException {
        List<JsonObject> events = exportAndParse(
            List.of(prompt("Hello Claude"), text("Hi!")), tmpDir);

        // First message event is at index 2 (after enqueue, dequeue)
        JsonObject userEvent = events.get(2);
        assertEquals("user", userEvent.get("type").getAsString());
        assertTrue(userEvent.has("promptId"));
        assertEquals("bypassPermissions", userEvent.get("permissionMode").getAsString());
        assertEquals("external", userEvent.get("userType").getAsString());
        assertEquals("sdk-cli", userEvent.get("entrypoint").getAsString());

        JsonObject message = userEvent.getAsJsonObject("message");
        assertEquals("user", message.get("role").getAsString());
        JsonArray content = message.getAsJsonArray("content");
        assertEquals(1, content.size());
        assertEquals("text", content.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("Hello Claude", content.get(0).getAsJsonObject().get("text").getAsString());

        // Metadata fields
        assertTrue(userEvent.has("uuid"));
        assertTrue(userEvent.has("timestamp"));
        assertEquals(SESSION_ID, userEvent.get("sessionId").getAsString());
        assertTrue(userEvent.has("cwd"));
        assertTrue(userEvent.has("version"));
        assertFalse(userEvent.get("isSidechain").getAsBoolean());
    }

    @Test
    void exportAssistantMessageHasCorrectFields(@TempDir Path tmpDir) throws IOException {
        List<JsonObject> events = exportAndParse(
            List.of(prompt("Hi"), text("Hello there!")), tmpDir);

        // Assistant event is at index 3 (enqueue, dequeue, user, assistant)
        JsonObject assistantEvent = events.get(3);
        assertEquals("assistant", assistantEvent.get("type").getAsString());
        assertTrue(assistantEvent.has("requestId"));
        assertEquals("external", assistantEvent.get("userType").getAsString());
        assertEquals("sdk-cli", assistantEvent.get("entrypoint").getAsString());

        JsonObject message = assistantEvent.getAsJsonObject("message");
        assertEquals("assistant", message.get("role").getAsString());
        assertEquals("message", message.get("type").getAsString());
        assertEquals("gpt-4", message.get("model").getAsString());
        assertTrue(message.has("id"));
        assertTrue(message.get("id").getAsString().startsWith("msg_"));
        assertTrue(message.has("stop_reason"));
        assertTrue(message.has("stop_sequence"));
        assertTrue(message.get("stop_sequence").isJsonNull());

        // Metadata fields
        assertTrue(assistantEvent.has("uuid"));
        assertTrue(assistantEvent.has("timestamp"));
        assertEquals(SESSION_ID, assistantEvent.get("sessionId").getAsString());
        assertFalse(assistantEvent.get("isSidechain").getAsBoolean());
    }

    @Test
    void parentUuidChaining(@TempDir Path tmpDir) throws IOException {
        List<JsonObject> events = exportAndParse(
            List.of(prompt("First"), text("Response"), prompt("Second"), text("Response 2")), tmpDir);

        // Filter to message events only (skip queue events and last-prompt)
        List<JsonObject> messageEvents = events.stream()
            .filter(e -> "user".equals(safeGetType(e)) || "assistant".equals(safeGetType(e)))
            .toList();

        assertTrue(messageEvents.size() >= 2, "Should have multiple message events");

        // First message has parentUuid = null
        JsonObject first = messageEvents.getFirst();
        assertTrue(first.get("parentUuid").isJsonNull(),
            "First message must have parentUuid: null");

        // Each subsequent message has parentUuid = previous message's uuid
        for (int i = 1; i < messageEvents.size(); i++) {
            String expectedParent = messageEvents.get(i - 1).get("uuid").getAsString();
            String actualParent = messageEvents.get(i).get("parentUuid").getAsString();
            assertEquals(expectedParent, actualParent,
                "Message " + i + " parentUuid should equal message " + (i - 1) + " uuid");
        }
    }

    @Test
    void assistantWithToolUseHasToolUseStopReason(@TempDir Path tmpDir) throws IOException {
        List<JsonObject> events = exportAndParse(
            List.of(prompt("Do it"), toolCall("read_file", "{\"path\":\"/test\"}", "contents")),
            tmpDir);

        // Find the assistant event that contains tool_use
        JsonObject assistantEvent = events.stream()
            .filter(e -> "assistant".equals(safeGetType(e)))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No assistant event found"));

        JsonObject message = assistantEvent.getAsJsonObject("message");
        assertEquals("tool_use", message.get("stop_reason").getAsString(),
            "Assistant with tool_use blocks must have stop_reason=tool_use");
    }

    @Test
    void assistantWithoutToolUseHasEndTurnStopReason(@TempDir Path tmpDir) throws IOException {
        List<JsonObject> events = exportAndParse(
            List.of(prompt("Hi"), text("Hello!")), tmpDir);

        JsonObject assistantEvent = events.stream()
            .filter(e -> "assistant".equals(safeGetType(e)))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No assistant event found"));

        JsonObject message = assistantEvent.getAsJsonObject("message");
        assertEquals("end_turn", message.get("stop_reason").getAsString(),
            "Text-only assistant must have stop_reason=end_turn");
    }

    @Test
    void toolResultUserMessageHasSourceToolAssistantUUID(@TempDir Path tmpDir) throws IOException {
        // Conversation: user prompt → assistant with tool_use → user with tool_result → assistant text
        List<JsonObject> events = exportAndParse(
            List.of(
                prompt("Read it"),
                toolCall("read_file", "{\"path\":\"/f\"}", "data"),
                text("Here is what I found.")),
            tmpDir);

        // Collect message events by type
        List<JsonObject> messageEvents = events.stream()
            .filter(e -> "user".equals(safeGetType(e)) || "assistant".equals(safeGetType(e)))
            .toList();

        // Find the assistant event with tool_use
        JsonObject toolAssistant = messageEvents.stream()
            .filter(e -> "assistant".equals(safeGetType(e)))
            .filter(e -> {
                JsonArray content = e.getAsJsonObject("message").getAsJsonArray("content");
                for (int i = 0; i < content.size(); i++) {
                    if ("tool_use".equals(content.get(i).getAsJsonObject().get("type").getAsString())) {
                        return true;
                    }
                }
                return false;
            })
            .findFirst()
            .orElseThrow(() -> new AssertionError("No tool-use assistant event found"));

        String toolAssistantUuid = toolAssistant.get("uuid").getAsString();

        // Find the user event with tool_result (comes after the tool-use assistant)
        JsonObject toolResultUser = messageEvents.stream()
            .filter(e -> "user".equals(safeGetType(e)))
            .filter(e -> {
                JsonArray content = e.getAsJsonObject("message").getAsJsonArray("content");
                for (int i = 0; i < content.size(); i++) {
                    if ("tool_result".equals(content.get(i).getAsJsonObject().get("type").getAsString())) {
                        return true;
                    }
                }
                return false;
            })
            .findFirst()
            .orElseThrow(() -> new AssertionError("No tool-result user event found"));

        assertTrue(toolResultUser.has("sourceToolAssistantUUID"),
            "Tool-result user message must have sourceToolAssistantUUID");
        assertEquals(toolAssistantUuid, toolResultUser.get("sourceToolAssistantUUID").getAsString(),
            "sourceToolAssistantUUID must point to the tool-use assistant");
    }

    @Test
    void userPromptDoesNotHaveSourceToolAssistantUUID(@TempDir Path tmpDir) throws IOException {
        List<JsonObject> events = exportAndParse(
            List.of(prompt("Hello"), text("Hi")), tmpDir);

        JsonObject userEvent = events.stream()
            .filter(e -> "user".equals(safeGetType(e)))
            .findFirst()
            .orElseThrow();

        assertFalse(userEvent.has("sourceToolAssistantUUID"),
            "Regular user prompts should NOT have sourceToolAssistantUUID");
    }

    // ── Version detection tests ──────────────────────────────────────

    @Test
    void detectsVersionFromExistingSessionFile(@TempDir Path tmpDir) throws IOException {
        // Create a fake existing session file with a version field
        Path sessionsDir = tmpDir.resolve("sessions");
        Files.createDirectories(sessionsDir);
        String existingSession = """
            {"type":"user","uuid":"abc","version":"3.5.42","sessionId":"old"}
            {"type":"assistant","uuid":"def","version":"3.5.42","sessionId":"old"}
            """;
        Files.writeString(sessionsDir.resolve("existing-session.jsonl"), existingSession, StandardCharsets.UTF_8);

        // Export to a new file in the same sessions directory
        Path target = sessionsDir.resolve("new-session.jsonl");
        ClaudeCliExporter.exportToFile(
            List.of(prompt("Hi"), text("Hello")),
            target, SESSION_ID, tmpDir.toString());

        List<JsonObject> events = parseJsonlFile(target);

        // Message events should use the detected version
        JsonObject messageEvent = events.stream()
            .filter(e -> "user".equals(safeGetType(e)) || "assistant".equals(safeGetType(e)))
            .findFirst()
            .orElseThrow();
        assertEquals("3.5.42", messageEvent.get("version").getAsString(),
            "Should detect version from existing session file");
    }

    @Test
    void fallsBackToDefaultVersionWhenNoExistingFiles(@TempDir Path tmpDir) throws IOException {
        // Empty sessions directory — no existing files to detect version from
        List<JsonObject> events = exportAndParse(
            List.of(prompt("Hi"), text("Hello")), tmpDir);

        JsonObject messageEvent = events.stream()
            .filter(e -> "user".equals(safeGetType(e)) || "assistant".equals(safeGetType(e)))
            .findFirst()
            .orElseThrow();
        assertEquals("2.1.78", messageEvent.get("version").getAsString(),
            "Should fall back to default version 2.1.78");
    }

    @Test
    void ignoresVersionOnePointZeroFromExistingFile(@TempDir Path tmpDir) throws IOException {
        // Version "1.0.0" is explicitly excluded by the detector
        Path sessionsDir = tmpDir.resolve("sessions");
        Files.createDirectories(sessionsDir);
        String existingSession = """
            {"type":"user","version":"1.0.0","sessionId":"old"}
            """;
        Files.writeString(sessionsDir.resolve("old.jsonl"), existingSession, StandardCharsets.UTF_8);

        Path target = sessionsDir.resolve("new.jsonl");
        ClaudeCliExporter.exportToFile(
            List.of(prompt("Hi"), text("Hello")),
            target, SESSION_ID, tmpDir.toString());

        List<JsonObject> events = parseJsonlFile(target);
        JsonObject messageEvent = events.stream()
            .filter(e -> "user".equals(safeGetType(e)) || "assistant".equals(safeGetType(e)))
            .findFirst()
            .orElseThrow();
        assertEquals("2.1.78", messageEvent.get("version").getAsString(),
            "Version 1.0.0 should be ignored, falling back to default");
    }

    // ── Git branch detection tests ───────────────────────────────────

    @Test
    void detectsGitBranch(@TempDir Path tmpDir) throws IOException {
        // Create a fake .git/HEAD file
        Path gitDir = tmpDir.resolve(".git");
        Files.createDirectories(gitDir);
        Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/main\n", StandardCharsets.UTF_8);

        List<JsonObject> events = exportAndParse(
            List.of(prompt("Hi"), text("Hello")), tmpDir);

        JsonObject messageEvent = events.stream()
            .filter(e -> "user".equals(safeGetType(e)) || "assistant".equals(safeGetType(e)))
            .findFirst()
            .orElseThrow();
        assertEquals("main", messageEvent.get("gitBranch").getAsString());
    }

    @Test
    void detectsFeatureBranch(@TempDir Path tmpDir) throws IOException {
        Path gitDir = tmpDir.resolve(".git");
        Files.createDirectories(gitDir);
        Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/feature/my-branch\n", StandardCharsets.UTF_8);

        List<JsonObject> events = exportAndParse(
            List.of(prompt("Hi"), text("Hello")), tmpDir);

        JsonObject messageEvent = events.stream()
            .filter(e -> "user".equals(safeGetType(e)) || "assistant".equals(safeGetType(e)))
            .findFirst()
            .orElseThrow();
        assertEquals("feature/my-branch", messageEvent.get("gitBranch").getAsString());
    }

    @Test
    void detectsDetachedHead(@TempDir Path tmpDir) throws IOException {
        Path gitDir = tmpDir.resolve(".git");
        Files.createDirectories(gitDir);
        Files.writeString(gitDir.resolve("HEAD"), "abc123def456789\n", StandardCharsets.UTF_8);

        List<JsonObject> events = exportAndParse(
            List.of(prompt("Hi"), text("Hello")), tmpDir);

        JsonObject messageEvent = events.stream()
            .filter(e -> "user".equals(safeGetType(e)) || "assistant".equals(safeGetType(e)))
            .findFirst()
            .orElseThrow();
        // Detached HEAD → uses first 12 chars as branch name
        assertEquals("abc123def456", messageEvent.get("gitBranch").getAsString());
    }

    @Test
    void noGitDirectory(@TempDir Path tmpDir) throws IOException {
        // No .git directory → gitBranch should be absent from events
        List<JsonObject> events = exportAndParse(
            List.of(prompt("Hi"), text("Hello")), tmpDir);

        JsonObject messageEvent = events.stream()
            .filter(e -> "user".equals(safeGetType(e)) || "assistant".equals(safeGetType(e)))
            .findFirst()
            .orElseThrow();
        assertFalse(messageEvent.has("gitBranch"),
            "No .git directory should mean no gitBranch field in events");
    }

    // ── Last prompt extraction ───────────────────────────────────────

    @Test
    void lastPromptUsesLastUserMessage(@TempDir Path tmpDir) throws IOException {
        List<JsonObject> events = exportAndParse(
            List.of(
                prompt("First question"),
                text("First answer"),
                prompt("Second question"),
                text("Second answer")),
            tmpDir);

        JsonObject lastPromptEvent = events.getLast();
        assertEquals("last-prompt", lastPromptEvent.get("type").getAsString());
        assertEquals("Second question", lastPromptEvent.get("lastPrompt").getAsString(),
            "last-prompt should use the LAST user message text");
    }

    @Test
    void noUserPrompt_noLastPromptEvent(@TempDir Path tmpDir) throws IOException {
        // Only assistant entries — no user prompts, so toAnthropicMessages will produce
        // an assistant message only; there's no user text for last-prompt
        List<JsonObject> events = exportAndParse(List.of(text("Just an assistant response")), tmpDir);

        boolean hasLastPrompt = events.stream()
            .anyMatch(e -> "last-prompt".equals(safeGetType(e)));
        assertFalse(hasLastPrompt, "No user prompts should mean no last-prompt event");
    }

    @Test
    void lastPromptIgnoresToolResultUserMessages(@TempDir Path tmpDir) throws IOException {
        // tool_result user messages contain no text blocks; last-prompt should use the
        // actual user prompt text, not be influenced by tool results
        List<JsonObject> events = exportAndParse(
            List.of(
                prompt("Read the file"),
                toolCall("read_file", "{}", "file data"),
                text("Done reading.")),
            tmpDir);

        JsonObject lastPromptEvent = events.getLast();
        assertEquals("last-prompt", lastPromptEvent.get("type").getAsString());
        assertEquals("Read the file", lastPromptEvent.get("lastPrompt").getAsString());
    }

    // ── UUID uniqueness tests ────────────────────────────────────────

    @Test
    void allUuidsAreUnique(@TempDir Path tmpDir) throws IOException {
        List<JsonObject> events = exportAndParse(
            List.of(
                prompt("One"),
                text("Two"),
                prompt("Three"),
                toolCall("read_file", "{}", "result"),
                text("Four")),
            tmpDir);

        List<String> uuids = events.stream()
            .filter(e -> e.has("uuid"))
            .map(e -> e.get("uuid").getAsString())
            .toList();

        assertEquals(uuids.size(), new HashSet<>(uuids).size(),
            "All UUIDs must be unique, got: " + uuids);
    }

    // ── CWD and sessionId tests ──────────────────────────────────────

    @Test
    void cwdPropagatedToAllMessageEvents(@TempDir Path tmpDir) throws IOException {
        List<JsonObject> events = exportAndParse(
            List.of(prompt("Hi"), text("Hello")), tmpDir);

        events.stream()
            .filter(e -> "user".equals(safeGetType(e)) || "assistant".equals(safeGetType(e)))
            .forEach(e -> assertEquals(tmpDir.toString(), e.get("cwd").getAsString(),
                "cwd must match the provided directory"));
    }

    @Test
    void sessionIdPropagatedToAllEvents(@TempDir Path tmpDir) throws IOException {
        List<JsonObject> events = exportAndParse(
            List.of(prompt("Hi"), text("Hello")), tmpDir);

        events.forEach(e -> assertEquals(SESSION_ID, e.get("sessionId").getAsString(),
            "sessionId must be present on every event"));
    }

    // ── Integration test ─────────────────────────────────────────────

    @Test
    void fullConversationWithTools(@TempDir Path tmpDir) throws IOException {
        // Set up git branch
        Path gitDir = tmpDir.resolve(".git");
        Files.createDirectories(gitDir);
        Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/develop\n", StandardCharsets.UTF_8);

        List<EntryData> conversation = List.of(
            prompt("Read test.txt and tell me what's in it"),
            text("Let me read that file for you."),
            toolCall("read_file", "{\"path\":\"/test.txt\"}", "Hello World"),
            text("The file contains: Hello World")
        );

        List<JsonObject> events = exportAndParse(conversation, tmpDir);

        // ── Verify event count and ordering ──
        // enqueue + dequeue + user(prompt) + assistant(text+tool_use) + user(tool_result)
        // + assistant(text) + last-prompt = 7 events
        assertEquals(7, events.size(),
            "Expected 7 events: 2 queue + 3 messages + 1 last-prompt + 1 more message; got: "
                + events.stream().map(ClaudeCliExporterTest::safeGetType).toList());

        // Queue events
        assertEquals("queue-operation", events.get(0).get("type").getAsString());
        assertEquals("enqueue", events.get(0).get("operation").getAsString());
        assertEquals("queue-operation", events.get(1).get("type").getAsString());
        assertEquals("dequeue", events.get(1).get("operation").getAsString());

        // Last event is last-prompt
        JsonObject lastPrompt = events.getLast();
        assertEquals("last-prompt", lastPrompt.get("type").getAsString());
        assertEquals("Read test.txt and tell me what's in it", lastPrompt.get("lastPrompt").getAsString());

        // ── Verify message events ──
        List<JsonObject> messageEvents = events.stream()
            .filter(e -> "user".equals(safeGetType(e)) || "assistant".equals(safeGetType(e)))
            .toList();

        assertEquals(4, messageEvents.size(), "Should have 4 message events");

        // Message 0: user prompt
        assertEquals("user", messageEvents.get(0).get("type").getAsString());
        assertEquals("user", messageEvents.get(0).getAsJsonObject("message").get("role").getAsString());

        // Message 1: assistant with text + tool_use → stop_reason=tool_use
        assertEquals("assistant", messageEvents.get(1).get("type").getAsString());
        assertEquals("tool_use", messageEvents.get(1).getAsJsonObject("message").get("stop_reason").getAsString());

        // Message 2: user with tool_result → has sourceToolAssistantUUID
        assertEquals("user", messageEvents.get(2).get("type").getAsString());
        assertTrue(messageEvents.get(2).has("sourceToolAssistantUUID"));
        assertEquals(messageEvents.get(1).get("uuid").getAsString(),
            messageEvents.get(2).get("sourceToolAssistantUUID").getAsString());

        // Message 3: assistant with text → stop_reason=end_turn
        assertEquals("assistant", messageEvents.get(3).get("type").getAsString());
        assertEquals("end_turn", messageEvents.get(3).getAsJsonObject("message").get("stop_reason").getAsString());

        // ── Verify parentUuid chain ──
        assertTrue(messageEvents.get(0).get("parentUuid").isJsonNull());
        for (int i = 1; i < messageEvents.size(); i++) {
            assertEquals(messageEvents.get(i - 1).get("uuid").getAsString(),
                messageEvents.get(i).get("parentUuid").getAsString(),
                "parentUuid chain broken at message " + i);
        }

        // ── Verify git branch ──
        messageEvents.forEach(e -> assertEquals("develop", e.get("gitBranch").getAsString()));

        // ── Verify version (no existing files → fallback) ──
        messageEvents.forEach(e -> assertEquals("2.1.78", e.get("version").getAsString()));
    }

    @Test
    void multipleToolCallsInSequence(@TempDir Path tmpDir) throws IOException {
        List<JsonObject> events = exportAndParse(
            List.of(
                prompt("Do two things"),
                toolCall("read_file", "{}", "result1"),
                toolCall("search_text", "{}", "result2"),
                text("Both done.")),
            tmpDir);

        List<JsonObject> messageEvents = events.stream()
            .filter(e -> "user".equals(safeGetType(e)) || "assistant".equals(safeGetType(e)))
            .toList();

        // The AnthropicClientExporter merges consecutive same-role messages,
        // so tool_use blocks should be in a single assistant message
        long assistantCount = messageEvents.stream()
            .filter(e -> "assistant".equals(safeGetType(e)))
            .count();
        assertTrue(assistantCount >= 1, "Should have at least one assistant event");

        // Verify at least one assistant has tool_use stop_reason
        boolean hasToolUseAssistant = messageEvents.stream()
            .filter(e -> "assistant".equals(safeGetType(e)))
            .anyMatch(e -> "tool_use".equals(
                e.getAsJsonObject("message").get("stop_reason").getAsString()));
        assertTrue(hasToolUseAssistant, "Should have assistant with stop_reason=tool_use");
    }

    @Test
    void emptyEntriesProducesMinimalOutput(@TempDir Path tmpDir) throws IOException {
        List<JsonObject> events = exportAndParse(List.of(), tmpDir);

        // Should still have queue events, but no message events and no last-prompt
        assertEquals(2, events.size(), "Empty entries should produce only queue events");
        assertEquals("queue-operation", events.get(0).get("type").getAsString());
        assertEquals("queue-operation", events.get(1).get("type").getAsString());
    }

    @Test
    void assistantMessageIdFormat(@TempDir Path tmpDir) throws IOException {
        List<JsonObject> events = exportAndParse(
            List.of(prompt("Hi"), text("Hello")), tmpDir);

        JsonObject assistantEvent = events.stream()
            .filter(e -> "assistant".equals(safeGetType(e)))
            .findFirst()
            .orElseThrow();

        String msgId = assistantEvent.getAsJsonObject("message").get("id").getAsString();
        assertTrue(msgId.startsWith("msg_"), "Assistant message id should start with 'msg_'");
        // msg_ prefix + 24 hex chars from UUID without hyphens
        assertEquals(4 + 24, msgId.length(),
            "Assistant message id should be msg_ + 24 chars");
    }

    @Test
    void timestampsAreIsoFormat(@TempDir Path tmpDir) throws IOException {
        List<JsonObject> events = exportAndParse(
            List.of(prompt("Hi"), text("Hello")), tmpDir);

        for (JsonObject event : events) {
            if (event.has("timestamp")) {
                String ts = event.get("timestamp").getAsString();
                // Should be parseable as an Instant (ISO-8601)
                Instant parsed = Instant.parse(ts);
                assertNotNull(parsed, "Timestamp should be valid ISO-8601: " + ts);
            }
        }
    }

    @Test
    void sourceToolAssistantUUIDResetAfterToolResults(@TempDir Path tmpDir) throws IOException {
        // After tool-result is consumed, a subsequent user prompt should NOT have
        // sourceToolAssistantUUID
        List<JsonObject> events = exportAndParse(
            List.of(
                prompt("First task"),
                toolCall("read_file", "{}", "data"),
                text("Done with tool."),
                prompt("Second task"),
                text("Here's the answer.")),
            tmpDir);

        List<JsonObject> userEvents = events.stream()
            .filter(e -> "user".equals(safeGetType(e)))
            .toList();

        // The second user prompt (non-tool-result) should NOT have sourceToolAssistantUUID
        // Find user events that are actual prompts (have text content, not tool_result)
        List<JsonObject> promptUserEvents = userEvents.stream()
            .filter(e -> {
                JsonArray content = e.getAsJsonObject("message").getAsJsonArray("content");
                for (int i = 0; i < content.size(); i++) {
                    if ("text".equals(content.get(i).getAsJsonObject().get("type").getAsString())) {
                        return true;
                    }
                }
                return false;
            })
            .toList();

        // None of the text-based user events should have sourceToolAssistantUUID
        for (JsonObject promptEvent : promptUserEvents) {
            assertFalse(promptEvent.has("sourceToolAssistantUUID"),
                "Prompt user events should not have sourceToolAssistantUUID");
        }
    }

    @Test
    void outputFileCreatedInNonExistentDirectory(@TempDir Path tmpDir) throws IOException {
        Path deepPath = tmpDir.resolve("a").resolve("b").resolve("c").resolve("session.jsonl");
        ClaudeCliExporter.exportToFile(
            List.of(prompt("Hi"), text("Hello")),
            deepPath, SESSION_ID, tmpDir.toString());

        assertTrue(Files.exists(deepPath), "Should create intermediate directories");
        List<String> lines = Files.readAllLines(deepPath, StandardCharsets.UTF_8);
        assertFalse(lines.isEmpty(), "Output file should not be empty");
    }

    // ── Helper methods ───────────────────────────────────────────────

    private List<JsonObject> exportAndParse(List<EntryData> entries, Path tmpDir) throws IOException {
        Path target = tmpDir.resolve("sessions").resolve("test-session.jsonl");
        ClaudeCliExporter.exportToFile(entries, target, SESSION_ID, tmpDir.toString());
        return parseJsonlFile(target);
    }

    private static List<JsonObject> parseJsonlFile(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
            .filter(l -> !l.isBlank())
            .map(l -> GSON.fromJson(l, JsonObject.class))
            .toList();
    }

    private static String safeGetType(JsonObject obj) {
        return obj.has("type") ? obj.get("type").getAsString() : "";
    }

    private static EntryData.Prompt prompt(String text) {
        return new EntryData.Prompt(text, Instant.now().toString(), null, "",
            UUID.randomUUID().toString());
    }

    private static EntryData.Text text(String content) {
        return new EntryData.Text(content, Instant.now().toString(),
            "copilot", "gpt-4", UUID.randomUUID().toString());
    }

    private static EntryData.ToolCall toolCall(String name, String args, String result) {
        return new EntryData.ToolCall(name, args, "other", result, "completed",
            null, null, false, null, false,
            Instant.now().toString(), "copilot", "gpt-4", UUID.randomUUID().toString());
    }
}
