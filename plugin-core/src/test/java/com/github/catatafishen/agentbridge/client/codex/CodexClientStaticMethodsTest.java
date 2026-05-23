package com.github.catatafishen.agentbridge.client.codex;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the pure static methods in {@link CodexClient}.
 */
class CodexClientStaticMethodsTest {

    // ── classifyMessageType ───────────────────────────────────────────────────

    @Nested
    class ClassifyMessageType {

        @Test
        void classifiesResponse() {
            JsonObject msg = new JsonObject();
            msg.addProperty("id", 1);
            msg.add("result", new JsonObject());
            assertEquals(CodexClient.MessageType.RESPONSE, CodexClient.classifyMessageType(msg));
        }

        @Test
        void classifiesServerRequest() {
            JsonObject msg = new JsonObject();
            msg.addProperty("id", 1);
            msg.addProperty("method", "tools/call");
            assertEquals(CodexClient.MessageType.SERVER_REQUEST, CodexClient.classifyMessageType(msg));
        }

        @Test
        void classifiesNotification() {
            JsonObject msg = new JsonObject();
            msg.addProperty("method", "turn/update");
            assertEquals(CodexClient.MessageType.NOTIFICATION, CodexClient.classifyMessageType(msg));
        }

        @Test
        void classifiesUnknown() {
            JsonObject msg = new JsonObject();
            assertEquals(CodexClient.MessageType.UNKNOWN, CodexClient.classifyMessageType(msg));
        }

        @Test
        void responseWithError() {
            JsonObject msg = new JsonObject();
            msg.addProperty("id", 1);
            msg.add("error", new JsonObject());
            assertEquals(CodexClient.MessageType.RESPONSE, CodexClient.classifyMessageType(msg));
        }
    }

    // ── buildServerCommandStatic ──────────────────────────────────────────────

    @Nested
    class BuildServerCommandStatic {

        @Test
        void buildsBasicCommand() {
            List<String> cmd = CodexClient.buildServerCommandStatic("/usr/bin/codex", 0);
            assertEquals("/usr/bin/codex", cmd.get(0));
            assertEquals("app-server", cmd.get(1));
            assertTrue(cmd.contains("features.shell_tool=false"));
            assertTrue(cmd.contains("features.unified_exec=false"));
        }

        @Test
        void includesMcpServerWhenPortPositive() {
            List<String> cmd = CodexClient.buildServerCommandStatic("/usr/bin/codex", 8080);
            assertTrue(cmd.stream().anyMatch(s -> s.contains("mcp_servers.agentbridge.url")));
            assertTrue(cmd.stream().anyMatch(s -> s.contains("localhost:8080")));
        }

        @Test
        void excludesMcpServerWhenPortZero() {
            List<String> cmd = CodexClient.buildServerCommandStatic("/usr/bin/codex", 0);
            assertFalse(cmd.stream().anyMatch(s -> s.contains("mcp_servers")));
        }

        @Test
        void excludesMcpServerWhenPortNegative() {
            List<String> cmd = CodexClient.buildServerCommandStatic("/usr/bin/codex", -1);
            assertFalse(cmd.stream().anyMatch(s -> s.contains("mcp_servers")));
        }
    }

    // ── buildCommandArgsJson ──────────────────────────────────────────────────

    @Nested
    class BuildCommandArgsJson {

        @Test
        void buildsSimpleCommand() {
            String result = CodexClient.buildCommandArgsJson("ls -la");
            assertEquals("{\"command\":\"ls -la\"}", result);
        }

        @Test
        void escapesQuotesInCommand() {
            String result = CodexClient.buildCommandArgsJson("echo \"hello\"");
            assertEquals("{\"command\":\"echo \\\"hello\\\"\"}", result);
        }

        @Test
        void handlesEmptyCommand() {
            String result = CodexClient.buildCommandArgsJson("");
            assertEquals("{\"command\":\"\"}", result);
        }
    }

    // ── extractJsonRpcErrorMessage ────────────────────────────────────────────

    @Nested
    class ExtractJsonRpcErrorMessage {

        @Test
        void extractsMessage() {
            JsonObject error = new JsonObject();
            error.addProperty("message", "Something went wrong");
            error.addProperty("code", -32600);
            String result = CodexClient.extractJsonRpcErrorMessage(error);
            assertTrue(result.contains("Something went wrong"));
        }
    }

    // ── isCodexAuthError ──────────────────────────────────────────────────────

    @Nested
    class IsCodexAuthError {

        @Test
        void nullIsFalse() {
            assertFalse(CodexClient.isCodexAuthError(null));
        }

        @Test
        void normalTextIsFalse() {
            assertFalse(CodexClient.isCodexAuthError("Everything is fine"));
        }
    }
}
