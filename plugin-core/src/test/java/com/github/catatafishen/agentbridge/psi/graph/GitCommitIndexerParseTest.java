package com.github.catatafishen.agentbridge.psi.graph;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GitCommitIndexerParseTest {

    @Test
    void parsesCommitWithFiles() {
        String output = "\u001E" +
            "abc123def456\u001Fabc123d\u001FJohn Doe\u001Fjohn@example.com\u001F2026-06-01T10:00:00+03:00\u001Ffeat: add login\n" +
            "M\tsrc/Login.java\n" +
            "A\tsrc/Auth.java\n";

        List<GitCommitIndexer.CommitRecord> records = GitCommitIndexer.parseGitLog(output);

        assertEquals(1, records.size());
        GitCommitIndexer.CommitRecord r = records.getFirst();
        assertEquals("abc123def456", r.hash());
        assertEquals("abc123d", r.shortHash());
        assertEquals("John Doe", r.author());
        assertEquals("john@example.com", r.email());
        assertEquals("2026-06-01T10:00:00+03:00", r.timestamp());
        assertEquals("feat: add login", r.message());
        assertEquals(2, r.files().size());
        assertEquals("src/Login.java", r.files().get(0).path());
        assertEquals("M", r.files().get(0).changeType());
        assertEquals("src/Auth.java", r.files().get(1).path());
        assertEquals("A", r.files().get(1).changeType());
    }

    @Test
    void parsesRenamedFile() {
        String output = "\u001E" +
            "def456\u001Fdef456\u001FJane\u001Fjane@ex.com\u001F2026-06-02T12:00:00Z\u001Frefactor: rename\n" +
            "R100\told/File.java\tnew/File.java\n";

        List<GitCommitIndexer.CommitRecord> records = GitCommitIndexer.parseGitLog(output);

        assertEquals(1, records.size());
        GitCommitIndexer.CommitRecord r = records.getFirst();
        assertEquals(1, r.files().size());
        assertEquals("new/File.java", r.files().getFirst().path());
        assertEquals("R", r.files().getFirst().changeType());
    }

    @Test
    void parsesMultipleCommits() {
        String output = "\u001E" +
            "aaa\u001Faaa\u001FAlice\u001Fa@x.com\u001F2026-01-01T00:00:00Z\u001Ffirst\n" +
            "A\tfile1.java\n" +
            "\u001E" +
            "bbb\u001Fbbb\u001FBob\u001Fb@x.com\u001F2026-01-02T00:00:00Z\u001Fsecond\n" +
            "M\tfile2.java\n" +
            "D\tfile3.java\n";

        List<GitCommitIndexer.CommitRecord> records = GitCommitIndexer.parseGitLog(output);

        assertEquals(2, records.size());
        assertEquals("first", records.get(0).message());
        assertEquals(1, records.get(0).files().size());
        assertEquals("second", records.get(1).message());
        assertEquals(2, records.get(1).files().size());
    }

    @Test
    void handlesEmptyOutput() {
        List<GitCommitIndexer.CommitRecord> records = GitCommitIndexer.parseGitLog("");
        assertTrue(records.isEmpty());
    }

    @Test
    void handlesCommitWithNoFiles() {
        String output = "\u001E" +
            "ccc\u001Fccc\u001FCharlie\u001Fc@x.com\u001F2026-01-03T00:00:00Z\u001Fempty commit\n";

        List<GitCommitIndexer.CommitRecord> records = GitCommitIndexer.parseGitLog(output);

        assertEquals(1, records.size());
        assertEquals("empty commit", records.getFirst().message());
        assertTrue(records.getFirst().files().isEmpty());
    }
}
