package com.github.catatafishen.agentbridge.agent.codex;

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.services.ToolPermission;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link CodexApprovalHandler} — covers all static/package-private helpers
 * and the state-management methods that don't require IntelliJ project services.
 */
class CodexApprovalHandlerTest {

    // ── isMcpToolApprovalQuestion ─────────────────────────────────────────────

    @Nested
    class IsMcpToolApprovalQuestion {

        @Test
        void matchingPrefix_returnsTrue() {
            assertTrue(CodexApprovalHandler.isMcpToolApprovalQuestion("mcp_tool_call_approval_abc123"));
        }

        @Test
        void matchingPrefix_exactPrefix_returnsTrue() {
            assertTrue(CodexApprovalHandler.isMcpToolApprovalQuestion("mcp_tool_call_approval_"));
        }

        @Test
        void nonMatchingString_returnsFalse() {
            assertFalse(CodexApprovalHandler.isMcpToolApprovalQuestion("some_other_question_id"));
        }

        @Test
        void emptyString_returnsFalse() {
            assertFalse(CodexApprovalHandler.isMcpToolApprovalQuestion(""));
        }

        @Test
        void partialPrefix_returnsFalse() {
            assertFalse(CodexApprovalHandler.isMcpToolApprovalQuestion("mcp_tool_call_approval"));
        }
    }

    // ── extractOptionLabels ───────────────────────────────────────────────────

    @Nested
    class ExtractOptionLabels {

        @Test
        void validOptions_returnsLabels() {
            JsonObject question = new JsonObject();
            JsonArray opts = new JsonArray();
            JsonObject opt1 = new JsonObject();
            opt1.addProperty("label", "Allow");
            JsonObject opt2 = new JsonObject();
            opt2.addProperty("label", "Deny");
            opts.add(opt1);
            opts.add(opt2);
            question.add("options", opts);

            List<String> labels = CodexApprovalHandler.extractOptionLabels(question);
            assertEquals(List.of("Allow", "Deny"), labels);
        }

        @Test
        void noOptionsField_returnsEmpty() {
            JsonObject question = new JsonObject();
            question.addProperty("question", "Choose:");

            List<String> labels = CodexApprovalHandler.extractOptionLabels(question);
            assertTrue(labels.isEmpty());
        }

        @Test
        void optionsNotArray_returnsEmpty() {
            JsonObject question = new JsonObject();
            question.addProperty("options", "not-an-array");

            List<String> labels = CodexApprovalHandler.extractOptionLabels(question);
            assertTrue(labels.isEmpty());
        }

        @Test
        void optionWithoutLabel_skipped() {
            JsonObject question = new JsonObject();
            JsonArray opts = new JsonArray();
            JsonObject opt1 = new JsonObject();
            opt1.addProperty("value", "x");
            JsonObject opt2 = new JsonObject();
            opt2.addProperty("label", "Keep");
            opts.add(opt1);
            opts.add(opt2);
            question.add("options", opts);

            List<String> labels = CodexApprovalHandler.extractOptionLabels(question);
            assertEquals(List.of("Keep"), labels);
        }
    }

    // ── findQuestionTextInArgs ────────────────────────────────────────────────

    @Nested
    class FindQuestionTextInArgs {

        @Test
        void findsQuestion_field() {
            JsonObject args = new JsonObject();
            args.addProperty("question", "What is your name?");

            assertEquals("What is your name?", CodexApprovalHandler.findQuestionTextInArgs(args));
        }

        @Test
        void findsPrompt_field() {
            JsonObject args = new JsonObject();
            args.addProperty("prompt", "Enter password:");

            assertEquals("Enter password:", CodexApprovalHandler.findQuestionTextInArgs(args));
        }

        @Test
        void findsMessage_field() {
            JsonObject args = new JsonObject();
            args.addProperty("message", "Confirm?");

            assertEquals("Confirm?", CodexApprovalHandler.findQuestionTextInArgs(args));
        }

