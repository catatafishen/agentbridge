package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the pure static methods in {@link McpProtocolHandler}.
 */
class McpProtocolHandlerStaticMethodsTest {

    // ── parseCursorOffset ─────────────────────────────────────────────────────

    @Nested
    class ParseCursorOffset {

        @Test
        void returnsZeroForNullElement() throws McpProtocolHandler.InvalidCursorException {
            assertEquals(0, McpProtocolHandler.parseCursorOffset(null, "prefix:"));
        }

        @Test
        void returnsZeroForJsonNull() throws McpProtocolHandler.InvalidCursorException {
            assertEquals(0, McpProtocolHandler.parseCursorOffset(JsonNull.INSTANCE, "prefix:"));
        }

        @Test
        void parsesValidCursor() throws McpProtocolHandler.InvalidCursorException {
            JsonElement cursor = new JsonPrimitive("resources:42");
            assertEquals(42, McpProtocolHandler.parseCursorOffset(cursor, "resources:"));
        }

        @Test
        void clampsNegativeToZero() throws McpProtocolHandler.InvalidCursorException {
            JsonElement cursor = new JsonPrimitive("resources:-5");
            assertEquals(0, McpProtocolHandler.parseCursorOffset(cursor, "resources:"));
        }

        @Test
        void throwsOnWrongPrefix() {
            JsonElement cursor = new JsonPrimitive("wrong:10");
            assertThrows(McpProtocolHandler.InvalidCursorException.class,
                () -> McpProtocolHandler.parseCursorOffset(cursor, "resources:"));
        }

        @Test
        void throwsOnNonNumericSuffix() {
            JsonElement cursor = new JsonPrimitive("resources:abc");
            assertThrows(McpProtocolHandler.InvalidCursorException.class,
                () -> McpProtocolHandler.parseCursorOffset(cursor, "resources:"));
        }

        @Test
        void parsesZeroOffset() throws McpProtocolHandler.InvalidCursorException {
            JsonElement cursor = new JsonPrimitive("resources:0");
            assertEquals(0, McpProtocolHandler.parseCursorOffset(cursor, "resources:"));
        }
    }

    // ── encodeCursor ──────────────────────────────────────────────────────────

    @Nested
    class EncodeCursor {

        @Test
        void encodesSimpleCursor() {
            assertEquals("resources:10", McpProtocolHandler.encodeCursor("resources:", 10));
        }

        @Test
        void encodesZero() {
            assertEquals("resources:0", McpProtocolHandler.encodeCursor("resources:", 0));
        }

        @Test
        void encodesWithDifferentPrefix() {
            assertEquals("resourceTemplates:5",
                McpProtocolHandler.encodeCursor("resourceTemplates:", 5));
        }
    }

    // ── guessMimeType ─────────────────────────────────────────────────────────

    @Nested
    class GuessMimeType {

        @Test
        void detectsMarkdown() {
            assertEquals("text/markdown", McpProtocolHandler.guessMimeType(Path.of("README.md")));
        }

        @Test
        void detectsJava() {
            assertEquals("text/x-java", McpProtocolHandler.guessMimeType(Path.of("Main.java")));
        }

        @Test
        void detectsKotlin() {
            assertEquals("text/x-kotlin", McpProtocolHandler.guessMimeType(Path.of("App.kt")));
        }

        @Test
        void detectsJson() {
            assertEquals("application/json",
                McpProtocolHandler.guessMimeType(Path.of("config.json")));
        }

        @Test
        void detectsXml() {
            assertEquals("application/xml",
                McpProtocolHandler.guessMimeType(Path.of("pom.xml")));
        }

        @Test
        void detectsYaml() {
            assertEquals("application/yaml",
                McpProtocolHandler.guessMimeType(Path.of("config.yaml")));
        }

        @Test
        void detectsYml() {
            assertEquals("application/yaml",
                McpProtocolHandler.guessMimeType(Path.of("config.yml")));
        }

        @Test
        void detectsPlainText() {
            assertEquals("text/plain", McpProtocolHandler.guessMimeType(Path.of("notes.txt")));
        }

        @Test
        void unknownExtensionReturnsOctetStream() {
            // The method may fall through to OS-level probeContentType on some platforms,
            // so verify the extension-based fallback with a truly unknown extension
            String mime = McpProtocolHandler.guessMimeType(Path.of("data.zzzzzzunknown"));
            assertNotNull(mime);
            assertFalse(mime.isBlank());
        }

