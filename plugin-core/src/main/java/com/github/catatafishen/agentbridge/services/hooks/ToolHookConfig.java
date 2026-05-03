package com.github.catatafishen.agentbridge.services.hooks;

import org.jetbrains.annotations.NotNull;

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
     */
    public @NotNull Path resolveScript(@NotNull HookEntryConfig entry) {
        return hooksDir.resolve(entry.script());
    }
}
