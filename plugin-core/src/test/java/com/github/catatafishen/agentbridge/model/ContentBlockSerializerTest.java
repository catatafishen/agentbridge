package com.github.catatafishen.agentbridge.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ContentBlockSerializer} at the model level.
 * Verifies Gson serialization of each {@link ContentBlock} variant via {@code toJson()}.
 */
class ContentBlockSerializerTest {

    private final Gson gson = new GsonBuilder()
            .registerTypeHierarchyAdapter(ContentBlock.class, new ContentBlockSerializer())
            .create();

    @Nested
    @DisplayName("Text serialization")
    class TextSerialization {

        @Test
        @DisplayName("produces {\"type\":\"text\",\"text\":\"...\"}")
        void basicText() {
            var rec = new ContentBlock.Text("hello world");
            String json = gson.toJson(rec, ContentBlock.class);

            assertTrue(json.contains("\"type\":\"text\""));
            assertTrue(json.contains("\"text\":\"hello world\""));
        }

        @Test
        @DisplayName("empty text value")
        void emptyText() {
            var rec = new ContentBlock.Text("");
            JsonObject obj = gson.toJsonTree(rec, ContentBlock.class).getAsJsonObject();

            assertEquals("text", obj.get("type").getAsString());
            assertEquals("", obj.get("text").getAsString());
            assertEquals(2, obj.size());
        }

        @Test
        @DisplayName("text with newlines and tabs")
        void textWithWhitespace() {
            var rec = new ContentBlock.Text("line1\nline2\ttab");
            JsonObject obj = gson.toJsonTree(rec, ContentBlock.class).getAsJsonObject();

            assertEquals("line1\nline2\ttab", obj.get("text").getAsString());
        }
    }

    @Nested
    @DisplayName("Thinking serialization")
    class ThinkingSerialization {

        @Test
        @DisplayName("produces {\"type\":\"thinking\",\"thinking\":\"...\"}")
        void basicThinking() {
            var rec = new ContentBlock.Thinking("pondering...");
            JsonObject obj = gson.toJsonTree(rec, ContentBlock.class).getAsJsonObject();

            assertEquals("thinking", obj.get("type").getAsString());
            assertEquals("pondering...", obj.get("thinking").getAsString());
            assertEquals(2, obj.size());
        }

        @Test
        @DisplayName("empty thinking value")
        void emptyThinking() {
            var rec = new ContentBlock.Thinking("");
            JsonObject obj = gson.toJsonTree(rec, ContentBlock.class).getAsJsonObject();

            assertEquals("thinking", obj.get("type").getAsString());
            assertEquals("", obj.get("thinking").getAsString());
        }
    }

    @Nested
    @DisplayName("Image serialization")
    class ImageSerialization {

        @Test
        @DisplayName("produces base64 image structure with mimeType")
        void basicImage() {
            var rec = new ContentBlock.Image("iVBORw0KGgo=", "image/png");
            JsonObject obj = gson.toJsonTree(rec, ContentBlock.class).getAsJsonObject();

            assertEquals("image", obj.get("type").getAsString());
            assertEquals("iVBORw0KGgo=", obj.get("data").getAsString());
            assertEquals("image/png", obj.get("mimeType").getAsString());
            assertEquals(3, obj.size());
        }

        @Test
        @DisplayName("jpeg image")
        void jpegImage() {
            var rec = new ContentBlock.Image("/9j/4AAQ=", "image/jpeg");
            JsonObject obj = gson.toJsonTree(rec, ContentBlock.class).getAsJsonObject();

            assertEquals("image/jpeg", obj.get("mimeType").getAsString());
        }
    }

    @Nested
    @DisplayName("Audio serialization")
    class AudioSerialization {

        @Test
        @DisplayName("produces audio structure with data and mimeType")
        void basicAudio() {
            var rec = new ContentBlock.Audio("UklGRi4A", "audio/wav");
            JsonObject obj = gson.toJsonTree(rec, ContentBlock.class).getAsJsonObject();

            assertEquals("audio", obj.get("type").getAsString());
            assertEquals("UklGRi4A", obj.get("data").getAsString());
            assertEquals("audio/wav", obj.get("mimeType").getAsString());
            assertEquals(3, obj.size());
        }