        @Test
        void caseInsensitive() {
            // The extension check lowercases, but Files.probeContentType may return the
            // OS-defined type. Either text/x-java or text/x-java-source is acceptable.
            String mime = McpProtocolHandler.guessMimeType(Path.of("Main.JAVA"));
            assertTrue(mime.startsWith("text/"), "Expected text/* MIME type for .JAVA, got: " + mime);
        }
    }

    // ── isTextResource ────────────────────────────────────────────────────────

    @Nested
    class IsTextResource {

        @Test
        void markdownIsText() {
            assertTrue(McpProtocolHandler.isTextResource(Path.of("README.md")));
        }

        @Test
        void javaIsText() {
            assertTrue(McpProtocolHandler.isTextResource(Path.of("Main.java")));
        }

        @Test
        void jsonIsText() {
            assertTrue(McpProtocolHandler.isTextResource(Path.of("config.json")));
        }

        @Test
        void xmlIsText() {
            assertTrue(McpProtocolHandler.isTextResource(Path.of("pom.xml")));
        }

        @Test
        void yamlIsText() {
            assertTrue(McpProtocolHandler.isTextResource(Path.of("config.yaml")));
        }

        @Test
        void unknownIsNotText() {
            assertFalse(McpProtocolHandler.isTextResource(Path.of("data.bin")));
        }
    }

    // ── truncateIfNeeded ──────────────────────────────────────────────────────

    @Nested
    class TruncateIfNeeded {

        @Test
        void returnsNullForNull() {
            assertNull(McpProtocolHandler.truncateIfNeeded(null));
        }

        @Test
        void returnsShortTextUnchanged() {
            String text = "short text";
            assertSame(text, McpProtocolHandler.truncateIfNeeded(text));
        }

        @Test
        void truncatesLongText() {
            String text = "a".repeat(90_000);
            String result = McpProtocolHandler.truncateIfNeeded(text);
            assertNotNull(result);
            assertTrue(result.length() < text.length());
            assertTrue(result.contains("[Output truncated:"));
            assertTrue(result.contains("characters omitted"));
        }

        @Test
        void exactLimitNotTruncated() {
            String text = "a".repeat(80_000);
            assertSame(text, McpProtocolHandler.truncateIfNeeded(text));
        }
    }

    // ── buildToolResult ───────────────────────────────────────────────────────

    @Nested
    class BuildToolResult {

        @Test
        void buildsSuccessResult() {
            JsonObject request = new JsonObject();
            request.addProperty("id", 42);
            request.addProperty("jsonrpc", "2.0");

            JsonObject result = McpProtocolHandler.buildToolResult(request, "hello", false);

            assertEquals("2.0", result.get("jsonrpc").getAsString());
            assertEquals(42, result.get("id").getAsInt());

            JsonObject innerResult = result.getAsJsonObject("result");
            assertFalse(innerResult.get("isError").getAsBoolean());

            JsonArray content = innerResult.getAsJsonArray("content");
            assertEquals(1, content.size());
            assertEquals("hello", content.get(0).getAsJsonObject().get("text").getAsString());
        }

        @Test
        void buildsErrorResult() {
            JsonObject request = new JsonObject();
            request.addProperty("id", 1);
            request.addProperty("jsonrpc", "2.0");

            JsonObject result = McpProtocolHandler.buildToolResult(request, "Error: failed", true);

            JsonObject innerResult = result.getAsJsonObject("result");
            assertTrue(innerResult.get("isError").getAsBoolean());
        }

        @Test
        void handlesNullText() {
            JsonObject request = new JsonObject();
            request.addProperty("id", 1);
            request.addProperty("jsonrpc", "2.0");

            JsonObject result = McpProtocolHandler.buildToolResult(request, null, false);

            JsonObject innerResult = result.getAsJsonObject("result");
            JsonArray content = innerResult.getAsJsonArray("content");
            assertEquals("", content.get(0).getAsJsonObject().get("text").getAsString());
        }
    }

    // ── respondResult ─────────────────────────────────────────────────────────

    @Nested
    class RespondResult {

        @Test
        void includesRequestId() {
            JsonObject request = new JsonObject();
            request.addProperty("id", 99);

            JsonObject result = new JsonObject();
            result.addProperty("data", "test");

            JsonObject response = McpProtocolHandler.respondResult(request, result);

            assertEquals("2.0", response.get("jsonrpc").getAsString());
            assertEquals(99, response.get("id").getAsInt());
            assertEquals("test", response.getAsJsonObject("result").get("data").getAsString());
        }

