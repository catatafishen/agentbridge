package com.github.catatafishen.agentbridge.psi.tools;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.services.AgentTabTracker;
import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.google.gson.JsonObject;
import com.intellij.execution.RunContentExecutor;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    private static final String KEY_TYPE = "type";
    private static final String KEY_PROPERTIES = "properties";
    private static final String KEY_REQUIRED = "required";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_DEFAULT = "default";

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
        root.addProperty(KEY_TYPE, "object");
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
                prop.add("items", items);
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
        prop.add("items", items);
    }

    protected static void addDictProperty(com.google.gson.JsonObject schema, String name, String description) {
        com.google.gson.JsonObject prop = new com.google.gson.JsonObject();
        prop.addProperty(KEY_TYPE, "object");
        prop.addProperty(KEY_DESCRIPTION, description);
        prop.add(KEY_PROPERTIES, new com.google.gson.JsonObject());
        com.google.gson.JsonObject additionalProps = new com.google.gson.JsonObject();
        additionalProps.addProperty(KEY_TYPE, TYPE_STRING);
        prop.add("additionalProperties", additionalProps);
        schema.getAsJsonObject(KEY_PROPERTIES).add(name, prop);
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
        CompletableFuture<Integer> exitFuture = new CompletableFuture<>();
        StringBuilder output = new StringBuilder();

        Process process = cmd.createProcess();
        OSProcessHandler processHandler = new OSProcessHandler(process, cmd.getCommandLineString());
        processHandler.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                output.append(event.getText());
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                exitFuture.complete(event.getExitCode());
            }
        });

        EdtUtil.invokeLater(() -> {
            try {
                // Evaluate chat-active state here on the EDT, not before invokeLater, to avoid
                // a stale capture: RunContentExecutor.run() is the actual UI operation,
                // and the user's focus may have changed between the check and this point.
                //
                // RunContentExecutor exposes TWO independent flags, both defaulting to true:
                //   - withActivateToolWindow → setActivateToolWindowWhenAdded (window activation)
                //   - withFocusToolWindow    → setAutoFocusContent           (tab content focus)
                // Even with activateToolWindow=false, setAutoFocusContent(true) still steals
                // keyboard focus into the new console tab when it is added to an already-visible
                // Run window. Both must be gated together to keep focus on the chat prompt.
                boolean chatActive = PsiBridgeService.isChatToolWindowActive(project);
                new RunContentExecutor(project, processHandler)
                    .withTitle(title)
                    .withActivateToolWindow(!chatActive)
                    .withFocusToolWindow(!chatActive)
                    .run();
            } catch (Exception e) {
                // RunContentExecutor.run() may have already called startNotify() before
                // throwing (e.g. if the Run panel fails to register the content descriptor).
                // Only call startNotify() here if the process was not yet started, so the
                // process output listeners can still receive events and the exit future completes.
                if (!processHandler.isStartNotified()) {
                    processHandler.startNotify();
                }
            }
        });

        AgentTabTracker.getInstance(project).trackTab("Run", title);

        try {
            int exitCode = exitFuture.get(timeoutSec, TimeUnit.SECONDS);
            return new ProcessResult(exitCode, output.toString(), false);
        } catch (TimeoutException e) {
            processHandler.destroyProcess();
            return new ProcessResult(-1, output.toString(), true);
        }
    }

}
