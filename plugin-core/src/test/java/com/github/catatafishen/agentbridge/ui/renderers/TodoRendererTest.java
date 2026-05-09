package com.github.catatafishen.agentbridge.ui.renderers;

import kotlin.text.MatchResult;
import kotlin.text.Regex;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests regex patterns in {@link TodoRenderer}.
 * Does not construct any Swing components.
 */
class TodoRendererTest {

    private final Regex checkboxLine = TodoRenderer.INSTANCE.getCHECKBOX_LINE();
    private final Regex headerLine = TodoRenderer.INSTANCE.getHEADER_LINE();

    // ── CHECKBOX_LINE ───────────────────────────────────────

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "- [x] Buy groceries|x|Buy groceries",
        "- [X] Done item|X|Done item",
        "- [ ] Pending item|' '|Pending item"
    })
    void checkboxLine_matchesCheckedAndUnchecked(String input, String expectedMark, String expectedText) {
        MatchResult m = checkboxLine.find(input, 0);
        assertNotNull(m);
        assertEquals(expectedMark, m.getGroupValues().get(1));
        assertEquals(expectedText, m.getGroupValues().get(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"- [] No space", "[x] no dash prefix"})
    void checkboxLine_doesNotMatchInvalid(String input) {
        assertNull(checkboxLine.find(input, 0));
    }

    // ── HEADER_LINE ─────────────────────────────────────────

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "# Top heading|Top heading",
        "## Section|Section",
        "### Sub-section|Sub-section",
        "#### Deep|Deep"
    })
    void headerLine_matchesValidHeaders(String input, String expectedText) {
        MatchResult m = headerLine.find(input, 0);
        assertNotNull(m);
        assertEquals(expectedText, m.getGroupValues().get(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"##### 5 levels", "No hash heading"})
    void headerLine_doesNotMatchInvalid(String input) {
        assertNull(headerLine.find(input, 0));
    }
}
