package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AgentNudgeService} static utility methods.
 */
class AgentNudgeServiceTest {

    @Nested
    class MergeNudges {
        @Test
        void nullExistingReturnsNew() {
            assertEquals("new nudge", AgentNudgeService.mergeNudges(null, "new nudge"));
        }

        @Test
        void emptyExistingReturnsNew() {
            assertEquals("new nudge", AgentNudgeService.mergeNudges("", "new nudge"));
        }

        @Test
        void mergesWithSeparator() {
            String result = AgentNudgeService.mergeNudges("existing", "new");
            assertEquals("existing\n\nnew", result);
        }

        @Test
        void preservesMultipleMerges() {
            String first = AgentNudgeService.mergeNudges(null, "one");
            String second = AgentNudgeService.mergeNudges(first, "two");
            String third = AgentNudgeService.mergeNudges(second, "three");
            assertEquals("one\n\ntwo\n\nthree", third);
        }
    }

    @Nested
    class MessageQueue {
        @Test
        void clearMessageQueue_drainsAllQueuedMessages_inOrder() {
            AgentNudgeService service = new AgentNudgeService();
            service.enqueueMessage("first");
            service.enqueueMessage("second");

            List<String> drained = service.clearMessageQueue();

            assertEquals(List.of("first", "second"), drained);
            // Queue is now empty — a subsequent poll returns null.
            assertNull(service.getNextQueuedMessage());
        }

        @Test
        void clearMessageQueue_emptyQueue_returnsEmptyList() {
            AgentNudgeService service = new AgentNudgeService();
            assertTrue(service.clearMessageQueue().isEmpty());
        }

        @Test
        void clearMessageQueue_isIdempotent() {
            AgentNudgeService service = new AgentNudgeService();
            service.enqueueMessage("only");

            assertEquals(List.of("only"), service.clearMessageQueue());
            assertTrue(service.clearMessageQueue().isEmpty(), "second clear must drain nothing");
        }
    }

    @Nested
    class AppendNudgeToResult {
        @Test
        void nullNudgeReturnsResult() {
            assertEquals("result text", AgentNudgeService.appendNudgeToResult("result text", null));
        }

        @Test
        void appendsNudgeWithPrefix() {
            String result = AgentNudgeService.appendNudgeToResult("tool output", "do better");
            assertEquals("tool output\n\n[User nudge]: do better", result);
        }

        @Test
        void emptyResultStillAppends() {
            String result = AgentNudgeService.appendNudgeToResult("", "nudge");
            assertEquals("\n\n[User nudge]: nudge", result);
        }
    }
}
