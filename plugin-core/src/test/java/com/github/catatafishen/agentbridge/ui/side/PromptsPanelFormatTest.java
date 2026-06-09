package com.github.catatafishen.agentbridge.ui.side;

import com.github.catatafishen.agentbridge.bridge.EntryData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for pure formatting methods in {@link PromptsPanel}'s companion object.
 */
class PromptsPanelFormatTest {

    @Nested
    class FormatStats {

        private static EntryData.TurnStats stats(long durationMs, long inputTokens, long outputTokens,
                                                 int toolCallCount, String model) {
            return new EntryData.TurnStats("t1", durationMs, inputTokens, outputTokens, 0.0,
                toolCallCount, 0, 0, model, "", 0, 0, 0, 0.0, 0, 0, 0, "");
        }

        @Test
        @DisplayName("null stats and empty agent → empty")
        void nullStatsEmptyAgent() {
            assertEquals("", PromptsPanel.formatStats(null, ""));
        }

        @Test
        @DisplayName("null stats with agent name → just agent name")
        void nullStatsWithAgent() {
            assertEquals("copilot", PromptsPanel.formatStats(null, "copilot"));
        }

        @Test
        @DisplayName("stats with tool calls")
        void toolCalls() {
            var s = stats(0, 0, 0, 5, "");
            assertEquals("5 tools", PromptsPanel.formatStats(s, ""));
        }

        @Test
        @DisplayName("stats with tokens")
        void tokens() {
            var s = stats(0, 1000, 2000, 0, "");
            assertEquals("1000↑ 2000↓", PromptsPanel.formatStats(s, ""));
        }

        @Test
        @DisplayName("stats with short duration (< 60s)")
        void shortDuration() {
            var s = stats(5500, 0, 0, 0, "");
            assertEquals("5.5s", PromptsPanel.formatStats(s, ""));
        }

        @Test
        @DisplayName("stats with long duration (>= 60s)")
        void longDuration() {
            var s = stats(135000, 0, 0, 0, "");
            assertEquals("2m 15s", PromptsPanel.formatStats(s, ""));
        }

        @Test
        @DisplayName("stats with model name")
        void model() {
            var s = stats(0, 0, 0, 0, "gpt-4o");
            assertEquals("gpt-4o", PromptsPanel.formatStats(s, ""));
        }

        @Test
        @DisplayName("all fields combined with separator")
        void allFieldsCombined() {
            var s = stats(3000, 100, 200, 2, "claude");
            assertEquals("2 tools · 100↑ 200↓ · 3.0s · claude · agent-x",
                PromptsPanel.formatStats(s, "agent-x"));
        }

        @Test
        @DisplayName("zero tokens not shown")
        void zeroTokensOmitted() {
            var s = stats(1000, 0, 0, 1, "");
            assertEquals("1 tools · 1.0s", PromptsPanel.formatStats(s, ""));
        }
    }

    @Nested
    @DisplayName("formatCommits")
    class FormatCommits {

        @Test
        @DisplayName("empty list → empty string")
        void emptyList() {
            assertEquals("", PromptsPanel.Companion.formatCommits(Collections.emptyList()));
        }

        @Test
        @DisplayName("single commit → 'Commit: hash7'")
        void singleCommit() {
            assertEquals("Commit: abc1234", PromptsPanel.Companion.formatCommits(List.of("abc1234def890")));
        }

        @Test
        @DisplayName("multiple commits → 'N commits: h1, h2'")
        void multipleCommits() {
            String result = PromptsPanel.Companion.formatCommits(List.of("abc1234def", "xyz7890abc"));
            assertEquals("2 commits: abc1234, xyz7890", result);
        }

        @Test
        @DisplayName("short hash stays as-is")
        void shortHash() {
            assertEquals("Commit: abc", PromptsPanel.Companion.formatCommits(List.of("abc")));
        }

        @Test
        @DisplayName("exactly 7 char hash not truncated")
        void exactSevenChars() {
            assertEquals("Commit: abc1234", PromptsPanel.Companion.formatCommits(List.of("abc1234")));
        }
    }
}
