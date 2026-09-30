package com.github.catatafishen.agentbridge.psi.review;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.github.catatafishen.agentbridge.psi.review.EditApprovalRequestParser.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link EditApprovalRequestParser} — pure JSON parsing, no IDE fixtures.
 */
class EditApprovalRequestParserTest {

    @Nested
    class DetectsEditApprovals {
        @Test
        @DisplayName("camelCase diff block (ACP SDK serialization) parses path, old, new")
        void camelCaseDiffBlock() {
            JsonObject params = editApproval("C:/proj/src/Main.java", "old body", "new body", "oldText", "newText");
            Result parsed = EditApprovalRequestParser.parse(params);
            Result.EditApproval approval = assertInstanceOf(Result.EditApproval.class, parsed);
            assertEquals("C:/proj/src/Main.java", approval.path());
            assertEquals("old body", approval.oldText());
            assertEquals("new body", approval.newText());
            assertEquals("Approve edit: C:/proj/src/Main.java", approval.title());
            assertEquals("tc-1", approval.toolCallId());
        }

        @Test
        @DisplayName("snake_case diff keys (agents without alias serialization) are accepted")
        void snakeCaseDiffKeys() {
            JsonObject params = editApproval("C:/x/a.py", "old", "new", "old_text", "new_text");
            Result parsed = EditApprovalRequestParser.parse(params);
            Result.EditApproval approval = assertInstanceOf(Result.EditApproval.class, parsed);
            assertEquals("old", approval.oldText());
            assertEquals("new", approval.newText());
        }

        @Test
        @DisplayName("absent oldText means new file (null, not empty)")
        void newFileHasNullOldText() {
            JsonObject params = editApproval("C:/proj/new.txt", null, "content", null, "newText");
            Result parsed = EditApprovalRequestParser.parse(params);
            Result.EditApproval approval = assertInstanceOf(Result.EditApproval.class, parsed);
            assertNull(approval.oldText());
            assertEquals("content", approval.newText());
        }

        @Test
        @DisplayName("windows backslash path parses unchanged (no normalization)")
        void backslashPathPreserved() {
            JsonObject params = editApproval("C:\\proj\\src\\Main.java", "a", "b", "oldText", "newText");
            Result parsed = EditApprovalRequestParser.parse(params);
            Result.EditApproval approval = assertInstanceOf(Result.EditApproval.class, parsed);
            assertEquals("C:\\proj\\src\\Main.java", approval.path());
        }

        @Test
        @DisplayName("multi-file comma-joined display path is passed through")
        void commaJoinedPathPassedThrough() {
            JsonObject params = editApproval("C:/a.java, C:/b.java", "old", "new", "oldText", "newText");
            Result parsed = EditApprovalRequestParser.parse(params);
            Result.EditApproval approval = assertInstanceOf(Result.EditApproval.class, parsed);
            assertEquals("C:/a.java, C:/b.java", approval.path());
        }
    }

    @Nested
    class RejectsNonEditApprovals {
        @Test
        @DisplayName("no toolCall object → not an edit approval")
        void noToolCall() {
            JsonObject params = new JsonObject();
            assertTrue(EditApprovalRequestParser.parse(params) instanceof Result.NotEditApproval);
        }

        @Test
        @DisplayName("null params → not an edit approval")
        void nullParams() {
            assertTrue(EditApprovalRequestParser.parse(null) instanceof Result.NotEditApproval);
        }

        @Test
        @DisplayName("toolCall that is not an object (malformed payload) is rejected, not thrown")
        void malformedToolCallPrimitive() {
            JsonObject params = new JsonObject();
            params.addProperty("toolCall", "not-an-object");
            assertTrue(EditApprovalRequestParser.parse(params) instanceof Result.NotEditApproval);
        }

        @Test
        @DisplayName("kind=edit without diff content block (a real tool call) stays default")
        void editKindWithoutDiff() {
            JsonObject toolCall = new JsonObject();
            toolCall.addProperty("toolCallId", "tc-2");
            toolCall.addProperty("title", "apply_patch");
            toolCall.addProperty("kind", "edit");
            JsonObject params = new JsonObject();
            params.add("toolCall", toolCall);
            assertTrue(EditApprovalRequestParser.parse(params) instanceof Result.NotEditApproval);
        }

        @Test
        @DisplayName("kind=execute command approval (rawInput, no diff) stays default")
        void executeKindCommandApproval() {
            JsonObject toolCall = new JsonObject();
            toolCall.addProperty("toolCallId", "tc-3");
            toolCall.addProperty("title", "Run command: git status");
            toolCall.addProperty("kind", "execute");
            JsonObject rawInput = new JsonObject();
            rawInput.addProperty("command", "git status");
            toolCall.add("rawInput", rawInput);
            JsonObject params = new JsonObject();
            params.add("toolCall", toolCall);
            assertTrue(EditApprovalRequestParser.parse(params) instanceof Result.NotEditApproval);
        }

        @Test
        @DisplayName("diff block without newText is skipped")
        void diffBlockWithoutNewText() {
            JsonObject toolCall = new JsonObject();
            toolCall.addProperty("toolCallId", "tc-4");
            toolCall.addProperty("kind", "edit");
            JsonObject diff = new JsonObject();
            diff.addProperty("type", "diff");
            diff.addProperty("path", "C:/x/a.txt");
            JsonArray content = new JsonArray();
            content.add(diff);
            toolCall.add("content", content);
            JsonObject params = new JsonObject();
            params.add("toolCall", toolCall);
            assertTrue(EditApprovalRequestParser.parse(params) instanceof Result.NotEditApproval);
        }

        @Test
        @DisplayName("content entry that is not a diff block is skipped")
        void nonDiffContentBlockSkipped() {
            JsonObject toolCall = new JsonObject();
            toolCall.addProperty("toolCallId", "tc-5");
            toolCall.addProperty("kind", "edit");
            JsonObject text = new JsonObject();
            text.addProperty("type", "content");
            text.addProperty("text", "hello");
            JsonArray content = new JsonArray();
            content.add(text);
            toolCall.add("content", content);
            JsonObject params = new JsonObject();
            params.add("toolCall", toolCall);
            assertTrue(EditApprovalRequestParser.parse(params) instanceof Result.NotEditApproval);
        }
    }

    /**
     * Builds an edit-approval permission request with one diff content block.
     *
     * @param oldKey      key name for old text ("oldText"/"old_text") or null to omit
     * @param newKey      key name for new text ("newText"/"new_text")
     */
    private static JsonObject editApproval(String path, String oldText, String newText,
                                            String oldKey, String newKey) {
        JsonObject diff = new JsonObject();
        diff.addProperty("type", "diff");
        diff.addProperty("path", path);
        if (oldKey != null && oldText != null) {
            diff.addProperty(oldKey, oldText);
        }
        diff.addProperty(newKey, newText);

        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("toolCallId", "tc-1");
        toolCall.addProperty("title", "Approve edit: " + path);
        toolCall.addProperty("kind", "edit");
        JsonArray content = new JsonArray();
        content.add(diff);
        toolCall.add("content", content);

        JsonObject params = new JsonObject();
        params.add("toolCall", toolCall);
        return params;
    }
}
