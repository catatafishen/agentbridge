package com.github.catatafishen.agentbridge.ui.renderers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskToolRendererTest {

    @Test
    void parseTaskOutputExtractsTaskIdAndStripsWrapper() {
        String raw = """
            task_id: session-123 (for resuming to continue this task if needed)

            <task_result>
            ## Result

            - item
            </task_result>
            """;

        TaskToolRenderer.ParsedTaskResult parsed = TaskToolRenderer.INSTANCE.parseTaskOutput(raw);

        assertEquals("session-123", parsed.getTaskId());
        assertTrue(parsed.getContent().contains("## Result"), parsed.getContent());
        assertFalse(parsed.getContent().contains("<task_result>"), parsed.getContent());
        assertFalse(parsed.getContent().contains("</task_result>"), parsed.getContent());
    }

    @Test
    void parseTaskOutputKeepsPlainContent() {
        TaskToolRenderer.ParsedTaskResult parsed =
            TaskToolRenderer.INSTANCE.parseTaskOutput("plain subagent output");

        assertNull(parsed.getTaskId());
        assertEquals("plain subagent output", parsed.getContent());
    }

    @Test
    void renderReturnsPanelForTaskOutput() {
        assertNotNull(TaskToolRenderer.INSTANCE.render("<task_result>hello</task_result>"));
    }
}
