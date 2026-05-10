package com.github.catatafishen.agentbridge.psi.tools.terminal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ── Nested edge-case groups ─────────────────────────────

    @Nested
    @DisplayName("tailLines — edge cases")
    class TailLinesEdgeCases {

        @Test
        @DisplayName("maxLines=0 delegates to truncateOutput (returns full short text)")
        void zeroMaxLines_returnsFullText() {
            String text = "line1\nline2\nline3";
            assertEquals(text, TerminalTool.tailLines(text, 0));
        }

        @Test
        @DisplayName("negative maxLines behaves like zero")
        void negativeMaxLines_returnsFullText() {
            String text = "a\nb\nc\nd";
            assertEquals(text, TerminalTool.tailLines(text, -1));
        }

        @Test
        @DisplayName("empty string with positive maxLines")
        void emptyString_positive() {
            assertEquals("", TerminalTool.tailLines("", 5));
        }

        @Test
        @DisplayName("empty string with zero maxLines")
        void emptyString_zero() {
            assertEquals("", TerminalTool.tailLines("", 0));
        }

        @Test
        @DisplayName("only-newlines string keeps last N split elements")
        void onlyNewlines_keepsLastN() {
            // "\n\n\n" splits into ["", "", "", ""] — 4 elements; last 2 joined = "\n"
            assertEquals("\n", TerminalTool.tailLines("\n\n\n", 2));
        }

        @Test
        @DisplayName("maxLines=1 returns only the last line")
        void maxLinesOne_returnsLastLine() {
            assertEquals("c", TerminalTool.tailLines("a\nb\nc", 1));
        }

        @Test
        @DisplayName("trailing newline with maxLines=1 returns empty last element")
        void trailingNewline_maxLinesOne() {
            // "x\ny\n" → ["x","y",""] — last element is ""
            assertEquals("", TerminalTool.tailLines("x\ny\n", 1));
        }

        @Test
        @DisplayName("very large maxLines returns full text unchanged")
        void veryLargeMaxLines() {
            String text = "a\nb";
            assertEquals(text, TerminalTool.tailLines(text, Integer.MAX_VALUE));
        }
    }

    @Nested
    @DisplayName("resolveInputEscapes — edge cases")
    class ResolveInputEscapesEdgeCases {

        @Test
        @DisplayName("unrecognized brace sequence is preserved")
        void unknownBraceSequence_unchanged() {
            assertEquals("{hello}", TerminalTool.resolveInputEscapes("{hello}"));
        }

        @Test
        @DisplayName("replacement is case-sensitive")
        void caseSensitive_upperNotRecognized() {
            assertEquals("{ENTER}", TerminalTool.resolveInputEscapes("{ENTER}"));
            assertEquals("{Tab}", TerminalTool.resolveInputEscapes("{Tab}"));
            assertEquals("{Ctrl-C}", TerminalTool.resolveInputEscapes("{Ctrl-C}"));
        }

        @Test
        @DisplayName("three different escape types combined")
        void multipleDifferentEscapes() {
            assertEquals("\u0003\r\t",
                TerminalTool.resolveInputEscapes("{ctrl-c}{enter}{tab}"));
        }

        @Test
        @DisplayName("partial opening brace at end of string")
        void partialBrace_unchanged() {
            assertEquals("{ent", TerminalTool.resolveInputEscapes("{ent"));
        }

        @Test
        @DisplayName("same escape repeated three times")
        void repeatedSameEscape() {
            assertEquals("\r\r\r",
                TerminalTool.resolveInputEscapes("{enter}{enter}{enter}"));
        }

        @Test
        @DisplayName("escape embedded between text fragments")
        void escapeInMiddle() {
            assertEquals("before\tafter",
                TerminalTool.resolveInputEscapes("before{tab}after"));
        }

        @Test
        @DisplayName("all four control-key escapes in sequence")
        void allControlKeys() {
            assertEquals("\u0003\u0004\u001A\u001B",
                TerminalTool.resolveInputEscapes("{ctrl-c}{ctrl-d}{ctrl-z}{escape}"));
        }

        @Test
        @DisplayName("all four arrow-key escapes in sequence")
        void allArrowKeys() {
            assertEquals("\u001B[A\u001B[B\u001B[D\u001B[C",
                TerminalTool.resolveInputEscapes("{up}{down}{left}{right}"));
        }

        @Test
        @DisplayName("backslash-n and brace escapes mixed in one string")
        void backslashAndBraceMixed() {
            assertEquals("hello\n\rworld\t",
                TerminalTool.resolveInputEscapes("hello\\n{enter}world\\t"));
        }

        @Test
        @DisplayName("backslash followed by letter other than n or t is preserved")
        void literalBackslash_notNorT() {
            assertEquals("\\r", TerminalTool.resolveInputEscapes("\\r"));
            assertEquals("\\0", TerminalTool.resolveInputEscapes("\\0"));
        }
    }

    @Nested
    @DisplayName("describeInput — edge cases")
    class DescribeInputEdgeCases {

        @Test
        @DisplayName("both raw and resolved empty — plain format")
        void emptyStrings() {
            assertEquals("''", TerminalTool.describeInput("", ""));
        }

        @Test
        @DisplayName("brace without recognized escape triggers char-count format")
        void nonEscapeBrace_showsCharCount() {
            assertEquals("'{hello}' (7 chars)",
                TerminalTool.describeInput("{hello}", "{hello}"));
        }

        @Test
        @DisplayName("backslash without n/t triggers char-count format")
        void backslashOther_showsCharCount() {
            // Java "a\\rb" is the 4-char string a\rb — contains \, so char-count format
            assertEquals("'a\\rb' (3 chars)",
                TerminalTool.describeInput("a\\rb", "arb"));
        }

        @Test
        @DisplayName("both brace and backslash in same raw string")
        void braceAndBackslash() {
            assertEquals("'{enter}\\n' (2 chars)",
                TerminalTool.describeInput("{enter}\\n", "\r\n"));
        }

        @Test
        @DisplayName("whitespace only, no special chars — plain format")
        void whitespaceOnly_plainFormat() {
            assertEquals("'   '", TerminalTool.describeInput("   ", "   "));
        }

        @Test
        @DisplayName("resolved much shorter than raw (control sequence)")
        void resolvedMuchShorter() {
            assertEquals("'{ctrl-c}' (1 chars)",
                TerminalTool.describeInput("{ctrl-c}", "\u0003"));
        }

        @Test
        @DisplayName("raw and resolved identical, no special chars")
        void identicalRawAndResolved() {
            assertEquals("'ls -la'", TerminalTool.describeInput("ls -la", "ls -la"));
        }
    }

    @Nested
    @DisplayName("truncateForTitle — edge cases")
    class TruncateForTitleEdgeCases {

        @Test
        @DisplayName("empty string stays empty")
        void emptyString() {
            assertEquals("", TerminalTool.truncateForTitle(""));
        }

        @Test
        @DisplayName("41 chars is truncated (boundary + 1)")
        void exactly41_truncated() {
            String cmd = "a".repeat(41);
            assertEquals("a".repeat(37) + "...", TerminalTool.truncateForTitle(cmd));
        }

        @Test
        @DisplayName("39 chars is not truncated")
        void exactly39_unchanged() {
            String cmd = "a".repeat(39);
            assertEquals(cmd, TerminalTool.truncateForTitle(cmd));
        }

        @Test
        @DisplayName("single character is not truncated")
        void singleChar() {
            assertEquals("x", TerminalTool.truncateForTitle("x"));
        }

        @Test
        @DisplayName("truncated result is always exactly 40 characters")
        void truncatedResult_alwaysLength40() {
            String result = TerminalTool.truncateForTitle("a".repeat(100));
            assertEquals(40, result.length());
            assertTrue(result.endsWith("..."));
        }
    }
}
