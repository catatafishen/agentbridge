package com.github.catatafishen.agentbridge.client.claude;

import com.github.catatafishen.agentbridge.model.ContentBlock;
import com.github.catatafishen.agentbridge.model.SessionUpdate;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeSubAgentRouterTest {

    private static final String AGENT_TOOL_USE = "toolu_agent";
    private static final String AGENT_ID = "a618a938f1087c72c";

    private final ClaudeSubAgentRouter router = new ClaudeSubAgentRouter();

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static JsonObject subAgentEvent() {
        return json("""
            {"type":"assistant","parent_tool_use_id":"%s","agent_id":"%s","task_description":"Count txt files"}
            """.formatted(AGENT_TOOL_USE, AGENT_ID));
    }

    private static JsonObject taskStarted(String taskType) {
        return json("""
            {"type":"system","subtype":"task_started","task_id":"%s","tool_use_id":"%s",
             "description":"Count txt files","task_type":"%s","is_backgrounded":true}
            """.formatted(AGENT_ID, AGENT_TOOL_USE, taskType));
    }

    private static JsonObject taskNotification(String status) {
        return json("""
            {"type":"system","subtype":"task_notification","task_id":"%s","tool_use_id":"%s",
             "status":"%s","summary":"It stopped."}
            """.formatted(AGENT_ID, AGENT_TOOL_USE, status));
    }

    private static String chunkText(SessionUpdate update) {
        SessionUpdate.AgentMessageChunk chunk = assertInstanceOf(SessionUpdate.AgentMessageChunk.class, update);
        return ((ContentBlock.Text) chunk.content().getFirst()).text();
    }

    @Nested
    class WhoWroteAnEvent {

        @Test
        void eventWithoutParentToolUseIsTheMainAgents() {
            assertNull(router.childId(json("{\"type\":\"assistant\"}")));
        }

        @Test
        void subAgentIsIdentifiedByItsAgentId() {
            assertEquals(AGENT_ID, router.childId(subAgentEvent()));
        }

        @Test
        void withoutAgentIdTheParentToolUseIdentifiesTheSubAgent() {
            assertEquals(AGENT_TOOL_USE, router.childId(json("{\"parent_tool_use_id\":\"" + AGENT_TOOL_USE + "\"}")));
        }

        @Test
        void mainAgentUpdatesGoStraightThrough() {
            List<SessionUpdate> seen = new ArrayList<>();
            router.target(null, seen::add).accept(router.text(null, "m1", "hi"));

            assertInstanceOf(SessionUpdate.AgentMessageChunk.class, seen.getFirst());
        }

        @Test
        void subAgentUpdatesAreWrappedWithItsIdAndDescription() {
            List<SessionUpdate> seen = new ArrayList<>();
            String childId = router.childId(subAgentEvent());
            router.target(childId, seen::add).accept(router.text(childId, "m1", "hi"));

            SessionUpdate.ChildSessionUpdate wrapped =
                assertInstanceOf(SessionUpdate.ChildSessionUpdate.class, seen.getFirst());
            assertEquals(AGENT_ID, wrapped.childSessionId());
            assertEquals("Count txt files", wrapped.title());
            assertEquals("hi", chunkText(wrapped.update()));
        }
    }

    @Nested
    class TextOfConsecutiveMessages {

        @Test
        void textOfANewMessageStartsANewParagraph() {
            router.text(null, "m1", "DONE");

            assertEquals("\n\nThe sub-agent finished.", chunkText(router.text(null, "m2", "The sub-agent finished.")));
        }

        @Test
        void blocksOfOneMessageAreNotSeparated() {
            router.text(null, "m1", "first");

            assertEquals("second", chunkText(router.text(null, "m1", "second")));
        }

        @Test
        void aToolCallInBetweenSeparatesTheTextAlready() {
            router.text(null, "m1", "before");
            router.toolCalled(null);

            assertEquals("after", chunkText(router.text(null, "m2", "after")));
        }

        @Test
        void eachSubAgentAndTheMainAgentAreSeparateStreams() {
            router.text(null, "m1", "main");

            assertEquals("sub", chunkText(router.text(AGENT_ID, "s1", "sub")));
            assertEquals("main again", chunkText(router.text(null, "m1", "main again")));
        }
    }

    @Nested
    class HandBack {

        @Test
        void handBackCarriesTheReport() {
            String report = router.handback(AGENT_ID, "toolu_hb", json("{\"message\":\"Found 1 file\"}"));

            assertEquals("Found 1 file", report);
            assertTrue(router.isHandbackResult("toolu_hb"));
        }

        @Test
        void handBackWithoutMessageHasNoReport() {
            assertNull(router.handback(AGENT_ID, "toolu_hb", new JsonObject()));
        }

        @Test
        void agentToolResultIsEmptyOnceTheSubAgentHandedItsReportBack() {
            router.childId(subAgentEvent());
            router.handback(AGENT_ID, "toolu_hb", json("{\"message\":\"Found 1 file\"}"));

            assertEquals("", router.toolResult(AGENT_TOOL_USE, "This agent's report was delivered to you as a message"));
        }

        @Test
        void otherToolResultsAreKept() {
            router.childId(subAgentEvent());

            assertEquals("probe.txt", router.toolResult("toolu_glob", "probe.txt"));
            assertEquals("The report", router.toolResult(AGENT_TOOL_USE, "The report"));
            assertFalse(router.isHandbackResult("toolu_glob"));
        }
    }

    @Nested
    class TaskEvents {

        @Test
        void startedSubAgentIsRunning() {
            SessionUpdate.ChildSessionStatus status = router.taskStatus(taskStarted("local_agent"));

            assertNotNull(status);
            assertEquals(AGENT_ID, status.childSessionId());
            assertEquals("Count txt files", status.title());
            assertSame(SessionUpdate.ChildSessionState.RUNNING, status.state());
        }

        @Test
        void tasksThatAreNotSubAgentsAreIgnored() {
            assertNull(router.taskStatus(taskStarted("local_bash")));
            assertNull(router.taskStatus(taskNotification("completed")));
        }

        @Test
        void completedSubAgentHasNoError() {
            router.taskStatus(taskStarted("local_agent"));

            SessionUpdate.ChildSessionStatus status = router.taskStatus(taskNotification("completed"));

            assertNotNull(status);
            assertSame(SessionUpdate.ChildSessionState.COMPLETED, status.state());
            assertNull(status.error());
        }

        @Test
        void killedSubAgentIsInterruptedWithTheSummary() {
            router.taskStatus(taskStarted("local_agent"));

            SessionUpdate.ChildSessionStatus status = router.taskStatus(taskNotification("killed"));

            assertNotNull(status);
            assertSame(SessionUpdate.ChildSessionState.INTERRUPTED, status.state());
            assertEquals("It stopped.", status.error());
        }

        @Test
        void unknownStatusFailsAndNamesTheStatus() {
            router.taskStatus(taskStarted("local_agent"));

            SessionUpdate.ChildSessionStatus status = router.taskStatus(taskNotification("exploded"));

            assertNotNull(status);
            assertSame(SessionUpdate.ChildSessionState.FAILED, status.state());
            assertTrue(status.error().contains("'exploded'"), status.error());
        }

        @Test
        void systemEventsOtherThanTaskEventsReportNothing() {
            assertNull(router.taskStatus(json("{\"type\":\"system\",\"subtype\":\"init\"}")));
            assertNull(router.taskStatus(json("{\"type\":\"system\",\"subtype\":\"task_progress\",\"task_id\":\"x\"}")));
        }
    }

    @Nested
    class EndOfStream {

        @Test
        void subAgentThatNeverReportedAnEndFails() {
            router.taskStatus(taskStarted("local_agent"));

            List<SessionUpdate.ChildSessionStatus> ended = router.unfinishedAtEndOfStream();

            assertEquals(1, ended.size());
            assertEquals(AGENT_ID, ended.getFirst().childSessionId());
            assertSame(SessionUpdate.ChildSessionState.FAILED, ended.getFirst().state());
            assertTrue(router.unfinishedAtEndOfStream().isEmpty(), "each sub-agent is reported once");
        }

        @Test
        void subAgentThatReportedAnEndIsNotReportedAgain() {
            router.taskStatus(taskStarted("local_agent"));
            router.taskStatus(taskNotification("completed"));

            assertTrue(router.unfinishedAtEndOfStream().isEmpty());
        }
    }

    @Nested
    class BackgroundLaunch {

        @Test
        void launchResultNamesTheSubAgent() {
            String result = "Async agent launched successfully. (This tool result is internal metadata.)\n"
                + "agentId: " + AGENT_ID + " (internal ID - do not mention to user.)\n"
                + "The agent is working in the background.";

            assertEquals(AGENT_ID, ClaudeSubAgentRouter.backgroundAgentId(result));
        }

        @Test
        void otherResultsAreNoLaunch() {
            assertNull(ClaudeSubAgentRouter.backgroundAgentId(null));
            assertNull(ClaudeSubAgentRouter.backgroundAgentId("Found 4 files. agentId: x"));
            assertNull(ClaudeSubAgentRouter.backgroundAgentId("Async agent launched successfully, but no id"));
        }
    }
}
