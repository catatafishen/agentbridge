package com.github.catatafishen.agentbridge.agent.claude;

import com.github.catatafishen.agentbridge.acp.model.Model;
import com.github.catatafishen.agentbridge.acp.model.PromptRequest;
import com.github.catatafishen.agentbridge.acp.model.PromptResponse;
import com.github.catatafishen.agentbridge.acp.model.SessionUpdate;
import com.github.catatafishen.agentbridge.agent.AgentException;
import com.github.catatafishen.agentbridge.settings.ProjectFilesSettings;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractClaudeAgentClientTest {

    /**
     * Minimal concrete subclass for testing.
     * normalizeToolName is a public instance method that doesn't use the registry field.
     */
    private static class TestableClient extends AbstractClaudeAgentClient {
        TestableClient() {
            super(null);
        }

        @Override
        public String agentId() {
            return "";
        }

        @Override
        public String displayName() {
            return "";
        }

        @Override
        public void start() throws Exception {
            // No-op test stub.
        }

        @Override
        public void stop() {
            // No-op test stub.
        }

        @Override
        public boolean isConnected() {
            return false;
        }

        @Override
        public String createSession(String cwd) throws Exception {
            return "";
        }

        @Override
        public void cancelSession(String sessionId) {
            // No-op test stub.
        }

        @Override
        public PromptResponse sendPrompt(PromptRequest request, Consumer<SessionUpdate> onUpdate) throws Exception {
            return null;
        }

        // ── public wrappers for protected methods ─────────────────────────
        public String testResolveModel(String sessionId, String model) {
            return resolveModel(sessionId, model);
        }

        public void testEnsureStarted() throws AgentException {
            ensureStarted();
        }

        public String testGetSessionOption(String sessionId, String key) {
            return getSessionOption(sessionId, key);
        }

        public void testEmitThought(String text, Consumer<SessionUpdate> onUpdate) {
            emitThought(text, onUpdate);
        }

        public void testEmitToolCallEnd(String id, String result, boolean success, Consumer<SessionUpdate> onUpdate) {
            emitToolCallEnd(id, result, success, onUpdate);
        }

        public void setStarted(boolean value) {
            started = value;
        }
    }

    private final TestableClient client = new TestableClient();

    // ── normalizeToolName ───────────────────────────────────────────────

    @Test
    void normalizeToolName_stripsMcpPrefix() {
        assertEquals("read_file", client.normalizeToolName("mcp__agentbridge__read_file"));
    }

    @Test
    void normalizeToolName_noPrefix() {
        assertEquals("read_file", client.normalizeToolName("read_file"));
    }

    @Test
    void normalizeToolName_partialPrefix() {
        assertEquals("mcp__other__tool", client.normalizeToolName("mcp__other__tool"));
    }

    @Test
    void normalizeToolName_onlyPrefix() {
        assertEquals("", client.normalizeToolName("mcp__agentbridge__"));
    }

    // ── isRateLimitError (protected static) ─────────────────────────────

    @Test
    void isRateLimitError_hitLimit() throws Exception {
        assertTrue(invokeIsRateLimitError("You hit your premium request limit"));
    }

    @Test
    void isRateLimitError_rateLimit() throws Exception {
        assertTrue(invokeIsRateLimitError("rate limit exceeded"));
    }

    @Test
    void isRateLimitError_rateLimitWithHyphen() throws Exception {
        assertTrue(invokeIsRateLimitError("rate-limit exceeded"));
    }

    @Test
    void isRateLimitError_usageLimit() throws Exception {
        assertTrue(invokeIsRateLimitError("Your usage limit has been reached"));
    }

    @Test
    void isRateLimitError_caseInsensitive() throws Exception {
        assertTrue(invokeIsRateLimitError("Rate Limit reached for this model"));
    }

    @Test
    void isRateLimitError_noMatch() throws Exception {
        assertFalse(invokeIsRateLimitError("connection refused"));
    }

    @Test
    void isRateLimitError_emptyString() throws Exception {
        assertFalse(invokeIsRateLimitError(""));
    }

    // ── resolveModel ──────────────────────────────────────────────────

    @Nested
    class ResolveModelTest {

        @Test
        void returnsExplicitModel() {
            assertEquals("claude-opus-4", client.testResolveModel("s1", "claude-opus-4"));
        }

        @Test
        void returnsStoredModelWhenExplicitIsNull() {
            client.sessionModels.put("s1", "claude-haiku");
            assertEquals("claude-haiku", client.testResolveModel("s1", null));
        }

        @Test
        void returnsDefaultWhenNoStoredModel() {
            assertEquals("claude-sonnet-4-6", client.testResolveModel("unknown", null));
        }

        @Test
        void emptyExplicitModelTreatedAsMissing() {
            client.sessionModels.put("s1", "claude-haiku");
            assertEquals("claude-haiku", client.testResolveModel("s1", ""));
        }

        @Test
        void emptyStoredModelFallsBackToDefault() {
            client.sessionModels.put("s1", "");
            assertEquals("claude-sonnet-4-6", client.testResolveModel("s1", null));
        }
    }

    // ── ensureStarted ───────────────────────────────────────────────────

    @Nested
    class EnsureStartedTest {

        @Test
        void throwsWhenNotStarted() {
            client.setStarted(false);
            AgentException ex = assertThrows(AgentException.class, client::testEnsureStarted);
            assertTrue(ex.getMessage().contains("not started"));
        }

        @Test
        void doesNotThrowWhenStarted() {
            client.setStarted(true);
            assertDoesNotThrow(client::testEnsureStarted);
        }
    }

    // ── getDefaultProjectFiles ──────────────────────────────────────────

    @Nested
    class GetDefaultProjectFilesTest {

        @Test
        void returnsSingleEntry() {
            List<ProjectFilesSettings.FileEntry> entries = client.getDefaultProjectFiles();
            assertEquals(1, entries.size());
        }

        @Test
        void entryHasCorrectLabel() {
            ProjectFilesSettings.FileEntry entry = client.getDefaultProjectFiles().get(0);
            assertEquals("CLAUDE.md", entry.getLabel());
        }

        @Test
        void entryHasCorrectPath() {
            ProjectFilesSettings.FileEntry entry = client.getDefaultProjectFiles().get(0);
            assertEquals("CLAUDE.md", entry.getPath());
        }

        @Test
        void entryIsNotGlob() {
            ProjectFilesSettings.FileEntry entry = client.getDefaultProjectFiles().get(0);
            assertFalse(entry.isGlob());
        }

        @Test
        void entryHasCorrectGroup() {
            ProjectFilesSettings.FileEntry entry = client.getDefaultProjectFiles().get(0);
            assertEquals("Claude", entry.getGroup());
        }
    }

    // ── emitThought ─────────────────────────────────────────────────────

    @Nested
    class EmitThoughtTest {

        @Test
        void emitsWhenTextNonEmpty() {
            List<SessionUpdate> captured = new ArrayList<>();
            client.testEmitThought("thinking...", captured::add);
            assertEquals(1, captured.size());
            assertInstanceOf(SessionUpdate.AgentThoughtChunk.class, captured.get(0));
            SessionUpdate.AgentThoughtChunk chunk = (SessionUpdate.AgentThoughtChunk) captured.get(0);
            assertEquals("thinking...", chunk.text());
        }

        @Test
        void skipsWhenConsumerIsNull() {
            assertDoesNotThrow(() -> client.testEmitThought("thinking...", null));
        }

        @Test
        void skipsWhenTextIsEmpty() {
            List<SessionUpdate> captured = new ArrayList<>();
            client.testEmitThought("", captured::add);
            assertTrue(captured.isEmpty());
        }
    }

    // ── emitToolCallEnd ─────────────────────────────────────────────────

    @Nested
    class EmitToolCallEndTest {

        @Test
        void successEmitsCompleted() {
            List<SessionUpdate> captured = new ArrayList<>();
            client.testEmitToolCallEnd("t1", "ok", true, captured::add);
            assertEquals(1, captured.size());
            assertInstanceOf(SessionUpdate.ToolCallUpdate.class, captured.get(0));
            SessionUpdate.ToolCallUpdate update = (SessionUpdate.ToolCallUpdate) captured.get(0);
            assertEquals("t1", update.toolCallId());
            assertEquals(SessionUpdate.ToolCallStatus.COMPLETED, update.status());
            assertEquals("ok", update.result());
            assertNull(update.error());
        }

        @Test
        void failureEmitsFailed() {
            List<SessionUpdate> captured = new ArrayList<>();
            client.testEmitToolCallEnd("t2", "boom", false, captured::add);
            assertEquals(1, captured.size());
            SessionUpdate.ToolCallUpdate update = (SessionUpdate.ToolCallUpdate) captured.get(0);
            assertEquals("t2", update.toolCallId());
            assertEquals(SessionUpdate.ToolCallStatus.FAILED, update.status());
            assertNull(update.result());
            assertEquals("boom", update.error());
        }

        @Test
        void nullConsumerDoesNotThrow() {
            assertDoesNotThrow(() -> client.testEmitToolCallEnd("t3", "result", true, null));
        }
    }

    // ── getAvailableModels ──────────────────────────────────────────────

    @Nested
    class GetAvailableModelsTest {

        @Test
        void returnsEmptyList() {
            List<Model> models = client.getAvailableModels();
            assertNotNull(models);
            assertTrue(models.isEmpty());
        }
    }

    // ── setModel + resolveModel ─────────────────────────────────────────

    @Nested
    class SetModelAndResolveTest {

        @Test
        void setModelThenResolveReturnsSetModel() {
            client.setModel("s1", "claude-opus-4");
            assertEquals("claude-opus-4", client.testResolveModel("s1", null));
        }

        @Test
        void setModelOverridesPrevious() {
            client.setModel("s1", "claude-opus-4");
            client.setModel("s1", "claude-haiku");
            assertEquals("claude-haiku", client.testResolveModel("s1", null));
        }

        @Test
        void differentSessionsAreIndependent() {
            client.setModel("s1", "model-a");
            client.setModel("s2", "model-b");
            assertEquals("model-a", client.testResolveModel("s1", null));
            assertEquals("model-b", client.testResolveModel("s2", null));
        }
    }

    // ── setSessionOption + getSessionOption ─────────────────────────────

    @Nested
    class SessionOptionTest {

        @Test
        void roundTrip() {
            client.setSessionOption("s1", "key1", "value1");
            assertEquals("value1", client.testGetSessionOption("s1", "key1"));
        }

        @Test
        void missingSessionReturnsNull() {
            assertNull(client.testGetSessionOption("nonexistent", "key1"));
        }

        @Test
        void missingKeyReturnsNull() {
            client.setSessionOption("s1", "key1", "value1");
            assertNull(client.testGetSessionOption("s1", "missing"));
        }

        @Test
        void overwritesExistingValue() {
            client.setSessionOption("s1", "key1", "v1");
            client.setSessionOption("s1", "key1", "v2");
            assertEquals("v2", client.testGetSessionOption("s1", "key1"));
        }

        @Test
        void differentSessionsAreIndependent() {
            client.setSessionOption("s1", "key", "a");
            client.setSessionOption("s2", "key", "b");
            assertEquals("a", client.testGetSessionOption("s1", "key"));
            assertEquals("b", client.testGetSessionOption("s2", "key"));
        }
    }

    // ── Reflection helpers ──────────────────────────────────────────────

    private static boolean invokeIsRateLimitError(String errorText) throws Exception {
        Method m = AbstractClaudeAgentClient.class.getDeclaredMethod("isRateLimitError", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, errorText);
    }
}
