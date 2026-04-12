package com.github.catatafishen.agentbridge.agent.claude;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for pure static methods in {@link ClaudeCliClient} that are untested
 * by {@link ClaudeCliClientTest} (which covers extractErrorText, safeGetInt, safeGetDouble).
 */
class ClaudeCliClientStaticMethodsTest {

    private static final Method BUILD_JSON_USER_MESSAGE;

    static {
        try {
            BUILD_JSON_USER_MESSAGE = ClaudeCliClient.class
                .getDeclaredMethod("buildJsonUserMessage", String.class);
            BUILD_JSON_USER_MESSAGE.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("Method not found — signature changed?", e);
        }
    }

    // ── buildJsonUserMessage (private static) ───────────────────────────────

    @Test
    void buildJsonUserMessage_createsValidJsonEnvelope() throws Exception {
        String result = invokeBuildJsonUserMessage("Hello");
        JsonObject parsed = JsonParser.parseString(result).getAsJsonObject();

        assertEquals("user", parsed.get("type").getAsString());

        JsonObject message = parsed.getAsJsonObject("message");
        assertEquals("user", message.get("role").getAsString());

        JsonArray content = message.getAsJsonArray("content");
        assertEquals(1, content.size());

        JsonObject textBlock = content.get(0).getAsJsonObject();
        assertEquals("text", textBlock.get("type").getAsString());
        assertEquals("Hello", textBlock.get("text").getAsString());
    }

    @Test
    void buildJsonUserMessage_preservesSpecialCharacters() throws Exception {
        String prompt = "Line1\nLine2\twith \"quotes\"";
        String result = invokeBuildJsonUserMessage(prompt);
        JsonObject parsed = JsonParser.parseString(result).getAsJsonObject();

        String text = parsed.getAsJsonObject("message")
            .getAsJsonArray("content")
            .get(0).getAsJsonObject()
            .get("text").getAsString();

        assertEquals(prompt, text);
    }

    @Test
    void buildJsonUserMessage_handlesEmptyPrompt() throws Exception {
        String result = invokeBuildJsonUserMessage("");
        JsonObject parsed = JsonParser.parseString(result).getAsJsonObject();
        String text = parsed.getAsJsonObject("message")
            .getAsJsonArray("content")
            .get(0).getAsJsonObject()
            .get("text").getAsString();
        assertEquals("", text);
    }

    @Test
    void buildJsonUserMessage_hasCorrectStructure() throws Exception {
        String result = invokeBuildJsonUserMessage("test");
        JsonObject parsed = JsonParser.parseString(result).getAsJsonObject();

        assertTrue(parsed.has("type"), "Top-level should have 'type'");
        assertTrue(parsed.has("message"), "Top-level should have 'message'");
        JsonObject message = parsed.getAsJsonObject("message");
        assertTrue(message.has("role"), "Message should have 'role'");
        assertTrue(message.has("content"), "Message should have 'content'");
        assertTrue(message.get("content").isJsonArray(), "Content should be an array");
    }

    @Test
    void buildJsonUserMessage_contentBlockHasTextType() throws Exception {
        String result = invokeBuildJsonUserMessage("prompt");
        JsonObject parsed = JsonParser.parseString(result).getAsJsonObject();
        JsonObject block = parsed.getAsJsonObject("message")
            .getAsJsonArray("content")
            .get(0).getAsJsonObject();
        assertEquals("text", block.get("type").getAsString());
    }

    private static String invokeBuildJsonUserMessage(String prompt) throws Exception {
        return (String) BUILD_JSON_USER_MESSAGE.invoke(null, prompt);
    }

    // ── extractToolResultContent (now package-private static) ────────────────

    @Test
    void extractToolResultContent_returnsEmptyForNoContentField() {
        assertEquals("", ClaudeCliClient.extractToolResultContent(new JsonObject()));
    }

    @Test
    void extractToolResultContent_returnsStringContent() {
        JsonObject event = new JsonObject();
        event.addProperty("content", "Hello world");
        assertEquals("Hello world", ClaudeCliClient.extractToolResultContent(event));
    }

    @Test
    void extractToolResultContent_extractsTextFromContentBlockArray() {
        JsonArray content = new JsonArray();
        JsonObject block1 = new JsonObject();
        block1.addProperty("type", "text");
        block1.addProperty("text", "First. ");
        content.add(block1);
        JsonObject block2 = new JsonObject();
        block2.addProperty("type", "text");
        block2.addProperty("text", "Second.");
        content.add(block2);

        JsonObject event = new JsonObject();
        event.add("content", content);
        assertEquals("First. Second.", ClaudeCliClient.extractToolResultContent(event));
    }

    @Test
    void extractToolResultContent_handlesPrimitiveStringsInArray() {
        JsonArray content = new JsonArray();
        content.add("plain string");
        JsonObject event = new JsonObject();
        event.add("content", content);
        assertEquals("plain string", ClaudeCliClient.extractToolResultContent(event));
    }

    @Test
    void extractToolResultContent_skipsNonObjectNonPrimitiveInArray() {
        JsonArray content = new JsonArray();
        JsonArray nested = new JsonArray();
        nested.add("ignored");
        content.add(nested);
        JsonObject event = new JsonObject();
        event.add("content", content);
        assertEquals("", ClaudeCliClient.extractToolResultContent(event));
    }

    @Test
    void extractToolResultContent_fallsBackToToStringForOtherTypes() {
        JsonObject contentObj = new JsonObject();
        contentObj.addProperty("key", "value");
        JsonObject event = new JsonObject();
        event.add("content", contentObj);
        String result = ClaudeCliClient.extractToolResultContent(event);
        assertTrue(result.contains("key"));
        assertTrue(result.contains("value"));
    }
}
