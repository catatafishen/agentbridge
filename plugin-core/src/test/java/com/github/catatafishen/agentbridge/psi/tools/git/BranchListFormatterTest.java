package com.github.catatafishen.agentbridge.psi.tools.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchListFormatterTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\n"})
    void emptyOrBlankInput_returnsNoBranches(String input) {
        assertEquals("(no branches)", BranchListFormatter.formatBranchTable(input));
    }

    @Test
    void singleCurrentBranch_formatsCorrectly() {
        String input = "* main|abc1234|2 hours ago|origin/main|[ahead 1]";
        String result = BranchListFormatter.formatBranchTable(input);

        assertTrue(result.startsWith("* main"));
        assertTrue(result.contains("abc1234"));
        assertTrue(result.contains("(2 hours ago)"));
        assertTrue(result.contains("-> origin/main"));
        assertTrue(result.contains("[ahead 1]"));
    }

    @Test
    void nonCurrentBranch_formatsWithSpacePrefix() {
        String input = "  feature/x|def5678|3 days ago|origin/feature/x|";
        String result = BranchListFormatter.formatBranchTable(input);

        assertTrue(result.startsWith("  feature/x"));
        assertTrue(result.contains("def5678"));
        assertTrue(result.contains("(3 days ago)"));
        assertTrue(result.contains("-> origin/feature/x"));
    }

    @Test
    void branchWithoutUpstream_omitsArrow() {
        String input = "  local-only|abc1234|5 minutes ago||";
        String result = BranchListFormatter.formatBranchTable(input);

        assertTrue(result.contains("local-only"));
        assertFalse(result.contains("->"));
    }

    @Test
    void branchWithoutTrackingInfo_omitsTrack() {
        String input = "  feature/y|abc1234|1 day ago|origin/feature/y|";
        String result = BranchListFormatter.formatBranchTable(input);

        assertTrue(result.contains("-> origin/feature/y"));
        assertFalse(result.contains("["));
    }

    @Test
    void multipleBranches_allFormatted() {
        String input = """
            * main|abc1234|2 hours ago|origin/main|[ahead 1]
              develop|def5678|1 day ago|origin/develop|
              feature/x|999aaaa|3 days ago||
            """.strip();
        String result = BranchListFormatter.formatBranchTable(input);

        String[] lines = result.split("\n");
        assertEquals(3, lines.length);
        assertTrue(lines[0].startsWith("* main"));
        assertTrue(lines[1].startsWith("  develop"));
        assertTrue(lines[2].startsWith("  feature/x"));
    }

    @Test
    void malformedLine_passedThrough() {
        String input = "some-garbage-line";
        String result = BranchListFormatter.formatBranchTable(input);
        // Lines shorter than 3 chars or with fewer than 3 pipe parts are passed through
        assertEquals("some-garbage-line", result);
    }

    @Test
    void trailingNewlines_stripped() {
        String input = "* main|abc|now|origin/main|\n";
        String result = BranchListFormatter.formatBranchTable(input);
        assertFalse(result.endsWith("\n"));
    }

    @Test
    void aheadAndBehind_trackShown() {
        String input = "* main|abc|now|origin/main|[ahead 2, behind 3]";
        String result = BranchListFormatter.formatBranchTable(input);
        assertTrue(result.contains("[ahead 2, behind 3]"));
    }
}
