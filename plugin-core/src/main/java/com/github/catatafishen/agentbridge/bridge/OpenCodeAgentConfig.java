package com.github.catatafishen.agentbridge.bridge;

import com.github.catatafishen.agentbridge.services.AgentProfile;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * OpenCode-specific {@link AgentConfig} implementation.
 *
 * <p>Extends the generic {@link ProfileBasedAgentConfig} with OpenCode's unique requirements:
 * <ul>
 *   <li>Writes a custom {@code opencode.json} config file (MCP + permissions)</li>
 *   <li>Uses {@code "mcp"} as the JSON key for server definitions (not {@code "mcpServers"})</li>
 *   <li>Denies OpenCode's native built-in tools so the model uses agentbridge MCP tools</li>
 *   <li>Checks {@code ~/.config/opencode/opencode.json} for existing MCP registrations</li>
 * </ul>
 */
final class OpenCodeAgentConfig extends ProfileBasedAgentConfig {

    private static final Logger LOG = Logger.getInstance(OpenCodeAgentConfig.class);

    static final String PROFILE_ID = "opencode";
    private static final String AGENT_WORK_DIR = ".agent-work";
    private static final String CONFIG_FILE = "opencode.json";

    /**
     * OpenCode's native built-in tool names. Denied in the generated config so the model
     * uses agentbridge MCP tools instead of OpenCode's own file/search/shell tools.
     */
    private static final List<String> NATIVE_TOOLS = List.of(
        "grep", "glob", "ls", "read", "write", "edit", "patch",
        "bash", "webfetch", "task", "todoread", "todowrite",
        "lsp", "websearch", "codesearch"
    );

    OpenCodeAgentConfig(@NotNull AgentProfile profile,
                        @Nullable ToolRegistry registry,
                        @Nullable Project project) {
        super(profile, registry, project);
    }

    @Override
    protected void configureProcess(@NotNull ProcessBuilder pb,
                                    @Nullable String projectBasePath,
                                    int mcpPort) {
        if (mcpPort <= 0 || projectBasePath == null) return;

        writeConfigFile(projectBasePath, mcpPort);
        String configPath = Path.of(projectBasePath, AGENT_WORK_DIR, PROFILE_ID, CONFIG_FILE).toString();
        pb.environment().put("OPENCODE_CONFIG", configPath);
    }

    @Override
    protected @NotNull List<Path> getAdditionalMcpConfigPaths() {
        String userHome = System.getProperty("user.home", "");
        return List.of(Path.of(userHome, ".config", PROFILE_ID, CONFIG_FILE));
    }

    @Override
    protected @NotNull String getMcpContainerKey() {
        return "mcp";
    }

    @Override
    protected @NotNull List<String> getNativeToolDenyList() {
        return NATIVE_TOOLS;
    }

    /**
     * Writes the OpenCode config file to disk so OpenCode can read it via
     * {@code OPENCODE_CONFIG} env var. Includes MCP server config, tool permissions,
     * and provider model modalities overrides.
     */
    private void writeConfigFile(@NotNull String projectBasePath, int mcpPort) {
        try {
            Path dir = Path.of(projectBasePath, AGENT_WORK_DIR, PROFILE_ID);
            Path configPath = dir.resolve(CONFIG_FILE);

            Files.createDirectories(dir);

            String resolved = resolveMcpTemplate(mcpPort);
            if (resolved == null || resolved.isEmpty()) {
                LOG.warn("Failed to resolve MCP config template for OpenCode (null or empty)");
                return;
            }

            String configWithPermissions = mergePermissionsIntoConfig(resolved);
            String configWithModalities = mergeProviderModalitiesIntoConfig(configWithPermissions);
            String finalConfig = convertMcpServersToObject(configWithModalities);
            String formatted = formatJsonSafely(finalConfig);

            Files.writeString(configPath, formatted, StandardCharsets.UTF_8);
            LOG.info("OpenCode config written to " + configPath + " (length: " + formatted.length() + ")");
        } catch (Exception e) {
            LOG.warn("Failed to write OpenCode config file", e);
        }
    }

