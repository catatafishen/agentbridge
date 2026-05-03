package com.github.catatafishen.agentbridge.services.hooks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Parsed hook configuration for a single MCP tool.
 * Each {@code <tool-id>.json} file in the hooks directory produces one {@code ToolHookConfig}.
 *
 * <p>The tool ID is derived from the filename (e.g. {@code git_commit.json} → {@code "git_commit"}).
 * Each trigger point maps to a list of {@link HookEntryConfig} entries that are executed
 * sequentially (chaining). Each entry can carry optional {@code prependString}/{@code appendString}
 * that are applied to the tool output at the respective trigger stage.
 *
 * @param toolId   the MCP tool ID this config applies to (from the filename)
 * @param triggers trigger → ordered list of hook entries
 * @param hooksDir the hooks directory (used to resolve relative script paths)
 */
public record ToolHookConfig(
    @NotNull String toolId,
    @NotNull Map<HookTrigger, List<HookEntryConfig>> triggers,
    @NotNull Path hooksDir
) {

    /**
     * Returns the hook entries for a trigger, or an empty list if none are defined.
     */
    public @NotNull List<HookEntryConfig> entriesFor(@NotNull HookTrigger trigger) {
        return triggers.getOrDefault(trigger, List.of());
    }

    /**
     * Returns true if any entries are defined for the given trigger.
     */
    public boolean hasTrigger(@NotNull HookTrigger trigger) {
        List<HookEntryConfig> entries = triggers.get(trigger);
        return entries != null && !entries.isEmpty();
    }

    /**
     * Resolves a script path from an entry against the hooks directory.
     * Returns null if the entry has no script.
     */
    public @Nullable Path resolveScript(@NotNull HookEntryConfig entry) {
        if (entry.script() == null) return null;
        return hooksDir.resolve(entry.script());
    }

    /**
     * Returns true if this config has no triggers — effectively empty.
     */
    public boolean isEmpty() {
        return triggers.isEmpty();
    }

    /**
     * Serializes this config back to a JSON object matching the on-disk format.
     */
    public @NotNull JsonObject toJson() {
        JsonObject root = new JsonObject();
        for (HookTrigger trigger : HookTrigger.values()) {
            List<HookEntryConfig> entries = entriesFor(trigger);
            if (entries.isEmpty()) continue;
            JsonArray array = new JsonArray();
            for (HookEntryConfig entry : entries) {
                array.add(serializeEntry(entry, trigger));
            }
            root.add(trigger.jsonKey(), array);
        }
        return root;
    }

    private static @NotNull JsonObject serializeEntry(@NotNull HookEntryConfig entry,
                                                      @NotNull HookTrigger trigger) {
        JsonObject obj = new JsonObject();
        if (entry.script() != null && !entry.script().isBlank()) {
            serializeScriptFields(obj, entry, trigger);
        }
        if (trigger != HookTrigger.PERMISSION) {
            if (entry.prependString() != null && !entry.prependString().isEmpty())
                obj.addProperty("prependString", entry.prependString());
            if (entry.appendString() != null && !entry.appendString().isEmpty())
                obj.addProperty("appendString", entry.appendString());
        }
        return obj;
    }

    private static void serializeScriptFields(@NotNull JsonObject obj,
                                              @NotNull HookEntryConfig entry,
                                              @NotNull HookTrigger trigger) {
        obj.addProperty("script", entry.script());
        if (entry.timeout() != 10) obj.addProperty("timeout", entry.timeout());
        if (trigger == HookTrigger.PERMISSION) {
            obj.addProperty("rejectOnFailure", !entry.failSilently());
        } else if (!entry.failSilently()) {
            obj.addProperty("failSilently", false);
        }
        if (entry.async()) obj.addProperty("async", true);
        if (!entry.env().isEmpty()) {
            JsonObject envObj = new JsonObject();
            entry.env().forEach(envObj::addProperty);
            obj.add("env", envObj);
        }
    }
}
