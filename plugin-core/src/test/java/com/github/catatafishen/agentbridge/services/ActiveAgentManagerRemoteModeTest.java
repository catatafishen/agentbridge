package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.acp.client.CopilotClient;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveAgentManagerRemoteModeTest {

    private ActiveAgentManager manager;

    @BeforeEach
    void setUp() {
        Project mockProject = Mockito.mock(Project.class);
        Mockito.when(mockProject.getName()).thenReturn("test-project");
        manager = new ActiveAgentManager(mockProject);
    }

    @Test
    void isRemoteMode_falseByDefault() {
        assertFalse(manager.isRemoteMode());
    }

    @Test
    void setRemoteMode_true_isRemoteModeReturnsTrue() {
        manager.setRemoteMode(true);
        assertTrue(manager.isRemoteMode());
    }

    @Test
    void setRemoteMode_false_isRemoteModeReturnsFalse() {
        manager.setRemoteMode(true);
        manager.setRemoteMode(false);
        assertFalse(manager.isRemoteMode());
    }

    @Test
    void setRemoteMode_toggles() {
        manager.setRemoteMode(true);
        assertTrue(manager.isRemoteMode());
        manager.setRemoteMode(true);
        assertTrue(manager.isRemoteMode());
        manager.setRemoteMode(false);
        assertFalse(manager.isRemoteMode());
    }

    @Test
    void setRemoteUrlListener_storesListener() throws Exception {
        Consumer<String> listener = url -> {
        };
        manager.setRemoteUrlListener(listener);
        var field = ActiveAgentManager.class.getDeclaredField("pendingRemoteUrlListener");
        field.setAccessible(true);
        assertSame(listener, field.get(manager));
    }

    @Test
    void setRemoteUrlListener_null_clearsListener() throws Exception {
        manager.setRemoteUrlListener(url -> {
        });
        manager.setRemoteUrlListener(null);
        var field = ActiveAgentManager.class.getDeclaredField("pendingRemoteUrlListener");
        field.setAccessible(true);
        assertNull(field.get(manager));
    }

    @Test
    void setRemoteUrlListener_replacesPreviousListener() throws Exception {
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();
        manager.setRemoteUrlListener(first::set);
        manager.setRemoteUrlListener(second::set);
        var field = ActiveAgentManager.class.getDeclaredField("pendingRemoteUrlListener");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") Consumer<String> stored = (Consumer<String>) field.get(manager);
        stored.accept("url");
        assertNull(first.get());
        assertEquals("url", second.get());
    }

    @Test
    void configureCopilotClientForStart_remoteModeEnabled_setsRemoteModeAndListener() {
        CopilotClient copilotClient = Mockito.mock(CopilotClient.class);
        Consumer<String> listener = url -> {
        };
        manager.setRemoteMode(true);
        manager.setRemoteUrlListener(listener);

        manager.configureCopilotClientForStart(copilotClient);

        Mockito.verify(copilotClient).setRemoteMode(true);
        Mockito.verify(copilotClient).setRemoteUrlListener(listener);
    }

    @Test
    void configureCopilotClientForStart_remoteModeDisabled_doesNotSetRemoteMode() {
        CopilotClient copilotClient = Mockito.mock(CopilotClient.class);

        manager.configureCopilotClientForStart(copilotClient);

        Mockito.verify(copilotClient, Mockito.never()).setRemoteMode(true);
        Mockito.verify(copilotClient, Mockito.never()).setRemoteUrlListener(Mockito.any());
    }

    @Test
    void configureCopilotClientForStart_withoutListener_onlySetsRemoteMode() {
        CopilotClient copilotClient = Mockito.mock(CopilotClient.class);
        manager.setRemoteMode(true);

        manager.configureCopilotClientForStart(copilotClient);

        Mockito.verify(copilotClient).setRemoteMode(true);
        Mockito.verify(copilotClient, Mockito.never()).setRemoteUrlListener(Mockito.any());
    }

    @Test
    void setRemoteErrorListener_storesListener() throws Exception {
        Consumer<String> listener = msg -> {
        };
        manager.setRemoteErrorListener(listener);
        var field = ActiveAgentManager.class.getDeclaredField("pendingRemoteErrorListener");
        field.setAccessible(true);
        assertSame(listener, field.get(manager));
    }

    @Test
    void setRemoteErrorListener_null_clearsListener() throws Exception {
        manager.setRemoteErrorListener(msg -> {
        });
        manager.setRemoteErrorListener(null);
        var field = ActiveAgentManager.class.getDeclaredField("pendingRemoteErrorListener");
        field.setAccessible(true);
        assertNull(field.get(manager));
    }

    @Test
    void configureCopilotClientForStart_remoteModeEnabled_setsErrorListener() {
        CopilotClient copilotClient = Mockito.mock(CopilotClient.class);
        Consumer<String> listener = msg -> {
        };
        manager.setRemoteMode(true);
        manager.setRemoteErrorListener(listener);

        manager.configureCopilotClientForStart(copilotClient);

        Mockito.verify(copilotClient).setRemoteErrorListener(listener);
    }

    @Test
    void configureCopilotClientForStart_noErrorListener_doesNotCallSetRemoteErrorListener() {
        CopilotClient copilotClient = Mockito.mock(CopilotClient.class);
        manager.setRemoteMode(true);

        manager.configureCopilotClientForStart(copilotClient);

        Mockito.verify(copilotClient, Mockito.never()).setRemoteErrorListener(Mockito.any());
    }
}
