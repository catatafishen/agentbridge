package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.TreeMap;

/**
 * Pure JSON normalization and deterministic hashing utilities for tool call arguments.
 *
 * <p>Extracted from {@link ToolCallTracker} so the logic can be reused
 * without pulling in registry state.
 */
final class ToolCallHasher {

    private static final Logger LOG = Logger.getInstance(ToolCallHasher.class);

    /**
     * Argument key injected by some agents that must be excluded from the hash.
     */
    static final String EXCLUDED_KEY = "__tool_use_purpose";

    private ToolCallHasher() {
    }

    /**
     * Compute an 8-hex-character deterministic hash of the given tool arguments.
     * Keys are sorted, {@value #EXCLUDED_KEY} is excluded, and values are recursively
     * normalised via {@link #computeStableValue(JsonElement)}.
     */
    static @NotNull String computeBaseHash(@NotNull JsonObject args) {
        try {
            TreeMap<String, String> sorted = new TreeMap<>();
            for (String key : args.keySet()) {
                if (!EXCLUDED_KEY.equals(key)) {
                    JsonElement value = args.get(key);
                    sorted.put(key, computeStableValue(value));
                }
            }
            String toHash = sorted.toString();
            String hash = String.format("%08x", toHash.hashCode());
            LOG.debug("ToolCallHasher: hashing '" + toHash + "' -> " + hash);
            return hash;
        } catch (Exception e) {
            LOG.warn("ToolCallHasher: hash error", e);
            return "00000000";
        }
    }

    /**
     * Recursively normalise a {@link JsonElement} into a deterministic string representation.
     * Objects are sorted by key; arrays preserve order; whole-number doubles are rendered as longs.
     */
    static String computeStableValue(JsonElement value) {
        if (value == null || value.isJsonNull()) return "null";
        if (value.isJsonObject()) {
            JsonObject obj = value.getAsJsonObject();
            TreeMap<String, String> sorted = new TreeMap<>();
            for (String key : obj.keySet()) {
                sorted.put(key, computeStableValue(obj.get(key)));
            }
            return sorted.toString();
        }
        if (value.isJsonArray()) {
            JsonArray arr = value.getAsJsonArray();
            ArrayList<String> items = new ArrayList<>();
            for (JsonElement item : arr) {
                items.add(computeStableValue(item));
            }
            return items.toString();
        }
        if (value.isJsonPrimitive()) {
            JsonPrimitive p = value.getAsJsonPrimitive();
            if (p.isNumber()) {
                double d = p.getAsDouble();
                if (d == (long) d) {
                    return String.valueOf((long) d);
                }
            }
        }
        return value.toString();
    }

    /**
     * Return {@code true} if {@code chipId} matches the given base hash exactly,
     * or starts with {@code baseHash + "-"} (suffixed duplicate counter).
     */
    static boolean isMatchingHash(@NotNull String chipId, @NotNull String baseHash) {
        return chipId.equals(baseHash) || chipId.startsWith(baseHash + "-");
    }
}
