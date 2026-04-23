package com.github.catatafishen.agentbridge.acp.client.intercept;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShellCommandSplitterTest {

    @Test
    void simpleArgvTokenizes() {
        assertEquals(List.of("git", "status"), ShellCommandSplitter.tokenize("git status"));
        assertEquals(List.of("cat", "README.md"), ShellCommandSplitter.tokenize("cat README.md"));
    }

    @Test
    void leadingAndTrailingWhitespaceIgnored() {
        assertEquals(List.of("ls", "-la"), ShellCommandSplitter.tokenize("   ls   -la   "));
    }

    @Test
    void doubleQuotesGroupTokens() {
        assertEquals(List.of("echo", "hello world"), ShellCommandSplitter.tokenize("echo \"hello world\""));
    }

    @Test
    void singleQuotesGroupTokens() {
        assertEquals(List.of("echo", "hello world"), ShellCommandSplitter.tokenize("echo 'hello world'"));
    }

    @Test
    void backslashEscapesSpaces() {
        assertEquals(List.of("cat", "my file.txt"), ShellCommandSplitter.tokenize("cat my\\ file.txt"));
    }

    @Test
    void rejectsPipe() {
        assertNull(ShellCommandSplitter.tokenize("ls | grep foo"));
    }

    @Test
    void rejectsRedirect() {
        assertNull(ShellCommandSplitter.tokenize("ls > out.txt"));
        assertNull(ShellCommandSplitter.tokenize("cat < in.txt"));
    }

    @Test
    void rejectsCommandSeparators() {
        assertNull(ShellCommandSplitter.tokenize("ls; rm -rf /"));
        assertNull(ShellCommandSplitter.tokenize("ls && echo done"));
        assertNull(ShellCommandSplitter.tokenize("ls || echo failed"));
    }

    @Test
    void rejectsBackgroundOperator() {
        assertNull(ShellCommandSplitter.tokenize("sleep 100 &"));
    }

    @Test
    void rejectsCommandSubstitution() {
        assertNull(ShellCommandSplitter.tokenize("echo $(date)"));
        assertNull(ShellCommandSplitter.tokenize("echo `date`"));
    }

    @Test
    void rejectsSubshell() {
        assertNull(ShellCommandSplitter.tokenize("(cd /tmp && ls)"));
    }

    @Test
    void rejectsUnbalancedQuotes() {
        assertNull(ShellCommandSplitter.tokenize("echo \"hello"));
        assertNull(ShellCommandSplitter.tokenize("echo 'hello"));
    }

    @Test
    void emptyStringReturnsNull() {
        assertNull(ShellCommandSplitter.tokenize(""));
        assertNull(ShellCommandSplitter.tokenize("   "));
    }

    @Test
    void quotedMetacharsAreNotShellMetachars() {
        // A pipe inside quotes is just literal text
        assertEquals(List.of("echo", "a|b"), ShellCommandSplitter.tokenize("echo \"a|b\""));
    }
}
