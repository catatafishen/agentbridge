package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.RunCommandRenderer;
import com.google.gson.JsonObject;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Runs a shell command with paginated output.
 */
public final class RunCommandTool extends InfrastructureTool {

    private static final String PARAM_COMMAND = "command";
    private static final String JSON_PARAMETERS = "parameters";
    private static final String PARAM_OFFSET = "offset";
    private static final String PARAM_TIMEOUT = "timeout";
    private static final String PARAM_MAX_CHARS = "max_chars";
    private static final String JSON_TITLE = "title";
    private static final String OS_NAME_PROPERTY = "os.name";
    private static final String JAVA_HOME_ENV = "JAVA_HOME";
    private static final String ERROR_NO_PROJECT_PATH = "No project base path";

    public RunCommandTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "run_command";
    }

    @Override
    public @NotNull String displayName() {
        return "Run Command";
    }

    @Override
    public @NotNull String description() {
        return "Run a shell command with paginated output. Prefer this over the built-in bash tool. " +
            "Returns stdout/stderr with exit code. Use offset parameter to paginate large output. " +
            "Default timeout: 60s. For interactive commands needing stdin, use run_in_terminal instead.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public boolean needsWriteLock() {
        return false;
    }

    @Override
    public boolean isOpenWorld() {
        return true;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Run: {command}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_COMMAND, TYPE_STRING, "Shell command to execute (e.g., 'gradle build', 'cat file.txt')"),
            Param.optional(PARAM_TIMEOUT, TYPE_INTEGER, "Timeout in seconds (default: 60)"),
            Param.optional(JSON_TITLE, TYPE_STRING, "Human-readable title for the Run panel tab. ALWAYS set this to a short descriptive name"),
            Param.optional(PARAM_OFFSET, TYPE_INTEGER, "Character offset to start output from (default: 0). Use for pagination when output is truncated"),
            Param.optional(PARAM_MAX_CHARS, TYPE_INTEGER, "Maximum characters to return per page (default: 8000)")
        );
    }

    @Override
    public @Nullable String detectPermissionAbuse(@Nullable Object toolCall) {
        if (!(toolCall instanceof com.google.gson.JsonObject jsonToolCall)) return null;
        String command = extractCommandFromToolCall(jsonToolCall);
        if (command == null) return null;
        return ToolUtils.detectCommandAbuseType(command);
    }

    private static @Nullable String extractCommandFromToolCall(com.google.gson.JsonObject toolCall) {
        // Check direct parameters
        if (toolCall.has(JSON_PARAMETERS) && toolCall.get(JSON_PARAMETERS).isJsonObject()) {
            var params = toolCall.getAsJsonObject(JSON_PARAMETERS);
            if (params.has(PARAM_COMMAND) && params.get(PARAM_COMMAND).isJsonPrimitive()) {
                return params.get(PARAM_COMMAND).getAsString().toLowerCase().trim();
            }
        }
        // Check nested input/arguments
        for (String wrapper : new String[]{"arguments", "input"}) {
            if (toolCall.has(wrapper) && toolCall.get(wrapper).isJsonObject()) {
                var nested = toolCall.getAsJsonObject(wrapper);
                if (nested.has(PARAM_COMMAND) && nested.get(PARAM_COMMAND).isJsonPrimitive()) {
                    return nested.get(PARAM_COMMAND).getAsString().toLowerCase().trim();
                }
            }
        }
        return null;
    }

    @Override
    @SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String command = args.get(PARAM_COMMAND).getAsString();
        String abuseType = ToolUtils.detectCommandAbuseType(command);
        if (abuseType != null) return ToolUtils.getCommandAbuseMessage(abuseType);

        EdtUtil.invokeAndWait(() ->
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments());

        String title = args.has(JSON_TITLE) ? args.get(JSON_TITLE).getAsString() : null;
        String basePath = project.getBasePath();
        if (basePath == null) return ERROR_NO_PROJECT_PATH;
        int timeoutSec = args.has(PARAM_TIMEOUT) ? args.get(PARAM_TIMEOUT).getAsInt() : 60;
        int offset = args.has(PARAM_OFFSET) ? args.get(PARAM_OFFSET).getAsInt() : 0;
        int maxChars = args.has(PARAM_MAX_CHARS) ? args.get(PARAM_MAX_CHARS).getAsInt() : 8000;
        String tabTitle = title != null ? title : "Command: " + truncateForTitle(command);

        GeneralCommandLine cmd = buildCommandLine(command, basePath);
        ProcessResult result = executeInRunPanel(cmd, tabTitle, timeoutSec);

        return formatExecuteOutput(result, args, maxChars, offset, timeoutSec);
    }

    @Override
    public @NotNull Object resultRenderer() {
        return RunCommandRenderer.INSTANCE;
    }

    private static String truncateForTitle(String command) {
        return command.length() > 40 ? command.substring(0, 37) + "..." : command;
    }

    private GeneralCommandLine buildCommandLine(String command, String basePath) {
        GeneralCommandLine cmd;
        if (System.getProperty(OS_NAME_PROPERTY).contains("Win")) {
            cmd = new GeneralCommandLine("cmd", "/c", command);
        } else {
            cmd = new GeneralCommandLine("sh", "-c", command);
        }
        cmd.setWorkDirectory(basePath);
        String javaHome = getProjectJavaHome();
        if (javaHome != null) {
            cmd.withEnvironment(JAVA_HOME_ENV, javaHome);
        }
        return cmd;
    }

    private String formatExecuteOutput(ProcessResult result, JsonObject args, int maxChars, int offset, int timeoutSec) {
        if (result.timedOut()) {
            return "Command timed out after " + timeoutSec + " seconds.\n\n"
                + ToolUtils.truncateOutput(result.output(), maxChars, offset);
        }
        String fullOutput = result.output();
        boolean failed = result.exitCode() != 0;
        int effectiveOffset = offset;
        if (failed && !args.has(PARAM_OFFSET) && fullOutput.length() > maxChars) {
            effectiveOffset = fullOutput.length() - maxChars;
        }
        String header = failed
            ? "Command failed (exit code " + result.exitCode() + ")"
            : "Command succeeded";
        if (failed && effectiveOffset > 0) {
            header += "\n(showing last " + maxChars + " chars — use offset=0 for beginning)";
        }
        return header + "\n\n" + ToolUtils.truncateOutput(fullOutput, maxChars, effectiveOffset);
    }

    private String getProjectJavaHome() {
        try {
            Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
            if (sdk != null && sdk.getHomePath() != null) {
                return sdk.getHomePath();
            }
        } catch (Exception ignored) {
            // SDK access errors are non-fatal
        }
        return System.getenv(JAVA_HOME_ENV);
    }
}
