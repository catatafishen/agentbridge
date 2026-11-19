package com.github.catatafishen.agentbridge.client.acp;

import com.github.catatafishen.agentbridge.model.SessionUpdate;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenCodeChildSessionsTest {

    private static final String ROOT = "ses_root";
    private static final String CHILD = "ses_child";

    private final OpenCodeChildSessions sessions = new OpenCodeChildSessions();
    private final List<JsonObject> parsed = new ArrayList<>();
    private final Function<JsonObject, SessionUpdate> parser = update -> {
        parsed.add(update);
        return new SessionUpdate.AgentMessageChunk(List.of());
    };

    /** Params as OpenCode 2.3.2 sent them in a live session. */
    private static JsonObject status(String status) {
        return JsonParser.parseString("""
            {"rootSessionId": "ses_root", "childSessionId": "ses_child", "parentSessionId": "ses_root",
             "depth": 1, "title": "List model directory files", "type": "status", "status": "%s"}"""
            .formatted(status)).getAsJsonObject();
    }

    @Test
    @DisplayName("a status names the sub-agent's session, its title and the state")
    void readsAStatus() {
        var update = assertInstanceOf(SessionUpdate.ChildSessionStatus.class,
            sessions.accept(status("created"), ROOT, parser));

        assertEquals(CHILD, update.childSessionId());
        assertEquals("List model directory files", update.title());
        assertEquals(SessionUpdate.ChildSessionState.CREATED, update.state());
        assertNull(update.error());
    }

    @Test
    @DisplayName("a sub-agent works from created or running until it completes, fails or is interrupted")
    void tracksWhetherASubAgentWorks() {
        assertFalse(sessions.anyWorking());
        sessions.accept(status("created"), ROOT, parser);
        assertTrue(sessions.anyWorking());
        sessions.accept(status("completed"), ROOT, parser);
        assertFalse(sessions.anyWorking());

        sessions.accept(status("running"), ROOT, parser);
        assertTrue(sessions.anyWorking(), "a follow-up makes it run again");
        sessions.accept(status("interrupted"), ROOT, parser);
        assertFalse(sessions.anyWorking());

        sessions.accept(status("running"), ROOT, parser);
        sessions.accept(status("failed"), ROOT, parser);
        assertFalse(sessions.anyWorking());
    }

    @Test
    @DisplayName("a failure carries OpenCode's error message")
    void readsTheErrorOfAFailure() {
        JsonObject params = status("failed");
        params.add("error", JsonParser.parseString("{\"type\": \"ProviderError\", \"message\": \"rate limited\"}"));

        var update = assertInstanceOf(SessionUpdate.ChildSessionStatus.class, sessions.accept(params, ROOT, parser));

        assertEquals(SessionUpdate.ChildSessionState.FAILED, update.state());
        assertEquals("rate limited", update.error());
    }

    @Test
    @DisplayName("a sub-agent's own update is parsed the standard way and keeps its session and title")
    void wrapsTheSubAgentsUpdate() {
        JsonObject params = JsonParser.parseString("""
            {"rootSessionId": "ses_root", "childSessionId": "ses_child", "parentSessionId": "ses_root",
             "depth": 1, "title": "List model directory files", "type": "update",
             "update": {"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": "Order.java"}}}"""
        ).getAsJsonObject();

        var update = assertInstanceOf(SessionUpdate.ChildSessionUpdate.class, sessions.accept(params, ROOT, parser));

        assertEquals(CHILD, update.childSessionId());
        assertEquals("List model directory files", update.title());
        assertInstanceOf(SessionUpdate.AgentMessageChunk.class, update.update());
        assertEquals("agent_message_chunk", parsed.getFirst().get("sessionUpdate").getAsString());
        assertFalse(sessions.anyWorking(), "an update says nothing about the state");
    }

    @Test
    @DisplayName("a notification for another main session, or with no session open, is dropped")
    void dropsOtherSessions() {
        assertNull(sessions.accept(status("created"), "ses_other", parser));
        assertNull(sessions.accept(status("created"), null, parser));
        assertFalse(sessions.anyWorking());
    }

    @Test
    @DisplayName("a notification without a child session, of an unknown type or state, or without params gives nothing")
    void malformedNotifications() {
        JsonObject noChild = status("created");
        noChild.remove("childSessionId");
        JsonObject unknownType = status("created");
        unknownType.addProperty("type", "progress");

        assertNull(sessions.accept(noChild, ROOT, parser));
        assertNull(sessions.accept(unknownType, ROOT, parser));
        assertNull(sessions.accept(status("paused"), ROOT, parser));
        assertNull(sessions.accept(null, ROOT, parser));
        assertFalse(sessions.anyWorking());
    }

    @Test
    @DisplayName("a blank title is no title")
    void blankTitle() {
        JsonObject params = status("created");
        params.addProperty("title", "  ");

        var update = assertInstanceOf(SessionUpdate.ChildSessionStatus.class, sessions.accept(params, ROOT, parser));

        assertNull(update.title());
    }

    @Test
    @DisplayName("clear forgets the sub-agents still working")
    void clear() {
        sessions.accept(status("running"), ROOT, parser);

        sessions.clear();

        assertFalse(sessions.anyWorking());
    }
}
