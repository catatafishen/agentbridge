package com.github.catatafishen.agentbridge.session.db;

import com.github.catatafishen.agentbridge.bridge.ContextFileRef;
import com.github.catatafishen.agentbridge.bridge.EntryData;
import com.github.catatafishen.agentbridge.bridge.FileRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@link ConversationReader} — the SQLite read path. Writes data
 * using {@link ConversationWriter} and then reads it back via the reader,
 * verifying the full roundtrip.
 *
 * <p>Pure JDBC against in-memory SQLite — no IntelliJ platform needed.
 */
class ConversationReaderTest {

    private Connection conn;
    private ConversationDatabase database;
    private ConversationWriter writer;
    private ConversationReader reader;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA foreign_keys = ON");
        }
        database = new ConversationDatabase();
        database.initializeWithConnection(conn);
        writer = new ConversationWriter(database);
        reader = new ConversationReader(database);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null) conn.close();
    }

    // ── Session listing ───────────────────────────────────────────────────────

    @Test
    void listSessionsReturnsEmptyWhenNoData() {
        List<ConversationReader.SessionRecord> sessions = reader.listSessions();
        assertTrue(sessions.isEmpty());
    }

    @Test
    void listSessionsReturnsSessions() {
        writer.recordEntries("sess-1", "Copilot", "copilot",
            List.of(new EntryData.Prompt("Hello", "2026-01-01T10:00:00Z", null, "t1", "t1")));
        writer.recordEntries("sess-2", "Claude", "opencode",
            List.of(new EntryData.Prompt("World", "2026-01-02T10:00:00Z", null, "t2", "t2")));

        List<ConversationReader.SessionRecord> sessions = reader.listSessions();
        assertEquals(2, sessions.size());
        // Most recent first
        assertEquals("sess-2", sessions.get(0).id());
        assertEquals("Claude", sessions.get(0).agentName());
        assertEquals("sess-1", sessions.get(1).id());
    }

    @Test
    void listSessionsIncludesTurnCount() {
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("First", "2026-01-01T10:00:00Z", null, "t1", "t1"),
            new EntryData.Prompt("Second", "2026-01-01T10:01:00Z", null, "t2", "t2")
        ));

        List<ConversationReader.SessionRecord> sessions = reader.listSessions();
        assertEquals(1, sessions.size());
        assertEquals(2, sessions.getFirst().turnCount());
    }

    // ── Session existence check ───────────────────────────────────────────────

    @Test
    void sessionExistsReturnsFalseForMissingSession() {
        assertFalse(reader.sessionExists("nonexistent"));
    }

    @Test
    void sessionExistsReturnsTrueAfterWrite() {
        writer.recordEntries("sess-1", "Copilot", "copilot",
            List.of(new EntryData.Prompt("Hello", "2026-01-01T10:00:00Z", null, "t1", "t1")));
        assertTrue(reader.sessionExists("sess-1"));
    }

    // ── Full entry loading ────────────────────────────────────────────────────

    @Test
    void loadEntriesReturnsEmptyForMissingSession() {
        List<EntryData> entries = reader.loadEntries("nonexistent");
        assertTrue(entries.isEmpty());
    }

    @Test
    void roundtripPromptEntry() {
        EntryData.Prompt prompt = new EntryData.Prompt(
            "Fix the bug", "2026-01-01T10:00:00Z", null, "turn-1", "turn-1");
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(prompt));

        List<EntryData> entries = reader.loadEntries("sess-1");
        assertEquals(1, entries.size());
        assertInstanceOf(EntryData.Prompt.class, entries.getFirst());
        EntryData.Prompt loaded = (EntryData.Prompt) entries.getFirst();
        assertEquals("Fix the bug", loaded.getText());
        assertEquals("2026-01-01T10:00:00Z", loaded.getTimestamp());
        assertEquals("turn-1", loaded.getEntryId());
    }

    @Test
    void roundtripPromptWithContextFiles() {
        List<ContextFileRef> ctxFiles = List.of(
            new ContextFileRef("Main.java", "/src/Main.java", 42),
            new ContextFileRef("Test.java", "/src/Test.java", 0)
        );
        EntryData.Prompt prompt = new EntryData.Prompt(
            "Help me", "2026-01-01T10:00:00Z", ctxFiles, "turn-1", "turn-1");
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(prompt));

        List<EntryData> entries = reader.loadEntries("sess-1");
        assertEquals(1, entries.size());
        EntryData.Prompt loaded = (EntryData.Prompt) entries.getFirst();
        assertNotNull(loaded.getContextFiles());
        assertEquals(2, loaded.getContextFiles().size());
        assertEquals("Main.java", loaded.getContextFiles().getFirst().getName());
        assertEquals("/src/Main.java", loaded.getContextFiles().getFirst().getPath());
        assertEquals(42, loaded.getContextFiles().getFirst().getLine());
    }

    @Test
    void roundtripTextEvent() {
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Help", "2026-01-01T10:00:00Z", null, "t1", "t1"),
            new EntryData.Text("Here's the fix", "2026-01-01T10:00:01Z", "assistant", "gpt-4", "e1")
        ));

        List<EntryData> entries = reader.loadEntries("sess-1");
        assertEquals(2, entries.size());
        assertInstanceOf(EntryData.Text.class, entries.get(1));
        EntryData.Text text = (EntryData.Text) entries.get(1);
        assertEquals("Here's the fix", text.getRaw());
        assertEquals("assistant", text.getAgent());
        assertEquals("gpt-4", text.getModel());
    }

    @Test
    void roundtripThinkingEvent() {
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Help", "2026-01-01T10:00:00Z", null, "t1", "t1"),
            new EntryData.Thinking("Let me think...", "2026-01-01T10:00:01Z", "assistant", "claude", "e1")
        ));

        List<EntryData> entries = reader.loadEntries("sess-1");
        assertEquals(2, entries.size());
        assertInstanceOf(EntryData.Thinking.class, entries.get(1));
        EntryData.Thinking thinking = (EntryData.Thinking) entries.get(1);
        assertEquals("Let me think...", thinking.getRaw());
        assertEquals("claude", thinking.getModel());
    }

    @Test
    void roundtripToolCallEvent() {
        EntryData.ToolCall tc = new EntryData.ToolCall(
            "agentbridge-read_file", "{\"path\":\"/src/Main.java\"}", "file",
            "file contents here", "success", null, "/src/Main.java",
            false, null, "read_file",
            "2026-01-01T10:00:01Z", "assistant", "gpt-4", "e1"
        );
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Help", "2026-01-01T10:00:00Z", null, "t1", "t1"),
            tc
        ));

        List<EntryData> entries = reader.loadEntries("sess-1");
        assertEquals(2, entries.size());
        assertInstanceOf(EntryData.ToolCall.class, entries.get(1));
        EntryData.ToolCall loaded = (EntryData.ToolCall) entries.get(1);
        // pluginTool is set and NOT NULL → canonicalToolName uses it as tool_name.
        // Title after roundtrip = canonical name (prefix stripped).
        assertEquals("read_file", loaded.getTitle());
        assertEquals("{\"path\":\"/src/Main.java\"}", loaded.getArguments());
        assertEquals("file", loaded.getKind());
        assertEquals("file contents here", loaded.getResult());
        assertEquals("success", loaded.getStatus());
        assertEquals("/src/Main.java", loaded.getFilePath());
        assertFalse(loaded.getAutoDenied());
    }

    @Test
    void roundtripSubAgentEvent() {
        EntryData.SubAgent sa = new EntryData.SubAgent(
            "explore", "Find the auth module", "Search for auth", "Found it in /src/auth",
            "completed", 0, "call-1", false, null,
            "2026-01-01T10:00:01Z", "assistant", "claude", "e1"
        );
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Help", "2026-01-01T10:00:00Z", null, "t1", "t1"),
            sa
        ));

        List<EntryData> entries = reader.loadEntries("sess-1");
        assertEquals(2, entries.size());
        assertInstanceOf(EntryData.SubAgent.class, entries.get(1));
        EntryData.SubAgent loaded = (EntryData.SubAgent) entries.get(1);
        assertEquals("explore", loaded.getAgentType());
        assertEquals("Find the auth module", loaded.getDescription());
        assertEquals("Search for auth", loaded.getPrompt());
        assertEquals("Found it in /src/auth", loaded.getResult());
        assertEquals("completed", loaded.getStatus());
        assertEquals("call-1", loaded.getCallId());
    }

    @Test
    void roundtripNudgeEvent() {
        EntryData.Nudge nudge = new EntryData.Nudge(
            "Use the read_file tool", "nudge-1", true, "2026-01-01T10:00:01Z", "e1");
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Help", "2026-01-01T10:00:00Z", null, "t1", "t1"),
            nudge
        ));

        List<EntryData> entries = reader.loadEntries("sess-1");
        assertEquals(2, entries.size());
        assertInstanceOf(EntryData.Nudge.class, entries.get(1));
        EntryData.Nudge loaded = (EntryData.Nudge) entries.get(1);
        assertEquals("Use the read_file tool", loaded.getText());
        assertTrue(loaded.getSent());
    }

    @Test
    void roundtripTurnStats() {
        EntryData.TurnStats stats = new EntryData.TurnStats(
            "t1", 5000, 1000, 2000, 0.05, 3, 42, 10, "gpt-4", "1.5",
            0, 0, 0, 0.0, 0, 0, 0,
            "2026-01-01T10:05:00Z", "stats-1", List.of("abc123", "def456")
        );
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Help", "2026-01-01T10:00:00Z", null, "t1", "t1"),
            stats
        ));

        List<EntryData> entries = reader.loadEntries("sess-1");
        // Prompt + TurnStats
        assertTrue(entries.size() >= 2);
        EntryData.TurnStats loaded = null;
        for (EntryData e : entries) {
            if (e instanceof EntryData.TurnStats ts) {
                loaded = ts;
                break;
            }
        }
        assertNotNull(loaded);
        assertEquals("t1", loaded.getTurnId());
        assertEquals(5000, loaded.getDurationMs());
        assertEquals(1000, loaded.getInputTokens());
        assertEquals(2000, loaded.getOutputTokens());
        assertEquals(0.05, loaded.getCostUsd(), 0.001);
        assertEquals(3, loaded.getToolCallCount());
        assertEquals(42, loaded.getLinesAdded());
        assertEquals(10, loaded.getLinesRemoved());
        assertEquals("gpt-4", loaded.getModel());
        // Commit hashes
        assertEquals(2, loaded.getCommitHashes().size());
        assertTrue(loaded.getCommitHashes().contains("abc123"));
        assertTrue(loaded.getCommitHashes().contains("def456"));
    }

    @Test
    void roundtripContextFilesEntry() {
        List<FileRef> files = List.of(
            new FileRef("Main.java", "/src/Main.java"),
            new FileRef("Test.java", "/test/Test.java")
        );
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Help", "2026-01-01T10:00:00Z", null, "t1", "t1"),
            new EntryData.ContextFiles(files, "cf-1")
        ));

        // Context files are stored as turn_context_files, and should show up
        // when loading (attached to the prompt's context or as additional files)
        List<EntryData> entries = reader.loadEntries("sess-1");
        assertFalse(entries.isEmpty());
    }

    // ── Multiple sessions interleaved ─────────────────────────────────────────

    @Test
    void loadEntriesIsolatesSessionsCorrectly() {
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Session 1 prompt", "2026-01-01T10:00:00Z", null, "t1", "t1"),
            new EntryData.Text("Session 1 response", "2026-01-01T10:00:01Z", "a", "m", "e1")
        ));
        writer.recordEntries("sess-2", "Claude", "opencode", List.of(
            new EntryData.Prompt("Session 2 prompt", "2026-01-02T10:00:00Z", null, "t2", "t2"),
            new EntryData.Text("Session 2 response", "2026-01-02T10:00:01Z", "a", "m", "e2")
        ));

        List<EntryData> sess1 = reader.loadEntries("sess-1");
        List<EntryData> sess2 = reader.loadEntries("sess-2");
        assertEquals(2, sess1.size());
        assertEquals(2, sess2.size());
        assertEquals("Session 1 prompt", ((EntryData.Prompt) sess1.getFirst()).getText());
        assertEquals("Session 2 prompt", ((EntryData.Prompt) sess2.getFirst()).getText());
    }

    // ── Recent entries ────────────────────────────────────────────────────────

    @Test
    void loadRecentEntriesLimitsByTurnCount() {
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("First", "2026-01-01T10:00:00Z", null, "t1", "t1"),
            new EntryData.Text("Response 1", "2026-01-01T10:00:01Z", "a", "m", "e1"),
            new EntryData.Prompt("Second", "2026-01-01T10:01:00Z", null, "t2", "t2"),
            new EntryData.Text("Response 2", "2026-01-01T10:01:01Z", "a", "m", "e2"),
            new EntryData.Prompt("Third", "2026-01-01T10:02:00Z", null, "t3", "t3"),
            new EntryData.Text("Response 3", "2026-01-01T10:02:01Z", "a", "m", "e3")
        ));

        List<EntryData> recent = reader.loadRecentEntries("sess-1", 2);
        // Should get last 2 turns: "Second" + response, "Third" + response
        assertEquals(4, recent.size());
        assertInstanceOf(EntryData.Prompt.class, recent.getFirst());
        assertEquals("Second", ((EntryData.Prompt) recent.getFirst()).getText());
        assertEquals("Third", ((EntryData.Prompt) recent.get(2)).getText());
    }

    // ── All prompts ───────────────────────────────────────────────────────────

    @Test
    void loadAllPromptsReturnsChronologically() {
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Alpha", "2026-01-01T10:00:00Z", null, "t1", "t1")
        ));
        writer.recordEntries("sess-2", "Claude", "opencode", List.of(
            new EntryData.Prompt("Beta", "2026-01-02T10:00:00Z", null, "t2", "t2")
        ));

        List<ConversationReader.PromptWithStats> prompts = reader.loadAllPrompts();
        assertEquals(2, prompts.size());
        assertEquals("Alpha", prompts.getFirst().prompt().getText());
        assertEquals("sess-1", prompts.getFirst().sessionId());
        assertEquals("Beta", prompts.get(1).prompt().getText());
        assertEquals("sess-2", prompts.get(1).sessionId());
    }

    @Test
    void loadAllPromptsIncludesTurnStats() {
        EntryData.TurnStats stats = new EntryData.TurnStats(
            "t1", 3000, 500, 1500, 0.02, 2, 10, 5, "gpt-4", "",
            0, 0, 0, 0.0, 0, 0, 0,
            "2026-01-01T10:03:00Z", "stats-1", List.of()
        );
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Hello", "2026-01-01T10:00:00Z", null, "t1", "t1"),
            stats
        ));

        List<ConversationReader.PromptWithStats> prompts = reader.loadAllPrompts();
        assertEquals(1, prompts.size());
        assertNotNull(prompts.getFirst().stats());
        assertEquals(3000, prompts.getFirst().stats().getDurationMs());
        assertEquals(500, prompts.getFirst().stats().getInputTokens());
    }

    // ── Status and SessionSeparator are not persisted ─────────────────────────

    @Test
    void statusAndSeparatorEntriesAreIgnored() {
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Help", "2026-01-01T10:00:00Z", null, "t1", "t1"),
            new EntryData.Status("⏳", "Thinking..."),
            new EntryData.SessionSeparator("2026-01-01T10:00:00Z", "Copilot")
        ));

        List<EntryData> entries = reader.loadEntries("sess-1");
        // Only the prompt should be loaded — status/separator are not persisted
        assertEquals(1, entries.size());
        assertInstanceOf(EntryData.Prompt.class, entries.getFirst());
    }

    // ── Unsent nudges are not persisted ───────────────────────────────────────

    @Test
    void unsentNudgesAreNotPersisted() {
        EntryData.Nudge unsent = new EntryData.Nudge("Draft", "n1", false, "2026-01-01T10:00:01Z", "e1");
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Help", "2026-01-01T10:00:00Z", null, "t1", "t1"),
            unsent
        ));

        List<EntryData> entries = reader.loadEntries("sess-1");
        assertEquals(1, entries.size()); // Only prompt
    }

    // ── Empty entries are a no-op ─────────────────────────────────────────────

    @Test
    void emptyEntriesListIsNoOp() {
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of());
        assertFalse(reader.sessionExists("sess-1"));
    }

    // ── countTurns ────────────────────────────────────────────────────────────

    @Test
    void countTurnsReturnsZeroForMissingSession() {
        assertEquals(0, reader.countTurns("nonexistent"));
    }

    @Test
    void countTurnsReturnsTurnCount() {
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("First", "2026-01-01T10:00:00Z", null, "t1", "t1")
        ));
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Second", "2026-01-01T10:01:00Z", null, "t2", "t2")
        ));
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Third", "2026-01-01T10:02:00Z", null, "t3", "t3")
        ));
        assertEquals(3, reader.countTurns("sess-1"));
    }

    @Test
    void countTurnsIsolatedBetweenSessions() {
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("A", "2026-01-01T10:00:00Z", null, "t1", "t1")
        ));
        writer.recordEntries("sess-2", "Claude", "claude", List.of(
            new EntryData.Prompt("B", "2026-01-01T10:00:00Z", null, "t2", "t2"),
            new EntryData.Prompt("C", "2026-01-01T10:01:00Z", null, "t3", "t3")
        ));
        assertEquals(1, reader.countTurns("sess-1"));
        assertEquals(2, reader.countTurns("sess-2"));
    }

    // ── loadTurnEntries ───────────────────────────────────────────────────────

    @Test
    void loadTurnEntriesReturnsEmptyForMissingTurn() {
        List<EntryData> entries = reader.loadTurnEntries("nonexistent");
        assertTrue(entries.isEmpty());
    }

    @Test
    void loadTurnEntriesReturnsPromptAndEvents() {
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Help me", "2026-01-01T10:00:00Z", null, "t1", "t1"),
            new EntryData.Text("Here you go", "2026-01-01T10:00:01Z", "assistant", "gpt-4", "e1"),
            new EntryData.ToolCall(
                "read_file", "{}", "file", "content", "success",
                null, "/src/Main.java", false, null, null,
                "2026-01-01T10:00:02Z", "assistant", "gpt-4", "e2"
            )
        ));

        List<EntryData> entries = reader.loadTurnEntries("t1");
        assertFalse(entries.isEmpty());
        // Should contain prompt + text + tool_call
        assertInstanceOf(EntryData.Prompt.class, entries.getFirst());
        assertEquals("Help me", ((EntryData.Prompt) entries.getFirst()).getText());
    }

    @Test
    void loadTurnEntriesIncludesContextFiles() {
        List<ContextFileRef> files = List.of(
            new ContextFileRef("Main.java", "/src/Main.java", 0)
        );
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Help", "2026-01-01T10:00:00Z", files, "t1", "t1")
        ));

        List<EntryData> entries = reader.loadTurnEntries("t1");
        assertInstanceOf(EntryData.Prompt.class, entries.getFirst());
        EntryData.Prompt prompt = (EntryData.Prompt) entries.getFirst();
        assertNotNull(prompt.getContextFiles());
        assertEquals(1, prompt.getContextFiles().size());
        assertEquals("Main.java", prompt.getContextFiles().getFirst().getName());
    }

    @Test
    void loadTurnEntriesIsolatesFromOtherTurns() {
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("First", "2026-01-01T10:00:00Z", null, "t1", "t1"),
            new EntryData.Text("Response 1", "2026-01-01T10:00:01Z", "assistant", "gpt-4", "e1")
        ));
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Second", "2026-01-01T10:01:00Z", null, "t2", "t2"),
            new EntryData.Text("Response 2", "2026-01-01T10:01:01Z", "assistant", "gpt-4", "e2")
        ));

        List<EntryData> turn1 = reader.loadTurnEntries("t1");
        List<EntryData> turn2 = reader.loadTurnEntries("t2");

        // Each turn should only have its own entries
        assertInstanceOf(EntryData.Prompt.class, turn1.getFirst());
        assertEquals("First", ((EntryData.Prompt) turn1.getFirst()).getText());
        assertInstanceOf(EntryData.Prompt.class, turn2.getFirst());
        assertEquals("Second", ((EntryData.Prompt) turn2.getFirst()).getText());
    }

    // ── loadAdjacentTurnIds ───────────────────────────────────────────────────

    @Test
    void loadAdjacentTurnIdsReturnsEmptyForMissingReference() {
        List<String> ids = reader.loadAdjacentTurnIds("sess-1", "nonexistent", 3);
        assertTrue(ids.isEmpty());
    }

    @Test
    void loadAdjacentTurnIdsReturnsLaterTurns() {
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("A", "2026-01-01T10:00:00Z", null, "t1", "t1")
        ));
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("B", "2026-01-01T10:01:00Z", null, "t2", "t2")
        ));
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("C", "2026-01-01T10:02:00Z", null, "t3", "t3")
        ));

        // Get turns after t1 (positive count = later)
        List<String> later = reader.loadAdjacentTurnIds("sess-1", "t1", 5);
        assertEquals(2, later.size());
        assertEquals("t2", later.get(0));
        assertEquals("t3", later.get(1));
    }

    @Test
    void loadAdjacentTurnIdsReturnsEarlierTurns() {
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("A", "2026-01-01T10:00:00Z", null, "t1", "t1")
        ));
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("B", "2026-01-01T10:01:00Z", null, "t2", "t2")
        ));
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("C", "2026-01-01T10:02:00Z", null, "t3", "t3")
        ));

        // Get turns before t3 (negative count = earlier)
        List<String> earlier = reader.loadAdjacentTurnIds("sess-1", "t3", -5);
        assertEquals(2, earlier.size());
        assertEquals("t1", earlier.get(0));
        assertEquals("t2", earlier.get(1));
    }

    @Test
    void loadAdjacentTurnIdsRespectsLimit() {
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("A", "2026-01-01T10:00:00Z", null, "t1", "t1")
        ));
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("B", "2026-01-01T10:01:00Z", null, "t2", "t2")
        ));
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("C", "2026-01-01T10:02:00Z", null, "t3", "t3")
        ));
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("D", "2026-01-01T10:03:00Z", null, "t4", "t4")
        ));

        // Get only 1 turn after t1
        List<String> later = reader.loadAdjacentTurnIds("sess-1", "t1", 1);
        assertEquals(1, later.size());
        assertEquals("t2", later.getFirst());

        // Get only 1 turn before t4
        List<String> earlier = reader.loadAdjacentTurnIds("sess-1", "t4", -1);
        assertEquals(1, earlier.size());
        assertEquals("t3", earlier.getFirst());
    }

    @Test
    void loadAdjacentTurnIdsIsolatesBetweenSessions() {
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("A", "2026-01-01T10:00:00Z", null, "t1", "t1")
        ));
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("B", "2026-01-01T10:01:00Z", null, "t2", "t2")
        ));
        writer.recordEntries("sess-2", "Claude", "claude", List.of(
            new EntryData.Prompt("X", "2026-01-01T10:00:30Z", null, "t3", "t3")
        ));

        // Adjacent turns for sess-1 from t1 — should only find t2, not t3 from sess-2
        List<String> later = reader.loadAdjacentTurnIds("sess-1", "t1", 5);
        assertEquals(1, later.size());
        assertEquals("t2", later.getFirst());
    }

    @Test
    void loadAdjacentTurnIdsReturnsEmptyWhenNoneAdjacent() {
        writer.recordEntries("sess-1", "Copilot", "copilot", List.of(
            new EntryData.Prompt("Only", "2026-01-01T10:00:00Z", null, "t1", "t1")
        ));

        // No turns after t1
        List<String> later = reader.loadAdjacentTurnIds("sess-1", "t1", 5);
        assertTrue(later.isEmpty());

        // No turns before t1
        List<String> earlier = reader.loadAdjacentTurnIds("sess-1", "t1", -5);
        assertTrue(earlier.isEmpty());
    }
}
