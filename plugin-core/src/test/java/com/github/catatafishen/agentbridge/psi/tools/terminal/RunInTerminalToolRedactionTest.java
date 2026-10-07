package com.github.catatafishen.agentbridge.psi.tools.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunInTerminalToolRedactionTest {

    @Test
    void masksHookInjectedGhTokenExport() {
        String command = "(export GH_TOKEN='ghs_abcdefghijklmnopqrstuvwxyz0123'; gh pr view 1)";
        assertEquals("(export GH_TOKEN='***'; gh pr view 1)", RunInTerminalTool.redactSecrets(command));
    }

    @Test
    void masksUnquotedAndDoubleQuotedAssignments() {
        assertEquals("API_KEY='***' run", RunInTerminalTool.redactSecrets("API_KEY=abc123 run"));
        assertEquals("DB_PASSWORD=\"***\" run", RunInTerminalTool.redactSecrets("DB_PASSWORD=\"p w\" run"));
    }

    @Test
    void masksBareCredentialVariableNames() {
        assertEquals("TOKEN='***' run", RunInTerminalTool.redactSecrets("TOKEN=short-secret run"));
        assertEquals("password='***' run", RunInTerminalTool.redactSecrets("password=hunter2 run"));
    }

    @Test
    void redactedCommandIsUsedForTabTitleBeforeTruncation() {
        String command = "(export GH_TOKEN='ghs_abcdefghijklmnopqrstuvwxyz0123'; gh pr view 1)";
        String title = RunInTerminalTool.truncateForTitle(RunInTerminalTool.redactSecrets(command));
        assertEquals(-1, title.indexOf("ghs_"), title);
        assertEquals(-1, title.indexOf("abcdefgh"), title);
    }

    @Test
    void masksBareGithubTokens() {
        assertEquals("curl -H 'Authorization: bearer ***'",
            RunInTerminalTool.redactSecrets("curl -H 'Authorization: bearer ghp_abcdefghijklmnopqrstuvwxyz0123'"));
    }

    @Test
    void leavesOrdinaryCommandsUntouched() {
        String command = "./gradlew test --tests '*FooTest' && echo KEYBOARD=1";
        assertEquals(command, RunInTerminalTool.redactSecrets(command));
    }
}
