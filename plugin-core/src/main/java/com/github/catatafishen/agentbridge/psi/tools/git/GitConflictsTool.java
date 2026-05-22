package com.github.catatafishen.agentbridge.psi.tools.git;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lists all files with unresolved merge conflicts in the working tree.
 * Uses {@code git ls-files --unmerged} to discover conflicted files and their types.
 */
@SuppressWarnings("java:S112")
public final class GitConflictsTool extends GitTool {

    public GitConflictsTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_conflicts";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Conflicts";
    }

    @Override
    public @NotNull String description() {
        return "List all files with unresolved merge conflicts. Shows each file's conflict type "
            + "(both modified, deleted by us/theirs, added by both). Use git_conflict_show to see "
            + "the 3-way diff for a specific file, and git_conflict_resolve to resolve conflicts.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.READ;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "List merge conflicts";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional(PARAM_REPO, TYPE_STRING, REPO_PARAM_DESCRIPTION)
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String repoParam = args.has(PARAM_REPO) ? args.get(PARAM_REPO).getAsString() : null;
        String root = resolveRepoRootOrError(repoParam);
        if (root.startsWith("Error")) return root;

        String lsFiles = runGitInQuiet(root, "ls-files", "--unmerged", "--abbrev");
        if (lsFiles == null || lsFiles.isBlank()) {
            return "No merge conflicts found." + getBranchSummaryIn(root);
        }

        return formatConflicts(lsFiles, root);
    }

    private String formatConflicts(@NotNull String lsFiles, @NotNull String root) {
        Map<String, int[]> fileStages = parseStagesToMap(lsFiles);

        StringBuilder result = new StringBuilder();
        result.append("Unresolved conflicts:\n\n");

        for (var entry : fileStages.entrySet()) {
            int[] stages = entry.getValue();
            String type = describeConflictType(stages[0] == 1, stages[1] == 1, stages[2] == 1);
            result.append("  ").append(entry.getKey()).append("  (").append(type).append(")\n");
        }

        result.append("\nTotal: ").append(fileStages.size()).append(" file(s)\n");
        result.append("\nUse git_conflict_show(path) to see the 3-way diff for a file.");
        result.append("\nUse git_conflict_resolve(path, action) to resolve.");
        result.append(getBranchSummaryIn(root));

        return result.toString();
    }

    static Map<String, int[]> parseStagesToMap(@NotNull String lsFiles) {
        Map<String, int[]> fileStages = new LinkedHashMap<>();
        for (String line : lsFiles.split("\n")) {
            int tabIdx = line.indexOf('\t');
            if (line.isBlank() || tabIdx < 2) continue;
            String path = line.substring(tabIdx + 1);
            // Stage digit is immediately before the tab: "100644 abc1234 2\tpath"
            int stage = line.charAt(tabIdx - 1) - '0';
            if (stage >= 1 && stage <= 3) {
                fileStages.computeIfAbsent(path, k -> new int[3])[stage - 1] = 1;
            }
        }
        return fileStages;
    }

    /**
     * Describes the conflict type based on which index stages are present.
     * Stage 1 = common ancestor (base), Stage 2 = ours, Stage 3 = theirs.
     */
    static String describeConflictType(boolean hasBase, boolean hasOurs, boolean hasTheirs) {
        if (hasOurs && hasTheirs) {
            return hasBase ? "both modified" : "added by both";
        }
        if (hasBase && hasOurs) return "deleted by them";
        if (hasBase && hasTheirs) return "deleted by us";
        return "conflicted";
    }
}