    /**
     * Merges provider model modalities overrides into the config JSON.
     *
     * <p><b>Root cause:</b> OpenCode v1.15.0 ships with a bundled {@code models-snapshot.js} that
     * captured {@code qwen3.6-plus-free} before image support was added to models.dev. The v1.15.0
     * snapshot records {@code attachment: false} and {@code modalities.input: ["text"]}, so OpenCode
     * reports "this model does not support image input" on first launch (before the background
     * models.dev refresh overwrites the on-disk cache).
     *
     * <p>In v1.2.27, the model was absent from the snapshot entirely, so OpenCode fetched the live
     * models.dev entry — which already had image support — and images worked.
     *
     * <p>This override injects the correct capabilities directly into the generated
     * {@code opencode.json} via the {@code provider.opencode.models} config path, which OpenCode's
     * {@code ConfigProvider.Model} schema explicitly supports. The override is skipped if the user
     * has already configured modalities for this model in their own config.
     */
    @NotNull
    private static String mergeProviderModalitiesIntoConfig(@NotNull String configJson) {
        try {
            JsonObject root = JsonParser.parseString(configJson).getAsJsonObject();

            JsonObject provider = root.has(PROVIDER_KEY) && root.get(PROVIDER_KEY).isJsonObject()
                ? root.getAsJsonObject(PROVIDER_KEY)
                : new JsonObject();

            JsonObject opencode = provider.has(PROFILE_ID) && provider.get(PROFILE_ID).isJsonObject()
                ? provider.getAsJsonObject(PROFILE_ID)
                : new JsonObject();

            JsonObject models = opencode.has(MODELS_KEY) && opencode.get(MODELS_KEY).isJsonObject()
                ? opencode.getAsJsonObject(MODELS_KEY)
                : new JsonObject();

            // Only inject if the user has not already configured modalities for this model.
            if (!models.has(QWEN_FREE_MODEL_ID)
                || !models.getAsJsonObject(QWEN_FREE_MODEL_ID).has("modalities")) {

                JsonObject qwenModel = models.has(QWEN_FREE_MODEL_ID) && models.get(QWEN_FREE_MODEL_ID).isJsonObject()
                    ? models.getAsJsonObject(QWEN_FREE_MODEL_ID)
                    : new JsonObject();

                qwenModel.addProperty("attachment", true);

                JsonArray inputModalities = new JsonArray();
                inputModalities.add("text");
                inputModalities.add("image");
                JsonArray outputModalities = new JsonArray();
                outputModalities.add("text");
                JsonObject modalities = new JsonObject();
                modalities.add("input", inputModalities);
                modalities.add("output", outputModalities);
                qwenModel.add("modalities", modalities);

                models.add(QWEN_FREE_MODEL_ID, qwenModel);
            }

            opencode.add(MODELS_KEY, models);
            provider.add(PROFILE_ID, opencode);
            root.add(PROVIDER_KEY, provider);

            return new Gson().toJson(root);
        } catch (Exception e) {
            LOG.warn("Failed to merge provider modalities into config", e);
            return configJson;
        }
    }

    private static final String MCP_SERVERS_KEY = "mcpServers";
    private static final String PROVIDER_KEY = "provider";
    private static final String MODELS_KEY = "models";
    private static final String QWEN_FREE_MODEL_ID = "qwen3.6-plus-free";

    /**
     * Converts {@code "mcpServers"} array to {@code "mcp"} object for OpenCode's expected format.
     */
    @NotNull
    private static String convertMcpServersToObject(@NotNull String configJson) {
        try {
            JsonObject root = JsonParser.parseString(configJson).getAsJsonObject();
            if (!root.has(MCP_SERVERS_KEY) || !root.get(MCP_SERVERS_KEY).isJsonArray()) {
                return configJson;
            }
            JsonArray servers = root.getAsJsonArray(MCP_SERVERS_KEY);
            JsonObject mcp = new JsonObject();
            for (JsonElement el : servers) {
                if (!el.isJsonObject()) continue;
                JsonObject s = el.getAsJsonObject();
                String name = s.has("name") ? s.get("name").getAsString() : "agentbridge";
                JsonObject entry = s.deepCopy();
                entry.remove("name");
                mcp.add(name, entry);
            }
            root.remove(MCP_SERVERS_KEY);
            root.add("mcp", mcp);
            return new Gson().toJson(root);
        } catch (Exception e) {
            LOG.warn("Failed to convert mcpServers to mcp object", e);
            return configJson;
        }
    }
}
