package com.github.catatafishen.ideagentforcopilot.services;

import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolRegistry {

    // ── Category enum (static — same across all projects) ────────────────

    public enum Category {
        FILE("File Operations"),
        SEARCH("Search & Navigation"),
        CODE_QUALITY("Code Quality"),
        BUILD("Build / Run / Test"),
        RUN("Terminal & Commands"),
        GIT("Git"),
        REFACTOR("Refactoring"),
        IDE("IDE & Project"),
        TESTING("Testing"),
        PROJECT("Project"),
        INFRASTRUCTURE("Infrastructure"),
        TERMINAL("Terminal"),
        EDITOR("Editor"),
        SHELL("Shell (built-in)"),
        OTHER("Other"),
        MACRO("Recorded Macros");

        public final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }
    }

    // ── Instance state (project-scoped) ──────────────────────────────────

    /**
     * Well-known built-in agent tool IDs. Used to send {@code excludedTools}
     * in the session configuration and to build permission deny lists.
     */
    private static final List<String> BUILT_IN_TOOL_IDS = List.of(
        "view", "read", "grep", "glob", "list", "search_contents_by_grep", "search_paths_by_glob",
        "bash", "edit", "write", "create", "execute", "runInTerminal", "open", "open_entire_file",
        "web_search", "multi_edit", "search_replace", "update_status", "submit", "answer"
    );
    private final Map<String, ToolDefinition> definitions = new LinkedHashMap<>();

    @SuppressWarnings("java:S1905") // Cast needed: IDE doesn't resolve Project→ComponentManager supertype
    public static ToolRegistry getInstance(@NotNull Project project) {
        return ((ComponentManager) project).getService(ToolRegistry.class);
    }

    @SuppressWarnings("unused") // instantiated by IntelliJ service container
    public ToolRegistry(@NotNull Project project) {
        // Tools are registered later by PsiBridgeService via registerAll()
    }

    // ── Registration ─────────────────────────────────────────────────────

    public void register(@NotNull ToolDefinition def) {
        definitions.put(def.id(), def);
    }

    public void unregister(@NotNull String id) {
        definitions.remove(id);
    }

    public void registerAll(@NotNull Collection<? extends ToolDefinition> defs) {
        for (ToolDefinition def : defs) {
            definitions.put(def.id(), def);
        }
    }

    // ── Lookups ──────────────────────────────────────────────────────────

    /**
     * Look up a tool definition by ID. Searches all registered tools
     * (both MCP tools and built-in agent tools).
     */
    @Nullable
    public ToolDefinition findDefinition(@NotNull String id) {
        return definitions.get(id);
    }

    /**
     * Look up a tool by ID (exact match).
     */
    @Nullable
    public ToolDefinition findById(@Nullable String id) {
        if (id == null) return null;
        return definitions.get(id);
    }

    /**
     * Returns all registered tool definitions (built-in + MCP).
     */
    @NotNull
    public List<ToolDefinition> getAllTools() {
        return List.copyOf(definitions.values());
    }

    /**
     * Returns IDs of well-known built-in agent tools that should be excluded
     * from the agent session when the profile enables built-in tool exclusion.
     * <p>
     * This is a static set rather than scanning registered tools because
     * different agents have different built-in tools that change over time.
     * We cannot enumerate them all — this covers the common ones.
     */
    @NotNull
    public static List<String> getBuiltInToolIds() {
        return BUILT_IN_TOOL_IDS;
    }
}
