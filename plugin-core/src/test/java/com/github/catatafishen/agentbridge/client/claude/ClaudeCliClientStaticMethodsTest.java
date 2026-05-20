package com.github.catatafishen.agentbridge.client.claude;

import com.github.catatafishen.agentbridge.model.ContentBlock;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for pure static methods in {@link ClaudeCliClient} that are untested
 * by {@link ClaudeCliClientTest} (which covers extractErrorText, safeGetInt, safeGetDouble).
 */
class ClaudeCliClientStaticMethodsTest {

    // ── buildJsonUserMessage (package-private static) ───────────────────────

    @Test
    void buildJsonUserMessage_createsValidJsonEnvelope() {
        String result = ClaudeCliClient.buildJsonUserMessage("Hello", List.of());
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
    void buildJsonUserMessage_preservesSpecialCharacters() {
        String prompt = "Line1\nLine2\twith \"quotes\"";
        String result = ClaudeCliClient.buildJsonUserMessage(prompt, List.of());
        JsonObject parsed = JsonParser.parseString(result).getAsJsonObject();

        String text = parsed.getAsJsonObject("message")
            .getAsJsonArray("content")
            .get(0).getAsJsonObject()
            .get("text").getAsString();

        assertEquals(prompt, text);
    }

    @Test
    void buildJsonUserMessage_handlesEmptyPrompt() {
        String result = ClaudeCliClient.buildJsonUserMessage("", List.of());
        JsonObject parsed = JsonParser.parseString(result).getAsJsonObject();
        String text = parsed.getAsJsonObject("message")
            .getAsJsonArray("content")
            .get(0).getAsJsonObject()
            .get("text").getAsString();
        assertEquals("", text);
    }

    @Test
    void buildJsonUserMessage_hasCorrectStructure() {
        String result = ClaudeCliClient.buildJsonUserMessage("test", List.of());
        JsonObject parsed = JsonParser.parseString(result).getAsJsonObject();

        assertTrue(parsed.has("type"), "Top-level should have 'type'");
        assertTrue(parsed.has("message"), "Top-level should have 'message'");
        JsonObject message = parsed.getAsJsonObject("message");
        assertTrue(message.has("role"), "Message should have 'role'");
        assertTrue(message.has("content"), "Message should have 'content'");
        assertTrue(message.get("content").isJsonArray(), "Content should be an array");
    }

    @Test
    void buildJsonUserMessage_contentBlockHasTextType() {
        String result = ClaudeCliClient.buildJsonUserMessage("prompt", List.of());
        JsonObject parsed = JsonParser.parseString(result).getAsJsonObject();
        JsonObject block = parsed.getAsJsonObject("message")
            .getAsJsonArray("content")
            .get(0).getAsJsonObject();
        assertEquals("text", block.get("type").getAsString());
    }

    @Test
    void buildJsonUserMessage_withImages_addsAnthropicImageBlocks() {
        ContentBlock.Image img = new ContentBlock.Image("iVBORw0KGgo=", "image/png");
        String result = ClaudeCliClient.buildJsonUserMessage("Describe this:", List.of(img));
        JsonObject parsed = JsonParser.parseString(result).getAsJsonObject();

        JsonArray content = parsed.getAsJsonObject("message").getAsJsonArray("content");
        assertEquals(2, content.size(), "Should have text block + image block");

        JsonObject imageBlock = content.get(1).getAsJsonObject();
        assertEquals("image", imageBlock.get("type").getAsString());

        JsonObject source = imageBlock.getAsJsonObject("source");
        assertEquals("base64", source.get("type").getAsString());
        assertEquals("image/png", source.get("media_type").getAsString());
        assertEquals("iVBORw0KGgo=", source.get("data").getAsString());
    }

    @Test
    void buildJsonUserMessage_withMultipleImages_addsAllImageBlocksAfterText() {
        ContentBlock.Image img1 = new ContentBlock.Image("data1=", "image/png");
        ContentBlock.Image img2 = new ContentBlock.Image("data2=", "image/jpeg");
        String result = ClaudeCliClient.buildJsonUserMessage("Compare:", List.of(img1, img2));
        JsonObject parsed = JsonParser.parseString(result).getAsJsonObject();

        JsonArray content = parsed.getAsJsonObject("message").getAsJsonArray("content");
        assertEquals(3, content.size(), "Should have text block + 2 image blocks");

        assertEquals("text", content.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("image/png", content.get(1).getAsJsonObject()
            .getAsJsonObject("source").get("media_type").getAsString());
        assertEquals("image/jpeg", content.get(2).getAsJsonObject()
            .getAsJsonObject("source").get("media_type").getAsString());
    }

    // ── extractImageBlocks (package-private static) ─────────────────────────

    @Test
    void extractImageBlocks_returnsEmptyListForNoImages() {
        List<ContentBlock> blocks = List.of(
            new ContentBlock.Text("hello"),
            new ContentBlock.Resource(new ContentBlock.ResourceLink("file://a", "a.txt", null, "content", null))
        );
        assertTrue(ClaudeCliClient.extractImageBlocks(blocks).isEmpty());
    }

    @Test
    void extractImageBlocks_extractsImageBlocks() {
        ContentBlock.Image img = new ContentBlock.Image("abc=", "image/png");
        List<ContentBlock> blocks = List.of(
            new ContentBlock.Text("describe this"),
            img
        );
        List<ContentBlock.Image> result = ClaudeCliClient.extractImageBlocks(blocks);
        assertEquals(1, result.size());
        assertEquals("abc=", result.get(0).data());
        assertEquals("image/png", result.get(0).mimeType());
    }

    @Test
    void extractImageBlocks_extractsMultipleImages() {
        ContentBlock.Image img1 = new ContentBlock.Image("data1=", "image/png");
        ContentBlock.Image img2 = new ContentBlock.Image("data2=", "image/jpeg");
        List<ContentBlock> blocks = List.of(new ContentBlock.Text("text"), img1, img2);
        List<ContentBlock.Image> result = ClaudeCliClient.extractImageBlocks(blocks);
        assertEquals(2, result.size());
    }

    @Test
    void extractImageBlocks_returnsEmptyForEmptyInput() {
        assertTrue(ClaudeCliClient.extractImageBlocks(List.of()).isEmpty());
    }

    // ── extractToolResultContent (package-private static) ───────────────────

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

    // ── extractErrorText (private static) ───────────────────────────────────

    @Test
    void extractErrorText_returnsPrimitiveString() throws Exception {
        JsonElement el = new JsonPrimitive("something went wrong");
        assertEquals("something went wrong", invokeExtractErrorText(el));
    }

    @Test
    void extractErrorText_returnsMessageFieldFromObject() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("message", "detailed error");
        assertEquals("detailed error", invokeExtractErrorText(obj));
    }

    @Test
    void extractErrorText_returnsToStringForObjectWithoutMessage() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("code", 500);
        String result = invokeExtractErrorText(obj);
        assertEquals(obj.toString(), result);
    }

