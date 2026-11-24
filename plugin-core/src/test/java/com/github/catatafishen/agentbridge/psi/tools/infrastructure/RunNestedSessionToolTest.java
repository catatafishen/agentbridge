package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.github.catatafishen.agentbridge.client.nested.NestedSessionJobs;
import com.github.catatafishen.agentbridge.client.nested.NestedSessionPolicy;
import com.github.catatafishen.agentbridge.psi.tools.McpRequestDeadline;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunNestedSessionToolTest {

    private final NestedSessionJobs jobs = new NestedSessionJobs(Executors::newCachedThreadPool);
    private final RunNestedSessionTool tool = new RunNestedSessionTool(null, jobs);
    private final ReadNestedSessionTool readTool = new ReadNestedSessionTool(null, jobs);

    @Test
    void doesNotHoldTheGlobalWriteSemaphoreWhileItRuns() {
        // The nested session's own file writes need that semaphore; holding it here would starve them.
        assertFalse(tool.needsWriteLock());
        assertFalse(readTool.needsWriteLock());
    }

    @Test
    void boundsItsOwnWaitSoTheGenericTimeoutDialogStaysOut() {
        assertTrue(tool.managesOwnTimeout());
        assertTrue(readTool.managesOwnTimeout());
    }

    @Test
    void aPromptIsRequired() throws InterruptedException {
        String result = tool.execute(new JsonObject());

        assertTrue(result.startsWith("Error"), result);
        assertTrue(result.contains("prompt"), result);
    }

    @Test
    void aBlankPromptIsNotAPrompt() throws InterruptedException {
        JsonObject args = new JsonObject();
        args.addProperty("prompt", "   ");

        String result = tool.execute(args);

        assertTrue(result.startsWith("Error"), result);
    }

    @Test
    void aNonPositiveTimeoutIsRejectedNotReplacedByTheDefault() throws InterruptedException {
        for (String name : new String[]{"timeout_seconds", "max_runtime_seconds"}) {
            for (int bad : new int[]{0, -1}) {
                JsonObject args = new JsonObject();
                args.addProperty("prompt", "do it");
                args.addProperty(name, bad);

                String result = tool.execute(args);

                assertTrue(result.startsWith("Error"), name + ": " + result);
                assertTrue(result.contains("positive"), name + ": " + result);
            }
        }
    }

    @Test
    void theDescriptionNamesTheLimitsAnAgentNeedsToKnow() {
        String description = tool.description();

        assertTrue(description.contains("unattended"), description);
        assertTrue(description.contains("Only one nested session runs at a time"), description);
        assertTrue(description.contains(String.valueOf(McpRequestDeadline.MAX_TIMEOUT_SECONDS)), description);
        assertTrue(description.contains(NestedSessionPolicy.READ_TOOL_ID), description);
        assertTrue(description.contains(String.valueOf(RunNestedSessionTool.MAX_RUNTIME_SECONDS)), description);
    }

    @Test
    void aNestedSessionCannotUseTheToolThatReadsNestedSessions() {
        assertTrue(NestedSessionPolicy.isForbidden(NestedSessionPolicy.newSessionKey(),
            NestedSessionPolicy.READ_TOOL_ID));
    }

    @Test
    void readingNeedsAnIdAndAKnownSession() throws InterruptedException {
        String missing = readTool.execute(new JsonObject());
        assertTrue(missing.startsWith("Error"), missing);
        assertTrue(missing.contains("session_id"), missing);

        JsonObject args = new JsonObject();
        args.addProperty("session_id", "abc12345");
        String unknown = readTool.execute(args);
        assertTrue(unknown.startsWith("Error"), unknown);
        assertTrue(unknown.contains("abc12345"), unknown);
    }

    @Test
    void readingRejectsANonPositiveWait() throws InterruptedException {
        JsonObject args = new JsonObject();
        args.addProperty("session_id", "abc12345");
        args.addProperty("wait_seconds", 0);

        String result = readTool.execute(args);

        assertTrue(result.startsWith("Error"), result);
        assertTrue(result.contains("positive"), result);
    }

    @Test
    void anUnknownSessionReplyListsWhatIsRunning() {
        assertTrue(NestedSessionReply.unknownSession("x", List.of("a1", "b2")).contains("a1, b2"));
        assertTrue(NestedSessionReply.unknownSession("x", List.of()).contains("None is running"));
    }
}
