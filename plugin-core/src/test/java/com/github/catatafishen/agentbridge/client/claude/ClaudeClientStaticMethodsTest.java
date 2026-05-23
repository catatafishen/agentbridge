package com.github.catatafishen.agentbridge.client.claude;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the pure static methods in {@link ClaudeClient}.
 */
class ClaudeClientStaticMethodsTest {

    // ── isClaudeAuthError ─────────────────────────────────────────────────────

    @Nested
    class IsClaudeAuthError {

        @Test
        void returnsFalseForNull() {
            assertFalse(ClaudeClient.isClaudeAuthError(null));
        }

        @Test
        void detectsInvalidApiKey() {
            assertTrue(ClaudeClient.isClaudeAuthError("Error: Invalid API key provided"));
        }

        @Test
        void detectsLoginRequired() {
            assertTrue(ClaudeClient.isClaudeAuthError("Please run /login to authenticate"));
        }

        @Test
        void detectsLoginWithBackticks() {
            assertTrue(ClaudeClient.isClaudeAuthError("Please run `/login` first"));
        }

        @Test
        void detectsNotAuthenticated() {
            assertTrue(ClaudeClient.isClaudeAuthError("Not authenticated"));
        }

        @Test
        void detectsUnauthorized() {
            assertTrue(ClaudeClient.isClaudeAuthError("Unauthorized access"));
        }

        @Test
        void detectsAuthRequired() {
            assertTrue(ClaudeClient.isClaudeAuthError("Authentication required for this action"));
        }

        @Test
        void detects401() {
            assertTrue(ClaudeClient.isClaudeAuthError("Server returned 401"));
        }

        @Test
        void caseInsensitive() {
            assertTrue(ClaudeClient.isClaudeAuthError("INVALID API KEY"));
        }

        @Test
        void normalTextReturnsFalse() {
            assertFalse(ClaudeClient.isClaudeAuthError("Everything is fine"));
        }
    }

    // ── extractProfileName ────────────────────────────────────────────────────

    @Nested
    class ExtractProfileName {

        @Test
        void extractsProfile() {
            List<String> args = List.of("--verbose", "--profile", "my-profile", "--model", "sonnet");
            assertEquals("my-profile", ClaudeClient.extractProfileName(args));
        }

        @Test
        void returnsNullWhenNotPresent() {
            List<String> args = List.of("--verbose", "--model", "sonnet");
            assertNull(ClaudeClient.extractProfileName(args));
        }

        @Test
        void returnsNullForEmptyList() {
            assertNull(ClaudeClient.extractProfileName(List.of()));
        }

        @Test
        void returnsNullWhenProfileIsLastArg() {
            List<String> args = List.of("--verbose", "--profile");
            assertNull(ClaudeClient.extractProfileName(args));
        }

        @Test
        void findsFirstProfile() {
            List<String> args = List.of("--profile", "first", "--profile", "second");
            assertEquals("first", ClaudeClient.extractProfileName(args));
        }
    }

    // ── extractErrorText ──────────────────────────────────────────────────────

    @Nested
    class ExtractErrorText {

        @Test
        void extractsFromString() {
            JsonElement el = new JsonPrimitive("something went wrong");
            assertEquals("something went wrong", ClaudeClient.extractErrorText(el));
        }

        @Test
        void extractsFromObjectWithMessage() {
            JsonObject obj = new JsonObject();
            obj.addProperty("message", "detailed error");
            assertEquals("detailed error", ClaudeClient.extractErrorText(obj));
        }

        @Test
        void fallsBackToToStringForObject() {
            JsonObject obj = new JsonObject();
            obj.addProperty("code", 500);
            String result = ClaudeClient.extractErrorText(obj);
            assertTrue(result.contains("500"));
        }

        @Test
        void handlesNumber() {
            JsonElement el = new JsonPrimitive(42);
            assertEquals("42", ClaudeClient.extractErrorText(el));
        }
    }

    // ── safeGetInt ────────────────────────────────────────────────────────────

    @Nested
    class SafeGetInt {

        @Test
        void returnsMissingFieldAsZero() {
            assertEquals(0, ClaudeClient.safeGetInt(new JsonObject(), "count"));
        }

        @Test
        void returnsJsonNullAsZero() {
            JsonObject obj = new JsonObject();
            obj.add("count", JsonNull.INSTANCE);
            assertEquals(0, ClaudeClient.safeGetInt(obj, "count"));
        }

        @Test
        void returnsIntValue() {
            JsonObject obj = new JsonObject();
            obj.addProperty("count", 42);
            assertEquals(42, ClaudeClient.safeGetInt(obj, "count"));
        }
    }

    // ── safeGetDouble ─────────────────────────────────────────────────────────

    @Nested
    class SafeGetDouble {

        @Test
        void returnsMissingFieldAsZero() {
            assertEquals(0.0, ClaudeClient.safeGetDouble(new JsonObject(), "cost"));
        }

