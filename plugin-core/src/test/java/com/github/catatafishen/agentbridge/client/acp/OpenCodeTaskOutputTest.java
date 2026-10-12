package com.github.catatafishen.agentbridge.client.acp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class OpenCodeTaskOutputTest {

    @Test
    void parsesOutputCapturedFromOpenCode() {
        // Verbatim shape of a completed `task` tool call logged from OpenCode 1.19.0.
        String raw = """
            <task id="ses_ed162fea7ffelMr7OYI9bYu52y" state="completed">
            <task_result>
            ## Why this approach?
            </task_result>
            </task>""";

        OpenCodeTaskOutput parsed = OpenCodeTaskOutput.parse(raw);

        assertNotNull(parsed);
        assertEquals("ses_ed162fea7ffelMr7OYI9bYu52y", parsed.childSessionId());
        assertEquals("## Why this approach?", parsed.body());
    }

    @Test
    void keepsMultilineBodyIntact() {
        String raw = """
            <task id="ses_1" state="completed">
            <task_result>
            line one

            - item
            </task_result>
            </task>""";

        OpenCodeTaskOutput parsed = OpenCodeTaskOutput.parse(raw);

        assertNotNull(parsed);
        assertEquals("line one\n\n- item", parsed.body());
    }

    @Test
    void toleratesMissingResultWrapperAndExtraAttributes() {
        String raw = """
            <task id="ses_2" state="error" extra="x">
            boom
            </task>""";

        OpenCodeTaskOutput parsed = OpenCodeTaskOutput.parse(raw);

        assertNotNull(parsed);
        assertEquals("ses_2", parsed.childSessionId());
        assertEquals("boom", parsed.body());
    }

    @Test
    void emptyResultGivesEmptyBody() {
        String raw = """
            <task id="ses_3" state="completed">
            <task_result>
            </task_result>
            </task>""";

        OpenCodeTaskOutput parsed = OpenCodeTaskOutput.parse(raw);

        assertNotNull(parsed);
        assertEquals("", parsed.body());
    }

    @Test
    void returnsNullForOtherFormats() {
        assertNull(OpenCodeTaskOutput.parse(null));
        assertNull(OpenCodeTaskOutput.parse(""));
        assertNull(OpenCodeTaskOutput.parse("plain subagent output"));
        assertNull(OpenCodeTaskOutput.parse("task_id: ses_4 (for resuming)\n\n<task_result>x</task_result>"));
        assertNull(OpenCodeTaskOutput.parse("text before <task id=\"ses_5\">x</task>"));
    }
}
