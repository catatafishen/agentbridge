package com.github.catatafishen.agentbridge.client.claude;

import com.github.catatafishen.agentbridge.acp.protocol.PromptRequest;
import com.github.catatafishen.agentbridge.model.ContentBlock;
import com.github.catatafishen.agentbridge.model.SessionUpdate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a prompt's stream-json output from Claude Code reaches the chat when the main agent starts sub-agents. */
class ClaudeClientSubAgentStreamTest {

    private static final String AGENT_ID = "a618a938f1087c72c";

    /** A background Explore sub-agent as Claude Code 2.1.330 reports it, with the main agent sleeping meanwhile. */
    private static final String BACKGROUND_RUN = """
        {"type":"system","subtype":"init","session_id":"cli-1"}
        {"type":"assistant","message":{"id":"msg_1","content":[{"type":"tool_use","id":"toolu_agent","name":"Agent","input":{"description":"List service files","subagent_type":"Explore","prompt":"List the files","run_in_background":true}}]}}
        {"type":"system","subtype":"task_started","task_id":"a618a938f1087c72c","tool_use_id":"toolu_agent","description":"List service files","task_type":"local_agent","is_backgrounded":true}
        {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_agent","content":[{"type":"text","text":"Async agent launched successfully.\\nagentId: a618a938f1087c72c (internal ID - do not mention to user.)"}]}]}}
        {"type":"assistant","message":{"id":"msg_2","content":[{"type":"tool_use","id":"toolu_sleep","name":"mcp__agentbridge__sleep","input":{"seconds":40}}]}}
        {"type":"assistant","parent_tool_use_id":"toolu_agent","agent_id":"a618a938f1087c72c","task_description":"List service files","message":{"id":"msg_s1","content":[{"type":"tool_use","id":"toolu_tree","name":"mcp__agentbridge__list_directory_tree","input":{"path":"src"}}]}}
        {"type":"user","parent_tool_use_id":"toolu_agent","agent_id":"a618a938f1087c72c","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_tree","content":"OrderService.java"}]}}
        {"type":"assistant","parent_tool_use_id":"toolu_agent","agent_id":"a618a938f1087c72c","message":{"id":"msg_s2","content":[{"type":"tool_use","id":"toolu_hb","name":"SubagentHandback","input":{"message":"Found OrderService.java"}}]}}
        {"type":"user","parent_tool_use_id":"toolu_agent","agent_id":"a618a938f1087c72c","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_hb","content":"{\\"success\\":true,\\"message\\":\\"Report delivered to your caller.\\"}"}]}}
        {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_sleep","content":"Slept 40 s"}]}}
        {"type":"assistant","message":{"id":"msg_3","content":[{"type":"text","text":"DONE"}]}}
        {"type":"result","subtype":"success","session_id":"cli-1"}
        {"type":"system","subtype":"task_notification","task_id":"a618a938f1087c72c","tool_use_id":"toolu_agent","status":"completed","summary":"Agent completed"}
        {"type":"assistant","message":{"id":"msg_4","content":[{"type":"text","text":"The sub-agent found OrderService.java."}]}}
        {"type":"result","subtype":"success","session_id":"cli-1"}
        """;

    private static List<SessionUpdate> run(String stdout) throws Exception {
        ClaudeClient client = ClaudeCliTestFixtures.clientPrinting(stdout);
        String sessionId = client.createSession(null);
        List<SessionUpdate> updates = new ArrayList<>();
        client.sendPrompt(new PromptRequest(sessionId, List.of(new ContentBlock.Text("go")), null, null), updates::add);
        return updates;
    }

    private static String text(SessionUpdate update) {
        SessionUpdate.AgentMessageChunk chunk = assertInstanceOf(SessionUpdate.AgentMessageChunk.class, update);
        return ((ContentBlock.Text) chunk.content().getFirst()).text();
    }

    private static List<SessionUpdate.ChildSessionUpdate> childUpdates(List<SessionUpdate> updates) {
        return updates.stream()
            .filter(SessionUpdate.ChildSessionUpdate.class::isInstance)
            .map(SessionUpdate.ChildSessionUpdate.class::cast)
            .toList();
    }

    @Test
    void subAgentToolCallsAndReportGoToItsSection() throws Exception {
        List<SessionUpdate.ChildSessionUpdate> child = childUpdates(run(BACKGROUND_RUN));

        assertTrue(child.stream().allMatch(u -> AGENT_ID.equals(u.childSessionId())), child.toString());
        assertTrue(child.stream().allMatch(u -> "List service files".equals(u.title())), child.toString());
        assertEquals(List.of("toolu_tree"), child.stream()
            .map(SessionUpdate.ChildSessionUpdate::update)
            .filter(SessionUpdate.ToolCall.class::isInstance)
            .map(u -> ((SessionUpdate.ToolCall) u).toolCallId())
            .toList());
        assertEquals(List.of("Found OrderService.java"), child.stream()
            .map(SessionUpdate.ChildSessionUpdate::update)
            .filter(SessionUpdate.AgentMessageChunk.class::isInstance)
            .map(ClaudeClientSubAgentStreamTest::text)
            .toList());
    }

