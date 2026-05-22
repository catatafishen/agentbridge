package com.github.catatafishen.agentbridge.psi.tools.git;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import org.jetbrains.annotations.NotNull;

/**
 * MCP tool that initializes a new git repository.
 *
 * <p>Runs {@code git init} in the project root (or a specified directory),
 * then refreshes IntelliJ's VCS mapping so the new repo is immediately
 * recognized by the IDE.
 */
public final class GitInitTool extends GitTool {

    private static final String PARAM_PATH = "path";

    public GitInitTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_init";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Init";
    }

    @Override
    public @NotNull String description() {
        return "Initialize a new git repository. Runs 'git init' in the project root "
            + "or a specified directory, then refreshes IntelliJ's VCS mapping so the "
            + "new repo is immediately recognized. Returns the initial branch name and "
            + "repository path.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.WRITE;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional(PARAM_PATH, TYPE_STRING,
                "Directory to initialize. Defaults to the project root. "
                    + "Can be absolute or relative to the project root.")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String basePath = project.getBasePath();
        if (basePath == null) return "Error: project has no base path";

        String targetDir = resolveTargetDir(args, basePath);

        java.io.File targetFile = new java.io.File(targetDir);
        if (targetFile.exists() && !targetFile.isDirectory()) {
            return "Error: path exists but is not a directory: " + targetDir;
        }
        if (!targetFile.exists() && !targetFile.mkdirs()) {
            return "Error: could not create directory: " + targetDir;
        }

        String result = runGitIn(targetDir, "init");

        refreshVcsMappings(targetDir, basePath);

        return buildResultMessage(result, targetDir);
    }

    private String resolveTargetDir(@NotNull JsonObject args, @NotNull String basePath) {
        if (args.has(PARAM_PATH) && hasText(args.get(PARAM_PATH).getAsString())) {
            String pathArg = args.get(PARAM_PATH).getAsString().trim();
            java.io.File pathFile = new java.io.File(pathArg);
            if (pathFile.isAbsolute()) {
                return pathFile.getPath();
            }
            return new java.io.File(basePath, pathArg).getPath();
        }
        return basePath;
    }

    private void refreshVcsMappings(@NotNull String targetDir, @NotNull String basePath) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
            var root = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByPath(targetDir);
            if (root != null) {
                com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(true, true, true, root);
            }
            com.intellij.openapi.vcs.changes.VcsDirtyScopeManager.getInstance(project).markEverythingDirty();
            String mappingPath = targetDir.equals(basePath) ? "" : targetDir;
            ProjectLevelVcsManager.getInstance(project).setDirectoryMapping(mappingPath, "Git");
        });
    }

    private @NotNull String buildResultMessage(@NotNull String result, @NotNull String targetDir) {
        StringBuilder sb = new StringBuilder();
        sb.append(result.trim());
        sb.append("\n\n--- Context ---\n");
        sb.append("Repository initialized in: ").append(targetDir).append('\n');

        String branch = runGitInQuiet(targetDir, "rev-parse", "--abbrev-ref", "HEAD");
        if (branch != null && !branch.isBlank()) {
            sb.append("Initial branch: ").append(branch.trim()).append('\n');
        }

        return sb.toString();
    }
}