        @Test
        @DisplayName("mp3 audio")
        void mp3Audio() {
            var rec = new ContentBlock.Audio("//uQx", "audio/mpeg");
            JsonObject obj = gson.toJsonTree(rec, ContentBlock.class).getAsJsonObject();

            assertEquals("audio/mpeg", obj.get("mimeType").getAsString());
        }
    }

    @Nested
    @DisplayName("Resource serialization")
    class ResourceSerialization {

        @Test
        @DisplayName("produces nested resource structure with all fields")
        void allFields() {
            var link = new ContentBlock.ResourceLink(
                    "file:///src/Main.java", "Main.java", "text/x-java", "class Main {}", "YmxvYg=="
            );
            var rec = new ContentBlock.Resource(link);
            JsonObject obj = gson.toJsonTree(rec, ContentBlock.class).getAsJsonObject();

            assertEquals("resource", obj.get("type").getAsString());
            assertTrue(obj.has("resource"));

            JsonObject nested = obj.getAsJsonObject("resource");
            assertEquals("file:///src/Main.java", nested.get("uri").getAsString());
            assertEquals("Main.java", nested.get("name").getAsString());
            assertEquals("text/x-java", nested.get("mimeType").getAsString());
            assertEquals("class Main {}", nested.get("text").getAsString());
            assertEquals("YmxvYg==", nested.get("blob").getAsString());
        }

        @Test
        @DisplayName("resource with only uri (nullable fields omitted)")
        void onlyUri() {
            var link = new ContentBlock.ResourceLink("file:///a.txt", null, null, null, null);
            var rec = new ContentBlock.Resource(link);
            JsonObject obj = gson.toJsonTree(rec, ContentBlock.class).getAsJsonObject();

            assertEquals("resource", obj.get("type").getAsString());
            JsonObject nested = obj.getAsJsonObject("resource");
            assertEquals("file:///a.txt", nested.get("uri").getAsString());
            // Gson omits null fields by default
            assertFalse(nested.has("name"));
            assertFalse(nested.has("text"));
            assertFalse(nested.has("blob"));
        }
    }

    @Nested
    @DisplayName("ResourceLink serialization")
    class ResourceLinkSerialization {

        @Test
        @DisplayName("ResourceLink serialized via Gson directly has all fields")
        void directSerialization() {
            var link = new ContentBlock.ResourceLink(
                    "file:///x.txt", "x.txt", "text/plain", "hello", null
            );
            String json = gson.toJson(link);
            assertTrue(json.contains("\"uri\":\"file:///x.txt\""));
            assertTrue(json.contains("\"name\":\"x.txt\""));
        }
    }

    @Nested
    @DisplayName("Null handling")
    class NullHandling {

        @Test
        @DisplayName("null ContentBlock produces empty JsonObject")
        void nullBlock() {
            ContentBlockSerializer serializer = new ContentBlockSerializer();
            var result = serializer.serialize(null, ContentBlock.class, null);

            assertTrue(result.isJsonObject());
            assertEquals(0, result.getAsJsonObject().size());
        }
    }

    @Nested
    @DisplayName("toJson string output")
    class ToJsonOutput {

        @Test
        @DisplayName("Text toJson produces valid JSON string")
        void textToJson() {
            var rec = new ContentBlock.Text("test");
            String json = gson.toJson(rec, ContentBlock.class);

            assertNotNull(json);
            assertFalse(json.isEmpty());
            // Should be parseable back
            JsonObject parsed = gson.fromJson(json, JsonObject.class);
            assertEquals("text", parsed.get("type").getAsString());
            assertEquals("test", parsed.get("text").getAsString());
        }

        @Test
        @DisplayName("Image toJson round-trip")
        void imageToJson() {
            var rec = new ContentBlock.Image("data==", "image/gif");
            String json = gson.toJson(rec, ContentBlock.class);

            JsonObject parsed = gson.fromJson(json, JsonObject.class);
            assertEquals("image", parsed.get("type").getAsString());
            assertEquals("data==", parsed.get("data").getAsString());
            assertEquals("image/gif", parsed.get("mimeType").getAsString());
        }
    }
}
