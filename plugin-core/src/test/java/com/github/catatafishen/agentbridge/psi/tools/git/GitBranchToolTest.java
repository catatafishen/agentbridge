package com.github.catatafishen.agentbridge.psi.tools.git;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitBranchToolTest {

    @Test
    void listAllPassesEachRefPatternAsSeparateArgument() {
        List<String> args = List.of(GitBranchTool.listBranchesArgs(true));
        assertEquals(List.of("refs/heads", "refs/remotes"), args.subList(args.size() - 2, args.size()));
        assertFalse(args.contains("refs/heads refs/remotes"));
    }

    @Test
    void listLocalOnlyUsesHeads() {
        List<String> args = List.of(GitBranchTool.listBranchesArgs(false));
        assertEquals("refs/heads", args.getLast());
        assertFalse(args.contains("refs/remotes"));
    }

    @Test
    void startPointNamesExplicitBase() {
        assertEquals("Started from: origin/master (abc1234)",
            GitBranchTool.formatStartPoint("origin/master", "abc1234", false));
    }

    @Test
    void startPointFlagsImplicitHead() {
        String line = GitBranchTool.formatStartPoint("local-directory-mock", "def5678", true);
        assertTrue(line.startsWith("Started from: local-directory-mock (def5678)"));
        assertTrue(line.contains("current HEAD"));
    }

    @Test
    void startPointSurfacesUnknownValues() {
        assertEquals("Started from: <unknown> (<unknown>)", GitBranchTool.formatStartPoint(null, " ", false));
    }
}
