package com.github.catatafishen.agentbridge.client.claude;

import com.github.catatafishen.agentbridge.bridge.AgentConfig;
import com.github.catatafishen.agentbridge.bridge.AuthMethod;
import com.github.catatafishen.agentbridge.services.AgentProfile;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the Stop button on Claude CLI.
 *
 * <p>The original bug: {@link ClaudeClient} did not override {@code getActiveSessionId()}, so it
 * inherited the {@code null}-returning default from {@code AbstractClient}. {@code
 * PromptOrchestrator.stop()} reads the active session id and only calls {@link
 * ClaudeClient#cancelSession(String)} when it is non-null — so Stop was a silent no-op for Claude
 * and the turn ran to completion. Two concurrently dispatched turns produced two orphaned {@code
 * claude} subprocesses that Stop could not kill.</p>
 */
class ClaudeClientCancelTest {

    private ClaudeClient newClient() {
        AgentProfile profile = new AgentProfile();
        profile.setDisplayName("test-claude");
        ClaudeClient.ProcessFactory unused = (cmd, env, workDir) -> {
            throw new UnsupportedOperationException();
        };
        return new ClaudeClient(profile, new StubAgentConfig(), null, null, 0, unused);
    }

    @Test
    void getActiveSessionId_isNullBeforeAnySession() {
        assertNull(newClient().getActiveSessionId(), "A fresh client has no active session");
    }

    @Test
    void getActiveSessionId_reflectsCreatedSession() {
        ClaudeClient client = newClient();
        String sessionId = client.createSession(null);
        assertEquals(sessionId, client.getActiveSessionId(),
            "Stop must be able to find the in-flight session id to cancel it");
    }

    @Test
    void dropCurrentSession_clearsActiveSessionId() {
        ClaudeClient client = newClient();
        client.createSession(null);
        client.dropCurrentSession();
        assertNull(client.getActiveSessionId());
    }

    @Test
    void cancelSession_setsCancelFlagAndDestroysActiveProcess() throws Exception {
        ClaudeClient client = newClient();
        String sessionId = client.createSession(null);
        RecordingProcess proc = registerActiveProcess(client, sessionId);

        client.cancelSession(sessionId);

        assertTrue(proc.destroyed, "Stop must forcibly terminate the running claude subprocess");
        assertTrue(cancelFlag(client, sessionId).get(),
            "The stream-reader loop checks this flag to stop emitting output");
        assertTrue(activeProcesses(client).isEmpty(), "Cancelled processes are removed");
    }

    @Test
    void cancelSession_alsoKillsOrphanedProcessFromAnotherSession() throws Exception {
        ClaudeClient client = newClient();
        // Two turns dispatched concurrently each create their own plugin session id but resume the
        // same CLI conversation. Stop knows only the latest; the earlier one is an orphan.
        String orphanSession = client.createSession(null);
        RecordingProcess orphan = registerActiveProcess(client, orphanSession);

        String currentSession = client.createSession(null);
        RecordingProcess current = registerActiveProcess(client, currentSession);

        client.cancelSession(currentSession);

        assertTrue(current.destroyed, "The current turn's process is killed");
        assertTrue(orphan.destroyed,
            "The orphaned concurrent turn must also be killed so Stop fully halts the agent");
        assertTrue(activeProcesses(client).isEmpty());
    }

    // ── reflection helpers ──────────────────────────────────────────────────

    private static RecordingProcess registerActiveProcess(ClaudeClient client, String sessionId)
        throws Exception {
        RecordingProcess proc = new RecordingProcess();
        activeProcesses(client).put(sessionId, proc);
        return proc;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Process> activeProcesses(ClaudeClient client) throws Exception {
        // activeProcesses is declared on ClaudeClient itself.
        Field f = ClaudeClient.class.getDeclaredField("activeProcesses");
        f.setAccessible(true);
        return (Map<String, Process>) f.get(client);
    }

    @SuppressWarnings("unchecked")
    private static AtomicBoolean cancelFlag(ClaudeClient client, String sessionId) throws Exception {
        // sessionCancelled is declared on the AbstractClaudeClient superclass.
        Field f = ClaudeClient.class.getSuperclass().getDeclaredField("sessionCancelled");
        f.setAccessible(true);
        return ((Map<String, AtomicBoolean>) f.get(client)).get(sessionId);
    }

    /** Minimal Process that records whether it was forcibly destroyed. */
    private static final class RecordingProcess extends Process {
        boolean destroyed;

        @Override
        public Process destroyForcibly() {
            destroyed = true;
            return this;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public boolean isAlive() {
            return !destroyed;
        }
    }

    /** Minimal AgentConfig stub that avoids IntelliJ service dependencies. */
    private static final class StubAgentConfig implements AgentConfig {
        @Override
        public @NotNull String getDisplayName() {
            return "test-claude";
        }

        @Override
        public @NotNull String getNotificationGroupId() {
            return "test";
        }

        @Override
        public void prepareForLaunch(String s) {
            // No-op: the stub never launches a real agent process.
        }

        @Override
        public @NotNull String findAgentBinary() {
            return "/usr/bin/claude";
        }

        @Override
        public @NotNull ProcessBuilder buildAcpProcess(String s, String s1, int i) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void parseInitializeResponse(@NotNull JsonObject o) {
            // No-op: the stub has no initialization payload to read.
        }

        @Override
        public String parseModelUsage(JsonObject o) {
            return null;
        }

        @Override
        public @NotNull AuthMethod getAuthMethod() {
            return new AuthMethod();
        }

        @Override
        public String getAgentBinaryPath() {
            return "/usr/bin/claude";
        }

        @Override
        public String getSessionInstructions() {
            return null;
        }
    }
}
