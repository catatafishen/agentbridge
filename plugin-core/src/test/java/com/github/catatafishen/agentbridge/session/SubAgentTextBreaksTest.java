package com.github.catatafishen.agentbridge.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubAgentTextBreaksTest {

    private static final String MSG_1 = "msg_1";
    private static final String MSG_2 = "msg_2";
    private SubAgentTextBreaks breaks;

    @BeforeEach
    void setUp() {
        breaks = new SubAgentTextBreaks();
    }

    @Test
    void firstTextStartsABlockEvenAfterToolCalls() {
        breaks.chipAdded();

        assertTrue(breaks.startsNewBlock(MSG_1));
    }

    @Test
    void textWithoutToolCallsInBetweenGoesOnAlsoAcrossMessages() {
        breaks.startsNewBlock(MSG_1);

        assertFalse(breaks.startsNewBlock(MSG_1));
        assertFalse(breaks.startsNewBlock(MSG_2), "the paragraph break separates them inside the bubble");
        assertFalse(breaks.startsNewBlock(null));
    }

    @Test
    void textOfANewMessageAfterAToolCallStartsABlock() {
        breaks.startsNewBlock(MSG_1);
        breaks.chipAdded();

        assertTrue(breaks.startsNewBlock(MSG_2));
    }

    @Test
    void textOfTheSameMessageAfterAToolCallGoesOn() {
        breaks.startsNewBlock(MSG_1);
        breaks.chipAdded();

        assertFalse(breaks.startsNewBlock(MSG_1), "OpenCode sends tool calls in mid-sentence");
    }

    @Test
    void textWithoutMessageIdAfterAToolCallStartsABlock() {
        breaks.startsNewBlock(null);
        breaks.chipAdded();

        assertTrue(breaks.startsNewBlock(null));
    }

    @Test
    void aBlockOnlyStartsOncePerToolCall() {
        breaks.startsNewBlock(MSG_1);
        breaks.chipAdded();
        breaks.startsNewBlock(MSG_2);

        assertFalse(breaks.startsNewBlock(MSG_2));
    }

    @Test
    void blockStartDropsOnlyTheLeadingParagraphBreak() {
        assertEquals("Found it.\n\nMore.", SubAgentTextBreaks.blockStart("\n\nFound it.\n\nMore."));
        assertEquals("Found it.", SubAgentTextBreaks.blockStart("Found it."));
        assertEquals("\nFound it.", SubAgentTextBreaks.blockStart("\nFound it."));
    }
}
