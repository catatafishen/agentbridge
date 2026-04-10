package com.github.catatafishen.agentbridge.psi.tools.memory;

import com.github.catatafishen.agentbridge.memory.MemoryService;
import com.github.catatafishen.agentbridge.memory.kg.KgTriple;
import com.github.catatafishen.agentbridge.memory.kg.KnowledgeGraph;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Get the timeline of all triples (including invalidated) for a subject.
 * Useful for understanding how knowledge has evolved over time.
 *
 * <p><b>Attribution:</b> adapted from MemPalace's mempalace_kg_timeline (MIT License).
 */
public final class MemoryKgTimelineTool extends Tool {

    private static final String PARAM_SUBJECT = "subject";
    private static final String PARAM_LIMIT = "limit";

    MemoryKgTimelineTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "memory_kg_timeline";
    }

    @Override
    public @NotNull String displayName() {
        return "Memory KG Timeline";
    }

    @Override
    public @NotNull String description() {
        return "Show the timeline of all facts (including invalidated) for a subject. "
            + "Returns triples ordered by creation date, showing how knowledge evolved. "
            + "Invalidated triples are marked with their valid_until date.";
    }

    @Override
    public @NotNull ToolDefinition.Kind kind() {
        return ToolDefinition.Kind.SEARCH;
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
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {PARAM_SUBJECT, TYPE_STRING, "Subject entity to get timeline for", true},
            {PARAM_LIMIT, TYPE_INTEGER, "Max entries (default: 50)", false},
        });
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String subject = args.get(PARAM_SUBJECT).getAsString();
        int limit = args.has(PARAM_LIMIT) ? args.get(PARAM_LIMIT).getAsInt() : 50;

        MemoryService memoryService = MemoryService.getInstance(project);
        KnowledgeGraph kg = memoryService.getKnowledgeGraph();
        if (kg == null) {
            return "Error: Memory is not initialized. Enable it in Settings > AgentBridge > Memory.";
        }

        List<KgTriple> timeline = kg.getTimeline(subject, limit);
        if (timeline.isEmpty()) {
            return "No facts found for subject: " + subject;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Timeline for '").append(subject).append("' (").append(timeline.size()).append(" entries):\n\n");
        for (KgTriple t : timeline) {
            sb.append("- [").append(t.createdAt()).append("] ")
                .append(t.predicate()).append(" → ").append(t.object());
            if (t.validUntil() != null) {
                sb.append(" ✗ invalidated ").append(t.validUntil());
            } else {
                sb.append(" ✓ current");
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }
}
