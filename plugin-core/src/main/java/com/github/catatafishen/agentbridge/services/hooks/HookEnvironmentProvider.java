package com.github.catatafishen.agentbridge.services.hooks;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.services.McpHttpServer;
import com.github.catatafishen.agentbridge.settings.AgentBridgeStorageSettings;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes environment variables that {@link HookExecutor} injects into every hook script subprocess.
 * Gives POSIX shell scripts access to project context without needing to parse JSON or call HTTP endpoints.
 */
public final class HookEnvironmentProvider {

    private static final Logger LOG = Logger.getInstance(HookEnvironmentProvider.class);

    private HookEnvironmentProvider() {
        // utility class
    }

    /**
     * Computes project-related environment variables for hook scripts.
     *
     * @param project the current IntelliJ project
     * @return a map of environment variable names to values
     */
    @NotNull
    public static Map<String, String> getProjectEnvironment(@NotNull Project project) {
        Map<String, String> env = new LinkedHashMap<>();

        // Project directory
        String basePath = project.getBasePath();
        if (basePath != null) {
            env.put("AGENTBRIDGE_PROJECT_DIR", basePath);
        }

        // Hooks directory
        try {
            String hooksDir = AgentBridgeStorageSettings.getInstance()
                .getProjectStorageDir(project)
                .resolve("hooks")
                .toString();
            env.put("AGENTBRIDGE_HOOKS_DIR", hooksDir);
        } catch (Exception e) {
            LOG.warn("Failed to compute hooks directory", e);
        }

        // MCP port (only if server is running)
        try {
            McpHttpServer server = McpHttpServer.getInstance(project);
            if (server != null && server.isRunning()) {
                env.put("AGENTBRIDGE_MCP_PORT", String.valueOf(server.getPort()));

                // Connected agent identity (from MCP initialize handshake)
                String agentName = server.getConnectedAgentName();
                if (agentName != null && !agentName.isBlank()) {
                    env.put("AGENTBRIDGE_AGENT_NAME", agentName);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to get MCP server port", e);
        }

        // Source roots
        try {
            computeSourceRoots(project, env);
        } catch (Exception e) {
            LOG.warn("Failed to compute source roots for hook environment", e);
        }

        return env;
    }

    private static void computeSourceRoots(@NotNull Project project, @NotNull Map<String, String> env) {
        Map<String, List<String>> classified = com.intellij.openapi.application.ApplicationManager
            .getApplication()
            .runReadAction((com.intellij.openapi.util.Computable<Map<String, List<String>>>)
                () -> PlatformApiCompat.collectSourceRootsByClassification(project));

        putIfNonEmpty(env, "AGENTBRIDGE_SOURCE_ROOTS", classified.get("sources"));
        putIfNonEmpty(env, "AGENTBRIDGE_TEST_ROOTS", classified.get("test_sources"));
        putIfNonEmpty(env, "AGENTBRIDGE_GENERATED_ROOTS", classified.get("generated_sources"));
        putIfNonEmpty(env, "AGENTBRIDGE_RESOURCE_ROOTS", classified.get("resources"));
        putIfNonEmpty(env, "AGENTBRIDGE_EXCLUDED_DIRS", classified.get("excluded"));
    }

    private static void putIfNonEmpty(@NotNull Map<String, String> env,
                                      @NotNull String key,
                                      @org.jetbrains.annotations.Nullable java.util.List<String> paths) {
        if (paths != null && !paths.isEmpty()) {
            env.put(key, String.join("\n", paths));
        }
    }

    /**
     * Converts top-level string and primitive values from a JSON arguments object
     * into environment variables prefixed with {@code HOOK_ARG_}.
     * Nested objects and arrays are skipped.
     *
     * @param arguments the JSON arguments object
     * @return a map of environment variable names to values
     */
    @NotNull
    public static Map<String, String> getArgumentEnvironment(@NotNull JsonObject arguments) {
        Map<String, String> env = new LinkedHashMap<>();

        for (Map.Entry<String, JsonElement> entry : arguments.entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull() || value.isJsonObject() || value.isJsonArray()) {
                continue;
            }
            if (value.isJsonPrimitive()) {
                env.put("HOOK_ARG_" + entry.getKey(), value.getAsString());
            }
        }

        return env;
    }
}
