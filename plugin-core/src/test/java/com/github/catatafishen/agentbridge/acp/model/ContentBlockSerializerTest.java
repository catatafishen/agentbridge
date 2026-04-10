package com.github.catatafishen.agentbridge.acp.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ContentBlockSerializer} — verifies the {@code "type"} discriminator
 * is emitted correctly for every {@link ContentBlock} variant.
 */
class ContentBlockSerializerTest {

    private final Gson gson = new GsonBuilder()
            .registerTypeHierarchyAdapter(ContentBlock.class, new ContentBlockSerializer())
            .create();

    // ── Text ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Text")
    class TextTests {

        @Test
        @DisplayName("basic text block")
        void basicText() {
            ContentBlock block = new ContentBlock.Text("hello");
            JsonObject json = gson.toJsonTree(block).getAsJsonObject();

            assertEquals("text", json.get("type").getAsString());
            assertEquals("hello", json.get("text").getAsString());
            assertEquals(2, json.size(), "should have exactly 2 fields");
        }

        @Test
        @DisplayName("empty text")
        void emptyText() {
            ContentBlock block = new ContentBlock.Text("");
            JsonObject json = gson.toJsonTree(block).getAsJsonObject();

            assertEquals("text", json.get("type").getAsString());
            assertEquals("", json.get("text").getAsString());
        }

        @Test
        @DisplayName("text with special characters (quotes, newlines, unicode)")
        void specialCharacters() {
            String special = "line1\nline2\t\"quoted\" and unicode: \u00e9\u00e0\u00fc \u2603";
            ContentBlock block = new ContentBlock.Text(special);
            JsonObject json = gson.toJsonTree(block).getAsJsonObject();

            assertEquals("text", json.get("type").getAsString());
            assertEquals(special, json.get("text").getAsString());
        }
    }

    // ── Thinking ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Thinking")
    class ThinkingTests {

        @Test
        @DisplayName("basic thinking block")
        void basicThinking() {
            ContentBlock block = new ContentBlock.Thinking("I need to consider...");
            JsonObject json = gson.toJsonTree(block).getAsJsonObject();

            assertEquals("thinking", json.get("type").getAsString());
            assertEquals("I need to consider...", json.get("thinking").getAsString());
            assertEquals(2, json.size(), "should have exactly 2 fields");
        }

        @Test
        @DisplayName("empty thinking")
        void emptyThinking() {
            ContentBlock block = new ContentBlock.Thinking("");
            JsonObject json = gson.toJsonTree(block).getAsJsonObject();

            assertEquals("thinking", json.get("type").getAsString());
            assertEquals("", json.get("thinking").getAsString());
        }
    }

    // ── Image ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Image")
    class ImageTests {

        @Test
        @DisplayName("basic image block")
        void basicImage() {
            ContentBlock block = new ContentBlock.Image("aWdQTkcN...", "image/png");
            JsonObject json = gson.toJsonTree(block).getAsJsonObject();

            assertEquals("image", json.get("type").getAsString());
            assertEquals("aWdQTkcN...", json.get("data").getAsString());
            assertEquals("image/png", json.get("mimeType").getAsString());
            assertEquals(3, json.size(), "should have exactly 3 fields");
        }
    }

    // ── Audio ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Audio")
    class AudioTests {

        @Test
        @DisplayName("basic audio block")
        void basicAudio() {
            ContentBlock block = new ContentBlock.Audio("UklGRi4A...", "audio/wav");
            JsonObject json = gson.toJsonTree(block).getAsJsonObject();

            assertEquals("audio", json.get("type").getAsString());
            assertEquals("UklGRi4A...", json.get("data").getAsString());
            assertEquals("audio/wav", json.get("mimeType").getAsString());
            assertEquals(3, json.size(), "should have exactly 3 fields");
        }
    }

    // ── Resource ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Resource")
    class ResourceTests {

