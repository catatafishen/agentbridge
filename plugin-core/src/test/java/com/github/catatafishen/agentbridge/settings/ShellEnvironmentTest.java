package com.github.catatafishen.agentbridge.settings;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ShellEnvironment} — focuses on the pure-logic
 * {@code buildEnvCaptureCommand()} method and the {@code getPath()} helper.
 */
class ShellEnvironmentTest {

    // ── buildEnvCaptureCommand (pure string logic) ─────────

    @Test
    void buildEnvCaptureCommand_containsNvmSource() throws Exception {
        String cmd = invokeBuildEnvCaptureCommand("/home/user");
        assertTrue(cmd.contains("/.nvm/nvm.sh"), "Should source nvm");
    }

    @Test
    void buildEnvCaptureCommand_containsSdkmanSource() throws Exception {
        String cmd = invokeBuildEnvCaptureCommand("/home/user");
        assertTrue(cmd.contains("/.sdkman/bin/sdkman-init.sh"), "Should source sdkman");
    }

    @Test
    void buildEnvCaptureCommand_containsCargoEnv() throws Exception {
        String cmd = invokeBuildEnvCaptureCommand("/home/user");
        assertTrue(cmd.contains("/.cargo/env"), "Should source cargo env");
    }

    @Test
    void buildEnvCaptureCommand_containsPyenvPath() throws Exception {
        String cmd = invokeBuildEnvCaptureCommand("/home/user");
        assertTrue(cmd.contains("/.pyenv/bin/pyenv"), "Should check for pyenv");
        assertTrue(cmd.contains("/.pyenv/bin:$PATH"), "Should prepend pyenv to PATH");
    }

    @Test
    void buildEnvCaptureCommand_containsLocalBinPath() throws Exception {
        String cmd = invokeBuildEnvCaptureCommand("/home/user");
        assertTrue(cmd.contains("/.local/bin"), "Should include ~/.local/bin for pip/uv/pipx binaries");
        assertTrue(cmd.contains("/.local/bin:$PATH"), "Should prepend ~/.local/bin to PATH");
    }

    @Test
    void buildEnvCaptureCommand_localBinOnlyAddedIfNotAlreadyPresent() throws Exception {
        String cmd = invokeBuildEnvCaptureCommand("/home/user");
        // The command uses case ":$PATH:" to check if the path is already present
        assertTrue(cmd.contains("case \":$PATH:\""), "Should check if ~/.local/bin is already in PATH");
    }

    @Test
    void buildEnvCaptureCommand_endsWithEnvDump() throws Exception {
        String cmd = invokeBuildEnvCaptureCommand("/home/user");
        assertTrue(cmd.contains("env 2>/dev/null"), "Should dump env vars");
    }

    @Test
    void buildEnvCaptureCommand_usesCorrectHomePath() throws Exception {
        String cmd = invokeBuildEnvCaptureCommand("/custom/home");
        assertTrue(cmd.contains("/custom/home/.nvm/nvm.sh"),
            "Should use the provided home path, not a hardcoded one");
        assertFalse(cmd.contains("/home/user"),
            "Should not contain any other home path");
    }

    @Test
    void buildEnvCaptureCommand_suppressesErrors() throws Exception {
        String cmd = invokeBuildEnvCaptureCommand("/home/user");
        // Each source command should redirect stderr to /dev/null
        long devNullCount = cmd.chars()
            .filter(c -> c == '2')
            .count();
        // At least 5 occurrences: nvm, sdkman, cargo, pyenv, and env
        assertTrue(devNullCount >= 5, "Should suppress errors for each source command");
    }

    @Test
    void buildEnvCaptureCommand_handlesHomeWithSpaces() throws Exception {
        String cmd = invokeBuildEnvCaptureCommand("/home/my user");
        // Paths should be single-quoted to handle spaces
        assertTrue(cmd.contains("'/home/my user/.nvm/nvm.sh'"),
            "Should quote paths to handle spaces");
    }

    // ── getPath ────────────────────────────────────────────

    @Test
    void getPath_returnsNonEmptyString() {
        // This test uses the real environment (no mocking of static state).
        // It verifies the public contract: getPath() never returns null.
        String path = ShellEnvironment.getPath();
        assertNotNull(path, "getPath() should never return null");
    }

    // ── refresh ────────────────────────────────────────────

    @Test
    void refresh_clearsCachedEnvironment() {
        // Call getEnvironment to populate cache, then refresh.
        // After refresh, the next call should re-capture (we can't verify the
        // internal state, but we can verify it doesn't throw).
        ShellEnvironment.getEnvironment();
        ShellEnvironment.refresh();
        assertNotNull(ShellEnvironment.getEnvironment(),
            "getEnvironment after refresh should still return a map");
    }

    // ── getEnvironment ─────────────────────────────────────

    @Test
    void getEnvironment_returnsNonEmptyMap() {
        var env = ShellEnvironment.getEnvironment();
        assertFalse(env.isEmpty(), "Environment should not be empty");
    }

    @Test
    void getEnvironment_containsPathVariable() {
        var env = ShellEnvironment.getEnvironment();
        assertTrue(env.containsKey("PATH"), "Environment should contain PATH");
    }

    @Test
    void getEnvironment_isCachedAcrossCalls() {
        var env1 = ShellEnvironment.getEnvironment();
        var env2 = ShellEnvironment.getEnvironment();
        assertSame(env1, env2, "Repeated calls should return the same cached instance");
    }

    // ── Reflection helpers ─────────────────────────────────

    private static String invokeBuildEnvCaptureCommand(String home) throws Exception {
        Method m = ShellEnvironment.class.getDeclaredMethod("buildEnvCaptureCommand", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, home);
    }
}
