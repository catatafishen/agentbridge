package com.github.catatafishen.agentbridge.client.claude;

import com.github.catatafishen.agentbridge.bridge.AgentConfig;
import com.github.catatafishen.agentbridge.bridge.AuthMethod;
import com.github.catatafishen.agentbridge.services.AgentProfile;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/** Fakes for driving a {@link ClaudeClient} without the {@code claude} binary or IntelliJ services. */
final class ClaudeCliTestFixtures {

    private ClaudeCliTestFixtures() {
    }

    /** A started client whose every CLI process prints {@code stdout} and exits. */
    static ClaudeClient clientPrinting(@NotNull String stdout) throws ReflectiveOperationException {
        byte[] bytes = stdout.getBytes(StandardCharsets.UTF_8);
        return startedClient((cmd, env, workDir) -> new FakeProcess(
            new ByteArrayInputStream(bytes), new ByteArrayOutputStream(), new ByteArrayInputStream(new byte[0])));
    }

    /** A client that runs {@code factory}'s processes, marked started so no binary is resolved. */
    static ClaudeClient startedClient(@NotNull ClaudeClient.ProcessFactory factory) throws ReflectiveOperationException {
        ClaudeClient client = newClient(factory);
        Field started = AbstractClaudeClient.class.getDeclaredField("started");
        started.setAccessible(true);
        started.set(client, true);
        Field binary = ClaudeClient.class.getDeclaredField("resolvedBinaryPath");
        binary.setAccessible(true);
        binary.set(client, "/usr/bin/claude");
        return client;
    }

    static ClaudeClient newClient(@NotNull ClaudeClient.ProcessFactory factory) {
        AgentProfile profile = new AgentProfile();
        profile.setDisplayName("test-claude");
        return new ClaudeClient(profile, new StubAgentConfig(), null, null, 0, factory);
    }

    /** A process that has already exited, with fixed output streams. */
    static class FakeProcess extends Process {
        private final InputStream stdout;
        private final OutputStream stdin;
        private final InputStream stderr;

        FakeProcess(InputStream stdout, OutputStream stdin, InputStream stderr) {
            this.stdout = stdout;
            this.stdin = stdin;
            this.stderr = stderr;
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
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
            // Nothing to stop: the process has already exited.
        }

        @Override
        public boolean isAlive() {
            return false;
        }
    }

    static final class StubAgentConfig implements AgentConfig {
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
