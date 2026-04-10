package com.github.catatafishen.agentbridge.memory.mining;

import com.github.catatafishen.agentbridge.ui.EntryData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ExchangeChunker} — Q+A pair extraction from EntryData.
 */
class ExchangeChunkerTest {

    @Test
    void singlePromptAndResponsePair() {
        List<EntryData> entries = List.of(
            new EntryData.Prompt("What is Java 21?", "2024-01-01T00:00:00Z"),
            new EntryData.Text("Java 21 is the latest LTS release.")
        );

        List<ExchangeChunker.Exchange> exchanges = ExchangeChunker.chunk(entries);
        assertEquals(1, exchanges.size());

        ExchangeChunker.Exchange ex = exchanges.get(0);
        assertEquals("What is Java 21?", ex.prompt());
        assertEquals("Java 21 is the latest LTS release.", ex.response());
        assertEquals("2024-01-01T00:00:00Z", ex.timestamp());
    }

    @Test
    void multipleResponsesAreConcatenated() {
        List<EntryData> entries = List.of(
            new EntryData.Prompt("Tell me about patterns"),
            new EntryData.Text("Here are some patterns:"),
            new EntryData.Text("1. Singleton\n2. Factory\n3. Observer")
        );

        List<ExchangeChunker.Exchange> exchanges = ExchangeChunker.chunk(entries);
        assertEquals(1, exchanges.size());
        assertTrue(exchanges.get(0).response().contains("Singleton"));
        assertTrue(exchanges.get(0).response().contains("Observer"));
    }

    @Test
    void multipleExchanges() {
        List<EntryData> entries = List.of(
            new EntryData.Prompt("Question 1"),
            new EntryData.Text("Answer 1"),
            new EntryData.Prompt("Question 2"),
            new EntryData.Text("Answer 2"),
            new EntryData.Prompt("Question 3"),
            new EntryData.Text("Answer 3")
        );

        List<ExchangeChunker.Exchange> exchanges = ExchangeChunker.chunk(entries);
        assertEquals(3, exchanges.size());
        assertEquals("Question 1", exchanges.get(0).prompt());
        assertEquals("Question 2", exchanges.get(1).prompt());
        assertEquals("Question 3", exchanges.get(2).prompt());
    }

    @Test
    void toolCallsAreSkippedFromResponseText() {
        List<EntryData> entries = List.of(
            new EntryData.Prompt("Fix the bug"),
            new EntryData.ToolCall("read_file"),
            new EntryData.Text("I found the issue and fixed it."),
            new EntryData.ToolCall("write_file")
        );

        List<ExchangeChunker.Exchange> exchanges = ExchangeChunker.chunk(entries);
        assertEquals(1, exchanges.size());
        assertEquals("I found the issue and fixed it.", exchanges.get(0).response());
    }

    @Test
    void thinkingEntriesAreSkipped() {
        List<EntryData> entries = List.of(
            new EntryData.Prompt("Explain"),
            new EntryData.Thinking("Let me think..."),
            new EntryData.Text("The answer is 42.")
        );

        List<ExchangeChunker.Exchange> exchanges = ExchangeChunker.chunk(entries);
        assertEquals(1, exchanges.size());
        assertEquals("The answer is 42.", exchanges.get(0).response());
    }

    @Test
    void promptWithNoResponseIsSkipped() {
        List<EntryData> entries = List.of(
            new EntryData.Prompt("Hello"),
            new EntryData.Prompt("Another prompt"),
            new EntryData.Text("Response to second prompt")
        );

        List<ExchangeChunker.Exchange> exchanges = ExchangeChunker.chunk(entries);
        assertEquals(1, exchanges.size());
        assertEquals("Another prompt", exchanges.get(0).prompt());
    }

    @Test
    void emptyEntryListReturnsEmpty() {
        List<ExchangeChunker.Exchange> exchanges = ExchangeChunker.chunk(List.of());
        assertTrue(exchanges.isEmpty());
    }

    @Test
    void blankResponseTextIsSkipped() {
        List<EntryData> entries = List.of(
            new EntryData.Prompt("Question"),
            new EntryData.Text("   "),
            new EntryData.Text("Real answer")
        );

        List<ExchangeChunker.Exchange> exchanges = ExchangeChunker.chunk(entries);
        assertEquals(1, exchanges.size());
        assertEquals("Real answer", exchanges.get(0).response());
    }

    @Test
    void combinedTextContainsBothPromptAndResponse() {
        ExchangeChunker.Exchange ex = new ExchangeChunker.Exchange(
            "my prompt", "my response", "", "", List.of());
        String combined = ex.combinedText();
        assertTrue(combined.contains("my prompt"));
        assertTrue(combined.contains("my response"));
    }

    @Test
    void noResponseEntriesAtAllReturnsEmpty() {
        List<EntryData> entries = List.of(
            new EntryData.Prompt("Just a prompt")
        );

        List<ExchangeChunker.Exchange> exchanges = ExchangeChunker.chunk(entries);
        assertTrue(exchanges.isEmpty());
    }

    // --- Traceability: entryId ---

    @Test
    void promptEntryIdIsCaptured() {
        EntryData.Prompt prompt = new EntryData.Prompt("Question", "2024-01-01T00:00:00Z");
        List<EntryData> entries = List.of(prompt, new EntryData.Text("Answer"));

        List<ExchangeChunker.Exchange> exchanges = ExchangeChunker.chunk(entries);
        assertEquals(1, exchanges.size());
        assertEquals(prompt.getEntryId(), exchanges.get(0).promptEntryId());
        assertFalse(exchanges.get(0).promptEntryId().isEmpty());
    }

