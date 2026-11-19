package com.github.catatafishen.agentbridge.client.acp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link PostTurnWindow}: after a turn ends, the agent's messages stay visible while it
 * keeps working, including work that shows only as AgentBridge tool calls.
 */
class PostTurnWindowTest {

    private static final long QUIET = 120;
    private static final long NOW = 1_000_000;

    @Test
    @DisplayName("window closes after the full quiet period with no message and no tool call")
    void closesAfterQuietPeriod() {
        assertEquals(0, PostTurnWindow.nanosUntilNextCheck(NOW, NOW - QUIET, NOW - QUIET, false, QUIET));
    }

    @Test
    @DisplayName("a recent agent message keeps the window open for the rest of the quiet period")
    void recentAgentMessageKeepsWindowOpen() {
        assertEquals(QUIET - 30, PostTurnWindow.nanosUntilNextCheck(NOW, NOW - 30, NOW - 500, false, QUIET));
    }

    @Test
    @DisplayName("a recently finished tool call keeps the window open although the agent sent nothing")
    void recentToolCallKeepsWindowOpen() {
        assertEquals(QUIET - 10, PostTurnWindow.nanosUntilNextCheck(NOW, NOW - 500, NOW - 10, false, QUIET));
    }

    @Test
    @DisplayName("a running tool call keeps the window open however long the agent has been silent")
    void runningToolKeepsWindowOpen() {
        assertEquals(QUIET, PostTurnWindow.nanosUntilNextCheck(NOW, NOW - 10 * QUIET, NOW - 10 * QUIET, true, QUIET));
    }

    @Test
    @DisplayName("the more recent of agent message and tool call decides")
    void moreRecentActivityDecides() {
        assertEquals(QUIET - 5, PostTurnWindow.nanosUntilNextCheck(NOW, NOW - 5, NOW - 60, false, QUIET));
        assertEquals(QUIET - 5, PostTurnWindow.nanosUntilNextCheck(NOW, NOW - 60, NOW - 5, false, QUIET));
    }

    @Test
    @DisplayName("times on either side of a nanoTime overflow are compared correctly")
    void handlesNanoTimeOverflow() {
        long now = Long.MIN_VALUE + 10;
        long elevenBefore = Long.MAX_VALUE; // 11 nanos before now, across the wrap
        assertEquals(QUIET - 11, PostTurnWindow.nanosUntilNextCheck(now, elevenBefore, elevenBefore, false, QUIET));
    }
}
