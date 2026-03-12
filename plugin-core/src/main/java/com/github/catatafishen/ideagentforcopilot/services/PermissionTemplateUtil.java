package com.github.catatafishen.ideagentforcopilot.services;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.StringJoiner;

/**
 * Utility for substituting placeholders in permission question templates.
 */
final class PermissionTemplateUtil {

    private PermissionTemplateUtil() {
    }

    static String substituteArgs(@NotNull String template, @NotNull JsonObject args) {
        String q = template;
        for (Map.Entry<String, JsonElement> e : args.entrySet()) {
            q = q.replace("{" + e.getKey() + "}", formatArgValue(e.getValue()));
        }
        return q;
    }

    @Nullable
    static String stripPlaceholders(@NotNull String text) {
        String q = text.replaceAll("\\{[^}]+}", "").replaceAll("\\(\\s*\\)", "")
                .replaceAll("\\s+", " ").trim();
        return q.isEmpty() ? null : q;
    }

    private static String formatArgValue(@NotNull JsonElement value) {
        if (value.isJsonNull()) return "";
        if (value.isJsonPrimitive()) {
            String s = value.getAsString();
            return s.length() > 60 ? s.substring(0, 57) + "…" : s;
        }
        if (value.isJsonArray()) {
            StringJoiner joiner = new StringJoiner(", ");
            for (JsonElement el : value.getAsJsonArray()) {
                joiner.add(el.isJsonPrimitive() ? el.getAsString() : el.toString());
            }
            return joiner.toString();
        }
        return value.toString();
    }
}
