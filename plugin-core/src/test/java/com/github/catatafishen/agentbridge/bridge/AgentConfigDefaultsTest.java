package com.github.catatafishen.agentbridge.bridge;

import com.github.catatafishen.agentbridge.agent.AgentException;
import com.github.catatafishen.agentbridge.services.PermissionInjectionMethod;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that every default method on {@link AgentConfig} returns
 * the expected default value without requiring any override.
 */
@DisplayName("AgentConfig default methods")
class AgentConfigDefaultsTest {

    private AgentConfig config;

    @BeforeEach
    void setUp() {
        config = new AgentConfig() {
            @Override
            public @NotNull String getDisplayName() {
                return "stub";
            }

            @Override
            public @NotNull String getNotificationGroupId() {
                return "stub";
            }

            @Override
            public void prepareForLaunch(@Nullable String projectBasePath) {
                // No-op test stub for default-method coverage.
            }

            @Override
            public @NotNull String findAgentBinary() throws AgentException {
                return "stub";
            }

            @Override
            public @NotNull ProcessBuilder buildAcpProcess(@NotNull String binaryPath,
                                                           @Nullable String projectBasePath,
                                                           int mcpPort) throws AgentException {
                return new ProcessBuilder("stub");
            }

            @Override
            public void parseInitializeResponse(@NotNull JsonObject result) {
                // No-op test stub for default-method coverage.
            }

            @Override
            public @Nullable String parseModelUsage(@Nullable JsonObject modelMeta) {
                return null;
            }

            @Override
            public @Nullable AuthMethod getAuthMethod() {
                return null;
            }

            @Override
            public @Nullable String getAgentBinaryPath() {
                return null;
            }
        };
    }

    @Test
    @DisplayName("getAgentsDirectory() returns null by default")
    void getAgentsDirectory_returnsNull() {
        assertNull(config.getAgentsDirectory());
    }

    @Test
    @DisplayName("requiresResourceContentDuplication() returns false by default")
    void requiresResourceContentDuplication_returnsFalse() {
        assertFalse(config.requiresResourceContentDuplication());
    }

    @Test
    @DisplayName("getPermissionInjectionMethod() returns NONE by default")
    void getPermissionInjectionMethod_returnsNone() {
        assertEquals(PermissionInjectionMethod.NONE, config.getPermissionInjectionMethod());
    }

    @Test
    @DisplayName("getEffectiveMcpServerName() returns 'agentbridge' by default")
    void getEffectiveMcpServerName_returnsAgentbridge() {
        assertEquals("agentbridge", config.getEffectiveMcpServerName());
    }

    @Test
    @DisplayName("getToolNameRegex() returns null by default")
    void getToolNameRegex_returnsNull() {
        assertNull(config.getToolNameRegex());
    }

    @Test
    @DisplayName("getToolNameReplacement() returns null by default")
    void getToolNameReplacement_returnsNull() {
        assertNull(config.getToolNameReplacement());
    }

    @Test
    @DisplayName("requiresResourceDuplication() returns false by default")
    void requiresResourceDuplication_returnsFalse() {
        assertFalse(config.requiresResourceDuplication());
    }

    @Test
    @DisplayName("sendResourceReferences() returns true by default")
    void sendResourceReferences_returnsTrue() {
        assertTrue(config.sendResourceReferences());
    }

    @Test
    @DisplayName("supportsSessionMessage() returns true by default")
    void supportsSessionMessage_returnsTrue() {
        assertTrue(config.supportsSessionMessage());
    }

    @Test
    @DisplayName("getSessionInstructions() returns null by default")
    void getSessionInstructions_returnsNull() {
        assertNull(config.getSessionInstructions());
    }

    @Test
    @DisplayName("clearSavedModel() does not throw by default")
    void clearSavedModel_doesNotThrow() {
        assertDoesNotThrow(() -> config.clearSavedModel());
    }

    @Test
    @DisplayName("getMcpConfigTemplate() returns empty string by default")
    void getMcpConfigTemplate_returnsEmptyString() {
        assertEquals("", config.getMcpConfigTemplate());
    }

    @Test
    @DisplayName("getMcpServerName() returns 'agentbridge' by default")
    void getMcpServerName_returnsAgentbridge() {
        assertEquals("agentbridge", config.getMcpServerName());
    }

    @Test
    @DisplayName("requiresMcpInSessionNew() returns false by default")
    void requiresMcpInSessionNew_returnsFalse() {
        assertFalse(config.requiresMcpInSessionNew());
    }
}
