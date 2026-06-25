package com.github.catatafishen.agentbridge.psi.tools;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Abstract base class for all individual tool implementations.
 * Each concrete tool subclass defines its identity, behavior flags,
 * and execution logic in a single self-contained class.
 *
 * @see ToolDefinition
 */
public abstract class Tool implements ToolDefinition {

    protected final Project project;
    protected final PlatformFacade platform;
    protected String argumentsHash;

    protected Tool(Project project) {
        this(project, PlatformFacade.application());
    }

    /**
     * Constructor for unit tests — accessible from subclasses in any package.
     *
     * <p>Use {@code DirectPlatformFacade} (in the test source tree) to run threading
     * operations synchronously without requiring a running IntelliJ Platform:
     * <pre>
     *     MyTool tool = new MyTool(project, new DirectPlatformFacade());
     * </pre>
     */
    protected Tool(Project project, PlatformFacade platform) {
        this.project = project;
        this.platform = platform;
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args, @Nullable String argumentsHash) throws Exception {
        this.argumentsHash = argumentsHash;
        return execute(args);
    }

    // category() is inherited from ToolDefinition — subclasses must implement it

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema();
    }

    @Override
    public boolean hasExecutionHandler() {
        return true;
    }

    // ── Shared utilities ─────────────────────────────────────

    // ── Schema builder helpers ─────────────────────────────────

    protected static final String TYPE_STRING = "string";
    protected static final String TYPE_BOOLEAN = "boolean";
    protected static final String TYPE_INTEGER = "integer";
    protected static final String TYPE_ARRAY = "array";
    protected static final String TYPE_OBJECT = "object";

    private static final String KEY_TYPE = "type";
    private static final String KEY_PROPERTIES = "properties";
    private static final String KEY_REQUIRED = "required";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_DEFAULT = "default";
    private static final String KEY_ITEMS = "items";

    /**
     * Type-safe parameter definition for MCP tool schemas.
     * Use factory methods to clearly distinguish required from optional parameters.
     */
    protected record Param(String name, String type, String description,
                           @Nullable Object defaultValue, boolean required) {

        public static Param required(String name, String type, String description) {
            return new Param(name, type, description, null, true);
        }

        public static Param optional(String name, String type, String description) {
            return new Param(name, type, description, null, false);
        }

        public static Param optional(String name, String type, String description, Object defaultValue) {
            return new Param(name, type, description, defaultValue, false);
        }
    }

    protected static com.google.gson.JsonObject schema(Param... params) {
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.addProperty(KEY_TYPE, TYPE_OBJECT);
        com.google.gson.JsonObject props = new com.google.gson.JsonObject();
        com.google.gson.JsonArray req = new com.google.gson.JsonArray();
        for (Param p : params) {
            com.google.gson.JsonObject prop = new com.google.gson.JsonObject();
            prop.addProperty(KEY_TYPE, p.type());
            prop.addProperty(KEY_DESCRIPTION, p.description());
            if (p.defaultValue() != null) {
                switch (p.defaultValue()) {
                    case String s -> prop.addProperty(KEY_DEFAULT, s);
                    case Number n -> prop.addProperty(KEY_DEFAULT, n);
                    case Boolean b -> prop.addProperty(KEY_DEFAULT, b);
                    default -> { /* unsupported default value type — skip */ }
                }
            }
            if (TYPE_ARRAY.equals(p.type())) {
                com.google.gson.JsonObject items = new com.google.gson.JsonObject();
                items.addProperty(KEY_TYPE, TYPE_STRING);
                prop.add(KEY_ITEMS, items);
            }
            props.add(p.name(), prop);
            if (p.required()) {
                req.add(p.name());
            }
        }
        root.add(KEY_PROPERTIES, props);
        root.add(KEY_REQUIRED, req);
        return root;
    }

    protected static void addArrayItems(com.google.gson.JsonObject schema, String propName) {
        com.google.gson.JsonObject prop = schema.getAsJsonObject(KEY_PROPERTIES).getAsJsonObject(propName);
        com.google.gson.JsonObject items = new com.google.gson.JsonObject();
        items.addProperty(KEY_TYPE, TYPE_STRING);
        prop.add(KEY_ITEMS, items);
    }

    /**
     * Replaces the auto-generated {@code items: {type: string}} for an array property with a proper
     * object schema so that MCP clients can validate and autocomplete the object's fields.
     *
     * <p>Use when an array parameter holds structured objects (not plain strings).
     * The item parameters are treated as object properties; their {@code required} flag is ignored
     * (object-level required arrays inside array items are not widely supported by clients).
     */
    protected static void addObjectArrayItems(com.google.gson.JsonObject schema, String propName, Param... itemParams) {
        com.google.gson.JsonObject prop = schema.getAsJsonObject(KEY_PROPERTIES).getAsJsonObject(propName);
        com.google.gson.JsonObject items = new com.google.gson.JsonObject();
        items.addProperty(KEY_TYPE, TYPE_OBJECT);
        com.google.gson.JsonObject itemProps = new com.google.gson.JsonObject();
        for (Param p : itemParams) {
            com.google.gson.JsonObject pDef = new com.google.gson.JsonObject();
            pDef.addProperty(KEY_TYPE, p.type());
            pDef.addProperty(KEY_DESCRIPTION, p.description());
            itemProps.add(p.name(), pDef);
        }
        items.add(KEY_PROPERTIES, itemProps);
        prop.add(KEY_ITEMS, items);
    }

    protected static void addDictProperty(com.google.gson.JsonObject schema, String name, String description) {
        com.google.gson.JsonObject prop = new com.google.gson.JsonObject();
        prop.addProperty(KEY_TYPE, TYPE_OBJECT);
        prop.addProperty(KEY_DESCRIPTION, description);
        prop.add(KEY_PROPERTIES, new com.google.gson.JsonObject());
        com.google.gson.JsonObject additionalProps = new com.google.gson.JsonObject();
        additionalProps.addProperty(KEY_TYPE, TYPE_STRING);
        prop.add("additionalProperties", additionalProps);
        schema.getAsJsonObject(KEY_PROPERTIES).add(name, prop);
    }

    /**
     * Reads the file path from args. Checks "path" first, then "file" as a silent backward-compat alias.
     * Returns null if neither key is present or both are null.
     */
    @Nullable
    protected static String readPathParam(@NotNull JsonObject args) {
        if (args.has("path") && !args.get("path").isJsonNull())
            return args.get("path").getAsString();
        if (args.has("file") && !args.get("file").isJsonNull())
            return args.get("file").getAsString();
        return null;
    }

    protected VirtualFile resolveVirtualFile(String path) {
        return ToolUtils.resolveVirtualFile(project, path);
    }

    /**
     * Resolves a VirtualFile by path, falling back to a synchronous VFS refresh when
     * {@code findFileByPath} returns null.
     * This handles the case where IntelliJ's VFS cache is stale (e.g. a file was just
     * created by another tool and the file-watcher event hasn't fired yet).
     * <p>
     * Must be called from a background thread (not the EDT) and outside any ReadAction,
     * because {@link com.intellij.openapi.vfs.LocalFileSystem#refreshAndFindFileByPath} emits VFS events that require a write lock.
     */
    protected VirtualFile refreshAndFindVirtualFile(String path) {
        return ToolUtils.refreshAndFindVirtualFile(project, path);
    }

    protected String relativize(String basePath, String filePath) {
        return ToolUtils.relativize(basePath, filePath);
    }

    protected record ProcessResult(int exitCode, String output, boolean timedOut) {
    }

    @SuppressWarnings("java:S112") // generic exception caught at JSON-RPC dispatch level
    protected ProcessResult executeInRunPanel(
        com.intellij.execution.configurations.GeneralCommandLine cmd,
        String title, int timeoutSec) throws Exception {
        RunPanelExecutor.RunResult result = RunPanelExecutor.execute(project, cmd, title, timeoutSec);
        return new ProcessResult(result.exitCode(), result.output(), result.timedOut());
    }

}
