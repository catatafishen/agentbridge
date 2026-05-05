package com.github.catatafishen.agentbridge.session.db;

import com.github.catatafishen.agentbridge.ui.EntryData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ConversationService} — session ID management, entry append/load
 * round-trips, session listing, and agent preservation.
 *
 * <p>Uses an in-memory SQLite database via the test constructor
 * {@link ConversationService#ConversationService(ConversationDatabase)},
 * completely decoupled from the IntelliJ platform.
 */
class ConversationServiceTest {

    @TempDir
    Path tempDir;

    private ConversationDatabase database;

    @BeforeEach
    void setUp() throws Exception {
        database = new ConversationDatabase();
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        database.initializeWithConnection(conn);
    }

    private ConversationService newService() {
        ConversationService service = new ConversationService(database);
        service.setCurrentAgent("test-agent");
        return service;
    }

    private Path currentSessionIdFile() {
        return tempDir.resolve(".agent-work").resolve("sessions").resolve(".current-session-id");
    }

    // ── getCurrentSessionId ───────────────────────────────────────────────

    @Test
    @DisplayName("getCurrentSessionId creates the .current-session-id file on first call")
    void getCurrentSessionId_createsFileOnFirstCall() {
        ConversationService service = newService();

        String id = service.getCurrentSessionId(tempDir.toString());

        assertNotNull(id);
        assertFalse(id.isBlank());
        assertTrue(currentSessionIdFile().toFile().exists(),
            ".current-session-id file should exist after first call");
    }

    @Test
    @DisplayName("getCurrentSessionId returns the same ID on repeated calls (cached on disk)")
    void getCurrentSessionId_returnsSameIdOnRepeatedCalls() {
        ConversationService service = newService();

        String first = service.getCurrentSessionId(tempDir.toString());
        String second = service.getCurrentSessionId(tempDir.toString());
        String third = service.getCurrentSessionId(tempDir.toString());

        assertEquals(first, second);
        assertEquals(second, third);
    }

    @Test
    @DisplayName("after resetCurrentSessionId the next call generates a new ID")
    void getCurrentSessionId_newIdAfterReset() {
        ConversationService service = newService();

        String original = service.getCurrentSessionId(tempDir.toString());
        service.resetCurrentSessionId(tempDir.toString());
        String fresh = service.getCurrentSessionId(tempDir.toString());

        assertNotEquals(original, fresh, "a new UUID should be generated after reset");
    }

    @Test
    @DisplayName("two different basePaths produce different session IDs")
    void getCurrentSessionId_differentBasePaths() throws IOException {
        ConversationService service = newService();
        Path otherDir = Files.createTempDirectory("other");

        String id1 = service.getCurrentSessionId(tempDir.toString());
        String id2 = service.getCurrentSessionId(otherDir.toString());

        assertNotEquals(id1, id2, "different base paths should produce different session IDs");
    }

    // ── resetCurrentSessionId ─────────────────────────────────────────────

    @Test
    @DisplayName("resetCurrentSessionId deletes the .current-session-id file when it exists")
    void resetCurrentSessionId_deletesFile() {
        ConversationService service = newService();
        service.getCurrentSessionId(tempDir.toString()); // create the file
        assertTrue(currentSessionIdFile().toFile().exists(), "prerequisite: file must exist");

        service.resetCurrentSessionId(tempDir.toString());

        assertFalse(currentSessionIdFile().toFile().exists(), "file should be deleted");
    }

    @Test
    @DisplayName("resetCurrentSessionId is a no-op when the file does not exist")
    void resetCurrentSessionId_noOpWhenFileMissing() {
        ConversationService service = newService();
        assertFalse(currentSessionIdFile().toFile().exists(), "prerequisite: file must not exist");

        assertDoesNotThrow(() -> service.resetCurrentSessionId(tempDir.toString()));
    }

    // ── listSessions ──────────────────────────────────────────────────────

    @Test
    @DisplayName("listSessions returns empty list when no sessions exist in the database")
    void listSessions_emptyWhenNoSessions() {
        ConversationService service = newService();

        List<ConversationService.SessionRecord> sessions = service.listSessions();

        assertNotNull(sessions);
        assertTrue(sessions.isEmpty());
    }

    @Test
    @DisplayName("listSessions returns sessions after appendEntries creates them")
    void listSessions_returnsSessionsAfterAppend() {
        ConversationService service = newService();

        service.appendEntries(tempDir.toString(),
            List.of(new EntryData.Prompt("Hello", "2024-01-01T00:00:00Z", null, "p1", "p1")));

        List<ConversationService.SessionRecord> sessions = service.listSessions();

        assertEquals(1, sessions.size());
        assertEquals("test-agent", sessions.getFirst().agent());
        assertEquals(1, sessions.getFirst().turnCount());
    }

    @Test
    @DisplayName("listSessions returns session with display name from first prompt")
    void listSessions_displaysNameFromFirstPrompt() {
        ConversationService service = newService();

        service.appendEntries(tempDir.toString(),
            List.of(new EntryData.Prompt("Fix the auth bug", "2024-01-01T00:00:00Z", null, "p1", "p1")));

        List<ConversationService.SessionRecord> sessions = service.listSessions();

        assertEquals(1, sessions.size());
        assertFalse(sessions.getFirst().name().isEmpty());
    }

    // ── appendEntries + loadEntries round-trip ────────────────────────────

    @Test
    @DisplayName("appendEntries + loadEntries round-trips a Prompt entry")
    void appendAndLoad_promptRoundTrip() {
        ConversationService service = newService();
        EntryData.Prompt prompt = new EntryData.Prompt("Hello world", "2024-01-01T00:00:00Z",
            null, "eid-1", "eid-1");

        service.appendEntries(tempDir.toString(), List.of(prompt));
        List<EntryData> loaded = service.loadEntries(tempDir.toString());

        assertNotNull(loaded);
        assertFalse(loaded.isEmpty());
        assertTrue(loaded.stream().anyMatch(e -> e instanceof EntryData.Prompt));
        EntryData.Prompt loadedPrompt = loaded.stream()
            .filter(e -> e instanceof EntryData.Prompt)
            .map(e -> (EntryData.Prompt) e)
            .findFirst().orElseThrow();
        assertEquals("Hello world", loadedPrompt.getText());
    }

    @Test
    @DisplayName("appendEntries + loadEntries round-trips Text, ToolCall, and Thinking entries")
    void appendAndLoad_multipleEntryTypesRoundTrip() {
        ConversationService service = newService();
        List<EntryData> entries = List.of(
            new EntryData.Prompt("Question", "2024-01-01T00:00:00Z", null, "p1", "p1"),
            new EntryData.Text("Answer", "2024-01-01T00:00:01Z", "test-agent", "gpt-4", "t1"),
            new EntryData.Thinking("hmm...", "2024-01-01T00:00:02Z", "test-agent", "gpt-4", "th1"),
            new EntryData.ToolCall("readFile", "{\"path\":\"a.txt\"}", "filesystem",
                "contents", "completed", "Read a file", "/a.txt",
                false, null, null, "2024-01-01T00:00:03Z", "test-agent", "gpt-4", "tc1")
        );

        service.appendEntries(tempDir.toString(), entries);
        List<EntryData> loaded = service.loadEntries(tempDir.toString());

        assertNotNull(loaded);
        assertTrue(loaded.stream().anyMatch(e -> e instanceof EntryData.Prompt));
        assertTrue(loaded.stream().anyMatch(e -> e instanceof EntryData.Text));
        assertTrue(loaded.stream().anyMatch(e -> e instanceof EntryData.Thinking));
        assertTrue(loaded.stream().anyMatch(e -> e instanceof EntryData.ToolCall));
        EntryData.ToolCall loadedTc = loaded.stream()
            .filter(e -> e instanceof EntryData.ToolCall)
            .map(e -> (EntryData.ToolCall) e)
            .findFirst().orElseThrow();
        assertEquals("readFile", loadedTc.getTitle());
    }

    @Test
    @DisplayName("loadEntries returns null when no session exists")
    void loadEntries_nullWhenNoSession() {
        ConversationService service = newService();

        List<EntryData> loaded = service.loadEntries(tempDir.toString());

        assertNull(loaded);
    }

    @Test
    @DisplayName("multiple appendEntries calls accumulate entries")
    void appendEntries_multipleAppendsAccumulate() {
        ConversationService service = newService();

        service.appendEntries(tempDir.toString(),
            List.of(new EntryData.Prompt("First", "2024-01-01T00:00:00Z", null, "p1", "p1")));
        service.appendEntries(tempDir.toString(),
            List.of(new EntryData.Prompt("Second", "2024-01-01T00:00:01Z", null, "p2", "p2")));

        List<EntryData> loaded = service.loadEntries(tempDir.toString());

        assertNotNull(loaded);
        long promptCount = loaded.stream().filter(e -> e instanceof EntryData.Prompt).count();
        assertEquals(2, promptCount, "both prompts should be stored");
    }

    // ── sessions metadata via appendEntries ───────────────────────────────

    @Test
    @DisplayName("after appendEntries the session appears in listSessions")
    void appendEntries_sessionAppearsInListSessions() {
        ConversationService service = newService();

        service.appendEntries(tempDir.toString(),
            List.of(new EntryData.Prompt("Fix the bug", "2024-01-01T00:00:00Z", null, "p1", "p1")));

        List<ConversationService.SessionRecord> sessions = service.listSessions();

        assertEquals(1, sessions.size());
        assertEquals("test-agent", sessions.getFirst().agent());
        assertEquals(1, sessions.getFirst().turnCount());
    }

    @Test
    @DisplayName("appendEntries increments turnCount for each Prompt in the batch")
    void appendEntries_incrementsTurnCount() {
        ConversationService service = newService();

        service.appendEntries(tempDir.toString(), List.of(
            new EntryData.Prompt("First turn", "2024-01-01T00:00:00Z", null, "p1", "p1"),
            new EntryData.Text("Reply", "2024-01-01T00:00:01Z", "test-agent", "gpt-4", "t1"),
            new EntryData.Prompt("Second turn", "2024-01-01T00:00:02Z", null, "p2", "p2")
        ));

        List<ConversationService.SessionRecord> sessions = service.listSessions();
        assertEquals(1, sessions.size());
        assertEquals(2, sessions.getFirst().turnCount());
    }

    @Test
    @DisplayName("appendEntries does not overwrite existing session name on second call")
    void appendEntries_doesNotOverwriteExistingName() {
        ConversationService service = newService();

        service.appendEntries(tempDir.toString(),
            List.of(new EntryData.Prompt("First prompt", "2024-01-01T00:00:00Z", null, "p1", "p1")));
        service.appendEntries(tempDir.toString(),
            List.of(new EntryData.Prompt("Second prompt", "2024-01-01T00:00:01Z", null, "p2", "p2")));

        List<ConversationService.SessionRecord> sessions = service.listSessions();
        assertFalse(sessions.isEmpty());
        String name = sessions.getFirst().name();
        assertTrue(name.contains("First") || !name.contains("Second"),
            "session name should be from the first prompt, not overwritten");
    }

    // ── branchCurrentSession ──────────────────────────────────────────────

    @Test
    @DisplayName("branchCurrentSession is a no-op (logs warning, does not throw)")
    void branchCurrentSession_noOp() {
        ConversationService service = newService();

        assertDoesNotThrow(service::branchCurrentSession);
    }

    // ── loadEntriesBySessionId ────────────────────────────────────────────

    @Test
    @DisplayName("loadEntriesBySessionId returns entries for the given session")
    void loadEntriesBySessionId_returnsEntriesForSession() {
        ConversationService service = newService();

        service.appendEntries(tempDir.toString(),
            List.of(new EntryData.Prompt("Hello", "2024-01-01T00:00:00Z", null, "p1", "p1")));
        String sessionId = service.getCurrentSessionId(tempDir.toString());

        List<EntryData> entries = service.loadEntriesBySessionId(sessionId);
        assertNotNull(entries);
        assertFalse(entries.isEmpty());
        assertTrue(entries.stream().anyMatch(e -> e instanceof EntryData.Prompt));
    }

    @Test
    @DisplayName("loadEntriesBySessionId returns null for unknown session")
    void loadEntriesBySessionId_nullForUnknownSession() {
        ConversationService service = newService();

        List<EntryData> entries = service.loadEntriesBySessionId("nonexistent-id");
        assertNull(entries);
    }

    // ── agent preservation ────────────────────────────────────────────────

    @Test
    @DisplayName("agent is not overwritten on subsequent appendEntries calls")
    void appendEntries_agentPreservedOnSubsequentAppend() {
        ConversationService service = newService();
        service.setCurrentAgent("agent-one");

        service.appendEntries(tempDir.toString(),
            List.of(new EntryData.Prompt("First prompt", "2024-01-01T00:00:00Z", null, "p1", "p1")));
        String sessionId = service.getCurrentSessionId(tempDir.toString());

        service.setCurrentAgent("agent-two");
        service.appendEntries(tempDir.toString(),
            List.of(new EntryData.Prompt("Second prompt", "2024-01-01T00:01:00Z", null, "p2", "p2")));

        Optional<ConversationService.SessionRecord> rec = service.listSessions()
            .stream().filter(r -> r.id().equals(sessionId)).findFirst();
        assertTrue(rec.isPresent(), "session should exist");
        assertEquals("agent-one", rec.get().agent(),
            "original agent must not be overwritten by subsequent appends");
    }

    @Test
    @DisplayName("appendEntries preserves original agent after agent switch")
    void appendEntries_preservesOriginalAgent() {
        ConversationService service = newService(); // currentAgent = "test-agent"

        service.appendEntries(tempDir.toString(),
            List.of(new EntryData.Prompt("Hello", "2024-01-01T00:00:00Z", null, "p1", "p1")));

        service.setCurrentAgent("different-agent");
        service.appendEntries(tempDir.toString(),
            List.of(new EntryData.Text("Reply", "2024-01-01T00:00:01Z", "different-agent", "gpt-4", "t1")));

        List<ConversationService.SessionRecord> sessions = service.listSessions();
        assertEquals(1, sessions.size());
        assertEquals("test-agent", sessions.getFirst().agent(),
            "session agent should remain from the first append, not be overwritten");
    }

    // ── appendEntries empty list ──────────────────────────────────────────

    @Test
    @DisplayName("appendEntries with empty list is a no-op")
    void appendEntries_emptyListNoOp() {
        ConversationService service = newService();

        service.appendEntries(tempDir.toString(), List.of());

        List<ConversationService.SessionRecord> sessions = service.listSessions();
        assertTrue(sessions.isEmpty(), "no sessions should be created for empty entry list");
    }
}