        @Test
        void findsText_field() {
            JsonObject args = new JsonObject();
            args.addProperty("text", "Hello?");

            assertEquals("Hello?", CodexApprovalHandler.findQuestionTextInArgs(args));
        }

        @Test
        void prefersQuestion_overOthers() {
            JsonObject args = new JsonObject();
            args.addProperty("text", "text val");
            args.addProperty("question", "question val");

            assertEquals("question val", CodexApprovalHandler.findQuestionTextInArgs(args));
        }

        @Test
        void emptyString_returnsNull() {
            JsonObject args = new JsonObject();
            args.addProperty("question", "  ");

            assertNull(CodexApprovalHandler.findQuestionTextInArgs(args));
        }

        @Test
        void noMatchingFields_returnsNull() {
            JsonObject args = new JsonObject();
            args.addProperty("foo", "bar");

            assertNull(CodexApprovalHandler.findQuestionTextInArgs(args));
        }

        @Test
        void nonPrimitiveValue_skipped() {
            JsonObject args = new JsonObject();
            args.add("question", new JsonObject());
            args.addProperty("text", "fallback");

            assertEquals("fallback", CodexApprovalHandler.findQuestionTextInArgs(args));
        }
    }

    // ── extractOptionsArray ──────────────────────────────────────────────────

    @Nested
    class ExtractOptionsArray {

        @Test
        void validOptionsArray_returned() {
            JsonObject args = new JsonObject();
            JsonArray opts = new JsonArray();
            opts.add("Yes");
            opts.add("No");
            args.add("options", opts);

            JsonArray result = CodexApprovalHandler.extractOptionsArray(args);
            assertEquals(2, result.size());
            assertEquals("Yes", result.get(0).getAsString());
        }

        @Test
        void noOptionsField_returnsDefaultContinue() {
            JsonObject args = new JsonObject();

            JsonArray result = CodexApprovalHandler.extractOptionsArray(args);
            assertEquals(1, result.size());
            assertEquals("Continue", result.get(0).getAsString());
        }

        @Test
        void optionsNotArray_returnsDefault() {
            JsonObject args = new JsonObject();
            args.addProperty("options", "not-array");

            JsonArray result = CodexApprovalHandler.extractOptionsArray(args);
            assertEquals(1, result.size());
            assertEquals("Continue", result.get(0).getAsString());
        }
    }

    // ── Pending MCP tool tracking ────────────────────────────────────────────

    @Nested
    class PendingMcpToolTracking {

        @Test
        void trackAndRetrieve() {
            CodexApprovalHandler handler = createHandler();
            handler.trackPendingMcpTool("call-1", "read_file");

            assertEquals("read_file", handler.getPendingMcpToolName("call-1"));
        }

        @Test
        void unknownCallId_returnsNull() {
            CodexApprovalHandler handler = createHandler();

            assertNull(handler.getPendingMcpToolName("nonexistent"));
        }

        @Test
        void removeStopsTracking() {
            CodexApprovalHandler handler = createHandler();
            handler.trackPendingMcpTool("call-2", "write_file");
            handler.removePendingMcpTool("call-2");

            assertNull(handler.getPendingMcpToolName("call-2"));
        }
    }

    // ── Session approval cache ───────────────────────────────────────────────

    @Nested
    class SessionApprovalCache {

        @Test
        void initiallyNotAllowed() {
            CodexApprovalHandler handler = createHandler();

            assertFalse(handler.isSessionApprovalAllowed("session-1", "run_command"));
        }

        @Test
        void afterAllow_isAllowed() {
            CodexApprovalHandler handler = createHandler();
            handler.allowSessionApproval("session-1", "run_command");

            assertTrue(handler.isSessionApprovalAllowed("session-1", "run_command"));
        }

        @Test
        void differentSession_notAllowed() {
            CodexApprovalHandler handler = createHandler();
            handler.allowSessionApproval("session-1", "run_command");

            assertFalse(handler.isSessionApprovalAllowed("session-2", "run_command"));
        }

