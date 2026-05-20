package com.github.catatafishen.agentbridge.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;

/**
 * Serializes {@link ContentBlock} variants with the required {@code "type"} discriminator field.
 * <p>
 * ACP agents require {@code {"type": "text", "text": "..."}} but Gson records serialize to
 * {@code {"text": "..."}} without the discriminator.
 */
public class ContentBlockSerializer implements JsonSerializer<ContentBlock> {

    @Override
    public JsonElement serialize(ContentBlock src, Type typeOfSrc, JsonSerializationContext ctx) {
        JsonObject obj = new JsonObject();
        switch (src) {
            case ContentBlock.Text(var text) -> {
                obj.addProperty("type", "text");
                obj.addProperty("text", text);
            }
            case ContentBlock.Thinking(var thinking) -> {
                obj.addProperty("type", "thinking");
                obj.addProperty("thinking", thinking);
            }
            case ContentBlock.Image(var data, var mimeType) -> {
                obj.addProperty("type", "image");
                obj.addProperty("data", data);
                obj.addProperty("mimeType", mimeType);
            }
            case ContentBlock.Audio(var data, var mimeType) -> {
                obj.addProperty("type", "audio");
                obj.addProperty("data", data);
                obj.addProperty("mimeType", mimeType);
            }
            case ContentBlock.Resource(var resource) -> {
                obj.addProperty("type", "resource");
                obj.add("resource", ctx.serialize(resource, ContentBlock.ResourceLink.class));
            }
            case null, default -> {
                // no additional properties for unrecognized block types
            }
        }
        return obj;
    }
}
