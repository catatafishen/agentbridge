package com.github.catatafishen.agentbridge.psi.tools.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerminalToolStaticMethodsTest {

    // ── resolveInputEscapes ─────────────────────────────────

    @Test
    void resolveInputEscapes_plainTextUnchanged() {
        assertEquals("hello world", TerminalTool.resolveInputEscapes("hello world"));
    }

    @Test
    void resolveInputEscapes_enter() {
        assertEquals("\r", TerminalTool.resolveInputEscapes("{enter}"));
    }

    @Test
    void resolveInputEscapes_tab() {
        assertEquals("\t", TerminalTool.resolveInputEscapes("{tab}"));
    }

    @Test
    void resolveInputEscapes_ctrlC() {
        assertEquals("\u0003", TerminalTool.resolveInputEscapes("{ctrl-c}"));
    }

    @Test
    void resolveInputEscapes_ctrlD() {
        assertEquals("\u0004", TerminalTool.resolveInputEscapes("{ctrl-d}"));
    }

    @Test
    void resolveInputEscapes_ctrlZ() {
        assertEquals("\u001A", TerminalTool.resolveInputEscapes("{ctrl-z}"));
    }

    @Test
    void resolveInputEscapes_escape() {
        assertEquals("\u001B", TerminalTool.resolveInputEscapes("{escape}"));
    }

    @Test
    void resolveInputEscapes_backspace() {
        assertEquals("\u007F", TerminalTool.resolveInputEscapes("{backspace}"));
    }

    @Test
    void resolveInputEscapes_arrowKeys() {
        assertEquals("\u001B[A", TerminalTool.resolveInputEscapes("{up}"));
        assertEquals("\u001B[B", TerminalTool.resolveInputEscapes("{down}"));
        assertEquals("\u001B[C", TerminalTool.resolveInputEscapes("{right}"));
        assertEquals("\u001B[D", TerminalTool.resolveInputEscapes("{left}"));
    }

    @Test
    void resolveInputEscapes_backslashEscapes() {
        assertEquals("\n", TerminalTool.resolveInputEscapes("\\n"));
        assertEquals("\t", TerminalTool.resolveInputEscapes("\\t"));
    }

    @Test
    void resolveInputEscapes_mixedEscapesAndText() {
        assertEquals("ls -la\r", TerminalTool.resolveInputEscapes("ls -la{enter}"));
    }

    @Test
    void resolveInputEscapes_multipleEscapes() {
        assertEquals("\u001B[B\u001B[B\r", TerminalTool.resolveInputEscapes("{down}{down}{enter}"));
    }

    @Test
    void resolveInputEscapes_emptyString() {
        assertEquals("", TerminalTool.resolveInputEscapes(""));
    }

    // ── describeInput ───────────────────────────────────────

    @Test
    void describeInput_plainText() {
        assertEquals("'hello'", TerminalTool.describeInput("hello", "hello"));
    }

    @Test
    void describeInput_withEscapeSequences() {
        assertEquals("'ls{enter}' (3 chars)", TerminalTool.describeInput("ls{enter}", "ls\r"));
    }

    @Test
    void describeInput_withBackslashN() {
        assertEquals("'line1\\nline2' (11 chars)", TerminalTool.describeInput("line1\\nline2", "line1\nline2"));
    }

    // ── tailLines ───────────────────────────────────────────

    @Test
    void tailLines_fewerLinesThanMax() {
        String text = "line1\nline2\nline3";
        assertEquals(text, TerminalTool.tailLines(text, 5));
    }

    @Test
    void tailLines_exactMatch() {
        String text = "line1\nline2\nline3";
        assertEquals(text, TerminalTool.tailLines(text, 3));
    }

    @Test
    void tailLines_truncatesOlderLines() {
        assertEquals("line4\nline5", TerminalTool.tailLines("line1\nline2\nline3\nline4\nline5", 2));
    }

    @Test
    void tailLines_singleLine() {
        assertEquals("only line", TerminalTool.tailLines("only line", 1));
    }

    @Test
    void tailLines_emptyTrailingLine() {
        // "a\nb\n" splits to ["a", "b", ""] — 3 elements, keep last 2 = "b" + ""
        assertEquals("b\n", TerminalTool.tailLines("a\nb\n", 2));
    }

    // ── truncateForTitle ────────────────────────────────────

    @Test
    void truncateForTitle_shortCommand() {
        assertEquals("npm run build", TerminalTool.truncateForTitle("npm run build"));
    }

    @Test
    void truncateForTitle_exactly40Chars() {
        String cmd = "a".repeat(40);
        assertEquals(cmd, TerminalTool.truncateForTitle(cmd));
    }

    @Test
    void truncateForTitle_longCommand() {
        String cmd = "a".repeat(50);
        assertEquals("a".repeat(37) + "...", TerminalTool.truncateForTitle(cmd));
    }
}