    @Test
    void multipleExchangesPreserveTheirOwnEntryIds() {
        EntryData.Prompt p1 = new EntryData.Prompt("Q1");
        EntryData.Prompt p2 = new EntryData.Prompt("Q2");
        List<EntryData> entries = List.of(
            p1, new EntryData.Text("A1"),
            p2, new EntryData.Text("A2")
        );

        List<ExchangeChunker.Exchange> exchanges = ExchangeChunker.chunk(entries);
        assertEquals(2, exchanges.size());
        assertEquals(p1.getEntryId(), exchanges.get(0).promptEntryId());
        assertEquals(p2.getEntryId(), exchanges.get(1).promptEntryId());
        assertNotEquals(exchanges.get(0).promptEntryId(), exchanges.get(1).promptEntryId());
    }

    // --- Traceability: git commit hashes ---

    @Test
    void gitCommitHashExtractedFromToolCallResult() {
        EntryData.ToolCall commitTc = new EntryData.ToolCall("git_commit");
        commitTc.setResult("[fix/my-branch abc1234] fix: resolve null pointer");

        List<EntryData> entries = List.of(
            new EntryData.Prompt("Fix the NPE"),
            commitTc,
            new EntryData.Text("Done.")
        );

        List<ExchangeChunker.Exchange> exchanges = ExchangeChunker.chunk(entries);
        assertEquals(1, exchanges.size());
        assertEquals(List.of("abc1234"), exchanges.get(0).commitHashes());
    }

    @Test
    void multipleCommitsInSameExchange() {
        EntryData.ToolCall tc1 = new EntryData.ToolCall("git_commit");
        tc1.setResult("[main aaa1111] feat: add feature A");
        EntryData.ToolCall tc2 = new EntryData.ToolCall("git_commit");
        tc2.setResult("[main bbb2222] fix: fix feature A edge case");

        List<EntryData> entries = List.of(
            new EntryData.Prompt("Add feature and fix edge case"),
            tc1, tc2,
            new EntryData.Text("Both done.")
        );

        List<ExchangeChunker.Exchange> exchanges = ExchangeChunker.chunk(entries);
        assertEquals(List.of("aaa1111", "bbb2222"), exchanges.get(0).commitHashes());
    }

    @Test
    void commitHashesResetBetweenExchanges() {
        EntryData.ToolCall tc = new EntryData.ToolCall("git_commit");
        tc.setResult("[main abc1234] fix: first");

        List<EntryData> entries = List.of(
            new EntryData.Prompt("First turn"),
            tc,
            new EntryData.Text("Done first."),
            new EntryData.Prompt("Second turn, no commits"),
            new EntryData.Text("Done second.")
        );

        List<ExchangeChunker.Exchange> exchanges = ExchangeChunker.chunk(entries);
        assertEquals(2, exchanges.size());
        assertEquals(List.of("abc1234"), exchanges.get(0).commitHashes());
        assertTrue(exchanges.get(1).commitHashes().isEmpty());
    }

    @Test
    void toolCallWithNullResultDoesNotCrash() {
        EntryData.ToolCall tc = new EntryData.ToolCall("read_file");
        // result is null by default

        List<EntryData> entries = List.of(
            new EntryData.Prompt("Read a file"),
            tc,
            new EntryData.Text("File content.")
        );

        List<ExchangeChunker.Exchange> exchanges = ExchangeChunker.chunk(entries);
        assertEquals(1, exchanges.size());
        assertTrue(exchanges.get(0).commitHashes().isEmpty());
    }

    @Test
    void toolCallWithNoGitOutputHasNoHashes() {
        EntryData.ToolCall tc = new EntryData.ToolCall("write_file");
        tc.setResult("Written: src/Main.java (500 chars)");

        List<EntryData> entries = List.of(
            new EntryData.Prompt("Write a file"),
            tc,
            new EntryData.Text("Done.")
        );

        List<ExchangeChunker.Exchange> exchanges = ExchangeChunker.chunk(entries);
        assertTrue(exchanges.get(0).commitHashes().isEmpty());
    }

    @Test
    void extractCommitHashesHandlesFullSha() {
        EntryData.ToolCall tc = new EntryData.ToolCall("git_commit");
        tc.setResult("[main 0123456789abcdef0123456789abcdef01234567] full sha");

        List<String> out = new ArrayList<>();
        ExchangeChunker.extractCommitHashes(tc, out);
        assertEquals(1, out.size());
        assertEquals("0123456789abcdef0123456789abcdef01234567", out.get(0));
    }

    @Test
    void commitHashesAreUnmodifiableInExchange() {
        EntryData.ToolCall tc = new EntryData.ToolCall("git_commit");
        tc.setResult("[main abc1234] commit");

        List<EntryData> entries = List.of(
            new EntryData.Prompt("Commit"),
            tc,
            new EntryData.Text("Done.")
        );

        List<ExchangeChunker.Exchange> exchanges = ExchangeChunker.chunk(entries);
        assertThrows(UnsupportedOperationException.class,
            () -> exchanges.get(0).commitHashes().add("should-fail"));
    }
}
