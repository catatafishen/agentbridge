package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.client.AgentException;
import com.github.catatafishen.agentbridge.bridge.AgentConfig;
import com.github.catatafishen.agentbridge.bridge.AuthMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CommandOverrideAgentConfig} — the decorator that replaces
 * binary discovery and process-building with a user-provided command string.
 */
class CommandOverrideAgentConfigTest {

    private AgentConfig delegate;

    @BeforeEach
    void setUp() {
        delegate = Mockito.mock(AgentConfig.class);
    }

    // ── Delegation ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("getDisplayName delegates to wrapped config")
    void getDisplayNameDelegates() {
        Mockito.when(delegate.getDisplayName()).thenReturn("Copilot");
        var config = new CommandOverrideAgentConfig(delegate, "some-binary");
        assertEquals("Copilot", config.getDisplayName());
    }

    @Test
    @DisplayName("getNotificationGroupId delegates to wrapped config")
    void getNotificationGroupIdDelegates() {
        Mockito.when(delegate.getNotificationGroupId()).thenReturn("CopilotGroup");
        var config = new CommandOverrideAgentConfig(delegate, "binary");
        assertEquals("CopilotGroup", config.getNotificationGroupId());
    }

    @Test
    @DisplayName("getAuthMethod delegates to wrapped config")
    void getAuthMethodDelegates() {
        AuthMethod authMethod = new AuthMethod();
        Mockito.when(delegate.getAuthMethod()).thenReturn(authMethod);
        var config = new CommandOverrideAgentConfig(delegate, "binary");
        assertSame(authMethod, config.getAuthMethod());
    }

    @Test
    @DisplayName("getEffectiveMcpServerName delegates to wrapped config")
    void getEffectiveMcpServerNameDelegates() {
        Mockito.when(delegate.getEffectiveMcpServerName()).thenReturn("agentbridge");
        var config = new CommandOverrideAgentConfig(delegate, "binary");
        assertEquals("agentbridge", config.getEffectiveMcpServerName());
    }

    @Test
    @DisplayName("getPermissionInjectionMethod delegates to wrapped config")
    void getPermissionInjectionMethodDelegates() {
        Mockito.when(delegate.getPermissionInjectionMethod()).thenReturn(PermissionInjectionMethod.CLI_FLAGS);
        var config = new CommandOverrideAgentConfig(delegate, "binary");
        assertEquals(PermissionInjectionMethod.CLI_FLAGS, config.getPermissionInjectionMethod());
    }

    @Test
    @DisplayName("getAgentsDirectory delegates to wrapped config")
    void getAgentsDirectoryDelegates() {
        Mockito.when(delegate.getAgentsDirectory()).thenReturn("/home/user/.copilot/agents");
        var config = new CommandOverrideAgentConfig(delegate, "binary");
        assertEquals("/home/user/.copilot/agents", config.getAgentsDirectory());
    }

    @Test
    @DisplayName("sendResourceReferences delegates to wrapped config")
    void sendResourceReferencesDelegates() {
        Mockito.when(delegate.sendResourceReferences()).thenReturn(true);
        var config = new CommandOverrideAgentConfig(delegate, "binary");
        assertTrue(config.sendResourceReferences());
    }

    @Test
    @DisplayName("supportsSessionMessage delegates to wrapped config")
    void supportsSessionMessageDelegates() {
        Mockito.when(delegate.supportsSessionMessage()).thenReturn(false);
        var config = new CommandOverrideAgentConfig(delegate, "binary");
        assertFalse(config.supportsSessionMessage());
    }

    @Test
    @DisplayName("requiresMcpInSessionNew delegates to wrapped config")
    void requiresMcpInSessionNewDelegates() {
        Mockito.when(delegate.requiresMcpInSessionNew()).thenReturn(true);
        var config = new CommandOverrideAgentConfig(delegate, "binary");
        assertTrue(config.requiresMcpInSessionNew());
    }

    // ── findAgentBinary ──────────────────────────────────────────────────────

