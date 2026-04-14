package com.github.catatafishen.agentbridge.acp.client;

import com.github.catatafishen.agentbridge.acp.transport.JsonRpcTransport;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopilotClientRemoteModeTest {

    // ── extractGitHubUrl (package-private static) ────────────────────────

    @Test
    void extractGitHubUrl_plainUrl() {
        String url = CopilotClient.extractGitHubUrl(
            "Open this URL: https://github.com/github/copilot/remote/sessions/abc123");
        assertEquals("https://github.com/github/copilot/remote/sessions/abc123", url);
    }

    @Test
    void extractGitHubUrl_urlOnly() {
        assertEquals(
            "https://github.com/copilot/remote",
            CopilotClient.extractGitHubUrl("https://github.com/copilot/remote"));
    }

    @Test
    void extractGitHubUrl_withQueryParams() {
        String line = "Session ready at https://github.com/github/copilot/remote?token=xyz&foo=bar";
        String url = CopilotClient.extractGitHubUrl(line);
        assertNotNull(url);
        assertTrue(url.startsWith("https://github.com/"));
        assertTrue(url.contains("token=xyz"));
    }

    @Test
    void extractGitHubUrl_ansiEscapedSurroundingText() {
        // ANSI green colour around the label, URL itself is plain
        String line = "\u001B[32mRemote session:\u001B[0m https://github.com/github/copilot/remote/abc";
        assertEquals("https://github.com/github/copilot/remote/abc",
            CopilotClient.extractGitHubUrl(line));
    }

    @Test
    void extractGitHubUrl_ansiEscapedUrl() {
        // ANSI codes injected inside the URL text (some terminals hyperlink-escape URLs)
        String ansiUrl = "\u001B[34mhttps://github.com/copilot/remote/session1\u001B[0m";
        String url = CopilotClient.extractGitHubUrl(ansiUrl);
        assertEquals("https://github.com/copilot/remote/session1", url);
    }

    @Test
    void extractGitHubUrl_noUrl_returnsNull() {
        assertNull(CopilotClient.extractGitHubUrl("Waiting for agent to start..."));
    }

    @Test
    void extractGitHubUrl_emptyLine_returnsNull() {
        assertNull(CopilotClient.extractGitHubUrl(""));
    }

    @Test
    void extractGitHubUrl_nonGitHubHttpsUrl_returnsNull() {
        assertNull(CopilotClient.extractGitHubUrl("Visit https://example.com/foo for more info"));
    }

    @Test
    void extractGitHubUrl_returnsFirstUrlWhenMultiple() {
        String line = "https://github.com/first https://github.com/second";
        assertEquals("https://github.com/first", CopilotClient.extractGitHubUrl(line));
    }

    // ── setRemoteMode / setRemoteUrlListener ────────────────────────────

    @Test
    void setRemoteMode_true_setsField() throws Exception {
        CopilotClient client = allocateClient();
        client.setRemoteMode(true);
        assertTrue(getRemoteModeField(client));
    }

    @Test
    void setRemoteMode_false_clearsField() throws Exception {
        CopilotClient client = allocateClient();
        client.setRemoteMode(true);
        client.setRemoteMode(false);
        assertFalse(getRemoteModeField(client));
    }

    @Test
    void setRemoteUrlListener_registersListener() throws Exception {
        CopilotClient client = allocateClient();
        Consumer<String> listener = url -> {
        };
        client.setRemoteUrlListener(listener);
        assertSame(listener, getListenerField(client));
    }

    @Test
    void setRemoteUrlListener_null_clearsListener() throws Exception {
        CopilotClient client = allocateClient();
        client.setRemoteUrlListener(url -> {
        });
        client.setRemoteUrlListener(null);
        assertNull(getListenerField(client));
    }

    // ── registerHandlers + stderr fire-once ──────────────────────────────

    @Test
    void registerHandlers_withRemoteMode_installsStderrHandler() throws Exception {
        CopilotClient client = allocateClient();
        client.setRemoteMode(true);
        invokeRegisterHandlers(client);
        assertNotNull(getStderrHandler(client));
    }

    @Test
    void registerHandlers_withoutRemoteMode_doesNotReplaceStderrHandler() throws Exception {
        CopilotClient client = allocateClient();
        // Without remote mode, super.registerHandlers() installs the base handler.
        // CopilotClient should not replace it when remoteMode=false.
        invokeRegisterHandlers(client);
        // The base class installs its own stderr handler; the remote one should NOT be on top.
        // We verify by checking that no GitHub URL triggers the (null) remote listener.
        Consumer<String> handler = getStderrHandler(client);
        AtomicReference<String> received = new AtomicReference<>();
        client.setRemoteUrlListener(received::set);
        if (handler != null) {
            handler.accept("https://github.com/copilot/remote/session");
        }
        assertNull(received.get(), "Remote URL listener must not fire when remoteMode=false");
    }

    @Test
    void registerHandlers_stderrHandler_firesListenerWithUrl() throws Exception {
        CopilotClient client = allocateClient();
        client.setRemoteMode(true);
        AtomicReference<String> received = new AtomicReference<>();
        client.setRemoteUrlListener(received::set);

        invokeRegisterHandlers(client);
        Consumer<String> handler = getStderrHandler(client);
        assertNotNull(handler);

        handler.accept("Session ready: https://github.com/github/copilot/remote/abc");
        assertEquals("https://github.com/github/copilot/remote/abc", received.get());
    }

    @Test
    void registerHandlers_stderrHandler_firesOnce() throws Exception {
        CopilotClient client = allocateClient();
        client.setRemoteMode(true);
        AtomicInteger callCount = new AtomicInteger();
        client.setRemoteUrlListener(url -> callCount.incrementAndGet());

        invokeRegisterHandlers(client);
        Consumer<String> handler = getStderrHandler(client);
        assertNotNull(handler);

        handler.accept("https://github.com/copilot/remote/1");
        handler.accept("https://github.com/copilot/remote/2");
        handler.accept("https://github.com/copilot/remote/3");

        assertEquals(1, callCount.get(), "Listener must fire exactly once");
    }

    @Test
    void registerHandlers_stderrHandler_noFireWhenListenerNull() throws Exception {
        CopilotClient client = allocateClient();
        client.setRemoteMode(true);
        // listener left as null — handler must not throw
        invokeRegisterHandlers(client);
        Consumer<String> handler = getStderrHandler(client);
        assertNotNull(handler);
        assertDoesNotThrow(() ->
            handler.accept("https://github.com/github/copilot/remote/session"));
    }

    @Test
    void registerHandlers_stderrHandler_noFireForNonGitHubLine() throws Exception {
        CopilotClient client = allocateClient();
        client.setRemoteMode(true);
        AtomicReference<String> received = new AtomicReference<>();
        client.setRemoteUrlListener(received::set);

        invokeRegisterHandlers(client);
        Consumer<String> handler = getStderrHandler(client);
        assertNotNull(handler);

        handler.accept("Initialising agent...");
        handler.accept("https://example.com/not-github");

        assertNull(received.get(), "Non-GitHub lines must not trigger the listener");
    }

    // ── buildCommand + --remote flag ─────────────────────────────────────

    @Test
    void buildCommand_withRemoteMode_insertsRemoteFlagAtPosition1() throws Exception {
        CopilotClient client = allocateClient();
        Mockito.doReturn(null).when(client).getResumeSessionId();
        client.setRemoteMode(true);

        List<String> cmd = invokeBuildCommand(client);

        assertEquals("copilot", cmd.get(0));
        assertEquals("--remote", cmd.get(1));
        assertTrue(cmd.contains("--acp"));
    }

    @Test
    void buildCommand_withoutRemoteMode_noRemoteFlag() throws Exception {
        CopilotClient client = allocateClient();
        Mockito.doReturn(null).when(client).getResumeSessionId();

        List<String> cmd = invokeBuildCommand(client);

        assertFalse(cmd.contains("--remote"));
        assertEquals("copilot", cmd.get(0));
        assertEquals("--acp", cmd.get(1));
    }

    @Test
    void buildCommand_withResumeId_appendsResumeFlag() throws Exception {
        CopilotClient client = allocateClient();
        Mockito.doReturn("session-abc-123").when(client).getResumeSessionId();

        List<String> cmd = invokeBuildCommand(client);

        assertTrue(cmd.stream().anyMatch(s -> s.startsWith("--resume=")));
    }

    @Test
    void buildCommand_withoutResumeId_noResumeFlag() throws Exception {
        CopilotClient client = allocateClient();
        Mockito.doReturn(null).when(client).getResumeSessionId();

        List<String> cmd = invokeBuildCommand(client);

        assertFalse(cmd.stream().anyMatch(s -> s.startsWith("--resume=")));
    }

    @Test
    void defaultAgentSlug_returnsIntellijDefault() throws Exception {
        CopilotClient client = allocateClient();
        assertEquals("intellij-default", client.defaultAgentSlug());
    }

    @Test
    void supportsSessionResumption_returnsFalse() throws Exception {
        CopilotClient client = allocateClient();
        Method m = CopilotClient.class.getDeclaredMethod("supportsSessionResumption");
        m.setAccessible(true);
        assertFalse((boolean) m.invoke(client));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static CopilotClient allocateClient() throws Exception {
        CopilotClient client = Mockito.mock(CopilotClient.class, Mockito.CALLS_REAL_METHODS);
        Field transportField = com.github.catatafishen.agentbridge.acp.client.AcpClient.class
            .getDeclaredField("transport");
        transportField.setAccessible(true);
        transportField.set(client, new JsonRpcTransport());
        return client;
    }

    private static void invokeRegisterHandlers(CopilotClient client) throws Exception {
        Method m = CopilotClient.class.getDeclaredMethod("registerHandlers");
        m.setAccessible(true);
        m.invoke(client);
    }

    @SuppressWarnings("unchecked")
    private static List<String> invokeBuildCommand(CopilotClient client) throws Exception {
        Method m = CopilotClient.class.getDeclaredMethod("buildCommand", String.class, int.class);
        m.setAccessible(true);
        return new ArrayList<>((List<String>) m.invoke(client, "/tmp", 9000));
    }

    private static boolean getRemoteModeField(CopilotClient client) throws Exception {
        Field f = CopilotClient.class.getDeclaredField("remoteMode");
        f.setAccessible(true);
        return (boolean) f.get(client);
    }

    @SuppressWarnings("unchecked")
    private static Consumer<String> getListenerField(CopilotClient client) throws Exception {
        Field f = CopilotClient.class.getDeclaredField("remoteUrlListener");
        f.setAccessible(true);
        return (Consumer<String>) f.get(client);
    }

    @SuppressWarnings("unchecked")
    private static Consumer<String> getStderrHandler(CopilotClient client) throws Exception {
        // transport is a protected final field in AcpClient
        Field transportField = com.github.catatafishen.agentbridge.acp.client.AcpClient.class
            .getDeclaredField("transport");
        transportField.setAccessible(true);
        Object transport = transportField.get(client);

        Field stderrField = com.github.catatafishen.agentbridge.acp.transport.JsonRpcTransport.class
            .getDeclaredField("stderrHandler");
        stderrField.setAccessible(true);
        return (Consumer<String>) stderrField.get(transport);
    }
}
