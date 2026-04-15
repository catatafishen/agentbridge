package com.github.catatafishen.agentbridge.memory.mining;

import com.github.catatafishen.agentbridge.ui.EntryData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ExchangeChunker} — Q+A pair extraction from EntryData.
 */
class ExchangeChunkerTest {

    // --- chunk() tests ---

    @Test
    void emptyList_returnsEmpty() {
        List<ExchangeChunker.Exchange> result = ExchangeChunker.chunk(List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void noPrompts_returnsEmpty() {
        List<EntryData> entries = List.of(
            new EntryData.Text("some text"),
            new EntryData.Text("more text")
        );

        List<ExchangeChunker.Exchange> result = ExchangeChunker.chunk(entries);
        assertTrue(result.isEmpty());
    }

    @Test
    void singleExchange_promptAndText() {
        List<EntryData> entries = List.of(
            new EntryData.Prompt("What is Kotlin?"),
            new EntryData.Text("Kotlin is a modern JVM language.")
        );

        List<ExchangeChunker.Exchange> result = ExchangeChunker.chunk(entries);
        assertEquals(1, result.size());
        assertEquals("What is Kotlin?", result.get(0).prompt());
        assertEquals("Kotlin is a modern JVM language.", result.get(0).response());
    }

    @Test
    void singleExchange_multipleTexts() {
        List<EntryData> entries = List.of(
            new EntryData.Prompt("Explain design patterns"),
            new EntryData.Text("Here are some patterns:"),
            new EntryData.Text("1. Singleton"),
            new EntryData.Text("2. Factory")
        );

        List<ExchangeChunker.Exchange> result = ExchangeChunker.chunk(entries);
        assertEquals(1, result.size());
        String response = result.get(0).response();
        assertTrue(response.contains("Here are some patterns:"));
        assertTrue(response.contains("1. Singleton"));
        assertTrue(response.contains("2. Factory"));
    }

    @Test
    void multipleExchanges() {
        List<EntryData> entries = List.of(
            new EntryData.Prompt("Q1"),
            new EntryData.Text("A1 part 1"),
            new EntryData.Text("A1 part 2"),
            new EntryData.Prompt("Q2"),
            new EntryData.Text("A2")
        );

        List<ExchangeChunker.Exchange> result = ExchangeChunker.chunk(entries);
        assertEquals(2, result.size());
        assertEquals("Q1", result.get(0).prompt());
        assertTrue(result.get(0).response().contains("A1 part 1"));
        assertTrue(result.get(0).response().contains("A1 part 2"));
        assertEquals("Q2", result.get(1).prompt());
        assertEquals("A2", result.get(1).response());
    }

    @Test
    void blankTextSkipped() {
        List<EntryData> entries = List.of(
            new EntryData.Prompt("Question"),
            new EntryData.Text("   "),
            new EntryData.Text(""),
            new EntryData.Text("Real answer")
        );

        List<ExchangeChunker.Exchange> result = ExchangeChunker.chunk(entries);
        assertEquals(1, result.size());
        assertEquals("Real answer", result.get(0).response());
    }

    @Test
    void textBeforeFirstPrompt_ignored() {
        List<EntryData> entries = List.of(
            new EntryData.Text("orphan text before any prompt"),
            new EntryData.Prompt("First real prompt"),
            new EntryData.Text("Response to prompt")
        );

        List<ExchangeChunker.Exchange> result = ExchangeChunker.chunk(entries);
        assertEquals(1, result.size());
        assertEquals("First real prompt", result.get(0).prompt());
        assertEquals("Response to prompt", result.get(0).response());
    }

    @Test
    void promptWithNoResponse_notIncluded() {
        List<EntryData> entries = List.of(
            new EntryData.Prompt("First prompt with no response"),
            new EntryData.Prompt("Second prompt"),
            new EntryData.Text("Response to second")
        );

        List<ExchangeChunker.Exchange> result = ExchangeChunker.chunk(entries);
        assertEquals(1, result.size());
        assertEquals("Second prompt", result.get(0).prompt());
        assertEquals("Response to second", result.get(0).response());
    }

    @Test
    void toolCallIgnoredForResponse() {
        List<EntryData> entries = List.of(
            new EntryData.Prompt("Fix the bug"),
            new EntryData.ToolCall("read_file"),
            new EntryData.Text("I found and fixed the issue."),
            new EntryData.ToolCall("write_file")
        );

        List<ExchangeChunker.Exchange> result = ExchangeChunker.chunk(entries);
        assertEquals(1, result.size());
        assertEquals("I found and fixed the issue.", result.get(0).response());
    }

    @Test
    void toolCall_withFilePath_addsEvidenceToResponse() {
        EntryData.ToolCall tc = new EntryData.ToolCall("read_file");
        tc.setFilePath("src/main/java/Foo.java");
        List<EntryData> entries = List.of(
            new EntryData.Prompt("Show me Foo"),
            tc,
            new EntryData.Text("Here is the file content.")
        );

        List<ExchangeChunker.Exchange> result = ExchangeChunker.chunk(entries);
        assertEquals(1, result.size());
        assertTrue(result.get(0).response().contains("src/main/java/Foo.java"),
            "Tool file path should appear in response: " + result.get(0).response());
    }

    @Test
    void toolCall_searchTool_includesFilePathOnly() {
        EntryData.ToolCall tc = new EntryData.ToolCall("search_text");
        tc.setResult("Found 3 matches:\nplugin-core/src/main/java/Bar.java:42 match");
        tc.setFilePath("plugin-core/src/main/java/Bar.java");
        List<EntryData> entries = List.of(
            new EntryData.Prompt("Find Bar"),
            tc,
            new EntryData.Text("Found it.")
        );

        List<ExchangeChunker.Exchange> result = ExchangeChunker.chunk(entries);
        assertEquals(1, result.size());
        assertTrue(result.get(0).response().contains("Bar.java"),
            "File path should appear in response: " + result.get(0).response());
        assertFalse(result.get(0).response().contains("Found 3 matches"),
            "Raw tool result text should NOT appear: " + result.get(0).response());
    }

    @Test
    void toolCall_nonSearchTool_excludesResult() {
        EntryData.ToolCall tc = new EntryData.ToolCall("write_file");
        tc.setResult("File written successfully to /tmp/test.txt");
        List<EntryData> entries = List.of(
            new EntryData.Prompt("Write the file"),
            tc,
            new EntryData.Text("Done.")
        );

        List<ExchangeChunker.Exchange> result = ExchangeChunker.chunk(entries);
        assertEquals(1, result.size());
        assertFalse(result.get(0).response().contains("written successfully"),
            "Non-search tool result should NOT appear: " + result.get(0).response());
    }

    // --- extractCommitHashes() tests ---

    @Test
    void extractCommitHashes_singleMatch() {
        EntryData.ToolCall tc = new EntryData.ToolCall("git_commit");
        tc.setResult("[main abc1234] fix: resolve null pointer");

        List<String> out = new ArrayList<>();
        ExchangeChunker.extractCommitHashes(tc, out);
        assertEquals(List.of("abc1234"), out);
    }

    @Test
    void extractCommitHashes_multipleMatches() {
        EntryData.ToolCall tc = new EntryData.ToolCall("git_commit");
        tc.setResult("[main aaa1111] feat: add feature\n[main bbb2222] fix: edge case");

        List<String> out = new ArrayList<>();
        ExchangeChunker.extractCommitHashes(tc, out);
        assertEquals(2, out.size());
        assertEquals("aaa1111", out.get(0));
        assertEquals("bbb2222", out.get(1));
    }

    @Test
    void extractCommitHashes_nullResult() {
        EntryData.ToolCall tc = new EntryData.ToolCall("read_file");
        // result is null by default

        List<String> out = new ArrayList<>();
        ExchangeChunker.extractCommitHashes(tc, out);
        assertTrue(out.isEmpty());
    }

    @Test
    void extractCommitHashes_noMatch() {
        EntryData.ToolCall tc = new EntryData.ToolCall("write_file");
        tc.setResult("Written: src/Main.java (500 chars)");

        List<String> out = new ArrayList<>();
        ExchangeChunker.extractCommitHashes(tc, out);
        assertTrue(out.isEmpty());
    }

    // --- Exchange.combinedText() ---

    @Test
    void combinedText_responseFirst() {
        ExchangeChunker.Exchange ex = new ExchangeChunker.Exchange(
            "my prompt", "my response", "", "", List.of());

        String combined = ex.combinedText();
        assertTrue(combined.startsWith("my response"),
            "Response should come first for embedding quality: " + combined);
        assertTrue(combined.contains("my prompt"),
            "Prompt should still be included: " + combined);
    }
}