    @Test
    void extractErrorText_returnsToStringForJsonArray() throws Exception {
        JsonArray arr = new JsonArray();
        arr.add("err1");
        arr.add("err2");
        assertEquals(arr.toString(), invokeExtractErrorText(arr));
    }

    // ── safeGetInt (private static) ─────────────────────────────────────────

    @Test
    void safeGetInt_returnsValueWhenPresent() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("tokens", 100);
        assertEquals(100, invokeSafeGetInt(obj, "tokens"));
    }

    @Test
    void safeGetInt_returnsZeroWhenFieldMissing() throws Exception {
        JsonObject obj = new JsonObject();
        assertEquals(0, invokeSafeGetInt(obj, "missing"));
    }

    @Test
    void safeGetInt_returnsZeroWhenFieldIsJsonNull() throws Exception {
        JsonObject obj = new JsonObject();
        obj.add("count", JsonNull.INSTANCE);
        assertEquals(0, invokeSafeGetInt(obj, "count"));
    }

    @Test
    void safeGetInt_returnsExactValue42() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("answer", 42);
        assertEquals(42, invokeSafeGetInt(obj, "answer"));
    }

    // ── safeGetDouble (private static) ──────────────────────────────────────

    @Test
    void safeGetDouble_returnsValueWhenPresent() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("cost", 1.5);
        assertEquals(1.5, invokeSafeGetDouble(obj, "cost"), 0.0001);
    }

    @Test
    void safeGetDouble_returnsZeroWhenFieldMissing() throws Exception {
        JsonObject obj = new JsonObject();
        assertEquals(0.0, invokeSafeGetDouble(obj, "missing"), 0.0001);
    }

    @Test
    void safeGetDouble_returnsZeroWhenFieldIsJsonNull() throws Exception {
        JsonObject obj = new JsonObject();
        obj.add("price", JsonNull.INSTANCE);
        assertEquals(0.0, invokeSafeGetDouble(obj, "price"), 0.0001);
    }

    @Test
    void safeGetDouble_returnsExactValuePi() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("pi", 3.14);
        assertEquals(3.14, invokeSafeGetDouble(obj, "pi"), 0.0001);
    }

    // ── respondToControlRequest (private static) ────────────────────────────

    @Test
    void respondToControlRequest_canUseTool_allowDecision() throws Exception {
        JsonObject event = new JsonObject();
        event.addProperty("subtype", "can_use_tool");
        event.addProperty("requestId", "req-1");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        invokeRespondToControlRequest(event, baos);

        JsonObject response = JsonParser.parseString(baos.toString(StandardCharsets.UTF_8).trim()).getAsJsonObject();
        assertEquals("control_response", response.get("type").getAsString());
        assertEquals("can_use_tool", response.get("subtype").getAsString());
        assertTrue(response.has("response"));
        assertEquals("allow", response.getAsJsonObject("response").get("decision").getAsString());
    }

    @Test
    void respondToControlRequest_unknownSubtype_noDecision() throws Exception {
        JsonObject event = new JsonObject();
        event.addProperty("subtype", "unknown_subtype");
        event.addProperty("requestId", "req-2");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        invokeRespondToControlRequest(event, baos);

        JsonObject response = JsonParser.parseString(baos.toString(StandardCharsets.UTF_8).trim()).getAsJsonObject();
        assertEquals("control_response", response.get("type").getAsString());
        assertFalse(response.has("response"), "Unknown subtype should not include a 'response' field");
    }

    @Test
    void respondToControlRequest_echoesRequestId() throws Exception {
        JsonObject event = new JsonObject();
        event.addProperty("subtype", "can_use_tool");
        event.addProperty("requestId", "my-unique-id-123");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        invokeRespondToControlRequest(event, baos);

        JsonObject response = JsonParser.parseString(baos.toString(StandardCharsets.UTF_8).trim()).getAsJsonObject();
        assertEquals("my-unique-id-123", response.get("requestId").getAsString());
    }

    @Test
    void respondToControlRequest_outputEndsWithNewline() throws Exception {
        JsonObject event = new JsonObject();
        event.addProperty("subtype", "can_use_tool");
        event.addProperty("requestId", "req-nl");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        invokeRespondToControlRequest(event, baos);

        String raw = baos.toString(StandardCharsets.UTF_8);
        assertTrue(raw.endsWith("\n"), "Output should end with a newline");
    }

    // ── Reflection helpers ──────────────────────────────────────────────────

    private static final Method EXTRACT_ERROR_TEXT;
    private static final Method SAFE_GET_INT;
    private static final Method SAFE_GET_DOUBLE;
    private static final Method RESPOND_TO_CONTROL_REQUEST;

    static {
        try {
            EXTRACT_ERROR_TEXT = ClaudeCliClient.class
                .getDeclaredMethod("extractErrorText", JsonElement.class);
            EXTRACT_ERROR_TEXT.setAccessible(true);

            SAFE_GET_INT = ClaudeCliClient.class
                .getDeclaredMethod("safeGetInt", JsonObject.class, String.class);
            SAFE_GET_INT.setAccessible(true);

            SAFE_GET_DOUBLE = ClaudeCliClient.class
                .getDeclaredMethod("safeGetDouble", JsonObject.class, String.class);
            SAFE_GET_DOUBLE.setAccessible(true);

            RESPOND_TO_CONTROL_REQUEST = ClaudeCliClient.class
                .getDeclaredMethod("respondToControlRequest", JsonObject.class, OutputStream.class);
            RESPOND_TO_CONTROL_REQUEST.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("Method not found — signature changed?", e);
        }
    }

    private static String invokeExtractErrorText(JsonElement el) throws Exception {
        return (String) EXTRACT_ERROR_TEXT.invoke(null, el);
    }

    private static int invokeSafeGetInt(JsonObject obj, String field) throws Exception {
        return (int) SAFE_GET_INT.invoke(null, obj, field);
    }

    private static double invokeSafeGetDouble(JsonObject obj, String field) throws Exception {
        return (double) SAFE_GET_DOUBLE.invoke(null, obj, field);
    }

    private static void invokeRespondToControlRequest(JsonObject event, OutputStream stdin) throws Exception {
        RESPOND_TO_CONTROL_REQUEST.invoke(null, event, stdin);
    }
}
