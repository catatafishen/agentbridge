package com.github.catatafishen.agentbridge.memory.mining;

import com.github.catatafishen.agentbridge.ui.EntryData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
    void toolCallsAreSkipped() {
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
            "my prompt", "my response", "");
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
}
