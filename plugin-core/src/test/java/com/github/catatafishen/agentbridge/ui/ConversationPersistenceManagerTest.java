package com.github.catatafishen.agentbridge.ui;

import com.github.catatafishen.agentbridge.bridge.EntryData;
import com.github.catatafishen.agentbridge.session.ConversationEntryStore;
import com.github.catatafishen.agentbridge.session.db.ConversationDatabase;
import com.github.catatafishen.agentbridge.session.db.ConversationService;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationPersistenceManagerTest {

    @TempDir
    Path tempDir;

    private ConversationDatabase database;
    private ConversationService service;
    private ConversationEntryStore entryStore;
    private ConversationPersistenceManager manager;

    @BeforeEach
    void setUp() throws Exception {
        database = new ConversationDatabase();
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        database.initializeWithConnection(connection);
        service = new ConversationService(database);
        service.setCurrentAgent("test-agent");

        Project project = Mockito.mock(Project.class);
        Mockito.when(project.getBasePath()).thenReturn(tempDir.toString());
        entryStore = new ConversationEntryStore();
        manager = new ConversationPersistenceManager(project, service);
        manager.setEntryStore(entryStore);
    }

    @AfterEach
    void tearDown() {
        database.dispose();
    }

    @Test
    @DisplayName("removing a persisted prompt does not make the next prompt look already saved")
    void removedPromptDoesNotSkipNextPrompt() throws Exception {
        entryStore.addPromptEntry("First", null, "prompt-1");
        manager.appendNewEntriesAsync().get(2, TimeUnit.SECONDS);

        entryStore.removePromptEntry("prompt-1");
        entryStore.addPromptEntry("Second", null, "prompt-2");
        manager.appendNewEntriesAsync().get(2, TimeUnit.SECONDS);

        List<EntryData> loaded = service.loadEntries(tempDir.toString());
        assertNotNull(loaded);
        List<String> prompts = loaded.stream()
            .filter(EntryData.Prompt.class::isInstance)
            .map(EntryData.Prompt.class::cast)
            .map(EntryData.Prompt::getText)
            .toList();
        assertEquals(List.of("First", "Second"), prompts);
    }

    @Test
    @DisplayName("text streamed after a mid-stream save is saved too, in the same entry")
    void textStreamedAfterAMidStreamSaveIsKept() throws Exception {
        entryStore.addPromptEntry("Spawn a sub-agent", null, "prompt-1");
        entryStore.appendText("The sub-agent is searching");
        // A tool call completing mid-sentence saves the conversation (PromptOrchestrator).
        manager.appendNewEntriesAsync().get(2, TimeUnit.SECONDS);
        entryStore.appendText(" across the codebase.");
        entryStore.appendThinkingText("Waiting for the report");
        manager.appendNewEntriesAsync().get(2, TimeUnit.SECONDS);
        entryStore.appendThinkingText(" to arrive");
        manager.appendNewEntriesAsync().get(2, TimeUnit.SECONDS);

        assertEquals(List.of("The sub-agent is searching across the codebase."), loadedRaw(EntryData.Text.class));
        assertEquals(List.of("Waiting for the report to arrive"), loadedRaw(EntryData.Thinking.class));
    }

    @Test
    @DisplayName("a save while the first insert of a streamed entry is still queued keeps the later text")
    void saveWhileTheInsertIsQueuedKeepsTheLaterText() throws Exception {
        entryStore.addPromptEntry("Explain", null, "prompt-1");
        entryStore.appendText("First part");
        var firstSave = manager.appendNewEntriesAsync();
        entryStore.appendText(", second part.");
        var secondSave = manager.appendNewEntriesAsync();

        CompletableFuture.allOf(firstSave, secondSave).get(2, TimeUnit.SECONDS);

        assertEquals(List.of("First part, second part."), loadedRaw(EntryData.Text.class));
    }

    @Test
    @DisplayName("text streamed after a mid-stream save is kept when the turn ends before the next save")
    void textStreamedBeforeTheTurnEndsIsKept() throws Exception {
        entryStore.addPromptEntry("Explain", null, "prompt-1");
        entryStore.appendText("The answer");
        manager.appendNewEntriesAsync().get(2, TimeUnit.SECONDS);
        entryStore.appendText(" is 42.");
        // PromptOrchestrator ends the response before its turn-end save.
        entryStore.finishResponse();
        manager.appendNewEntriesAsync().get(2, TimeUnit.SECONDS);

        assertEquals(List.of("The answer is 42."), loadedRaw(EntryData.Text.class));
    }

    @Test
    @DisplayName("text streamed after a mid-stream save is kept when the entry is closed before the next save")
    void textStreamedBeforeTheEntryIsClosedIsKept() throws Exception {
        entryStore.addPromptEntry("Explain", null, "prompt-1");
        entryStore.appendText("Before");
        manager.appendNewEntriesAsync().get(2, TimeUnit.SECONDS);
        entryStore.appendText(" the tools.");
        entryStore.closeCurrentTextEntry();
        entryStore.appendText("After the tools.");
        manager.appendNewEntriesAsync().get(2, TimeUnit.SECONDS);

        assertEquals(List.of("Before the tools.", "After the tools."), loadedRaw(EntryData.Text.class));
    }

    @Test
    @DisplayName("a finished entry is written once more, then not again")
    void finishedEntryIsNotWrittenAgain() throws Exception {
        entryStore.addPromptEntry("Explain", null, "prompt-1");
        entryStore.appendText("Answer.");
        manager.appendNewEntriesAsync().get(2, TimeUnit.SECONDS);
        entryStore.finishResponse();
        manager.appendNewEntriesAsync().get(2, TimeUnit.SECONDS);

        // Nothing new and nothing streamed since the last save: the save writes nothing.
        assertTrue(manager.appendNewEntriesAsync().isDone());
        assertEquals(List.of("Answer."), loadedRaw(EntryData.Text.class));
    }

    private List<String> loadedRaw(Class<? extends EntryData> type) {
        List<EntryData> loaded = service.loadEntries(tempDir.toString());
        assertNotNull(loaded);
        return loaded.stream()
            .filter(type::isInstance)
            .map(e -> e instanceof EntryData.Text t ? t.getRaw() : ((EntryData.Thinking) e).getRaw())
            .toList();
    }

    @Test
    @DisplayName("failed entries are retried by the next persistence attempt")
    void failedEntriesRemainRetryable() throws Exception {
        database.dispose();
        entryStore.addPromptEntry("Retry me", null, "prompt-retry");

        assertThrows(ExecutionException.class, () ->
            manager.appendNewEntriesAsync().get(2, TimeUnit.SECONDS));

        Connection replacement = DriverManager.getConnection("jdbc:sqlite::memory:");
        database.initializeWithConnection(replacement);
        manager.appendNewEntriesAsync().get(2, TimeUnit.SECONDS);

        List<EntryData> loaded = service.loadEntries(tempDir.toString());
        assertNotNull(loaded);
        assertEquals(1, loaded.stream().filter(EntryData.Prompt.class::isInstance).count());
    }
}
