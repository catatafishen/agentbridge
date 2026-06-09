package com.github.catatafishen.agentbridge.services;

import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ToolRegistry {

    /**
     * Fired after tools are registered or unregistered. Subscribers receive
     * a notification on the project message bus — use to refresh UI that
     * depends on tool availability (e.g. the Knowledge Graph panel).
     */
    @Topic.ProjectLevel
    public static final Topic<Runnable> TOOLS_CHANGED =
        Topic.create("ToolRegistry.toolsChanged", Runnable.class);

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
        DEBUG("Debugging"),
        EDITOR("Editor"),
        SHELL("Shell (built-in)"),
        OTHER("Other"),
        MACRO("Recorded Macros"),
        CUSTOM_MCP("Custom MCP Servers"),
        DATABASE("Database"),
        MEMORY("Memory");

        public final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }
    }

    // ── Instance state (project-scoped) ──────────────────────────────────

    private final Project project;
    private final Map<String, ToolDefinition> definitions = new ConcurrentHashMap<>();

    @SuppressWarnings("java:S1905") // Cast needed: IDE doesn't resolve Project→ComponentManager supertype
    public static ToolRegistry getInstance(@NotNull Project project) {
        return ((ComponentManager) project).getService(ToolRegistry.class);
    }

    @SuppressWarnings("unused") // instantiated by IntelliJ service container
    public ToolRegistry(@NotNull Project project) {
        this.project = project;
    }

    // ── Registration ─────────────────────────────────────────────────────

    public void register(@NotNull ToolDefinition def) {
        definitions.put(def.id(), def);
        fireChanged();
    }

    public void unregister(@NotNull String id) {
        definitions.remove(id);
        fireChanged();
    }

    public void registerAll(@NotNull Collection<? extends ToolDefinition> defs) {
        for (ToolDefinition def : defs) {
            definitions.put(def.id(), def);
        }
        fireChanged();
    }

    private void fireChanged() {
        project.getMessageBus().syncPublisher(TOOLS_CHANGED).run();
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
     * Look up a tool by its human-readable display name (e.g. "Git Stage").
     * Used to recognize MCP tools when Copilot CLI sends display names
     * in permission requests instead of snake_case IDs.
     */
    @Nullable
    public ToolDefinition findByDisplayName(@NotNull String displayName) {
        for (ToolDefinition def : definitions.values()) {
            if (displayName.equalsIgnoreCase(def.displayName())) {
                return def;
            }
        }
        return null;
    }

    /**
     * Returns all registered tool definitions (built-in + MCP), sorted by ID
     * for deterministic ordering across invocations.
     */
    @NotNull
    public List<ToolDefinition> getAllTools() {
        return definitions.values().stream()
            .sorted(Comparator.comparing(ToolDefinition::id))
            .toList();
    }
}
