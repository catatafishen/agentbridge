package com.github.catatafishen.agentbridge.client.acp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Presentation helpers for ACP-native permission requests (agent asking the user to
 * approve an edit or command): the bubble context the chat panels render, the bounded
 * unified diff, and full-change stats.
 */
class AgentPermissionRequestsTest {

    private static JsonObject editApprovalParams(String path) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "diff");
        block.addProperty("path", path);
        block.addProperty("oldText", "a");
        block.addProperty("newText", "b");
        JsonArray content = new JsonArray();
        content.add(block);
        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("title", "Approve edit: " + path);
        toolCall.addProperty("kind", "edit");
        toolCall.add("content", content);
        JsonObject params = new JsonObject();
        params.add("toolCall", toolCall);
        return params;
    }

    @Test
    void bubbleContextCarriesDiffForEditApproval() {
        JsonObject params = editApprovalParams("C:\\x\\y\\File.java");
        String json = AgentPermissionRequests.bubbleContext(params, "Approve edit: C:\\x\\y\\File.java");
        // Must be the {question, args} shape the chat renders, with the path and a
        // bounded unified diff (the raw oldText/newText pair is whole-file content).
        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("Approve this file edit?", parsed.get("question").getAsString());
        JsonObject args = parsed.getAsJsonObject("args");
        assertEquals("C:\\x\\y\\File.java", args.get("path").getAsString());
        assertEquals("-a\n+b\n", args.get("diff").getAsString());
        assertEquals(1, args.get("diffAdded").getAsInt());
        assertEquals(1, args.get("diffRemoved").getAsInt());
    }

    @Test
    void bubbleContextKeepsPathWhenDiffIsNull() {
        // Identical texts: no diff card renders, so the path MUST stay a plain arg row
        // (the panels only skip path/stats rows when a diff card is actually shown).
        JsonObject params = editApprovalParams("C:/x/y/File.java");
        params.getAsJsonObject("toolCall")
            .getAsJsonArray("content").get(0).getAsJsonObject().addProperty("newText", "a");
        String json = AgentPermissionRequests.bubbleContext(params, "Approve edit: C:/x/y/File.java");
        JsonObject args = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("args");
        assertEquals("C:/x/y/File.java", args.get("path").getAsString());
        assertNull(args.get("diff"));
        assertNull(args.get("diffAdded"));
        assertNull(args.get("diffRemoved"));
    }

    @Test
    void bubbleDiffCollapsesUnchangedRegions() {
        String oldText = "keep1\nkeep2\nkeep3\nremove\nkeep4\nkeep5\nkeep6\nkeep7\nkeep8";
        String newText = "keep1\nkeep2\nkeep3\ninsert\nkeep4\nkeep5\nkeep6\nkeep7\nkeep8";
        String diff = AgentPermissionRequests.bubbleDiff(oldText, newText);
        assertNotNull(diff);
        // 3 context lines before + the change + 3 after; the rest collapses to "…".
        assertTrue(diff.contains("-remove"), diff);
        assertTrue(diff.contains("+insert"), diff);
        assertTrue(diff.contains("\u2026"), diff);
        assertFalse(diff.contains("keep8\nkeep8"), diff);
        // Bounded output: never more than the cap plus the more-lines marker.
        assertTrue(diff.split("\\R", -1).length <= 402, diff);
    }

    @Test
    void bubbleDiffHandlesNewFileAndIdenticalText() {
        // New file (no oldText): pure additions.
        assertEquals("+new line\n", AgentPermissionRequests.bubbleDiff(null, "new line"));
        // Identical texts: nothing to show.
        assertNull(AgentPermissionRequests.bubbleDiff("same", "same"));
        // Missing new text: nothing to show.
        assertNull(AgentPermissionRequests.bubbleDiff("old", null));
    }

    @Test
    void changeStatsCountFullChangeBeyondDiffCap() {
        // Stats must reflect the FULL change, not the truncated card diff.
        StringBuilder oldText = new StringBuilder();
        StringBuilder newText = new StringBuilder();
        for (int k = 0; k < 500; k++) {
            oldText.append("old line ").append(k).append('\n');
            newText.append("new line ").append(k).append('\n');
        }
        int[] stats = AgentPermissionRequests.changeStats(oldText.toString(), newText.toString());
        assertEquals(500, stats[0]);
        assertEquals(500, stats[1]);
    }

    @Test
    void bubbleContextCarriesCommandForExecuteApproval() {
        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("title", "Run tests: gradle test");
        toolCall.addProperty("kind", "execute");
        JsonObject rawInput = new JsonObject();
        rawInput.addProperty("command", "gradle test");
        toolCall.add("rawInput", rawInput);
        JsonObject params = new JsonObject();
        params.add("toolCall", toolCall);
        String json = AgentPermissionRequests.bubbleContext(params, "Run tests: gradle test");
        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("Approve this command?", parsed.get("question").getAsString());
        assertEquals("gradle test", parsed.getAsJsonObject("args").get("command").getAsString());
    }

    @Test
    void requestKindDetectsEditAndExecuteByProtocolShape() {
        assertEquals("edit", AgentPermissionRequests.requestKind(editApprovalParams("C:/x/y/File.java")));
        assertEquals("edit", AgentPermissionRequests.requestKind(editApprovalParams("C:\\x\\y\\File.java")));

        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("kind", "execute");
        JsonObject rawInput = new JsonObject();
        rawInput.addProperty("command", "gradle test");
        toolCall.add("rawInput", rawInput);
        JsonObject params = new JsonObject();
        params.add("toolCall", toolCall);
        assertEquals("execute", AgentPermissionRequests.requestKind(params));

        // A bare kind without the matching payload is not an agent permission request.
        JsonObject bare = new JsonObject();
        bare.addProperty("kind", "edit");
        JsonObject bareParams = new JsonObject();
        bareParams.add("toolCall", bare);
        assertNull(AgentPermissionRequests.requestKind(bareParams));
    }

    @Test
    void requestKindToleratesNonObjectToolCall() {
        // A non-object toolCall value must fall through to the raw prompt path, not throw
        // (same shape-quirk immunity as bubbleContext and the AcpClient caller).
        JsonObject params = new JsonObject();
        params.addProperty("toolCall", "not an object");
        assertNull(AgentPermissionRequests.requestKind(params));

        JsonObject arrayParams = new JsonObject();
        arrayParams.add("toolCall", new JsonArray());
        assertNull(AgentPermissionRequests.requestKind(arrayParams));

        assertNull(AgentPermissionRequests.requestKind(null));
    }
}
