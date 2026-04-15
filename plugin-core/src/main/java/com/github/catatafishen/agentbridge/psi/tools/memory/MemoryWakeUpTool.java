package com.github.catatafishen.agentbridge.psi.tools.memory;

import com.github.catatafishen.agentbridge.memory.MemoryService;
import com.github.catatafishen.agentbridge.memory.store.DrawerDocument;
import com.github.catatafishen.agentbridge.memory.store.MemoryStore;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Returns the "wake-up" context: L0 identity + L1 essential story from the memory store.
 * This is the compact memory summary injected at session start.
 *
 * <p><b>Attribution:</b> wake-up layers adapted from MemPalace's layers.py (MIT License).
 */
public final class MemoryWakeUpTool extends Tool {

    private static final int MAX_DRAWERS = 15;
    private static final int SNIPPET_LENGTH = 200;
    private static final int MAX_CHARS = 3200;

    MemoryWakeUpTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "memory_wake_up";
    }

    @Override
    public @NotNull String displayName() {
        return "Memory Wake Up";
    }

    @Override
    public @NotNull String description() {
        return "Get the essential memory context for this project. Returns top drawers "
            + "grouped by room with 200-char snippets (max ~800 tokens). Call at the start "
            + "of a session to load context, or when you need a quick overview of what's been "
            + "remembered about this project.";
    }

    @Override
    public @NotNull ToolDefinition.Kind kind() {
        return ToolDefinition.Kind.READ;
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.MEMORY;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        MemoryService memoryService = MemoryService.getInstance(project);
        MemoryStore store = memoryService.getStore();
        if (store == null) {
            return "Memory not initialized. Enable in Settings > AgentBridge > Memory.";
        }

        String wing = memoryService.getEffectiveWing();
        List<DrawerDocument> topDrawers = store.getTopDrawersDiverse(wing, MAX_DRAWERS);

        // Skip stale memories — wake-up context should be reliable
        List<DrawerDocument> reliable = topDrawers.stream()
            .filter(d -> !DrawerDocument.STATE_STALE.equals(d.verificationState()))
            .toList();

        if (reliable.isEmpty()) {
            return "No memories stored yet for wing '" + wing + "'.";
        }

        Map<String, List<DrawerDocument>> byRoom = new LinkedHashMap<>();
        for (DrawerDocument d : reliable) {
            byRoom.computeIfAbsent(d.room(), k -> new java.util.ArrayList<>()).add(d);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Memory — ").append(wing).append("\n\n");
        for (var entry : byRoom.entrySet()) {
            appendRoomSection(sb, entry.getKey(), entry.getValue());
            if (sb.length() > MAX_CHARS) break;
        }
        return sb.toString().trim();
    }

    private static void appendRoomSection(StringBuilder sb, String room, List<DrawerDocument> drawers) {
        sb.append("### ").append(room).append('\n');
        for (DrawerDocument d : drawers) {
            String snippet = d.content().length() > SNIPPET_LENGTH
                ? d.content().substring(0, SNIPPET_LENGTH) + "…"
                : d.content();
            sb.append("- ");
            if (DrawerDocument.STATE_VERIFIED.equals(d.verificationState())) {
                sb.append("✓ ");
            }
            sb.append("[").append(d.memoryType()).append("] ")
                .append(snippet.replace('\n', ' ')).append('\n');
        }
        sb.append('\n');
    }
}
