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
    void listFormatPrefixesRemoteRefsForRenderer() throws Exception {
        java.nio.file.Path repo = java.nio.file.Files.createTempDirectory("gbt");
        run(repo, "git", "init", "-q", "-b", "main");
        run(repo, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "c");
        run(repo, "git", "update-ref", "refs/remotes/origin/main", "HEAD");
        String[] toolArgs = GitBranchTool.listBranchesArgs(true);
        String[] cmd = new String[toolArgs.length + 1];
        cmd[0] = "git";
        System.arraycopy(toolArgs, 0, cmd, 1, toolArgs.length);
        String out = run(repo, cmd);
        assertTrue(out.contains("* main|"), out);
        assertTrue(out.contains("  remotes/origin/main|"), out);
    }

    private static String run(java.nio.file.Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), out);
        return out;
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