        @Test
        void differentPermissionKey_notAllowed() {
            CodexApprovalHandler handler = createHandler();
            handler.allowSessionApproval("session-1", "run_command");

            assertFalse(handler.isSessionApprovalAllowed("session-1", "write_file"));
        }

        @Test
        void multipleKeys_sameSession() {
            CodexApprovalHandler handler = createHandler();
            handler.allowSessionApproval("s1", "run_command");
            handler.allowSessionApproval("s1", "write_file");

            assertTrue(handler.isSessionApprovalAllowed("s1", "run_command"));
            assertTrue(handler.isSessionApprovalAllowed("s1", "write_file"));
        }
    }

    // ── handleUserInputRequest (MCP auto-approval path) ──────────────────────

    @Nested
    class HandleUserInputRequest {

        @Test
        void mcpApprovalQuestion_autoApproves() {
            AtomicReference<JsonElement> sentResult = new AtomicReference<>();
            CodexApprovalHandler handler = createHandler((id, result) -> sentResult.set(result));

            // Track a pending tool so resolveMcpToolApprovalAnswer can resolve it
            handler.trackPendingMcpTool("item-123", "search_text");

            JsonObject params = new JsonObject();
            params.addProperty("itemId", "item-123");
            JsonArray questions = new JsonArray();
            JsonObject q = new JsonObject();
            q.addProperty("id", "mcp_tool_call_approval_xyz");
            JsonArray opts = new JsonArray();
            JsonObject opt = new JsonObject();
            opt.addProperty("label", "Allow for this session");
            opts.add(opt);
            q.add("options", opts);
            questions.add(q);
            params.add("questions", questions);

            try (var mocked = org.mockito.Mockito.mockStatic(ToolLayerSettings.class)) {
                ToolLayerSettings mockSettings = mock(ToolLayerSettings.class);
                org.mockito.Mockito.when(mockSettings.getToolPermission(org.mockito.Mockito.anyString()))
                    .thenReturn(ToolPermission.ASK);
                mocked.when(() -> ToolLayerSettings.getInstance(org.mockito.Mockito.any()))
                    .thenReturn(mockSettings);

                handler.handleUserInputRequest(new JsonPrimitive(42), params);
            }

            assertNotNull(sentResult.get());
            JsonObject result = sentResult.get().getAsJsonObject();
            assertTrue(result.has("answers"));
            JsonObject answers = result.getAsJsonObject("answers");
            assertTrue(answers.has("mcp_tool_call_approval_xyz"));

            JsonObject answer = answers.getAsJsonObject("mcp_tool_call_approval_xyz");
            String label = answer.getAsJsonArray("answers").get(0).getAsString();
            assertEquals("Allow for this session", label);
        }

        @Test
        void emptyQuestions_sendsEmptyAnswers() {
            AtomicReference<JsonElement> sentResult = new AtomicReference<>();
            CodexApprovalHandler handler = createHandler((id, result) -> sentResult.set(result));

            JsonObject params = new JsonObject();
            params.add("questions", new JsonArray());

            handler.handleUserInputRequest(new JsonPrimitive(1), params);

            JsonObject result = sentResult.get().getAsJsonObject();
            assertEquals(0, result.getAsJsonObject("answers").size());
        }
    }

    // ── Permission request listener ──────────────────────────────────────────

    @Nested
    class PermissionRequestListener {

        @Test
        void setAndClear() {
            CodexApprovalHandler handler = createHandler();

            handler.setPermissionRequestListener(prompt -> { /* verify it accepts a listener */ });
            handler.setPermissionRequestListener(null);
            // No exception thrown — null clears it
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static CodexApprovalHandler createHandler() {
        return createHandler((id, result) -> {
        });
    }

    private static CodexApprovalHandler createHandler(CodexApprovalHandler.ResponseSender sender) {
        return new CodexApprovalHandler(mock(Project.class), sender);
    }
}
