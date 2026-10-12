package com.github.catatafishen.agentbridge.client.acp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenCodeTranscriptFormatterTest {

    @Test
    void formatsTextReasoningAndToolParts() {
        String json = """
            [
              {"info": {"role": "user"}, "parts": [{"type": "text", "text": "Find the bug"}]},
              {"info": {"role": "assistant"}, "parts": [
                {"type": "reasoning", "text": "Look at\\nthe logs"},
                {"type": "tool", "tool": "read_file",
                 "state": {"status": "completed", "input": {"path": "a.txt"}, "output": "contents"}},
                {"type": "text", "text": "Found it."}
              ]}
            ]
            """;

        String md = OpenCodeTranscriptFormatter.toMarkdown(json);

        assertTrue(md.contains("### User\n\nFind the bug"), md);
        assertTrue(md.contains("### Assistant"), md);
        assertTrue(md.contains("> *Reasoning:* Look at\n> the logs"), md);
        assertTrue(md.contains("**Tool:** `read_file` (completed)"), md);
        assertTrue(md.contains("{\"path\":\"a.txt\"}"), md);
        assertTrue(md.contains("contents"), md);
        assertTrue(md.endsWith("Found it."), md);
    }

    @Test
    void skipsUnknownPartTypesAndBlankText() {
        String json = """
            [{"info": {"role": "assistant"}, "parts": [
              {"type": "step-start"}, {"type": "text", "text": "  "}
            ]}]
            """;

        assertEquals("### Assistant", OpenCodeTranscriptFormatter.toMarkdown(json));
    }

    @Test
    void truncatesLongToolOutput() {
        String longOutput = "x".repeat(OpenCodeTranscriptFormatter.MAX_TOOL_FIELD_CHARS + 50);
        String json = "[{\"info\":{\"role\":\"assistant\"},\"parts\":[{\"type\":\"tool\",\"tool\":\"bash\","
            + "\"state\":{\"status\":\"completed\",\"output\":\"" + longOutput + "\"}}]}]";

        String md = OpenCodeTranscriptFormatter.toMarkdown(json);

        assertTrue(md.contains("… (50 more characters)"), md);
    }

    @Test
    void missingRoleIsShownAsUnknown() {
        assertEquals("### Unknown", OpenCodeTranscriptFormatter.toMarkdown("[{\"parts\": []}]"));
    }

    @Test
    void rejectsNonArrayResponse() {
        assertThrows(IllegalArgumentException.class,
            () -> OpenCodeTranscriptFormatter.toMarkdown("{\"error\": \"not found\"}"));
    }
}
