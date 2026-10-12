package com.github.catatafishen.agentbridge.client.acp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenCodeTranscriptTest {

    @Test
    void parsesTextReasoningAndToolPartsInOrder() {
        String json = """
            [
              {"info": {"role": "user"}, "parts": [{"type": "text", "text": "Find the bug"}]},
              {"info": {"role": "assistant"}, "parts": [
                {"type": "step-start"},
                {"type": "reasoning", "text": "  Look at the logs  "},
                {"type": "tool", "id": "prt_1", "tool": "agentbridge_read_file",
                 "state": {"status": "completed", "input": {"path": "a.txt"}, "output": "contents"}},
                {"type": "text", "text": "Found it."}
              ]}
            ]
            """;

        OpenCodeTranscript transcript = OpenCodeTranscript.parse(json);

        assertEquals(2, transcript.messages().size());
        OpenCodeTranscript.Message user = transcript.messages().getFirst();
        assertFalse(user.isAssistant());
        assertEquals("Find the bug", user.parts().getFirst().text());

        OpenCodeTranscript.Message assistant = transcript.messages().get(1);
        assertTrue(assistant.isAssistant());
        List<OpenCodeTranscript.Part> parts = assistant.parts();
        assertEquals(3, parts.size());
        assertEquals(OpenCodeTranscript.PartType.REASONING, parts.getFirst().type());
        assertEquals("Look at the logs", parts.getFirst().text());

        OpenCodeTranscript.Part tool = parts.get(1);
        assertEquals(OpenCodeTranscript.PartType.TOOL, tool.type());
        assertEquals("prt_1", tool.callId());
        assertEquals("read_file", tool.tool());
        assertEquals(OpenCodeTranscript.STATUS_COMPLETED, tool.status());
        assertEquals("{\"path\":\"a.txt\"}", tool.input());
        assertEquals("contents", tool.output());

        assertEquals(OpenCodeTranscript.PartType.TEXT, parts.get(2).type());
        assertEquals("Found it.", parts.get(2).text());
    }

    @Test
    void failedToolUsesTheErrorMessageAsOutput() {
        String json = """
            [{"info": {"role": "assistant"}, "parts": [
              {"type": "tool", "id": "prt_2", "tool": "bash",
               "state": {"status": "error", "input": {"command": "x"}, "error": "command not found"}}
            ]}]
            """;

        OpenCodeTranscript.Part tool = OpenCodeTranscript.parse(json).messages().getFirst().parts().getFirst();

        assertEquals(OpenCodeTranscript.STATUS_FAILED, tool.status());
        assertEquals("command not found", tool.output());
        assertEquals("bash", tool.tool());
    }

    @Test
    void unfinishedToolIsRunningAndMissingIdsGetStableFallbacks() {
        String json = """
            [{"info": {"role": "assistant"}, "parts": [
              {"type": "tool", "tool": "grep", "state": {"status": "running"}},
              {"type": "tool", "tool": "glob"}
            ]}]
            """;

        List<OpenCodeTranscript.Part> parts = OpenCodeTranscript.parse(json).messages().getFirst().parts();

        assertEquals(OpenCodeTranscript.STATUS_RUNNING, parts.getFirst().status());
        assertNull(parts.getFirst().input());
        assertEquals("0-0", parts.getFirst().callId());
        assertEquals("0-1", parts.get(1).callId());
        assertEquals(OpenCodeTranscript.STATUS_RUNNING, parts.get(1).status());
    }

    @Test
    void skipsUnknownPartTypesAndBlankText() {
        String json = """
            [{"info": {"role": "assistant"}, "parts": [
              {"type": "step-start"}, {"type": "text", "text": "  "}, {"type": "step-finish"}
            ]}]
            """;

        assertTrue(OpenCodeTranscript.parse(json).messages().getFirst().parts().isEmpty());
    }

    @Test
    void missingRoleIsUnknownAndNotAssistant() {
        OpenCodeTranscript.Message message = OpenCodeTranscript.parse("[{\"parts\": []}]").messages().getFirst();

        assertEquals("unknown", message.role());
        assertFalse(message.isAssistant());
    }

    @Test
    void rejectsNonArrayResponse() {
        assertThrows(IllegalArgumentException.class,
            () -> OpenCodeTranscript.parse("{\"error\": \"not found\"}"));
    }
}
