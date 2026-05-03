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
 * sequentially (chaining).
 *
 * <p>Optional {@code prependString} and {@code appendString} fields provide static text that is
 * prepended/appended to successful tool output without requiring a script. These replace the
 * legacy {@code outputTemplate} setting.
 *
 * @param toolId        the MCP tool ID this config applies to (from the filename)
 * @param triggers      trigger → ordered list of hook entries
 * @param hooksDir      the hooks directory (used to resolve relative script paths)
 * @param prependString optional static text prepended to successful tool output
 * @param appendString  optional static text appended to successful tool output
 */
public record ToolHookConfig(
    @NotNull String toolId,
    @NotNull Map<HookTrigger, List<HookEntryConfig>> triggers,
    @NotNull Path hooksDir,
    @Nullable String prependString,
    @Nullable String appendString
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
     */
    public @NotNull Path resolveScript(@NotNull HookEntryConfig entry) {
        return hooksDir.resolve(entry.script());
    }

    /**
     * Returns true if this config has no triggers, no prepend/append text — effectively empty.
     */
    public boolean isEmpty() {
        return triggers.isEmpty()
            && (prependString == null || prependString.isEmpty())
            && (appendString == null || appendString.isEmpty());
    }

    /**
     * Serializes this config back to a JSON object matching the on-disk format.
     */
    public @NotNull JsonObject toJson() {
        JsonObject root = new JsonObject();

        if (prependString != null && !prependString.isEmpty()) {
            root.addProperty("prependString", prependString);
        }
        if (appendString != null && !appendString.isEmpty()) {
            root.addProperty("appendString", appendString);
        }

        for (HookTrigger trigger : HookTrigger.values()) {
            List<HookEntryConfig> entries = entriesFor(trigger);
            if (entries.isEmpty()) continue;

            JsonArray array = new JsonArray();
            for (HookEntryConfig entry : entries) {
                JsonObject entryObj = new JsonObject();
                entryObj.addProperty("script", entry.script());
                if (entry.timeout() != 10) {
                    entryObj.addProperty("timeout", entry.timeout());
                }
                if (trigger == HookTrigger.PERMISSION) {
                    entryObj.addProperty("rejectOnFailure", !entry.failSilently());
                } else if (!entry.failSilently()) {
                    entryObj.addProperty("failSilently", false);
                }
                if (entry.async()) {
                    entryObj.addProperty("async", true);
                }
                if (!entry.env().isEmpty()) {
                    JsonObject envObj = new JsonObject();
                    entry.env().forEach(envObj::addProperty);
                    entryObj.add("env", envObj);
                }
                array.add(entryObj);
            }
            root.add(trigger.jsonKey(), array);
        }

        return root;
    }
}
