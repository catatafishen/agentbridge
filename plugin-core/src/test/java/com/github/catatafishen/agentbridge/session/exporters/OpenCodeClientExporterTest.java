package com.github.catatafishen.agentbridge.session.exporters;

import com.github.catatafishen.agentbridge.ui.EntryData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for static utility methods in {@link OpenCodeClientExporter}.
 * Covers SHA-1 hashing, slug generation, ID generation, and budget trimming.
 */
class OpenCodeClientExporterTest {

    // ── sha1Hex ──────────────────────────────────────────────────────────────

    @Test
    void sha1Hex_producesCorrectHash() throws Exception {
        // Verify against known SHA-1 hash of "hello"
        String expected = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-1").digest("hello".getBytes(StandardCharsets.UTF_8))
        );
        assertEquals(expected, invokeSha1Hex("hello"));
    }

    @Test
    void sha1Hex_produces40CharHex() throws Exception {
        String hash = invokeSha1Hex("/home/user/project");
        assertEquals(40, hash.length());
        assertTrue(hash.matches("[0-9a-f]+"));
    }

    @Test
    void sha1Hex_isDeterministic() throws Exception {
        assertEquals(invokeSha1Hex("same input"), invokeSha1Hex("same input"));
    }

    @Test
    void sha1Hex_differentInputsDifferentHashes() throws Exception {
        assertNotEquals(invokeSha1Hex("path/a"), invokeSha1Hex("path/b"));
    }

    // ── generateSlug ─────────────────────────────────────────────────────────

    @Test
    void generateSlug_returnsAdjectiveNounFormat() throws Exception {
        String slug = invokeGenerateSlug();
        assertNotNull(slug);
        assertTrue(slug.contains("-"), "Slug should be adjective-noun: " + slug);
        assertEquals(2, slug.split("-").length, "Slug should have exactly two parts: " + slug);
    }

    @Test
    void generateSlug_containsOnlyLowercaseAndDash() throws Exception {
        for (int i = 0; i < 20; i++) {
            String slug = invokeGenerateSlug();
            assertTrue(slug.matches("[a-z]+-[a-z]+"), "Invalid slug format: " + slug);
        }
    }

    // ── generateId ───────────────────────────────────────────────────────────

    @Test
    void generateId_hasPrefixAndUnderscore() throws Exception {
        String id = invokeGenerateId("ses");
        assertTrue(id.startsWith("ses_"), "ID should start with prefix_: " + id);
    }

    @Test
    void generateId_isUnique() throws Exception {
        String id1 = invokeGenerateId("msg");
        String id2 = invokeGenerateId("msg");
        assertNotEquals(id1, id2);
    }

    @Test
    void generateId_differentPrefixes() throws Exception {
        String sesId = invokeGenerateId("ses");
        String msgId = invokeGenerateId("msg");
        assertTrue(sesId.startsWith("ses_"));
        assertTrue(msgId.startsWith("msg_"));
    }

    // ── trimEntriesToBudget ──────────────────────────────────────────────────

    @Test
    void trimEntriesToBudget_returnsAllWhenUnderBudget() throws Exception {
        List<EntryData> entries = List.of(
            new EntryData.Prompt("short prompt"),
            new EntryData.Text("short reply")
        );
        List<EntryData> result = invokeTrimEntriesToBudget(entries, 10000);
        assertEquals(2, result.size());
    }

    @Test
    void trimEntriesToBudget_returnsAllWhenBudgetIsZero() throws Exception {
        List<EntryData> entries = List.of(
            new EntryData.Prompt("hello"),
            new EntryData.Text("world")
        );
        // maxTotalChars <= 0 means no limit
        List<EntryData> result = invokeTrimEntriesToBudget(entries, 0);
        assertEquals(2, result.size());
    }

    @Test
    void trimEntriesToBudget_dropsOlderTurnsFirst() throws Exception {
        // Two turns: prompt1 + text1, prompt2 + text2
        // Each prompt = 100 chars, each text = 100 chars => total 400 chars
        String longText = "x".repeat(100);
        List<EntryData> entries = List.of(
            new EntryData.Prompt(longText),
            new EntryData.Text(longText),
            new EntryData.Prompt(longText),
            new EntryData.Text(longText)
        );
        // Budget of 250 should drop the first turn (200 chars)
        List<EntryData> result = invokeTrimEntriesToBudget(entries, 250);
        assertTrue(result.size() < 4, "Should have dropped older entries");
        // The first entry in result should be the second prompt
        assertInstanceOf(EntryData.Prompt.class, result.getFirst());
    }

    @Test
    void trimEntriesToBudget_singleTurnDropsNonPromptEntries() throws Exception {
        String longText = "x".repeat(200);
        List<EntryData> entries = List.of(
            new EntryData.Prompt("short prompt"),
            new EntryData.Text(longText),
            new EntryData.Text(longText)
        );
        // Budget of 250 should drop some text entries
        List<EntryData> result = invokeTrimEntriesToBudget(entries, 250);
        assertTrue(result.size() < 3);
        // Prompt should always be preserved
        assertInstanceOf(EntryData.Prompt.class, result.getFirst());
    }

    @Test
    void trimEntriesToBudget_handlesToolCallEntries() throws Exception {
        String longArgs = "a".repeat(200);
        List<EntryData> entries = List.of(
            new EntryData.Prompt("prompt"),
            new EntryData.ToolCall("tool1", longArgs),
            new EntryData.Prompt("prompt2"),
            new EntryData.Text("reply")
        );
        // ToolCall with 200 chars args contributes to budget
        List<EntryData> result = invokeTrimEntriesToBudget(entries, 100);
        // Should trim down to fit budget
        assertFalse(result.isEmpty());
    }

    @Test
    void trimEntriesToBudget_handlesEmptyList() throws Exception {
        List<EntryData> result = invokeTrimEntriesToBudget(List.of(), 100);
        assertTrue(result.isEmpty());
    }

    // ── Reflection helpers ───────────────────────────────────────────────────

    private static String invokeSha1Hex(String input) throws Exception {
        Method m = OpenCodeClientExporter.class.getDeclaredMethod("sha1Hex", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, input);
    }

    private static String invokeGenerateSlug() throws Exception {
        Method m = OpenCodeClientExporter.class.getDeclaredMethod("generateSlug");
        m.setAccessible(true);
        return (String) m.invoke(null);
    }

    private static String invokeGenerateId(String prefix) throws Exception {
        Method m = OpenCodeClientExporter.class.getDeclaredMethod("generateId", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, prefix);
    }

    @SuppressWarnings("unchecked")
    private static List<EntryData> invokeTrimEntriesToBudget(List<EntryData> entries, int maxChars) throws Exception {
        Method m = OpenCodeClientExporter.class.getDeclaredMethod("trimEntriesToBudget", List.class, int.class);
        m.setAccessible(true);
        return (List<EntryData>) m.invoke(null, entries, maxChars);
    }
}