        @Test
        void returnsJsonNullAsZero() {
            JsonObject obj = new JsonObject();
            obj.add("cost", JsonNull.INSTANCE);
            assertEquals(0.0, ClaudeClient.safeGetDouble(obj, "cost"));
        }

        @Test
        void returnsDoubleValue() {
            JsonObject obj = new JsonObject();
            obj.addProperty("cost", 3.14);
            assertEquals(3.14, ClaudeClient.safeGetDouble(obj, "cost"), 0.001);
        }
    }

    // ── extractToolResultContent ──────────────────────────────────────────────

    @Nested
    class ExtractToolResultContent {

        @Test
        void returnsEmptyForMissingContent() {
            assertEquals("", ClaudeClient.extractToolResultContent(new JsonObject()));
        }

        @Test
        void extractsStringContent() {
            JsonObject event = new JsonObject();
            event.addProperty("content", "plain text result");
            assertEquals("plain text result", ClaudeClient.extractToolResultContent(event));
        }

        @Test
        void extractsArrayContent() {
            JsonObject textBlock = new JsonObject();
            textBlock.addProperty("text", "hello ");
            JsonObject textBlock2 = new JsonObject();
            textBlock2.addProperty("text", "world");

            JsonArray arr = new JsonArray();
            arr.add(textBlock);
            arr.add(textBlock2);

            JsonObject event = new JsonObject();
            event.add("content", arr);
            assertEquals("hello world", ClaudeClient.extractToolResultContent(event));
        }

        @Test
        void extractsPrimitiveArrayItems() {
            JsonArray arr = new JsonArray();
            arr.add("line1");
            arr.add("line2");

            JsonObject event = new JsonObject();
            event.add("content", arr);
            assertEquals("line1line2", ClaudeClient.extractToolResultContent(event));
        }

        @Test
        void fallsBackToToString() {
            JsonObject event = new JsonObject();
            event.addProperty("content", true);
            assertEquals("true", ClaudeClient.extractToolResultContent(event));
        }
    }

    // ── respondToControlRequest ───────────────────────────────────────────────

    @Nested
    class RespondToControlRequest {

        @Test
        void sendsAllowForCanUseTool() {
            JsonObject event = new JsonObject();
            event.addProperty("subtype", "can_use_tool");
            event.addProperty("requestId", "req-123");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ClaudeClient.respondToControlRequest(event, out);

            String written = out.toString(StandardCharsets.UTF_8);
            // respondToControlRequest writes JSON with newline; verify key elements
            assertFalse(written.isEmpty(), "Expected non-empty output, got empty string");
            assertTrue(written.contains("control_response"),
                "Expected 'control_response' in: " + written);
            assertTrue(written.contains("can_use_tool"),
                "Expected 'can_use_tool' in: " + written);
            assertTrue(written.contains("allow"),
                "Expected 'allow' in: " + written);
            assertTrue(written.contains("req-123"),
                "Expected requestId propagation in: " + written);
        }

        @Test
        void sendsResponseWithoutDecisionForOtherSubtype() {
            JsonObject event = new JsonObject();
            event.addProperty("subtype", "other");
            event.addProperty("requestId", "req-456");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ClaudeClient.respondToControlRequest(event, out);

            String written = out.toString(StandardCharsets.UTF_8);
            assertTrue(written.contains("control_response"));
            assertFalse(written.contains("allow"));
            assertTrue(written.contains("req-456"),
                "Expected requestId propagation in: " + written);
        }
    }

    // ── buildJsonUserMessage ──────────────────────────────────────────────────

    @Nested
    class BuildJsonUserMessage {

        @Test
        void buildsTextOnlyMessage() {
            String result = ClaudeClient.buildJsonUserMessage("hello", List.of());

            assertTrue(result.contains("\"type\":\"user\""));
            assertTrue(result.contains("\"role\":\"user\""));
            assertTrue(result.contains("\"text\":\"hello\""));
        }

        @Test
        void includesImageBlocks() {
            var img = new com.github.catatafishen.agentbridge.model.ContentBlock.Image(
                "base64data", "image/png");
            String result = ClaudeClient.buildJsonUserMessage("describe this", List.of(img));

            assertTrue(result.contains("\"type\":\"image\""));
            assertTrue(result.contains("\"media_type\":\"image/png\""));
            assertTrue(result.contains("\"data\":\"base64data\""));
            assertTrue(result.contains("describe this"));
        }
    }

    // ── buildKnownModels ──────────────────────────────────────────────────────

    @Nested
    class BuildKnownModels {

        @Test
        void returnsNonEmptyList() {
            var models = ClaudeClient.buildKnownModels();
            assertFalse(models.isEmpty());
        }

        @Test
        void allModelsHaveId() {
            for (var model : ClaudeClient.buildKnownModels()) {
                assertNotNull(model.id());
                assertFalse(model.id().isEmpty());
            }
        }

        @Test
        void allModelsHaveName() {
            for (var model : ClaudeClient.buildKnownModels()) {
                assertNotNull(model.name());
                assertFalse(model.name().isEmpty());
            }
        }
    }
}
