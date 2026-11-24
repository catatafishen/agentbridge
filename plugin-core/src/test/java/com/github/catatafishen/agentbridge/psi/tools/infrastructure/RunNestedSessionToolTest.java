package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.github.catatafishen.agentbridge.psi.tools.McpRequestDeadline;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunNestedSessionToolTest {

    private final RunNestedSessionTool tool = new RunNestedSessionTool(null);

    @Test
    void doesNotHoldTheGlobalWriteSemaphoreWhileItRuns() {
        // The nested session's own file writes need that semaphore; holding it here would starve them.
        assertFalse(tool.needsWriteLock());
    }

    @Test
    void boundsItsOwnWaitSoTheGenericTimeoutDialogStaysOut() {
        assertTrue(tool.managesOwnTimeout());
    }

    @Test
    void aPromptIsRequired() {
        String result = tool.execute(new JsonObject());

        assertTrue(result.startsWith("Error"), result);
        assertTrue(result.contains("prompt"), result);
    }

    @Test
    void aBlankPromptIsNotAPrompt() {
        JsonObject args = new JsonObject();
        args.addProperty("prompt", "   ");

        String result = tool.execute(args);

        assertTrue(result.startsWith("Error"), result);
    }

    @Test
    void aNonPositiveTimeoutIsRejectedNotReplacedByTheDefault() {
        for (int bad : new int[]{0, -1}) {
            JsonObject args = new JsonObject();
            args.addProperty("prompt", "do it");
            args.addProperty("timeout_seconds", bad);

            String result = tool.execute(args);

            assertTrue(result.startsWith("Error"), result);
            assertTrue(result.contains("positive"), result);
        }
    }

    @Test
    void theDescriptionNamesTheLimitsAnAgentNeedsToKnow() {
        String description = tool.description();

        assertTrue(description.contains("unattended"), description);
        assertTrue(description.contains("Only one nested session runs at a time"), description);
        assertTrue(description.contains(String.valueOf(McpRequestDeadline.MAX_TIMEOUT_SECONDS)), description);
    }
}
