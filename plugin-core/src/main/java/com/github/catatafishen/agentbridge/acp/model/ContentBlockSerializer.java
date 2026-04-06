package com.github.catatafishen.agentbridge.acp.model;

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
        if (src instanceof ContentBlock.Text t) {
            obj.addProperty("type", "text");
            obj.addProperty("text", t.text());
        } else if (src instanceof ContentBlock.Thinking t) {
            obj.addProperty("type", "thinking");
            obj.addProperty("thinking", t.thinking());
        } else if (src instanceof ContentBlock.Image img) {
            obj.addProperty("type", "image");
            obj.addProperty("data", img.data());
            obj.addProperty("mimeType", img.mimeType());
        } else if (src instanceof ContentBlock.Audio a) {
            obj.addProperty("type", "audio");
            obj.addProperty("data", a.data());
            obj.addProperty("mimeType", a.mimeType());
        } else if (src instanceof ContentBlock.Resource r) {
            obj.addProperty("type", "resource");
            obj.add("resource", ctx.serialize(r.resource(), ContentBlock.ResourceLink.class));
        }
        return obj;
    }
}
