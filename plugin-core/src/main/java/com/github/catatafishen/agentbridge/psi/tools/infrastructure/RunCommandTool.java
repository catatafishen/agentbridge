package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.testing.RunTestsTool;
import com.github.catatafishen.agentbridge.settings.ShellEnvironment;
import com.github.catatafishen.agentbridge.ui.renderers.RunCommandRenderer;
import com.google.gson.JsonObject;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.util.execution.ParametersListUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a shell command with paginated output.
 */
public final class RunCommandTool extends InfrastructureTool {

    private static final String PARAM_COMMAND = "command";
    private static final String PARAM_SHELL = "shell";
    private static final String PARAM_OFFSET = "offset";
    private static final String PARAM_TIMEOUT = "timeout";
    private static final String PARAM_MAX_CHARS = "max_chars";
    private static final String JSON_TITLE = "title";
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
        return Kind.EXECUTE;
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
        String configuredShell = project != null ? ShellEnvironment.getShellPath(project) : "IDE-configured shell";
        return schema(
            Param.required(PARAM_COMMAND, TYPE_STRING, "Shell command to execute (e.g., 'gradle build', 'cat file.txt')"),
            Param.optional(PARAM_SHELL, TYPE_STRING,
                "Shell executable to use (default: '" + configuredShell + "', the IDE-configured terminal shell). "
                    + "Override with any shell path available on this system, e.g. '/usr/bin/zsh' or 'pwsh'."),
            Param.optional(PARAM_TIMEOUT, TYPE_INTEGER, "Timeout in seconds (default: 60)"),
            Param.optional(JSON_TITLE, TYPE_STRING, "Human-readable title for the Run panel tab. ALWAYS set this to a short descriptive name"),
            Param.optional(PARAM_OFFSET, TYPE_INTEGER, "Character offset to start output from (default: 0). Use for pagination when output is truncated"),
            Param.optional(PARAM_MAX_CHARS, TYPE_INTEGER, "Maximum characters to return per page (default: 8000)")
        );
    }

    @Override
    @SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String command = args.get(PARAM_COMMAND).getAsString();
        String abuseType = ToolUtils.detectCommandAbuseType(command);
        if ("test".equals(abuseType)) {
            return new RunTestsTool(project).executeFromCommand(command);
        }
        if ("grep".equals(abuseType) && ToolUtils.grepTargetsOnlyOutsideSourceRoots(project, command)) {
            abuseType = null;
        }
        if ("grep".equals(abuseType)) {
            return ToolUtils.getCommandAbuseMessage("grep");
        }

        EdtUtil.invokeAndWait(() ->
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments());

        String title = args.has(JSON_TITLE) ? args.get(JSON_TITLE).getAsString() : null;
        String basePath = project.getBasePath();
        if (basePath == null) return ERROR_NO_PROJECT_PATH;
        int timeoutSec = args.has(PARAM_TIMEOUT) ? args.get(PARAM_TIMEOUT).getAsInt() : 60;
        int offset = args.has(PARAM_OFFSET) ? args.get(PARAM_OFFSET).getAsInt() : 0;
        int maxChars = args.has(PARAM_MAX_CHARS) ? args.get(PARAM_MAX_CHARS).getAsInt() : 8000;
        String tabTitle = title != null ? title : "Command: " + truncateForTitle(command);
        String shellOverride = args.has(PARAM_SHELL) ? args.get(PARAM_SHELL).getAsString() : null;
        // Treat a blank shell override (e.g. from JSON templating) the same as "not provided"
        if (shellOverride != null && shellOverride.isBlank()) shellOverride = null;

        Map<String, String> injectedEnv = extractInjectedEnv(args);
        GeneralCommandLine cmd = buildCommandLine(command, basePath, injectedEnv, shellOverride);
        String effectiveShell = shellOverride != null ? shellOverride : ShellEnvironment.getShellPath(project);
        // Tokenize before extracting the shell name in case the setting includes extra args
        String shellName = shellBaseName(ParametersListUtil.parse(effectiveShell).getFirst());
        ProcessResult result = executeInRunPanel(cmd, tabTitle, timeoutSec);

        return formatExecuteOutput(result, args, maxChars, offset, timeoutSec, shellName);
    }

    @Override
    public @NotNull Object resultRenderer() {
        return RunCommandRenderer.INSTANCE;
    }

    private static String truncateForTitle(String command) {
        return command.length() > 40 ? command.substring(0, 37) + "..." : command;
    }

    private GeneralCommandLine buildCommandLine(String command, String basePath,
                                                Map<String, String> injectedEnv,
                                                @Nullable String shellOverride) {
        String rawShellPath = (shellOverride != null && !shellOverride.isBlank())
            ? shellOverride
            : ShellEnvironment.getShellPath(project);
        // The IDE terminal setting may be a full command line (e.g. "/usr/bin/bash --login" or
        // "\"C:\Program Files\Git\bin\bash.exe\" --login"). Tokenize so that we pass the
        // executable and any extra args separately to GeneralCommandLine.
        List<String> shellTokens = ParametersListUtil.parse(rawShellPath);
        String shellExe = shellTokens.getFirst();
        String shellName = shellBaseName(shellExe);
        String invocationFlag = switch (shellName) {
            case "powershell", "pwsh" -> "-Command";
            case "cmd" -> "/c";
            default -> "-c"; // POSIX: sh, bash, zsh…
        };
        GeneralCommandLine cmd = new GeneralCommandLine();
        cmd.setExePath(shellExe);
        // Preserve any extra args from the configured shell command line (e.g. --login)
        shellTokens.subList(1, shellTokens.size()).forEach(cmd::addParameter);
        cmd.addParameter(invocationFlag);
        cmd.addParameter(command);
        cmd.setWorkDirectory(basePath);
        if (!injectedEnv.isEmpty()) {
            cmd.withEnvironment(injectedEnv);
        }
        String javaHome = getProjectJavaHome();
        if (javaHome != null) {
            cmd.withEnvironment(JAVA_HOME_ENV, javaHome);
        }
        return cmd;
    }

    /**
     * Extracts the lowercase base name (no path, no extension) from a shell path.
     */
    static String shellBaseName(String shellPath) {
        int lastSlash = Math.max(shellPath.lastIndexOf('/'), shellPath.lastIndexOf('\\'));
        String name = lastSlash >= 0 ? shellPath.substring(lastSlash + 1) : shellPath;
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.toLowerCase();
    }

    /**
     * Scans {@code args} for entries whose key starts with {@code _env.} and returns them as a
     * map of variable-name → value. These entries are written by {@code Hook.setEnv(name, value)}
     * in pre-hooks so that sensitive values (e.g., {@code GH_TOKEN}) can be injected as OS-level
     * environment variables without modifying or wrapping the command string.
     */
    static Map<String, String> extractInjectedEnv(JsonObject args) {
        Map<String, String> env = new LinkedHashMap<>();
        for (var entry : args.entrySet()) {
            if (entry.getKey().startsWith("_env.") && entry.getValue().isJsonPrimitive()) {
                env.put(entry.getKey().substring(5), entry.getValue().getAsString());
            }
        }
        return env;
    }

    private String formatExecuteOutput(ProcessResult result, JsonObject args, int maxChars,
                                       int offset, int timeoutSec, String shellName) {
        if (result.timedOut()) {
            return "Command timed out after " + timeoutSec + " seconds [" + shellName + "].\n\n"
                + ToolUtils.truncateOutput(result.output(), maxChars, offset);
        }
        String fullOutput = result.output();
        boolean failed = result.exitCode() != 0;
        int effectiveOffset = offset;
        if (failed && !args.has(PARAM_OFFSET) && fullOutput.length() > maxChars) {
            effectiveOffset = fullOutput.length() - maxChars;
        }
        String header = failed
            ? "Command failed (exit code " + result.exitCode() + ") [" + shellName + "]"
            : "Command succeeded [" + shellName + "]";
        if (failed && effectiveOffset > 0) {
            header += "\n(showing last " + maxChars + " chars — use offset=0 for beginning)";
        }
        return header + "\n\n" + ToolUtils.truncateOutput(fullOutput, maxChars, effectiveOffset);
    }

    private String getProjectJavaHome() {
        try {
            @Nullable Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
            if (sdk != null && sdk.getHomePath() != null) {
                return sdk.getHomePath();
            }
        } catch (Exception ignored) {
            // SDK access errors are non-fatal
        }
        return System.getenv(JAVA_HOME_ENV);
    }
}
