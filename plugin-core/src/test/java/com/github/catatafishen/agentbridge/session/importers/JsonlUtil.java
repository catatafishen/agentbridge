package com.github.catatafishen.agentbridge.session.importers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class JsonlUtil {

    private static final Logger LOG = Logger.getInstance(JsonlUtil.class);

    private JsonlUtil() {
    }

    @NotNull
    public static List<JsonObject> parseJsonl(@NotNull String content) {
        List<JsonObject> result = new ArrayList<>();
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                JsonElement el = JsonParser.parseString(line);
                if (el.isJsonObject()) {
                    result.add(el.getAsJsonObject());
                }
            } catch (Exception e) {
                LOG.warn("Skipping malformed JSONL line: " + line, e);
            }
        }
        return result;
    }

    @Nullable
    public static String getStr(@NotNull JsonObject obj, @NotNull String key) {
        JsonElement el = obj.get(key);
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
    }

    @Nullable
    public static JsonArray getArray(@NotNull JsonObject obj, @NotNull String key) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonArray() ? el.getAsJsonArray() : null;
    }

    @Nullable
    public static JsonObject getObject(@NotNull JsonObject obj, @NotNull String key) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }
}