        @Test
        @DisplayName("resource with all fields populated")
        void allFields() {
            ContentBlock.ResourceLink link = new ContentBlock.ResourceLink(
                    "file:///src/Main.java", "Main.java", "text/x-java", "class Main {}", "YmxvYg=="
            );
            ContentBlock block = new ContentBlock.Resource(link);
            JsonObject json = gson.toJsonTree(block).getAsJsonObject();

            assertEquals("resource", json.get("type").getAsString());
            assertTrue(json.has("resource"), "should contain 'resource' key");

            JsonObject res = json.getAsJsonObject("resource");
            assertEquals("file:///src/Main.java", res.get("uri").getAsString());
            assertEquals("Main.java", res.get("name").getAsString());
            assertEquals("text/x-java", res.get("mimeType").getAsString());
            assertEquals("class Main {}", res.get("text").getAsString());
            assertEquals("YmxvYg==", res.get("blob").getAsString());
        }

        @Test
        @DisplayName("resource with null optional fields")
        void nullOptionalFields() {
            ContentBlock.ResourceLink link = new ContentBlock.ResourceLink(
                    "file:///src/Main.java", null, null, null, null
            );
            ContentBlock block = new ContentBlock.Resource(link);
            JsonObject json = gson.toJsonTree(block).getAsJsonObject();

            assertEquals("resource", json.get("type").getAsString());

            JsonObject res = json.getAsJsonObject("resource");
            assertEquals("file:///src/Main.java", res.get("uri").getAsString());
            // Gson default: null fields are omitted
            assertFalse(res.has("name"), "null 'name' should be absent");
            assertFalse(res.has("mimeType"), "null 'mimeType' should be absent");
            assertFalse(res.has("text"), "null 'text' should be absent");
            assertFalse(res.has("blob"), "null 'blob' should be absent");
        }

        @Test
        @DisplayName("resource with only URI (required field)")
        void onlyUri() {
            ContentBlock.ResourceLink link = new ContentBlock.ResourceLink(
                    "https://example.com/readme.md", null, null, null, null
            );
            ContentBlock block = new ContentBlock.Resource(link);
            JsonObject json = gson.toJsonTree(block).getAsJsonObject();

            assertEquals("resource", json.get("type").getAsString());

            JsonObject res = json.getAsJsonObject("resource");
            assertEquals("https://example.com/readme.md", res.get("uri").getAsString());
            assertEquals(1, res.size(), "should contain only 'uri'");
        }
    }

    // ── Mixed list round-trip ────────────────────────────────────────────────

    @Nested
    @DisplayName("Mixed list serialization")
    class MixedListTests {

        @Test
        @DisplayName("list of mixed content blocks preserves type discriminators")
        void mixedList() {
            List<ContentBlock> blocks = List.of(
                    new ContentBlock.Text("hello"),
                    new ContentBlock.Thinking("hmm"),
                    new ContentBlock.Image("data1", "image/jpeg"),
                    new ContentBlock.Audio("data2", "audio/mp3"),
                    new ContentBlock.Resource(new ContentBlock.ResourceLink(
                            "file:///a.txt", "a.txt", "text/plain", "contents", null
                    ))
            );

            Type listType = new TypeToken<List<ContentBlock>>() {}.getType();
            JsonArray array = gson.toJsonTree(blocks, listType).getAsJsonArray();

            assertEquals(5, array.size());

            assertEquals("text", array.get(0).getAsJsonObject().get("type").getAsString());
            assertEquals("thinking", array.get(1).getAsJsonObject().get("type").getAsString());
            assertEquals("image", array.get(2).getAsJsonObject().get("type").getAsString());
            assertEquals("audio", array.get(3).getAsJsonObject().get("type").getAsString());
            assertEquals("resource", array.get(4).getAsJsonObject().get("type").getAsString());
        }

        @Test
        @DisplayName("serialized JSON string is valid and parseable")
        void jsonStringRoundTrip() {
            List<ContentBlock> blocks = List.of(
                    new ContentBlock.Text("first"),
                    new ContentBlock.Thinking("second")
            );

            Type listType = new TypeToken<List<ContentBlock>>() {}.getType();
            String jsonStr = gson.toJson(blocks, listType);

            // Parse back and verify structure
            JsonArray array = gson.fromJson(jsonStr, JsonArray.class);
            assertEquals(2, array.size());

            JsonObject first = array.get(0).getAsJsonObject();
            assertEquals("text", first.get("type").getAsString());
            assertEquals("first", first.get("text").getAsString());

            JsonObject second = array.get(1).getAsJsonObject();
            assertEquals("thinking", second.get("type").getAsString());
            assertEquals("second", second.get("thinking").getAsString());
        }
    }
}
