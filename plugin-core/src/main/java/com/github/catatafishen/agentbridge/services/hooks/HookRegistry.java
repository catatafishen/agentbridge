package com.github.catatafishen.agentbridge.services.hooks;

import com.github.catatafishen.agentbridge.settings.AgentBridgeStorageSettings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovers and caches {@link ToolHookConfig}s from the project's hooks directory.
 * Scans for {@code <tool-id>.json} files in {@code <storage-dir>/hooks/}.
 *
 * <p>The hooks directory is at {@code <storage-dir>/hooks/} where the storage
 * directory is resolved from {@link AgentBridgeStorageSettings}.
 */
public final class HookRegistry {

    private static final Logger LOG = Logger.getInstance(HookRegistry.class);
    private static final String HOOKS_DIR_NAME = "hooks";
    private static final String JSON_EXT = ".json";

    private final Project project;
    private final ConcurrentHashMap<String, ToolHookConfig> hooksByTool = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    public HookRegistry(@NotNull Project project) {
        this.project = project;
    }

    @SuppressWarnings("java:S1905") // Cast needed: IDE doesn't resolve Project→ComponentManager supertype
    public static HookRegistry getInstance(@NotNull Project project) {
        return ((ComponentManager) project).getService(HookRegistry.class);
    }

    /**
     * Returns the hook config for a tool, or null if no hooks are defined for it.
     */
    public @Nullable ToolHookConfig findConfig(@NotNull String toolId) {
        ensureLoaded();
        return hooksByTool.get(toolId);
    }

    /**
     * Returns the hook entries for a specific tool and trigger, or an empty list.
     */
    public @NotNull List<HookEntryConfig> findEntries(@NotNull String toolId, @NotNull HookTrigger trigger) {
        ToolHookConfig config = findConfig(toolId);
        return config != null ? config.entriesFor(trigger) : List.of();
    }

    /**
     * Returns all loaded hook configs.
     */
    public @NotNull List<ToolHookConfig> getAllConfigs() {
        ensureLoaded();
        return List.copyOf(hooksByTool.values());
    }

    public void reload() {
        synchronized (this) {
            hooksByTool.clear();
            loaded = false;
            doLoad();
            loaded = true;
        }
    }

    private void ensureLoaded() {
        if (loaded) return;
        synchronized (this) {
            if (loaded) return;
            doLoad();
            loaded = true;
        }
    }

    private void doLoad() {
        Path hooksDir = resolveHooksDirectory();
        if (!Files.isDirectory(hooksDir)) return;

        try (DirectoryStream<Path> files = Files.newDirectoryStream(hooksDir,
            path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(JSON_EXT))) {

            for (Path jsonFile : files) {
                loadToolHookFile(jsonFile, hooksDir);
            }
        } catch (IOException e) {
            LOG.warn("Failed to scan hooks directory: " + hooksDir, e);
        }
    }

    private void loadToolHookFile(@NotNull Path jsonFile, @NotNull Path hooksDir) {
        String fileName = jsonFile.getFileName().toString();
        String toolId = fileName.substring(0, fileName.length() - JSON_EXT.length());

        try (Reader reader = Files.newBufferedReader(jsonFile, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            ToolHookConfig config = parseToolConfig(toolId, root, hooksDir);
            hooksByTool.put(toolId, config);
            LOG.info("Loaded hooks for tool '" + toolId + "' from " + jsonFile.getFileName());
        } catch (Exception e) {
            LOG.warn("Failed to load hook config: " + jsonFile, e);
        }
    }

    /**
     * Parses a per-tool JSON hook config. Package-private for testing.
     */
    static @NotNull ToolHookConfig parseToolConfig(@NotNull String toolId,
                                                    @NotNull JsonObject root,
                                                    @NotNull Path hooksDir) {
        Map<HookTrigger, List<HookEntryConfig>> triggers = new HashMap<>();

        for (HookTrigger trigger : HookTrigger.values()) {
            if (root.has(trigger.jsonKey())) {
                JsonArray array = root.getAsJsonArray(trigger.jsonKey());
                List<HookEntryConfig> entries = parseEntryArray(array, trigger);
                if (!entries.isEmpty()) {
                    triggers.put(trigger, List.copyOf(entries));
                }
            }
        }

        return new ToolHookConfig(toolId, Map.copyOf(triggers), hooksDir);
    }

    private static @NotNull List<HookEntryConfig> parseEntryArray(@NotNull JsonArray array,
                                                                   @NotNull HookTrigger trigger) {
        List<HookEntryConfig> entries = new ArrayList<>();
        for (JsonElement elem : array) {
            if (elem.isJsonObject()) {
                entries.add(parseEntry(elem.getAsJsonObject(), trigger));
            }
        }
        return entries;
    }

    private static @NotNull HookEntryConfig parseEntry(@NotNull JsonObject obj,
                                                        @NotNull HookTrigger trigger) {
        String script = obj.get("script").getAsString();
        int timeout = obj.has("timeout") ? obj.get("timeout").getAsInt() : 10;
        boolean async = obj.has("async") && obj.get("async").getAsBoolean();

        boolean failSilently = resolveFailSilently(obj, trigger);

        Map<String, String> env = new HashMap<>();
        if (obj.has("env")) {
            JsonObject envObj = obj.getAsJsonObject("env");
            for (String key : envObj.keySet()) {
                env.put(key, envObj.get(key).getAsString());
            }
        }

        return new HookEntryConfig(script, timeout, failSilently, async, Map.copyOf(env));
    }

    /**
     * Resolves the failSilently behavior from JSON fields.
     * Permission hooks use {@code rejectOnFailure} (default true → failSilently=false).
     * Other hooks use {@code failSilently} (default true).
     */
    private static boolean resolveFailSilently(@NotNull JsonObject obj, @NotNull HookTrigger trigger) {
        if (trigger == HookTrigger.PERMISSION) {
            if (obj.has("rejectOnFailure")) {
                return !obj.get("rejectOnFailure").getAsBoolean();
            }
            return false; // default: rejectOnFailure=true → failSilently=false
        }
        if (obj.has("failSilently")) {
            return obj.get("failSilently").getAsBoolean();
        }
        return true; // default: fail silently for non-permission hooks
    }

    @NotNull
    Path resolveHooksDirectory() {
        Path storageDir = AgentBridgeStorageSettings.getInstance().getProjectStorageDir(project);
        return storageDir.resolve(HOOKS_DIR_NAME);
    }
}