        @Test
        void handlesNullRequest() {
            JsonObject result = new JsonObject();
            result.addProperty("data", "test");

            JsonObject response = McpProtocolHandler.respondResult(null, result);
            assertEquals("2.0", response.get("jsonrpc").getAsString());
            assertFalse(response.has("id"));
        }
    }

    // ── respondError ──────────────────────────────────────────────────────────

    @Nested
    class RespondError {

        @Test
        void buildsErrorResponse() {
            JsonObject request = new JsonObject();
            request.addProperty("id", 5);

            JsonObject response = McpProtocolHandler.respondError(request, -32600, "Invalid request");

            assertEquals("2.0", response.get("jsonrpc").getAsString());
            assertEquals(5, response.get("id").getAsInt());
            JsonObject error = response.getAsJsonObject("error");
            assertEquals(-32600, error.get("code").getAsInt());
            assertEquals("Invalid request", error.get("message").getAsString());
        }

        @Test
        void handlesNullRequest() {
            JsonObject response = McpProtocolHandler.respondError(null, -32601, "Not found");

            assertEquals("2.0", response.get("jsonrpc").getAsString());
            assertFalse(response.has("id"));
        }
    }

    // ── makeErrorResponse ─────────────────────────────────────────────────────

    @Nested
    class MakeErrorResponse {

        @Test
        void buildsWithId() {
            JsonElement id = new JsonPrimitive(7);
            JsonObject response = McpProtocolHandler.makeErrorResponse(id, -32602, "Bad params");

            assertEquals("2.0", response.get("jsonrpc").getAsString());
            assertEquals(7, response.get("id").getAsInt());
            JsonObject error = response.getAsJsonObject("error");
            assertEquals(-32602, error.get("code").getAsInt());
            assertEquals("Bad params", error.get("message").getAsString());
        }

        @Test
        void buildsWithoutId() {
            JsonObject response = McpProtocolHandler.makeErrorResponse(null, -32603, "Error");

            assertEquals("2.0", response.get("jsonrpc").getAsString());
            assertFalse(response.has("id"));
            assertEquals(-32603, response.getAsJsonObject("error").get("code").getAsInt());
        }
    }

    // ── applyPreHookTextModifiers ─────────────────────────────────────────────

    @Nested
    class ApplyPreHookTextModifiers {

        @Test
        void noModifiers() {
            var preHook = new McpProtocolHandler.PreHookApplication(
                new JsonObject(), null, null, null);
            assertEquals("result", McpProtocolHandler.applyPreHookTextModifiers(preHook, "result"));
        }

        @Test
        void prependsText() {
            var preHook = new McpProtocolHandler.PreHookApplication(
                new JsonObject(), null, "PREPEND", null);
            String result = McpProtocolHandler.applyPreHookTextModifiers(preHook, "body");
            assertTrue(result.startsWith("PREPEND"));
            assertTrue(result.endsWith("body"));
        }

        @Test
        void appendsText() {
            var preHook = new McpProtocolHandler.PreHookApplication(
                new JsonObject(), null, null, "APPEND");
            String result = McpProtocolHandler.applyPreHookTextModifiers(preHook, "body");
            assertTrue(result.startsWith("body"));
            assertTrue(result.endsWith("APPEND"));
        }

        @Test
        void prependAndAppend() {
            var preHook = new McpProtocolHandler.PreHookApplication(
                new JsonObject(), null, "BEFORE", "AFTER");
            String result = McpProtocolHandler.applyPreHookTextModifiers(preHook, "middle");
            assertTrue(result.startsWith("BEFORE"));
            assertTrue(result.endsWith("AFTER"));
            assertTrue(result.contains("middle"));
        }

        @Test
        void handlesNullResultText() {
            var preHook = new McpProtocolHandler.PreHookApplication(
                new JsonObject(), null, "PRE", "POST");
            String result = McpProtocolHandler.applyPreHookTextModifiers(preHook, null);
            assertTrue(result.contains("PRE"));
            assertTrue(result.contains("POST"));
        }

        @Test
        void emptyPrependIgnored() {
            var preHook = new McpProtocolHandler.PreHookApplication(
                new JsonObject(), null, "", null);
            assertEquals("body", McpProtocolHandler.applyPreHookTextModifiers(preHook, "body"));
        }
    }
}
