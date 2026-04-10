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

import java.time.Instant;

/**
 * Add a structured fact to the knowledge graph as a subject-predicate-object triple.
 * Optionally invalidates existing triples for the same subject+predicate before adding.
 *
 * <p><b>Attribution:</b> adapted from MemPalace's mempalace_kg_add (MIT License).
 */
public final class MemoryKgAddTool extends Tool {

    private static final String PARAM_SUBJECT = "subject";
    private static final String PARAM_PREDICATE = "predicate";
    private static final String PARAM_OBJECT = "object";
    private static final String PARAM_VALID_FROM = "valid_from";
    private static final String PARAM_REPLACE = "replace";

    MemoryKgAddTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "memory_kg_add";
    }

    @Override
    public @NotNull String displayName() {
        return "Memory KG Add";
    }

    @Override
    public @NotNull String description() {
        return "Add a structured fact (subject → predicate → object) to the knowledge graph. "
            + "Examples: ('project', 'uses', 'Java 21'), ('team', 'prefers', 'conventional commits'). "
            + "Set replace=true to invalidate any existing triples with the same subject+predicate first.";
    }

    @Override
    public @NotNull ToolDefinition.Kind kind() {
        return ToolDefinition.Kind.WRITE;
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.MEMORY;
    }

    @Override
    public boolean isIdempotent() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {PARAM_SUBJECT, TYPE_STRING, "Subject entity (e.g., 'project', 'team', 'auth-module')"},
            {PARAM_PREDICATE, TYPE_STRING, "Relationship/predicate (e.g., 'uses', 'prefers', 'depends-on')"},
            {PARAM_OBJECT, TYPE_STRING, "Object/value (e.g., 'Java 21', 'conventional commits')"},
            {PARAM_VALID_FROM, TYPE_STRING, "ISO 8601 date when this fact became valid (optional)"},
            {PARAM_REPLACE, TYPE_BOOLEAN, "If true, invalidate existing triples with same subject+predicate first (default: false)"},
        }, PARAM_SUBJECT, PARAM_PREDICATE, PARAM_OBJECT);
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String subject = KgTriple.sanitizeName(args.get(PARAM_SUBJECT).getAsString());
        String predicate = KgTriple.sanitizeName(args.get(PARAM_PREDICATE).getAsString());
        String object = KgTriple.sanitizeContent(args.get(PARAM_OBJECT).getAsString());
        boolean replace = args.has(PARAM_REPLACE) && args.get(PARAM_REPLACE).getAsBoolean();

        Instant validFrom = null;
        if (args.has(PARAM_VALID_FROM)) {
            validFrom = Instant.parse(args.get(PARAM_VALID_FROM).getAsString());
        }

        MemoryService memoryService = MemoryService.getInstance(project);
        KnowledgeGraph kg = memoryService.getKnowledgeGraph();
        if (kg == null) {
            return "Error: Memory is not initialized. Enable it in Settings > AgentBridge > Memory.";
        }

        if (replace) {
            int invalidated = kg.invalidateBySubjectPredicate(subject, predicate);
            if (invalidated > 0) {
                // Continue to add the new triple
            }
        }

        KgTriple triple = KgTriple.builder()
            .subject(subject)
            .predicate(predicate)
            .object(object)
            .validFrom(validFrom)
            .build();

        long id = kg.addTriple(triple);

        StringBuilder sb = new StringBuilder();
        sb.append("Triple added (id: ").append(id).append("): ")
            .append(subject).append(" → ").append(predicate).append(" → ").append(object);
        if (replace) {
            sb.append(" [replaced previous values]");
        }
        return sb.toString();
    }
}
