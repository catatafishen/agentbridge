package com.github.catatafishen.agentbridge.psi.tools.navigation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure-unit tests for search_text's paging checks: values it cannot honour are refused, and a page
 * past the last match says so instead of reporting that nothing matched.
 */
@DisplayName("SearchTextTool paging")
class SearchTextToolPagingTest {

    @Test
    @DisplayName("max_results 0 is refused instead of searching nothing")
    void zeroMaxResultsRefused() {
        assertEquals("Error: max_results must be >= 1, got: 0", SearchTextTool.checkPaging(0, 0));
    }

    @Test
    @DisplayName("a negative offset is refused")
    void negativeOffsetRefused() {
        assertEquals("Error: offset must be >= 0, got: -5", SearchTextTool.checkPaging(10, -5));
    }

    @Test
    @DisplayName("default paging and the smallest valid values pass")
    void validPagingPasses() {
        assertNull(SearchTextTool.checkPaging(100, 0));
        assertNull(SearchTextTool.checkPaging(1, 0));
        assertNull(SearchTextTool.checkPaging(1, 7));
    }

    @Test
    @DisplayName("an offset past the last match reports how many matches there were")
    void offsetPastEndReportsCount() {
        assertEquals("No matches found for 'TODO' at offset 5 (3 matches in total; use an offset below 3)",
            SearchTextTool.emptyPageLine("TODO", 5, 3));
        assertEquals("No matches found for 'TODO' at offset 1 (1 match in total; use an offset below 1)",
            SearchTextTool.emptyPageLine("TODO", 1, 1));
    }

    @Test
    @DisplayName("comparison: no match at all still says no matches found")
    void noMatchesAtAll() {
        assertEquals("No matches found for 'TODO'", SearchTextTool.emptyPageLine("TODO", 0, 0));
        assertEquals("No matches found for 'TODO'", SearchTextTool.emptyPageLine("TODO", 5, 0));
    }
}
