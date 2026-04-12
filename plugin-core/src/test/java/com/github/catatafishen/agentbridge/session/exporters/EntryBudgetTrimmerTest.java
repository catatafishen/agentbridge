package com.github.catatafishen.agentbridge.session.exporters;

import com.github.catatafishen.agentbridge.ui.EntryData;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EntryBudgetTrimmer}.
 */
class EntryBudgetTrimmerTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private static EntryData.Prompt prompt(String text) {
        return new EntryData.Prompt(text);
    }

    private static EntryData.Text text(String raw) {
        return new EntryData.Text(raw);
    }

    private static EntryData.ToolCall toolCall(String title, String arguments, String result) {
        return new EntryData.ToolCall(title, arguments, "other", result);
    }

    private static EntryData.Thinking thinking(String raw) {
        return new EntryData.Thinking(raw);
    }

    // ── countEntryChars ───────────────────────────────────────────────────────

    @Nested
    class CountEntryChars {

        @Test
        void promptCountsTextLength() {
            assertEquals(5, EntryBudgetTrimmer.countEntryChars(prompt("hello")));
        }

        @Test
        void promptEmptyTextReturnsZero() {
            assertEquals(0, EntryBudgetTrimmer.countEntryChars(prompt("")));
        }

        @Test
        void textCountsRawLength() {
            assertEquals(11, EntryBudgetTrimmer.countEntryChars(text("hello world")));
        }

        @Test
        void textEmptyRawReturnsZero() {
            assertEquals(0, EntryBudgetTrimmer.countEntryChars(text("")));
        }

        @Test
        void toolCallCountsBothArgumentsAndResult() {
            // arguments(3) + result(4) = 7
            assertEquals(7, EntryBudgetTrimmer.countEntryChars(toolCall("t", "abc", "defg")));
        }

        @Test
        void toolCallWithNullArgumentsCountsOnlyResult() {
            assertEquals(5, EntryBudgetTrimmer.countEntryChars(toolCall("t", null, "12345")));
        }

        @Test
        void toolCallWithNullResultCountsOnlyArguments() {
            assertEquals(3, EntryBudgetTrimmer.countEntryChars(toolCall("t", "abc", null)));
        }

        @Test
        void toolCallWithBothNullReturnsZero() {
            assertEquals(0, EntryBudgetTrimmer.countEntryChars(toolCall("t", null, null)));
        }

        @Test
        void thinkingReturnsZeroViaDefaultBranch() {
            // Thinking is not Prompt, Text, or ToolCall — falls into default → 0
            assertEquals(0, EntryBudgetTrimmer.countEntryChars(thinking("some thoughts")));
        }

        @Test
        void turnStatsReturnsZeroViaDefaultBranch() {
            EntryData.TurnStats stats = new EntryData.TurnStats("turn1");
            assertEquals(0, EntryBudgetTrimmer.countEntryChars(stats));
        }

        @Test
        void statusReturnsZeroViaDefaultBranch() {
            EntryData.Status status = new EntryData.Status("✅", "done");
            assertEquals(0, EntryBudgetTrimmer.countEntryChars(status));
        }

        @Test
        void contextFilesReturnsZeroViaDefaultBranch() {
            EntryData.ContextFiles cf = new EntryData.ContextFiles(List.of());
            assertEquals(0, EntryBudgetTrimmer.countEntryChars(cf));
        }
    }

    // ── countTotalChars ───────────────────────────────────────────────────────

    @Nested
    class CountTotalChars {

        @Test
        void emptyListReturnsZero() {
            assertEquals(0, EntryBudgetTrimmer.countTotalChars(List.of()));
        }

        @Test
        void singleEntryReturnsThatEntrysCount() {
            assertEquals(5, EntryBudgetTrimmer.countTotalChars(List.of(prompt("hello"))));
        }

        @Test
        void multipleEntriesSumsAllCounts() {
            List<EntryData> entries = List.of(
                prompt("hi"),      // 2
                text("world"),     // 5
                toolCall("t", "ab", "cde") // 2+3=5
            );
            assertEquals(12, EntryBudgetTrimmer.countTotalChars(entries));
        }

        @Test
        void entriesWithZeroCountDontAffectSum() {
            List<EntryData> entries = List.of(
                prompt("abc"),    // 3
                thinking("xyz"), // 0 (default branch)
                text("de")       // 2
            );
            assertEquals(5, EntryBudgetTrimmer.countTotalChars(entries));
        }
    }

    // ── findSecondPromptIndex ─────────────────────────────────────────────────

    @Nested
    class FindSecondPromptIndex {

        @Test
        void emptyListReturnsMinusOne() {
            assertEquals(-1, EntryBudgetTrimmer.findSecondPromptIndex(List.of()));
        }

        @Test
        void noPromptsReturnsMinusOne() {
            List<EntryData> entries = List.of(text("a"), text("b"));
            assertEquals(-1, EntryBudgetTrimmer.findSecondPromptIndex(entries));
        }

        @Test
        void singlePromptReturnsMinusOne() {
            List<EntryData> entries = List.of(prompt("first"), text("reply"));
            assertEquals(-1, EntryBudgetTrimmer.findSecondPromptIndex(entries));
        }

        @Test
        void twoPromptsReturnsIndexOfSecond() {
            List<EntryData> entries = List.of(
                prompt("first"),   // index 0
                text("reply"),     // index 1
                prompt("second")   // index 2
            );
            assertEquals(2, EntryBudgetTrimmer.findSecondPromptIndex(entries));
        }

        @Test
        void threePromptsReturnsIndexOfSecondNotThird() {
            List<EntryData> entries = List.of(
                prompt("first"),   // index 0
                prompt("second"),  // index 1
                prompt("third")    // index 2
            );
            assertEquals(1, EntryBudgetTrimmer.findSecondPromptIndex(entries));
        }

        @Test
        void consecutivePromptsAtStartReturnsOne() {
            List<EntryData> entries = List.of(
                prompt("a"),  // index 0
                prompt("b")   // index 1
            );
            assertEquals(1, EntryBudgetTrimmer.findSecondPromptIndex(entries));
        }

        @Test
        void nonPromptsBetweenPromptsAreSkipped() {
            List<EntryData> entries = List.of(
                prompt("p1"),      // index 0
                text("t1"),        // index 1
                text("t2"),        // index 2
                thinking("th"),    // index 3
                prompt("p2")       // index 4
            );
            assertEquals(4, EntryBudgetTrimmer.findSecondPromptIndex(entries));
        }
    }

    // ── dropOldestNonPrompt ───────────────────────────────────────────────────

    @Nested
    class DropOldestNonPrompt {

        @Test
        void allPromptsReturnsMinusOne() {
            List<EntryData> entries = new ArrayList<>(List.of(prompt("a"), prompt("b")));
            assertEquals(-1, EntryBudgetTrimmer.dropOldestNonPrompt(entries));
            assertEquals(2, entries.size(), "list unchanged when nothing could be dropped");
        }

        @Test
        void singlePromptReturnsMinusOne() {
            List<EntryData> entries = new ArrayList<>(List.of(prompt("only")));
            assertEquals(-1, EntryBudgetTrimmer.dropOldestNonPrompt(entries));
            assertEquals(1, entries.size());
        }

        @Test
        void emptyListReturnsMinusOne() {
            List<EntryData> entries = new ArrayList<>();
            assertEquals(-1, EntryBudgetTrimmer.dropOldestNonPrompt(entries));
        }

        @Test
        void dropsFirstNonPromptAfterInitialPrompt() {
            EntryData.Text t1 = text("12345");  // 5 chars, index 1
            EntryData.Text t2 = text("ab");      // 2 chars, index 2
            List<EntryData> entries = new ArrayList<>(List.of(prompt("p"), t1, t2));

            int freed = EntryBudgetTrimmer.dropOldestNonPrompt(entries);

            assertEquals(5, freed, "should return char count of dropped entry");
            assertEquals(2, entries.size(), "list should shrink by one");
            assertSame(t2, entries.get(1), "second text should now be at index 1");
        }

        @Test
        void skipsFirstEntryEvenIfNonPrompt() {
            // Index 0 is a Text entry — method starts from index 1
            EntryData.Text t0 = text("first");
            EntryData.Text t1 = text("second");
            List<EntryData> entries = new ArrayList<>(List.of(t0, t1));

            int freed = EntryBudgetTrimmer.dropOldestNonPrompt(entries);

            assertEquals(6, freed, "should drop 'second' (6 chars)");
            assertEquals(1, entries.size());
            assertSame(t0, entries.get(0), "first entry preserved");
        }

        @Test
        void dropsNonPromptSkippingPromptAtIndex1() {
            List<EntryData> entries = new ArrayList<>(List.of(
                prompt("p1"),
                prompt("p2"),
                text("target")  // index 2, first non-prompt after index 0
            ));

            int freed = EntryBudgetTrimmer.dropOldestNonPrompt(entries);

            assertEquals(6, freed);
            assertEquals(2, entries.size());
        }

        @Test
        void returnsCharCountOfDroppedToolCall() {
            EntryData.ToolCall tc = toolCall("tool", "args", "result");
            List<EntryData> entries = new ArrayList<>(List.of(prompt("p"), tc));

            int freed = EntryBudgetTrimmer.dropOldestNonPrompt(entries);

            // "args"(4) + "result"(6) = 10
            assertEquals(10, freed);
            assertEquals(1, entries.size());
        }
    }

    // ── trimEntriesToBudget ───────────────────────────────────────────────────

    @Nested
    class TrimEntriesToBudget {

        @Test
        void emptyListReturnedAsIs() {
            List<EntryData> result = EntryBudgetTrimmer.trimEntriesToBudget(List.of(), 100);
            assertTrue(result.isEmpty());
        }

        @Test
        void alreadyWithinBudgetReturnsSameInstance() {
            List<EntryData> entries = List.of(prompt("hi"), text("ok"));
            // total = 2 + 2 = 4
            List<EntryData> result = EntryBudgetTrimmer.trimEntriesToBudget(entries, 100);
            assertSame(entries, result, "should return same list if under budget");
        }

        @Test
        void exactlyAtBudgetReturnsSameInstance() {
            List<EntryData> entries = List.of(prompt("ab"), text("cd"));
            // total = 2 + 2 = 4
            List<EntryData> result = EntryBudgetTrimmer.trimEntriesToBudget(entries, 4);
            assertSame(entries, result);
        }

        @Test
        void zeroBudgetReturnsSameInstance() {
            List<EntryData> entries = List.of(prompt("hello"));
            List<EntryData> result = EntryBudgetTrimmer.trimEntriesToBudget(entries, 0);
            assertSame(entries, result, "maxTotalChars <= 0 returns original");
        }

        @Test
        void negativeBudgetReturnsSameInstance() {
            List<EntryData> entries = List.of(prompt("hello"));
            List<EntryData> result = EntryBudgetTrimmer.trimEntriesToBudget(entries, -5);
            assertSame(entries, result);
        }

        @Test
        void trimsByDroppingEntriesBeforeSecondPrompt() {
            // Two-turn conversation: prompt1 + reply1 + prompt2 + reply2
            List<EntryData> entries = List.of(
                prompt("first"),      // 5
                text("reply one"),    // 9
                prompt("second"),     // 6
                text("reply two")     // 9
            );
            // total = 29. Budget = 20.
            // findSecondPromptIndex → 2.  Drop entries [0,2) = "first"(5) + "reply one"(9) = 14 freed
            // remaining total = 29 - 14 = 15 ≤ 20 ✓
            List<EntryData> result = EntryBudgetTrimmer.trimEntriesToBudget(entries, 20);

            assertEquals(2, result.size());
            assertInstanceOf(EntryData.Prompt.class, result.get(0));
            assertEquals("second", ((EntryData.Prompt) result.get(0)).getText());
        }

        @Test
        void trimsMultipleTurnsUntilWithinBudget() {
            // Three-turn conversation: p1 + r1 + p2 + r2 + p3 + r3
            List<EntryData> entries = List.of(
                prompt("aaaa"),       // 4
                text("bbbb"),         // 4
                prompt("cccc"),       // 4
                text("dddd"),         // 4
                prompt("eeee"),       // 4
                text("ffff")          // 4
            );
            // total = 24. Budget = 10.
            // Pass 1: second prompt at index 2. Drop [0,2) = 8 freed → total 16 > 10
            // Pass 2: list now [p("cccc"), t("dddd"), p("eeee"), t("ffff")]
            //          second prompt at index 2. Drop [0,2) = 8 freed → total 8 ≤ 10 ✓
            List<EntryData> result = EntryBudgetTrimmer.trimEntriesToBudget(entries, 10);

            assertEquals(2, result.size());
            assertEquals("eeee", ((EntryData.Prompt) result.get(0)).getText());
            assertEquals("ffff", ((EntryData.Text) result.get(1)).getRaw());
        }

        @Test
        void fallsBackToDropOldestNonPromptWhenOnlyOnePrompt() {
            List<EntryData> entries = List.of(
                prompt("p"),      // 1
                text("aaa"),      // 3
                text("bbb")       // 3
            );
            // total = 7. Budget = 5.
            // findSecondPromptIndex → -1.
            // dropOldestNonPrompt drops text("aaa") at index 1, frees 3 → total 4 ≤ 5 ✓
            List<EntryData> result = EntryBudgetTrimmer.trimEntriesToBudget(entries, 5);

            assertEquals(2, result.size());
            assertInstanceOf(EntryData.Prompt.class, result.get(0));
            assertEquals("bbb", ((EntryData.Text) result.get(1)).getRaw());
        }

        @Test
        void stopsWhenNothingLeftToDrop() {
            // Only prompts — can't drop any more after second-prompt path exhausted
            List<EntryData> entries = List.of(prompt("hello"));
            // total = 5. Budget = 2.
            // findSecondPromptIndex → -1.
            // dropOldestNonPrompt → -1 (only one entry, and it starts from index 1). Break.
            List<EntryData> result = EntryBudgetTrimmer.trimEntriesToBudget(entries, 2);

            assertEquals(1, result.size(), "first prompt is always preserved");
            assertEquals("hello", ((EntryData.Prompt) result.get(0)).getText());
        }

        @Test
        void doesNotModifyOriginalList() {
            List<EntryData> original = new ArrayList<>(List.of(
                prompt("first"),
                text("reply"),
                prompt("second"),
                text("reply2")
            ));
            int originalSize = original.size();

            EntryBudgetTrimmer.trimEntriesToBudget(original, 10);

            assertEquals(originalSize, original.size(), "original list must not be modified");
        }

        @Test
        void mixedTrimStrategiesInSequence() {
            // First pass uses second-prompt dropping, second pass uses dropOldestNonPrompt
            List<EntryData> entries = List.of(
                prompt("aa"),        // 2
                text("bbb"),         // 3
                prompt("cc"),        // 2
                text("ddddd"),       // 5
                text("ee")           // 2
            );
            // total = 14. Budget = 4.
            // Pass 1: second prompt at index 2. Drop [0,2) = "aa"(2) + "bbb"(3) = 5.
            //          total = 9 > 4.
            // Pass 2: list = [p("cc"), t("ddddd"), t("ee")]. No second prompt.
            //          dropOldestNonPrompt: drop t("ddddd") at index 1, frees 5. total = 4 ≤ 4. ✓
            List<EntryData> result = EntryBudgetTrimmer.trimEntriesToBudget(entries, 4);

            assertEquals(2, result.size());
            assertEquals("cc", ((EntryData.Prompt) result.get(0)).getText());
            assertEquals("ee", ((EntryData.Text) result.get(1)).getRaw());
        }
    }

    // ── generateId ────────────────────────────────────────────────────────────

    @Nested
    class GenerateId {

        @Test
        void startsWithPrefixFollowedByUnderscore() {
            String id = EntryBudgetTrimmer.generateId("ses");
            assertTrue(id.startsWith("ses_"), "expected prefix 'ses_', got: " + id);
        }

        @Test
        void differentPrefixesProduceDifferentFormats() {
            String msgId = EntryBudgetTrimmer.generateId("msg");
            assertTrue(msgId.startsWith("msg_"));

            String turnId = EntryBudgetTrimmer.generateId("turn");
            assertTrue(turnId.startsWith("turn_"));
        }

        @Test
        void uuidPartIs32HexCharsWithNoDashes() {
            String id = EntryBudgetTrimmer.generateId("x");
            String uuidPart = id.substring("x_".length());
            assertEquals(32, uuidPart.length(), "UUID without dashes should be 32 chars");
            assertTrue(uuidPart.matches("[0-9a-f]+"), "UUID part should be lowercase hex: " + uuidPart);
        }

        @Test
        void twoCallsProduceDifferentIds() {
            String id1 = EntryBudgetTrimmer.generateId("ses");
            String id2 = EntryBudgetTrimmer.generateId("ses");
            assertNotEquals(id1, id2, "each call should produce a unique ID");
        }

        @Test
        void emptyPrefixProducesUnderscoreFollowedByUuid() {
            String id = EntryBudgetTrimmer.generateId("");
            assertTrue(id.startsWith("_"), "empty prefix should produce '_<uuid>', got: " + id);
            assertEquals(33, id.length(), "should be _ + 32 hex chars");
        }
    }

    // ── sha1Hex ───────────────────────────────────────────────────────────────

    @Nested
    class Sha1Hex {

        @Test
        void knownHashForHello() {
            // Well-known SHA-1 of "hello"
            assertEquals("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d",
                EntryBudgetTrimmer.sha1Hex("hello"));
        }

        @Test
        void emptyStringHash() {
            // SHA-1 of empty string
            assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709",
                EntryBudgetTrimmer.sha1Hex(""));
        }

        @Test
        void unicodeInputProducesValidHex() {
            String result = EntryBudgetTrimmer.sha1Hex("日本語テスト");
            assertEquals(40, result.length(), "SHA-1 hex should always be 40 chars");
            assertTrue(result.matches("[0-9a-f]+"), "result should be lowercase hex: " + result);
        }

        @Test
        void sameInputProducesSameHash() {
            String hash1 = EntryBudgetTrimmer.sha1Hex("deterministic");
            String hash2 = EntryBudgetTrimmer.sha1Hex("deterministic");
            assertEquals(hash1, hash2);
        }

        @Test
        void differentInputsProduceDifferentHashes() {
            String hash1 = EntryBudgetTrimmer.sha1Hex("alpha");
            String hash2 = EntryBudgetTrimmer.sha1Hex("bravo");
            assertNotEquals(hash1, hash2);
        }

        @Test
        void resultIsAlwaysLowercaseHex() {
            String result = EntryBudgetTrimmer.sha1Hex("ABC");
            assertTrue(result.matches("[0-9a-f]{40}"), "expected 40 lowercase hex chars: " + result);
        }
    }
}
