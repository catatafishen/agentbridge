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
import java.util.EnumMap;
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
 *
 * <p>Hook configs are automatically reloaded when the hooks directory changes.
 * A time-based check (2-second window) prevents redundant rescans within the same
 * tool execution pipeline.
 */
public final class HookRegistry {

    private static final Logger LOG = Logger.getInstance(HookRegistry.class);
    private static final String HOOKS_DIR_NAME = "hooks";
    private static final String JSON_EXT = ".json";
    private static final long RELOAD_INTERVAL_MS = 2000;
    private static final String KEY_PREPEND_STRING = "prependString";
    private static final String KEY_APPEND_STRING = "appendString";

    private final Project project;
    private final ConcurrentHashMap<String, ToolHookConfig> hooksByTool = new ConcurrentHashMap<>();
    private volatile long lastLoadedMs;

    public HookRegistry(@NotNull Project project) {
        this.project = project;
    }

    @SuppressWarnings("java:S1905") // Cast needed: IDE doesn't resolve Project→ComponentManager supertype
    public static HookRegistry getInstance(@NotNull Project project) {
        return ((ComponentManager) project).getService(HookRegistry.class);
    }

    /**
     * Returns the hook config for a tool, or null if no hooks are defined for it.
     * Automatically reloads if the hooks directory has changed since the last scan.
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

    /**
     * Forces an immediate reload of all hook configs from disk.
     */
    public void reload() {
        synchronized (this) {
            hooksByTool.clear();
            doLoad();
            lastLoadedMs = System.currentTimeMillis();
        }
    }

    /**
     * Writes a hook config to disk as a JSON file. Creates the hooks directory if needed.
     * If the config is empty (no triggers, no prepend/append), deletes the file instead.
     * Triggers a reload after writing.
     */
    public void writeConfig(@NotNull ToolHookConfig config) throws IOException {
        Path hooksDir = resolveHooksDirectory();
        Path jsonFile = hooksDir.resolve(config.toolId() + JSON_EXT);

        if (config.isEmpty()) {
            Files.deleteIfExists(jsonFile);
        } else {
            Files.createDirectories(hooksDir);
            String json = new com.google.gson.GsonBuilder()
                .setPrettyPrinting()
                .create()
                .toJson(config.toJson());
            Files.writeString(jsonFile, json, StandardCharsets.UTF_8);
        }
        reload();
    }

    /**
     * Returns the hooks directory path. Used by the settings UI to locate hook files.
     */
    public @NotNull Path getHooksDirectory() {
        return resolveHooksDirectory();
    }

    private void ensureLoaded() {
        long now = System.currentTimeMillis();
        if ((now - lastLoadedMs) < RELOAD_INTERVAL_MS) return;
        synchronized (this) {
            now = System.currentTimeMillis();
            if ((now - lastLoadedMs) < RELOAD_INTERVAL_MS) return;
            hooksByTool.clear();
            doLoad();
            lastLoadedMs = now;
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
        Map<HookTrigger, List<HookEntryConfig>> triggers = new EnumMap<>(HookTrigger.class);

        for (HookTrigger trigger : HookTrigger.values()) {
            if (root.has(trigger.jsonKey())) {
                JsonArray array = root.getAsJsonArray(trigger.jsonKey());
                List<HookEntryConfig> entries = parseEntryArray(array, trigger);
                if (!entries.isEmpty()) {
                    triggers.put(trigger, List.copyOf(entries));
                }
            }
        }

        String prependString = root.has(KEY_PREPEND_STRING)
            ? root.get(KEY_PREPEND_STRING).getAsString() : null;
        String appendString = root.has(KEY_APPEND_STRING)
            ? root.get(KEY_APPEND_STRING).getAsString() : null;

        return new ToolHookConfig(toolId, Map.copyOf(triggers), hooksDir,
            prependString, appendString);
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
    private Path resolveHooksDirectory() {
        Path storageDir = AgentBridgeStorageSettings.getInstance().getProjectStorageDir(project);
        return storageDir.resolve(HOOKS_DIR_NAME);
    }
}
