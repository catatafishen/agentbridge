package com.github.catatafishen.agentbridge.psi.tools.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;

/**
 * Unit tests for {@link QueryCodeGraphTool#stripTrailingSemicolons(String)}, which replaced the
 * {@code replaceAll(";+$", "")} regex used before appending an injected {@code LIMIT} clause.
 */
class QueryCodeGraphToolStripSemicolonsTest {

    @Test
    void removesSingleTrailingSemicolon() {
        assertEquals("SELECT 1", QueryCodeGraphTool.stripTrailingSemicolons("SELECT 1;"));
    }

    @Test
    void removesRunOfTrailingSemicolons() {
        assertEquals("SELECT 1", QueryCodeGraphTool.stripTrailingSemicolons("SELECT 1;;;"));
    }

    @Test
    void leavesInnerSemicolonsAlone() {
        assertEquals("SELECT ';' AS s; SELECT 2", QueryCodeGraphTool.stripTrailingSemicolons("SELECT ';' AS s; SELECT 2;"));
    }

    @Test
    void leavesStatementWithoutTrailingSemicolonUnchanged() {
        assertEquals("SELECT 1", QueryCodeGraphTool.stripTrailingSemicolons("SELECT 1"));
    }

    @Test
    void emptyAndSemicolonOnlyInputsBecomeEmpty() {
        assertEquals("", QueryCodeGraphTool.stripTrailingSemicolons(""));
        assertEquals("", QueryCodeGraphTool.stripTrailingSemicolons(";;;"));
    }

    @Test
    void longSemicolonRunFollowedByTextStaysFast() {
        // ";+$" backtracks quadratically on this shape; the loop must be linear.
        String input = ";".repeat(200_000) + "x";
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertEquals(input, QueryCodeGraphTool.stripTrailingSemicolons(input));
        });
    }
}