    @Test
    @DisplayName("findAgentBinary returns first token from command")
    void findAgentBinaryReturnsBinary() throws AgentException {
        var config = new CommandOverrideAgentConfig(delegate, "copilot --version");
        assertEquals("copilot", config.findAgentBinary());
    }

    @Test
    @DisplayName("findAgentBinary with single-word command returns that word")
    void findAgentBinarySingleToken() throws AgentException {
        var config = new CommandOverrideAgentConfig(delegate, "gh");
        assertEquals("gh", config.findAgentBinary());
    }

    @ParameterizedTest(name = "command=''{0}'' throws AgentException")
    @ValueSource(strings = {"", "   ", "/nonexistent/binary --flag"})
    @DisplayName("findAgentBinary throws AgentException for invalid commands")
    void findAgentBinaryInvalidCommandThrows(String rawCommand) {
        var config = new CommandOverrideAgentConfig(delegate, rawCommand);
        assertThrows(AgentException.class, config::findAgentBinary);
    }

    @Test
    @DisplayName("findAgentBinary with absolute path to existing file succeeds")
    void findAgentBinaryAbsoluteExistingSucceeds() throws AgentException {
        // /usr/bin/env is guaranteed to exist on any Unix system
        var env = new File("/usr/bin/env");
        if (!env.exists()) return; // Skip on systems without it
        var config = new CommandOverrideAgentConfig(delegate, "/usr/bin/env --version");
        assertEquals("/usr/bin/env", config.findAgentBinary());
    }

    // ── getAgentBinaryPath ────────────────────────────────────────────────────

    @Test
    @DisplayName("getAgentBinaryPath returns null before findAgentBinary is called")
    void getAgentBinaryPathNullBeforeFind() {
        var config = new CommandOverrideAgentConfig(delegate, "copilot --api");
        assertNull(config.getAgentBinaryPath());
    }

    @Test
    @DisplayName("getAgentBinaryPath returns resolved binary after findAgentBinary")
    void getAgentBinaryPathAfterFind() throws AgentException {
        var config = new CommandOverrideAgentConfig(delegate, "copilot --api");
        config.findAgentBinary();
        assertEquals("copilot", config.getAgentBinaryPath());
    }

    // ── buildAcpProcess ───────────────────────────────────────────────────────

    @Test
    @DisplayName("buildAcpProcess replaces first token with provided binaryPath")
    void buildAcpProcessReplacesBinary() {
        var config = new CommandOverrideAgentConfig(delegate, "old-binary --api --flag");
        var pb = config.buildAcpProcess("/resolved/binary", "/project", 3333);
        assertEquals(List.of("/resolved/binary", "--api", "--flag"), pb.command());
    }

    @Test
    @DisplayName("buildAcpProcess with single-token command uses provided binaryPath")
    void buildAcpProcessSingleTokenCommand() {
        var config = new CommandOverrideAgentConfig(delegate, "copilot");
        var pb = config.buildAcpProcess("/usr/local/bin/copilot", null, 4444);
        assertEquals(List.of("/usr/local/bin/copilot"), pb.command());
    }

    @Test
    @DisplayName("buildAcpProcess with blank command produces empty command list")
    void buildAcpProcessBlankCommandEmptyList() {
        var config = new CommandOverrideAgentConfig(delegate, "  ");
        var pb = config.buildAcpProcess("anything", null, 1234);
        assertTrue(pb.command().isEmpty());
    }

    // ── parseCommand (via findAgentBinary) ────────────────────────────────────

    @Test
    @DisplayName("multi-word command is split on whitespace")
    void commandSplitOnWhitespace() throws AgentException {
        var config = new CommandOverrideAgentConfig(delegate, "  copilot   api   --model gpt-4  ");
        // findAgentBinary returns the first token
        assertEquals("copilot", config.findAgentBinary());
        // buildAcpProcess confirms full split
        var pb = config.buildAcpProcess("copilot", null, 0);
        assertEquals(List.of("copilot", "api", "--model", "gpt-4"), pb.command());
    }
}
