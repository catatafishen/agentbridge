package com.github.catatafishen.agentbridge.psi.tools.project;

import com.github.catatafishen.agentbridge.ui.renderers.SimpleStatusRenderer;
import com.google.gson.JsonObject;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import org.jetbrains.annotations.NotNull;

import java.io.File;

/**
 * Attaches a local directory (or a freshly cloned git repository) as a temporary read-only
 * IntelliJ module, making its files visible to all existing read tools via the project index.
 *
 * <p>Provide {@code path} for a local directory, or {@code git_url} to clone a repo first.
 * When cloning, the destination is placed in the parent folder of the current project (a sibling
 * directory) so the agent's working set stays organised. Use {@code detach_external_dir} to
 * remove the module when done.
 *
 * <p>The module has no source roots — files are indexed for search and navigation but are not
 * compiled. Common output directories (.git, node_modules, build, target, …) are excluded from
 * indexing automatically.
 *
 * <p>Write tools (write_file, edit_text, create_file, delete_file) reject paths inside attached
 * external directories to prevent accidental modification.
 */
public final class AttachExternalDirTool extends ProjectTool {

    private static final String PARAM_PATH = "path";
    private static final String PARAM_GIT_URL = "git_url";
    private static final String PARAM_ALIAS = "alias";
    private static final String PARAM_DESTINATION = "destination";

    public AttachExternalDirTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "attach_external_dir";
    }

    @Override
    public @NotNull String displayName() {
        return "Attach External Dir";
    }

    @Override
    public @NotNull String description() {
        return """
            Attaches a local directory or a git repository as a temporary read-only module, \
            making its files visible to all read tools (list_project_files, search_text, \
            get_file_outline, search_symbols, etc.).

            Provide 'path' for a local directory, or 'git_url' to clone a repository first. \
            When cloning, the destination is a sibling folder of the current project (same \
            parent directory). Use 'destination' to override the clone target path.

            The directory is indexed without source roots — files are navigable and searchable \
            but not compiled. Common noise directories (.git, node_modules, build, target, …) \
            are excluded automatically. Write tools reject paths inside attached directories.

            Use detach_external_dir to remove the module when done.""";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Attach external directory: {path}{git_url}";
    }

    @Override
    public boolean isOpenWorld() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional(PARAM_PATH, TYPE_STRING, "Absolute path to a local directory to attach"),
            Param.optional(PARAM_GIT_URL, TYPE_STRING, "Git repository URL to clone and attach. The clone is placed in a sibling folder of the current project"),
            Param.optional(PARAM_ALIAS, TYPE_STRING, "Short alias for the attached directory (used with detach_external_dir). Defaults to the directory name"),
            Param.optional(PARAM_DESTINATION, TYPE_STRING, "Override clone destination path (only used with git_url). Defaults to a sibling folder named after the alias")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SimpleStatusRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        boolean hasPath = args.has(PARAM_PATH) && !args.get(PARAM_PATH).getAsString().isBlank();
        boolean hasGitUrl = args.has(PARAM_GIT_URL) && !args.get(PARAM_GIT_URL).getAsString().isBlank();

        if (!hasPath && !hasGitUrl) {
            return "Error: provide either 'path' (local directory) or 'git_url' (repository to clone).";
        }
        if (hasPath && hasGitUrl) {
            return "Error: provide either 'path' or 'git_url', not both.";
        }

        if (hasGitUrl) {
            String gitUrl = args.get(PARAM_GIT_URL).getAsString().trim();
            String alias = resolveAlias(args, guessNameFromUrl(gitUrl));
            String destination = resolveCloneDestination(args, alias);
            String cloneError = cloneRepo(gitUrl, destination);
            if (cloneError != null) return cloneError;
            return doAttach(destination, alias);
        } else {
            String rawPath = args.get(PARAM_PATH).getAsString().trim();
            String alias = resolveAlias(args, guessNameFromPath(rawPath));
            return doAttach(rawPath, alias);
        }
    }

    private String doAttach(String absolutePath, String alias) throws Exception {
        ExternalDirRegistry registry = ExternalDirRegistry.getInstance(project);
        String error = registry.attach(absolutePath, alias);
        if (error != null) return error;

        // Trigger VFS refresh so the files appear in the index promptly
        LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath);

        return "Attached: " + absolutePath + "\nAlias: " + alias
            + "\nModule: " + ExternalDirRegistry.MODULE_NAME_PREFIX + alias
            + "\n\nFiles are now visible to list_project_files, search_text, "
            + "get_file_outline, and other read tools."
            + "\nIndexing is running in the background — use get_indexing_status to check progress."
            + "\nUse detach_external_dir with alias='" + alias + "' to remove when done.";
    }

    private String cloneRepo(String gitUrl, String destination) throws Exception {
        File destFile = new File(destination);
        if (destFile.exists()) {
            return "Error: clone destination already exists: " + destination
                + ". Provide a 'destination' parameter or use 'path' to attach an existing directory.";
        }

        GeneralCommandLine cmd = new GeneralCommandLine("git", "clone", "--", gitUrl, destination);
        cmd.setWorkDirectory(System.getProperty("user.dir"));

        ProcessResult result = executeInRunPanel(cmd, "git clone " + gitUrl, 120);
        if (result.exitCode() != 0) {
            return "Error: git clone failed (exit " + result.exitCode() + "):\n" + result.output();
        }
        return null;
    }

    private String resolveCloneDestination(JsonObject args, String alias) {
        if (args.has(PARAM_DESTINATION) && !args.get(PARAM_DESTINATION).getAsString().isBlank()) {
            return args.get(PARAM_DESTINATION).getAsString().trim();
        }
        // Default: sibling of project base path
        String basePath = project.getBasePath();
        if (basePath != null) {
            String parentDir = new File(basePath).getParent();
            if (parentDir != null) {
                return parentDir + "/" + alias;
            }
        }
        return System.getProperty("user.dir") + "/" + alias;
    }

    private static final java.util.regex.Pattern SPECIAL_CHARS = java.util.regex.Pattern.compile("[^a-zA-Z0-9_.\\-]");
    private static final java.util.regex.Pattern CONSECUTIVE_DASHES = java.util.regex.Pattern.compile("-{2,}");
    private static final java.util.regex.Pattern URL_PATH_PREFIX = java.util.regex.Pattern.compile(".*/");
    private static final java.util.regex.Pattern GIT_SUFFIX = java.util.regex.Pattern.compile("\\.git$");

    private static String resolveAlias(JsonObject args, String defaultAlias) {
        if (args.has(PARAM_ALIAS) && !args.get(PARAM_ALIAS).getAsString().isBlank()) {
            return sanitizeAlias(args.get(PARAM_ALIAS).getAsString().trim());
        }
        return sanitizeAlias(defaultAlias);
    }

    private static String sanitizeAlias(String raw) {
        // Module names cannot contain spaces or special chars
        return CONSECUTIVE_DASHES.matcher(SPECIAL_CHARS.matcher(raw).replaceAll("-")).replaceAll("-");
    }

    private static String guessNameFromUrl(String gitUrl) {
        // Extract last path segment, strip .git suffix
        String name = GIT_SUFFIX.matcher(URL_PATH_PREFIX.matcher(gitUrl).replaceAll("")).replaceAll("");
        return name.isEmpty() ? "external" : name;
    }

    private static String guessNameFromPath(String path) {
        String name = new File(path).getName();
        return name.isEmpty() ? "external" : name;
    }

}
