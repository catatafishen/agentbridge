package com.github.catatafishen.agentbridge.client.acp;

import com.github.catatafishen.agentbridge.bridge.EntryData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenCodeTranscriptTest {

    private static final String SESSION = """
        [
          {"info": {"role": "user", "agent": "general"},
           "parts": [{"id": "prt_u", "type": "text", "text": "Find the bug"}]},
          {"info": {"role": "assistant", "agent": "general", "modelID": "deepseek-v4-pro",
                    "time": {"created": 1791783403886}},
           "parts": [
            {"id": "prt_s", "type": "step-start"},
            {"id": "prt_r", "type": "reasoning", "text": "  Look at the logs  "},
            {"id": "prt_t", "type": "tool", "tool": "agentbridge_read_file",
             "state": {"status": "completed", "input": {"path": "a.txt"}, "output": "contents"}},
            {"id": "prt_a", "type": "text", "text": "Found it."}
          ]}
        ]
        """;

    @Test
    void parsesTextReasoningAndToolPartsInOrder() {
        OpenCodeTranscript transcript = OpenCodeTranscript.parse(SESSION);

        assertEquals(2, transcript.messages().size());
        OpenCodeTranscript.Message user = transcript.messages().getFirst();
        assertFalse(user.isAssistant());
        assertEquals("Find the bug", user.parts().getFirst().text());

        OpenCodeTranscript.Message assistant = transcript.messages().get(1);
        assertTrue(assistant.isAssistant());
        assertEquals("general", assistant.agent());
        assertEquals("deepseek-v4-pro", assistant.model());
        assertEquals("2026-10-12T05:36:43.886Z", assistant.timestamp());

        List<OpenCodeTranscript.Part> parts = assistant.parts();
        assertEquals(3, parts.size());
        assertEquals(OpenCodeTranscript.PartType.REASONING, parts.getFirst().type());
        assertEquals("prt_r", parts.getFirst().id());
        assertEquals("Look at the logs", parts.getFirst().text());

        OpenCodeTranscript.Part tool = parts.get(1);
        assertEquals(OpenCodeTranscript.PartType.TOOL, tool.type());
        assertEquals("prt_t", tool.id());
        assertEquals("read_file", tool.tool());
        assertEquals(OpenCodeTranscript.STATUS_COMPLETED, tool.status());
        assertEquals("{\"path\":\"a.txt\"}", tool.input());
        assertEquals("contents", tool.output());

        assertEquals(OpenCodeTranscript.PartType.TEXT, parts.get(2).type());
        assertEquals("Found it.", parts.get(2).text());
    }

    @Test
    void convertsTheAssistantSideIntoEntriesWithStableIds() {
        List<EntryData> entries = OpenCodeTranscript.parse(SESSION).toEntries("sa1");

        assertEquals(3, entries.size(), "the user's prompt is not part of the sub-agent's children");
        EntryData.Thinking thinking = assertInstanceOf(EntryData.Thinking.class, entries.getFirst());
        assertEquals("sa1:prt_r", thinking.getEntryId());
        assertEquals("Look at the logs", thinking.getRaw());
        assertEquals("general", thinking.getAgent());
        assertEquals("deepseek-v4-pro", thinking.getModel());
        assertEquals("2026-10-12T05:36:43.886Z", thinking.getTimestamp());

        EntryData.ToolCall tool = assertInstanceOf(EntryData.ToolCall.class, entries.get(1));
        assertEquals("sa1:prt_t", tool.getEntryId());
        assertEquals("read_file", tool.getTitle());
        assertEquals("{\"path\":\"a.txt\"}", tool.getArguments());
        assertEquals("contents", tool.getResult());
        assertEquals("completed", tool.getStatus());

        EntryData.Text text = assertInstanceOf(EntryData.Text.class, entries.get(2));
        assertEquals("Found it.", text.getRaw());

        List<EntryData> again = OpenCodeTranscript.parse(SESSION).toEntries("sa1");
        assertEquals(entries.stream().map(EntryData::getEntryId).toList(),
            again.stream().map(EntryData::getEntryId).toList());
    }

    @Test
    void aSecondSubAgentOnTheSameSessionGetsItsOwnIds() {
        OpenCodeTranscript transcript = OpenCodeTranscript.parse(SESSION);

        assertEquals("sa2:prt_a", transcript.toEntries("sa2").getLast().getEntryId());
        assertEquals("sa1:prt_a", transcript.toEntries("sa1").getLast().getEntryId());
    }

    @Test
    void failedToolUsesTheErrorMessageAsOutput() {
        String json = """
            [{"info": {"role": "assistant"}, "parts": [
              {"id": "prt_2", "type": "tool", "tool": "bash",
               "state": {"status": "error", "input": {"command": "x"}, "error": "command not found"}}
            ]}]
            """;

        OpenCodeTranscript.Part tool = OpenCodeTranscript.parse(json).messages().getFirst().parts().getFirst();

        assertEquals(OpenCodeTranscript.STATUS_FAILED, tool.status());
        assertEquals("command not found", tool.output());
        assertEquals("bash", tool.tool());
    }

    @Test
    void unfinishedToolIsRunningAndMissingIdsGetPositionalFallbacks() {
        String json = """
            [{"info": {"role": "assistant"}, "parts": [
              {"type": "tool", "tool": "grep", "state": {"status": "running"}},
              {"type": "tool", "tool": "glob"}
            ]}]
            """;

        List<OpenCodeTranscript.Part> parts = OpenCodeTranscript.parse(json).messages().getFirst().parts();

        assertEquals(OpenCodeTranscript.STATUS_RUNNING, parts.getFirst().status());
        assertNull(parts.getFirst().input());
        assertEquals("0-0", parts.getFirst().id());
        assertEquals("0-1", parts.get(1).id());
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
        assertTrue(OpenCodeTranscript.parse(json).toEntries("sa1").isEmpty());
    }

    @Test
    void missingInfoGivesUnknownRoleAndEmptyAuthorFields() {
        OpenCodeTranscript.Message message =
            OpenCodeTranscript.parse("[{\"parts\": []}]").messages().getFirst();

        assertEquals("unknown", message.role());
        assertFalse(message.isAssistant());
        assertEquals("", message.agent());
        assertEquals("", message.model());
        assertEquals("", message.timestamp());
    }

    @Test
    void rejectsNonArrayResponse() {
        assertThrows(IllegalArgumentException.class,
            () -> OpenCodeTranscript.parse("{\"error\": \"not found\"}"));
    }
}
