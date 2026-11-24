package com.github.catatafishen.agentbridge.client;

import com.github.catatafishen.agentbridge.model.ContentBlock;
import com.github.catatafishen.agentbridge.model.SessionUpdate;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageBreaksTest {

    private static final String CHILD = "ses_child";

    private final MessageBreaks breaks = new MessageBreaks();

    private static SessionUpdate.AgentMessageChunk chunk(String messageId, String text) {
        return new SessionUpdate.AgentMessageChunk(List.of(new ContentBlock.Text(text)), messageId);
    }

    private static SessionUpdate toolCall() {
        return new SessionUpdate.ToolCall("call_1", "execute", null, null, null, null, null, null, null, null);
    }

    private static SessionUpdate.AgentThoughtChunk thought(String messageId, String text) {
        return new SessionUpdate.AgentThoughtChunk(List.of(new ContentBlock.Text(text)), messageId);
    }

    private String thoughtOf(SessionUpdate update) {
        return assertInstanceOf(SessionUpdate.AgentThoughtChunk.class, breaks.separate(update)).text();
    }

    private static SessionUpdate.ChildSessionUpdate fromChild(SessionUpdate inner) {
        return new SessionUpdate.ChildSessionUpdate(CHILD, "Count files", inner);
    }

    private String textOf(SessionUpdate update) {
        return assertInstanceOf(SessionUpdate.AgentMessageChunk.class, breaks.separate(update)).text();
    }

    @Nested
    class StartsNewMessage {

        @Test
        void theFirstMessageOfAStreamIsNotNew() {
            assertFalse(breaks.startsNewMessage(null, "msg_1"));
        }

        @Test
        void moreTextOfTheSameMessageIsNotNew() {
            breaks.startsNewMessage(null, "msg_1");
            assertFalse(breaks.startsNewMessage(null, "msg_1"));
        }

        @Test
        void anotherMessageRightAfterTextIsNew() {
            breaks.startsNewMessage(null, "msg_1");
            assertTrue(breaks.startsNewMessage(null, "msg_2"));
        }

        @Test
        void aToolCallInBetweenAlreadySeparatesTheMessages() {
            breaks.startsNewMessage(null, "msg_1");
            breaks.toolCalled(null);
            assertFalse(breaks.startsNewMessage(null, "msg_2"));
        }

        @Test
        void textWithoutAMessageIdIsNeverNewAndKeepsTheLastId() {
            breaks.startsNewMessage(null, "msg_1");
            assertFalse(breaks.startsNewMessage(null, null));
            assertFalse(breaks.startsNewMessage(null, "msg_1"));
        }

        @Test
        void streamsAreTrackedApart() {
            breaks.startsNewMessage(null, "msg_1");
            assertFalse(breaks.startsNewMessage(CHILD, "msg_2"));
            assertFalse(breaks.startsNewMessage(null, "msg_1"));
        }

        @Test
        void clearForgetsEveryStream() {
            breaks.startsNewMessage(null, "msg_1");
            breaks.clear();
            assertFalse(breaks.startsNewMessage(null, "msg_2"));
        }
    }

    @Nested
    class Separate {

        @Test
        void aNewMainAgentMessageGetsAParagraphBreakInFront() {
            breaks.separate(chunk("msg_1", "Both sub-agents are running."));
            assertEquals("\n\nSub-agent A reported ALPHA.", textOf(chunk("msg_2", "Sub-agent A reported ALPHA.")));
        }

        @Test
        void theBreakKeepsTheMessageId() {
            breaks.separate(chunk("msg_1", "First."));
            SessionUpdate separated = breaks.separate(chunk("msg_2", "Second."));
            assertEquals("msg_2", ((SessionUpdate.AgentMessageChunk) separated).messageId());
        }

        @Test
        void chunksOfOneMessageAreLeftAlone() {
            SessionUpdate first = chunk("msg_1", "Hello");
            SessionUpdate second = chunk("msg_1", " world");
            assertSame(first, breaks.separate(first));
            assertSame(second, breaks.separate(second));
        }

        @Test
        void textAfterAMainAgentToolCallGetsNoBreak() {
            breaks.separate(chunk("msg_1", "Running it now."));
            breaks.separate(toolCall());
            assertEquals("Done.", textOf(chunk("msg_2", "Done.")));
        }

        @Test
        void aThoughtInBetweenDoesNotSeparateTheMessages() {
            breaks.separate(chunk("msg_1", "Sub-agent A reported ALPHA."));
            breaks.separate(thought("msg_2:reasoning:0", "Sub-agent B reported."));
            assertEquals("\n\nSub-agent B reported BRAVO.", textOf(chunk("msg_2", "Sub-agent B reported BRAVO.")));
        }

        @Test
        void aThoughtOfANewMessageGetsAParagraphBreakInFront() {
            breaks.separate(thought("msg_1:reasoning:0", "Sub-agent A reported ALPHA again."));
            assertEquals("\n\nSub-agent B reported BRAVO.",
                thoughtOf(thought("msg_2:reasoning:0", "Sub-agent B reported BRAVO.")));
        }

        @Test
        void thoughtChunksOfOneMessageAreLeftAlone() {
            SessionUpdate first = thought("msg_1:reasoning:0", "Sub-agent A");
            SessionUpdate second = thought("msg_1:reasoning:0", " reported.");
            assertSame(first, breaks.separate(first));
            assertSame(second, breaks.separate(second));
        }

        @Test
        void textInBetweenDoesNotSeparateTheThoughts() {
            breaks.separate(thought("msg_1:reasoning:0", "A reported."));
            breaks.separate(chunk("msg_1", "Sub-agent A reported ALPHA."));
            assertEquals("\n\nB reported.", thoughtOf(thought("msg_2:reasoning:0", "B reported.")));
        }

        @Test
        void aThoughtAfterAToolCallGetsNoBreak() {
            breaks.separate(thought("msg_1:reasoning:0", "Run it."));
            breaks.separate(toolCall());
            assertEquals("It ran.", thoughtOf(thought("msg_2:reasoning:0", "It ran.")));
        }

        @Test
        void aSubAgentsNewMessageGetsABreakInsideItsOwnUpdate() {
            breaks.separate(fromChild(chunk("msg_1", "ALPHA")));
            SessionUpdate separated = breaks.separate(fromChild(chunk("msg_2", "BRAVO")));
            SessionUpdate.ChildSessionUpdate child = assertInstanceOf(SessionUpdate.ChildSessionUpdate.class, separated);
            assertEquals(CHILD, child.childSessionId());
            assertEquals("Count files", child.title());
            assertEquals("\n\nBRAVO", ((SessionUpdate.AgentMessageChunk) child.update()).text());
        }

        @Test
        void aSubAgentsToolCallDoesNotSeparateTheMainAgentsMessages() {
            breaks.separate(chunk("msg_1", "First."));
            breaks.separate(fromChild(toolCall()));
            assertEquals("\n\nSecond.", textOf(chunk("msg_2", "Second.")));
        }

        @Test
        void aSubAgentsTextDoesNotStartANewMainAgentMessage() {
            breaks.separate(chunk("msg_1", "First."));
            SessionUpdate childText = fromChild(chunk("msg_9", "ALPHA"));
            assertSame(childText, breaks.separate(childText));
            assertEquals(" more", textOf(chunk("msg_1", " more")));
        }

        @Test
        void otherUpdatesPassThrough() {
            breaks.separate(chunk("msg_1", "First."));
            SessionUpdate user = new SessionUpdate.UserMessageChunk(List.of(new ContentBlock.Text("Go on.")));
            assertSame(user, breaks.separate(user));
        }
    }
}
