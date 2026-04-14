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
 * Query the knowledge graph for structured facts. Returns triples matching
 * optional subject/predicate/object filters. Only returns currently valid triples.
 *
 * <p><b>Attribution:</b> adapted from MemPalace's mempalace_kg_query (MIT License).
 */
public final class MemoryKgQueryTool extends Tool {

    private static final String PARAM_SUBJECT = "subject";
    private static final String PARAM_PREDICATE = "predicate";
    private static final String PARAM_OBJECT = "object";
    private static final String PARAM_LIMIT = "limit";

    MemoryKgQueryTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "memory_kg_query";
    }

    @Override
    public @NotNull String displayName() {
        return "Memory KG Query";
    }

    @Override
    public @NotNull String description() {
        return "Query the knowledge graph for structured facts. Returns subject-predicate-object triples "
            + "matching optional filters. Only returns currently valid (non-invalidated) triples. "
            + "Use for looking up known facts, relationships, and project properties.";
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
        return schema(
            Param.optional(PARAM_SUBJECT, TYPE_STRING, "Filter by subject entity (e.g., 'project', 'team')"),
            Param.optional(PARAM_PREDICATE, TYPE_STRING, "Filter by relationship (e.g., 'uses', 'prefers')"),
            Param.optional(PARAM_OBJECT, TYPE_STRING, "Filter by object (substring match)"),
            Param.optional(PARAM_LIMIT, TYPE_INTEGER, "Max results (default: 20)")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String subject = args.has(PARAM_SUBJECT) ? args.get(PARAM_SUBJECT).getAsString() : null;
        String predicate = args.has(PARAM_PREDICATE) ? args.get(PARAM_PREDICATE).getAsString() : null;
        String object = args.has(PARAM_OBJECT) ? args.get(PARAM_OBJECT).getAsString() : null;
        int limit = args.has(PARAM_LIMIT) ? args.get(PARAM_LIMIT).getAsInt() : 20;

        MemoryService memoryService = MemoryService.getInstance(project);
        KnowledgeGraph kg = memoryService.getKnowledgeGraph();
        if (kg == null) {
            return "Error: Memory is not initialized. Enable it in Settings > AgentBridge > Memory.";
        }

        List<KgTriple> triples = kg.query(subject, predicate, object, limit);
        if (triples.isEmpty()) {
            return "No matching triples found.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(triples.size()).append(" triple(s):\n\n");
        for (KgTriple t : triples) {
            sb.append("- [").append(t.id()).append("] ")
                .append(t.subject()).append(" → ").append(t.predicate())
                .append(" → ").append(t.object());
            if (t.validFrom() != null) {
                sb.append(" (from: ").append(t.validFrom()).append(')');
            }
            String evidence = t.evidence();
            if (!evidence.isEmpty() && !"[]".equals(evidence)) {
                sb.append("\n  Evidence: ").append(evidence);
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }
}
