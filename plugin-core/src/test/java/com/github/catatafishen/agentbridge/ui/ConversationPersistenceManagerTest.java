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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
