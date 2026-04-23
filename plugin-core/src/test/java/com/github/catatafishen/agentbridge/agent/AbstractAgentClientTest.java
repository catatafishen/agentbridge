package com.github.catatafishen.agentbridge.agent;

import com.github.catatafishen.agentbridge.acp.model.Model;
import com.github.catatafishen.agentbridge.acp.model.PromptRequest;
import com.github.catatafishen.agentbridge.acp.model.PromptResponse;
import com.github.catatafishen.agentbridge.acp.model.SessionUpdate;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AbstractAgentClientTest {

    private TestAgentClient client;

    @BeforeEach
    void setUp() {
        client = new TestAgentClient();
    }

    // Concrete test subclass

    private static class TestAgentClient extends AbstractAgentClient {
        private boolean connected = false;
        private List<Model> models = List.of();
        private String currentModeSlug = null;
        private String currentAgentSlug = null;

        @Override public String agentId() { return "test"; }
        @Override public String displayName() { return "Test Agent"; }
        @Override public void start() { connected = true; }
        @Override public void stop() { connected = false; }
        @Override public boolean isConnected() { return connected; }
        @Override public String createSession(String cwd) { return "test-session-1"; }
        @Override public void cancelSession(String sessionId) {}
        @Override public PromptResponse sendPrompt(PromptRequest request, Consumer<SessionUpdate> onUpdate) { return null; }
        @Override public List<Model> getAvailableModels() { return models; }
        @Override public void setModel(String sessionId, String modelId) {}

        void setModels(List<Model> m) { models = m; }
        @Override public String getCurrentModeSlug() { return currentModeSlug; }
        @Override public void setCurrentModeSlug(String slug) { currentModeSlug = slug; }
        @Override public String getCurrentAgentSlug() { return currentAgentSlug; }
        @Override public void setCurrentAgentSlug(String slug) { currentAgentSlug = slug; }
    }

    private static class MultiplierAgentClient extends TestAgentClient {
        @Override
        public @org.jetbrains.annotations.Nullable String getModelMultiplier(@NotNull Model model) {
            return "2x";
        }
    }

    // Identity

    @Nested
    class Identity {
        @Test
        void agentIdReturnsTest() {
            assertEquals("test", client.agentId());
        }

        @Test
        void displayNameReturnsTestAgent() {
            assertEquals("Test Agent", client.displayName());
        }
    }

    // Lifecycle defaults

    @Nested
    class Lifecycle {
        @Test
        void initiallyNotConnected() {
            assertFalse(client.isConnected());
        }

        @Test
        void startConnects() throws Exception {
            client.start();
            assertTrue(client.isConnected());
        }

        @Test
        void stopDisconnects() throws Exception {
            client.start();
            client.stop();
            assertFalse(client.isConnected());
        }

        @Test
        void isHealthyDelegatesToIsConnected() throws Exception {
            assertFalse(client.isHealthy());
            client.start();
            assertTrue(client.isHealthy());
        }

        @Test
        void closeDelegatesToStop() throws Exception {
            client.start();
            assertTrue(client.isConnected());
            client.close();
            assertFalse(client.isConnected());
        }

        @Test
        void checkAuthenticationReturnsNullWhenConnected() throws Exception {
            client.start();
            assertNull(client.checkAuthentication());
        }

        @Test
        void checkAuthenticationReturnsErrorWhenNotConnected() {
            assertEquals("Agent not started", client.checkAuthentication());
        }
    }

    // getEffectiveModeSlug

    @Nested
    class EffectiveModeSlug {
        @Test
        void bothNullReturnsNull() {
            client.setCurrentModeSlug(null);
            client.setCurrentAgentSlug(null);
            assertNull(client.getEffectiveModeSlug());
        }

        @Test
        void modeSetAgentNullReturnsModeSlug() {
            client.setCurrentModeSlug("ask");
            client.setCurrentAgentSlug(null);
            assertEquals("ask", client.getEffectiveModeSlug());
        }

        @Test
        void agentSetModeNullReturnsAgentSlug() {
            client.setCurrentModeSlug(null);
            client.setCurrentAgentSlug("custom-agent");
            assertEquals("custom-agent", client.getEffectiveModeSlug());
        }

        @Test
        void bothSetAgentTakesPriority() {
            client.setCurrentModeSlug("ask");
            client.setCurrentAgentSlug("custom-agent");
            assertEquals("custom-agent", client.getEffectiveModeSlug());
        }

        @Test
        void emptyAgentFallsBackToMode() {
            client.setCurrentModeSlug("ask");
            client.setCurrentAgentSlug("");
            assertEquals("ask", client.getEffectiveModeSlug());
        }
    }

    // getModelMultiplier(String)

    @Nested
    class ModelMultiplier {
        @Test
        void modelNotInListReturnsNull() {
            client.setModels(List.of());
            assertNull(client.getModelMultiplier("gpt-4"));
        }

        @Test
        void modelInListButBaseReturnsNullMultiplier() {
            client.setModels(List.of(new Model("gpt-4", "GPT-4", null, null)));
            assertNull(client.getModelMultiplier("gpt-4"));
        }

        @Test
        void overriddenMultiplierReturnedWhenModelMatches() {
            MultiplierAgentClient multiplierClient = new MultiplierAgentClient();
            multiplierClient.setModels(List.of(new Model("gpt-4", "GPT-4", null, null)));
            assertEquals("2x", multiplierClient.getModelMultiplier("gpt-4"));
        }
    }

    // Default no-ops

    @Nested
    class DefaultNoOps {
        @Test
        void supportsMultiplierReturnsFalse() {
            assertFalse(client.supportsMultiplier());
        }

        @Test
        void modelDisplayModeReturnsNone() {
            assertEquals(AbstractAgentClient.ModelDisplayMode.NONE, client.modelDisplayMode());
        }

        @Test
        void getAvailableModesReturnsEmptyList() {
            assertTrue(client.getAvailableModes().isEmpty());
        }

        @Test
        void getAvailableAgentsReturnsEmptyList() {
            assertTrue(client.getAvailableAgents().isEmpty());
        }

        @Test
        void getAvailableConfigOptionsReturnsEmptyList() {
            assertTrue(client.getAvailableConfigOptions().isEmpty());
        }

        @Test
        void listSessionOptionsReturnsEmptyList() {
            assertTrue(client.listSessionOptions().isEmpty());
        }

        @Test
        void getLoadedSessionHistoryReturnsNull() {
            assertNull(client.getLoadedSessionHistory());
        }

        @Test
        void defaultModeSlugReturnsNull() {
            assertNull(client.defaultModeSlug());
        }

        @Test
        void defaultAgentSlugReturnsNull() {
            assertNull(client.defaultAgentSlug());
        }

        @Test
        void getDefaultProjectFilesReturnsEmptyList() {
            assertTrue(client.getDefaultProjectFiles().isEmpty());
        }

        @Test
        void getAuthMethodReturnsNull() {
            assertNull(client.getAuthMethod());
        }
    }

    // Inner records

    @Nested
    class InnerRecords {
        @Test
        void agentModeFieldsAccessible() {
            var mode = new AbstractAgentClient.AgentMode("ask", "Ask Mode", "Ask questions");
            assertEquals("ask", mode.slug());
            assertEquals("Ask Mode", mode.name());
            assertEquals("Ask questions", mode.description());
        }

        @Test
        void agentConfigOptionFieldsAccessible() {
            var value1 = new AbstractAgentClient.AgentConfigOptionValue("low", "Low");
            var value2 = new AbstractAgentClient.AgentConfigOptionValue("high", "High");
            var option = new AbstractAgentClient.AgentConfigOption(
                    "effort", "Effort Level", "Controls thinking effort",
                    List.of(value1, value2), "low"
            );
            assertEquals("effort", option.id());
            assertEquals("Effort Level", option.label());
            assertEquals("Controls thinking effort", option.description());
            assertEquals(2, option.values().size());
            assertEquals("low", option.selectedValueId());
        }

        @Test
        void agentConfigOptionValueFieldsAccessible() {
            var value = new AbstractAgentClient.AgentConfigOptionValue("medium", "Medium");
            assertEquals("medium", value.id());
            assertEquals("Medium", value.label());
        }
    }
}