    @Test
    void handBackConfirmationIsNotShown() throws Exception {
        List<SessionUpdate> updates = run(BACKGROUND_RUN);

        assertTrue(updates.stream()
            .map(u -> u instanceof SessionUpdate.ChildSessionUpdate c ? c.update() : u)
            .noneMatch(u -> u instanceof SessionUpdate.ToolCallUpdate t && "toolu_hb".equals(t.toolCallId())));
    }

    @Test
    void mainAgentKeepsItsOwnToolCallsAndText() throws Exception {
        List<SessionUpdate> updates = run(BACKGROUND_RUN);

        assertEquals(List.of("toolu_agent", "toolu_sleep"), updates.stream()
            .filter(SessionUpdate.ToolCall.class::isInstance)
            .map(u -> ((SessionUpdate.ToolCall) u).toolCallId())
            .toList());
        assertEquals(List.of("DONE", "\n\nThe sub-agent found OrderService.java."), updates.stream()
            .filter(SessionUpdate.AgentMessageChunk.class::isInstance)
            .map(ClaudeClientSubAgentStreamTest::text)
            .toList());
    }

    @Test
    void subAgentRunsFromItsStartToItsNotification() throws Exception {
        List<SessionUpdate.ChildSessionStatus> statuses = run(BACKGROUND_RUN).stream()
            .filter(SessionUpdate.ChildSessionStatus.class::isInstance)
            .map(SessionUpdate.ChildSessionStatus.class::cast)
            .toList();

        assertEquals(List.of(SessionUpdate.ChildSessionState.RUNNING, SessionUpdate.ChildSessionState.COMPLETED),
            statuses.stream().map(SessionUpdate.ChildSessionStatus::state).toList());
        assertTrue(statuses.stream().allMatch(s -> AGENT_ID.equals(s.childSessionId())));
    }

    @Test
    void subAgentStillRunningWhenTheOutputEndsFails() throws Exception {
        String cut = BACKGROUND_RUN.substring(0, BACKGROUND_RUN.indexOf("{\"type\":\"system\",\"subtype\":\"task_notification\""));

        List<SessionUpdate> updates = run(cut);

        SessionUpdate.ChildSessionStatus last =
            assertInstanceOf(SessionUpdate.ChildSessionStatus.class, updates.getLast());
        assertEquals(SessionUpdate.ChildSessionState.FAILED, last.state());
        assertEquals(AGENT_ID, last.childSessionId());
    }

    @Test
    void foregroundSubAgentResultIsEmptyOnceItsReportIsShown() throws Exception {
        String foreground = """
            {"type":"assistant","message":{"id":"msg_1","content":[{"type":"tool_use","id":"toolu_agent","name":"Agent","input":{"description":"Count","subagent_type":"Explore","prompt":"Count"}}]}}
            {"type":"assistant","parent_tool_use_id":"toolu_agent","agent_id":"agent-fg","message":{"id":"msg_s1","content":[{"type":"tool_use","id":"toolu_hb","name":"SubagentHandback","input":{"message":"Three files"}}]}}
            {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_agent","content":"This agent's report was delivered to you as a message from agent-fg."}]}}
            {"type":"result","subtype":"success","session_id":"cli-2"}
            """;

        List<SessionUpdate> updates = run(foreground);

        SessionUpdate.ToolCallUpdate agentEnd = updates.stream()
            .filter(SessionUpdate.ToolCallUpdate.class::isInstance)
            .map(SessionUpdate.ToolCallUpdate.class::cast)
            .filter(u -> "toolu_agent".equals(u.toolCallId()))
            .findFirst().orElseThrow();
        assertEquals("", agentEnd.result());
    }

    /** A foreground sub-agent as Claude Code 2.1.330 reports it: its report comes only in the Agent call's result. */
    @Test
    void foregroundSubAgentResultIsItsReportWithoutTheCliFrame() throws Exception {
        String foreground = """
            {"type":"assistant","message":{"id":"msg_1","content":[{"type":"tool_use","id":"toolu_agent","name":"Agent","input":{"description":"Count","subagent_type":"Explore","prompt":"Count"}}]}}
            {"type":"assistant","parent_tool_use_id":"toolu_agent","agent_id":"agent-fg","message":{"id":"msg_s1","content":[{"type":"tool_use","id":"toolu_tree","name":"mcp__agentbridge__list_directory_tree","input":{}}]}}
            {"type":"user","parent_tool_use_id":"toolu_agent","agent_id":"agent-fg","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_tree","content":"src"}]}}
            {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_agent","content":[{"type":"text","text":"  <tools-used-before-summary>mcp__agentbridge__list_directory_tree</tools-used-before-summary>\\n  \\n[Subagent hand-back] The text below is the final report of a subagent this session delegated to. The report follows:\\n  2"}]}]}}
            {"type":"result","subtype":"success","session_id":"cli-3"}
            """;

        List<SessionUpdate> updates = run(foreground);

        SessionUpdate.ToolCallUpdate agentEnd = updates.stream()
            .filter(SessionUpdate.ToolCallUpdate.class::isInstance)
            .map(SessionUpdate.ToolCallUpdate.class::cast)
            .filter(u -> "toolu_agent".equals(u.toolCallId()))
            .findFirst().orElseThrow();
        assertEquals("2", agentEnd.result());
    }
}
