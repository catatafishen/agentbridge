package com.github.catatafishen.agentbridge.agent.claude;

import com.github.catatafishen.agentbridge.acp.model.ContentBlock;
import com.github.catatafishen.agentbridge.acp.model.Model;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeCliClientTest {

    // ── extractErrorText (private static) ───────────────────────────────

    @Test
    void extractErrorText_string() throws Exception {
        assertEquals("boom", invokeExtractErrorText(JsonParser.parseString("\"boom\"")));
    }

    @Test
    void extractErrorText_objectWithMessage() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("message", "auth failed");
        assertEquals("auth failed", invokeExtractErrorText(obj));
    }

    @Test
    void extractErrorText_objectWithoutMessage() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("code", 401);
        assertEquals("{\"code\":401}", invokeExtractErrorText(obj));
    }

    @Test
    void extractErrorText_number() throws Exception {
        assertEquals("42", invokeExtractErrorText(JsonParser.parseString("42")));
    }

    @Test
    void extractErrorText_boolean() throws Exception {
        assertEquals("true", invokeExtractErrorText(JsonParser.parseString("true")));
    }

    // ── safeGetInt (private static) ─────────────────────────────────────

    @Test
    void safeGetInt_exists() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("tokens", 42);
        assertEquals(42, invokeSafeGetInt(obj, "tokens"));
    }

    @Test
    void safeGetInt_missing() throws Exception {
        assertEquals(0, invokeSafeGetInt(new JsonObject(), "tokens"));
    }

    @Test
    void safeGetInt_null() throws Exception {
        JsonObject obj = new JsonObject();
        obj.add("tokens", null);
        assertEquals(0, invokeSafeGetInt(obj, "tokens"));
    }

    @Test
    void safeGetInt_zero() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("tokens", 0);
        assertEquals(0, invokeSafeGetInt(obj, "tokens"));
    }

    // ── safeGetDouble (private static) ──────────────────────────────────

    @Test
    void safeGetDouble_exists() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("cost", 0.042);
        assertEquals(0.042, invokeSafeGetDouble(obj, "cost"), 1e-9);
    }

    @Test
    void safeGetDouble_missing() throws Exception {
        assertEquals(0.0, invokeSafeGetDouble(new JsonObject(), "cost"), 1e-9);
    }

    @Test
    void safeGetDouble_null() throws Exception {
        JsonObject obj = new JsonObject();
        obj.add("cost", null);
        assertEquals(0.0, invokeSafeGetDouble(obj, "cost"), 1e-9);
    }

    @Test
    void safeGetDouble_zero() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("cost", 0.0);
        assertEquals(0.0, invokeSafeGetDouble(obj, "cost"), 1e-9);
    }

    @Test
    void safeGetDouble_intValue() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("cost", 5);
        assertEquals(5.0, invokeSafeGetDouble(obj, "cost"), 1e-9);
    }

    // ── buildKnownModels ────────────────────────────────────────────────

    @Nested
    class BuildKnownModels {
        private final List<Model> models = ClaudeCliClient.buildKnownModels();

        @Test
        void returnsSomeModels() {
            assertFalse(models.isEmpty());
        }

        @Test
        void firstModelIsDefault() {
            assertEquals("default", models.getFirst().id());
            assertTrue(models.getFirst().name().toLowerCase().contains("default"));
        }

        @Test
        void containsSonnet() {
            assertTrue(models.stream().anyMatch(m -> "sonnet".equals(m.id())));
        }

        @Test
        void containsOpus() {
            assertTrue(models.stream().anyMatch(m -> "opus".equals(m.id())));
        }

        @Test
        void containsHaiku() {
            assertTrue(models.stream().anyMatch(m -> "haiku".equals(m.id())));
        }

        @Test
        void allHaveNonBlankId() {
            for (Model m : models) {
                assertNotNull(m.id());
                assertFalse(m.id().isBlank(), "Model id must not be blank");
            }
        }

        @Test
        void allHaveNonBlankDisplayName() {
            for (Model m : models) {
                assertNotNull(m.name());
                assertFalse(m.name().isBlank(), "Display name for " + m.id() + " must not be blank");
            }
        }

        @Test
        void listIsUnmodifiable() {
            assertThrows(UnsupportedOperationException.class,
                () -> models.add(new Model("test", "test", null, null)));
        }
    }

    // ── extractPromptText ───────────────────────────────────────────────

    @Nested
    class ExtractPromptText {
        @Test
        void textBlocks() {
            List<ContentBlock> blocks = List.of(
                new ContentBlock.Text("Hello "),
                new ContentBlock.Text("world")
            );
            assertEquals("Hello world", ClaudeCliClient.extractPromptText(blocks));
        }

        @Test
        void emptyBlocks() {
            assertEquals("", ClaudeCliClient.extractPromptText(List.of()));
        }

        @Test
        void resourceBlockWithText() {
            ContentBlock.ResourceLink rl = new ContentBlock.ResourceLink(
                "file:///path/to/file.txt", "file.txt", "text/plain", "line1\nline2", null);
            List<ContentBlock> blocks = List.of(new ContentBlock.Resource(rl));
            String result = ClaudeCliClient.extractPromptText(blocks);
            assertTrue(result.contains("File: file:///path/to/file.txt"));
            assertTrue(result.contains("line1\nline2"));
            assertTrue(result.contains("```"));
        }

        @Test
        void resourceBlockWithEmptyText() {
            ContentBlock.ResourceLink rl = new ContentBlock.ResourceLink(
                "file:///path/to/file.txt", "file.txt", "text/plain", "", null);
            List<ContentBlock> blocks = List.of(new ContentBlock.Resource(rl));
            assertEquals("", ClaudeCliClient.extractPromptText(blocks));
        }

        @Test
        void resourceBlockWithNullText() {
            ContentBlock.ResourceLink rl = new ContentBlock.ResourceLink(
                "file:///path/to/file.txt", "file.txt", "text/plain", null, null);
            List<ContentBlock> blocks = List.of(new ContentBlock.Resource(rl));
            assertEquals("", ClaudeCliClient.extractPromptText(blocks));
        }

        @Test
        void mixedBlocks() {
            ContentBlock.ResourceLink rl = new ContentBlock.ResourceLink(
                "file:///test.txt", "test.txt", "text/plain", "content", null);
            List<ContentBlock> blocks = List.of(
                new ContentBlock.Text("Before "),
                new ContentBlock.Resource(rl),
                new ContentBlock.Text("After")
            );
            String result = ClaudeCliClient.extractPromptText(blocks);
            assertTrue(result.startsWith("Before "));
            assertTrue(result.endsWith("After"));
            assertTrue(result.contains("content"));
        }
    }

    // ── buildJsonUserMessage ────────────────────────────────────────────

    @Nested
    class BuildJsonUserMessage {
        @Test
        void validStructure() {
            String json = ClaudeCliClient.buildJsonUserMessage("Hello");
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
            assertEquals("user", parsed.get("type").getAsString());
            assertTrue(parsed.has("message"));
            JsonObject msg = parsed.getAsJsonObject("message");
            assertEquals("user", msg.get("role").getAsString());
            assertTrue(msg.has("content"));
            JsonArray content = msg.getAsJsonArray("content");
            assertEquals(1, content.size());
            JsonObject textBlock = content.get(0).getAsJsonObject();
            assertEquals("text", textBlock.get("type").getAsString());
            assertEquals("Hello", textBlock.get("text").getAsString());
        }

        @Test
        void preservesSpecialCharacters() {
            String json = ClaudeCliClient.buildJsonUserMessage("\"quoted\" & <tags>");
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
            String text = parsed.getAsJsonObject("message")
                .getAsJsonArray("content").get(0).getAsJsonObject()
                .get("text").getAsString();
            assertEquals("\"quoted\" & <tags>", text);
        }

        @Test
        void emptyPrompt() {
            String json = ClaudeCliClient.buildJsonUserMessage("");
            JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
            String text = parsed.getAsJsonObject("message")
                .getAsJsonArray("content").get(0).getAsJsonObject()
                .get("text").getAsString();
            assertEquals("", text);
        }
    }

    // ── extractToolResultContent ────────────────────────────────────────

    @Nested
    class ExtractToolResultContent {
        @Test
        void stringContent() {
            JsonObject event = new JsonObject();
            event.addProperty("content", "result text");
            assertEquals("result text", ClaudeCliClient.extractToolResultContent(event));
        }

        @Test
        void arrayContent_textBlocks() {
            JsonObject block = new JsonObject();
            block.addProperty("text", "Hello ");
            JsonObject block2 = new JsonObject();
            block2.addProperty("text", "world");
            JsonArray arr = new JsonArray();
            arr.add(block);
            arr.add(block2);
            JsonObject event = new JsonObject();
            event.add("content", arr);
            assertEquals("Hello world", ClaudeCliClient.extractToolResultContent(event));
        }

        @Test
        void arrayContent_primitiveStrings() {
            JsonArray arr = new JsonArray();
            arr.add("foo");
            arr.add("bar");
            JsonObject event = new JsonObject();
            event.add("content", arr);
            assertEquals("foobar", ClaudeCliClient.extractToolResultContent(event));
        }

        @Test
        void noContent() {
            assertEquals("", ClaudeCliClient.extractToolResultContent(new JsonObject()));
        }

        @Test
        void objectContent_fallsBackToString() {
            JsonObject inner = new JsonObject();
            inner.addProperty("key", "value");
            JsonObject event = new JsonObject();
            event.add("content", inner);
            String result = ClaudeCliClient.extractToolResultContent(event);
            assertTrue(result.contains("key"));
        }
    }

    // ── createDefaultProfile ────────────────────────────────────────────

    @Nested
    class CreateDefaultProfile {
        @Test
        void profileIdIsSet() {
            assertEquals("claude-cli", ClaudeCliClient.createDefaultProfile().getId());
        }

        @Test
        void displayNameIsNonEmpty() {
            assertFalse(ClaudeCliClient.createDefaultProfile().getDisplayName().isEmpty());
        }

        @Test
        void binaryNameIsSet() {
            assertEquals("claude", ClaudeCliClient.createDefaultProfile().getBinaryName());
        }

        @Test
        void excludesBuiltInTools() {
            assertTrue(ClaudeCliClient.createDefaultProfile().isExcludeAgentBuiltInTools());
        }

        @Test
        void usesPluginPermissions() {
            assertTrue(ClaudeCliClient.createDefaultProfile().isUsePluginPermissions());
        }
    }

    // ── Reflection helpers ──────────────────────────────────────────────

    private static String invokeExtractErrorText(com.google.gson.JsonElement el) throws Exception {
        Method m = ClaudeCliClient.class.getDeclaredMethod("extractErrorText", com.google.gson.JsonElement.class);
        m.setAccessible(true);
        return (String) m.invoke(null, el);
    }

    private static int invokeSafeGetInt(JsonObject obj, String field) throws Exception {
        Method m = ClaudeCliClient.class.getDeclaredMethod("safeGetInt", JsonObject.class, String.class);
        m.setAccessible(true);
        return (int) m.invoke(null, obj, field);
    }

    private static double invokeSafeGetDouble(JsonObject obj, String field) throws Exception {
        Method m = ClaudeCliClient.class.getDeclaredMethod("safeGetDouble", JsonObject.class, String.class);
        m.setAccessible(true);
        return (double) m.invoke(null, obj, field);
    }
}
