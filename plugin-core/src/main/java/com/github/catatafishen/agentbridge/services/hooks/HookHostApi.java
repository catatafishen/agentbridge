package com.github.catatafishen.agentbridge.services.hooks;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.services.McpHttpServer;
import com.github.catatafishen.agentbridge.services.ProcessStreamUtils;
import com.github.catatafishen.agentbridge.settings.AgentBridgeStorageSettings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Host object exposed to hook {@code .js} scripts as the global {@code Hook}.
 *
 * <p>Provides read access to the tool-call payload and the IntelliJ project model, plus methods
 * to record the hook's decision (deny / append / error / modified arguments). It replaces the
 * {@code _lib.sh} / {@code _lib.ps1} shell libraries and the {@code /hooks/query} HTTP bridge:
 * source-root classification is resolved directly from the in-process project model, so scripts
 * never need to parse environment variables or make HTTP calls.
 *
 * <p>Every {@code public} method is callable from JavaScript. Only strings, booleans and
 * {@code void} cross the boundary — no IntelliJ objects are ever handed to the sandboxed script.
 * The script records at most one outcome; the last recording call wins.
 *
 * <p><b>Capabilities.</b> Filesystem ({@code readFile}, {@code writeFile}, {@code deletePath},
 * {@code listDir}, {@code exists}, {@code isDirectory}) and subprocess ({@code exec}) access is
 * gated by the hook's declared {@link HookCapability} set. A script that calls one of these
 * without the matching capability in its JSON config gets a clear error instead of silent access.
 */
public final class HookHostApi {

    private static final Logger LOG = Logger.getInstance(HookHostApi.class);
    private static final int MAX_PROCESS_OUTPUT_CHARS = 16_000;

    private final Project project;
    private final HookPayload payload;
    private final Set<HookCapability> capabilities;
    private final int timeoutSeconds;
    private @Nullable JsonObject pendingArguments;
    private @Nullable String resultJson;

    HookHostApi(@NotNull Project project,
                @NotNull HookPayload payload,
                @NotNull Set<HookCapability> capabilities,
                int timeoutSeconds) {
        this.project = project;
        this.payload = payload;
        this.capabilities = capabilities;
        this.timeoutSeconds = timeoutSeconds;
    }

    // ------------------------------------------------------------------
    // Payload accessors
    // ------------------------------------------------------------------

    /**
     * Returns a string argument from the tool call, or {@code null} if absent or non-primitive.
     */
    public @Nullable String arg(@NotNull String name) {
        JsonObject args = payload.arguments();
        if (!args.has(name)) return null;
        var el = args.get(name);
        return el.isJsonPrimitive() ? el.getAsString() : null;
    }

    /**
     * The MCP tool name (e.g. {@code "run_command"}).
     */
    public @NotNull String tool() {
        return payload.toolName();
    }

    /**
     * The tool output (present for success/failure hooks), or {@code null} for pre-execution hooks.
     */
    public @Nullable String output() {
        return payload.output();
    }

    /**
     * Whether the tool reported an error (meaningful for success/failure hooks).
     */
    public boolean isError() {
        return payload.error();
    }

    /**
     * Absolute path of the project base directory (forward-slash separated), or empty if unknown.
     */
    public @NotNull String projectDir() {
        String base = project.getBasePath();
        return base != null ? base : "";
    }

    /**
     * The display name of the agent connected over MCP (e.g. {@code "Copilot"}), or {@code null}
     * when no agent is connected. Lets identity hooks attribute commits/PRs to the live agent
     * instead of a hardcoded name.
     */
    public @Nullable String agentName() {
        try {
            McpHttpServer server = McpHttpServer.getInstance(project);
            return server != null ? server.getConnectedAgentName() : null;
        } catch (Exception e) {
            LOG.warn("Failed to resolve connected agent name for hook", e);
            return null;
        }
    }

    /**
     * Returns the value of an environment variable visible to the IDE process, or {@code null}.
     * Useful for hooks that read user-provided secrets (e.g. {@code AGENTBRIDGE_BOT_TOKEN}).
     */
    public @Nullable String env(@NotNull String name) {
        return System.getenv(name);
    }

    /**
     * The current user's home directory (forward-slash separated), or empty if unknown.
     */
    public @NotNull String homeDir() {
        String home = System.getProperty("user.home");
        return home != null ? home.replace('\\', '/') : "";
    }

    /**
     * The hooks directory for this project (forward-slash separated), or empty if unknown. Lets a
     * hook locate sibling scripts it shells out to (e.g. a token-minting helper).
     */
    public @NotNull String hooksDir() {
        try {
            return AgentBridgeStorageSettings.getInstance()
                .getProjectStorageDir(project)
                .resolve("hooks")
                .toString()
                .replace('\\', '/');
        } catch (Exception e) {
            LOG.warn("Failed to resolve hooks directory for hook", e);
            return "";
        }
    }

    // ------------------------------------------------------------------
    // Source-root awareness (resolved from the IntelliJ project model)
    // ------------------------------------------------------------------

    /**
     * Classifies a path using the IntelliJ project model. Returns one of
     * {@code "sources"}, {@code "test_sources"}, {@code "resources"}, {@code "test_resources"},
     * {@code "generated_sources"}, {@code "generated_test_sources"}, {@code "excluded"},
     * {@code "content"}, or {@code ""} when the path is outside all content roots or not found.
     * Relative paths are resolved against the project directory.
     */
    public @NotNull String classify(@NotNull String path) {
        String abs = toAbsolute(path);
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(abs);
        if (vf == null) return "";
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            ProjectFileIndex index = ProjectFileIndex.getInstance(project);
            if (index.isExcluded(vf)) return "excluded";
            String sourceClass = PlatformApiCompat.classifyFileSourceRoot(index, vf);
            if (!sourceClass.isEmpty()) return sourceClass;
            if (index.getContentRootForFile(vf) != null) return "content";
            return "";
        });
    }

    /**
     * Returns true if {@code path} is inside a production or test <em>source</em> root.
     *
     * <p>Unlike {@link #classify}, this also matches files that do not yet exist (e.g. a shell
     * redirect target like {@code > src/New.java}) by comparing against the source-root
     * directories themselves, not just the VFS classification of an existing file.
     */
    public boolean isSourceOrTest(@NotNull String path) {
        String abs = toAbsolute(path);
        Map<String, List<String>> roots = ApplicationManager.getApplication().runReadAction(
            (Computable<Map<String, List<String>>>) () -> PlatformApiCompat.collectSourceRootsByClassification(project));
        return underAny(abs, roots.get("sources"))
            || underAny(abs, roots.get("test_sources"))
            || underAny(abs, roots.get("generated_sources"))
            || underAny(abs, roots.get("generated_test_sources"));
    }

    private static boolean underAny(@NotNull String abs, @Nullable List<String> roots) {
        if (roots == null) return false;
        for (String root : roots) {
            String normalized = root.replace('\\', '/');
            if (abs.equals(normalized) || abs.startsWith(normalized + "/")) return true;
        }
        return false;
    }

    private @NotNull String toAbsolute(@NotNull String path) {
        return resolveFsPath(path).toString().replace('\\', '/');
    }

    // ------------------------------------------------------------------
    // Filesystem capability ("filesystem")
    // ------------------------------------------------------------------

    /**
     * Reads a UTF-8 file and returns its content, or {@code null} if it does not exist.
     * Requires the {@code filesystem} capability.
     */
    public @Nullable String readFile(@NotNull String path) {
        require(HookCapability.FILESYSTEM, "readFile");
        Path p = resolveFsPath(path);
        try {
            return Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            throw new HookApiException("Hook.readFile failed for '" + path + "': " + e.getMessage());
        }
    }

    /**
     * Writes (creating parent directories as needed) UTF-8 {@code content} to {@code path}.
     * Requires the {@code filesystem} capability.
     */
    public void writeFile(@NotNull String path, @NotNull String content) {
        require(HookCapability.FILESYSTEM, "writeFile");
        Path p = resolveFsPath(path);
        try {
            Path parent = p.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(p, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new HookApiException("Hook.writeFile failed for '" + path + "': " + e.getMessage());
        }
    }

    /**
     * Recursively deletes {@code path}. Returns true if nothing remains afterwards.
     * Requires the {@code filesystem} capability.
     */
    public boolean deletePath(@NotNull String path) {
        require(HookCapability.FILESYSTEM, "deletePath");
        Path p = resolveFsPath(path);
        if (!Files.exists(p)) return false;
        try (Stream<Path> walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(child -> {
                try {
                    Files.deleteIfExists(child);
                } catch (IOException e) {
                    LOG.warn("Hook.deletePath could not delete " + child + ": " + e.getMessage());
                }
            });
        } catch (IOException e) {
            throw new HookApiException("Hook.deletePath failed for '" + path + "': " + e.getMessage());
        }
        return !Files.exists(p);
    }

    /**
     * Returns true if {@code path} exists. Requires the {@code filesystem} capability.
     */
    public boolean exists(@NotNull String path) {
        require(HookCapability.FILESYSTEM, "exists");
        return Files.exists(resolveFsPath(path));
    }

    /**
     * Returns true if {@code path} is a directory. Requires the {@code filesystem} capability.
     */
    public boolean isDirectory(@NotNull String path) {
        require(HookCapability.FILESYSTEM, "isDirectory");
        return Files.isDirectory(resolveFsPath(path));
    }

    /**
     * Lists the immediate child names of a directory as a JSON array string (empty array if the
     * path is not a directory). Requires the {@code filesystem} capability.
     */
    public @NotNull String listDir(@NotNull String path) {
        require(HookCapability.FILESYSTEM, "listDir");
        Path p = resolveFsPath(path);
        JsonArray arr = new JsonArray();
        if (Files.isDirectory(p)) {
            try (Stream<Path> children = Files.list(p)) {
                children.forEach(child -> arr.add(child.getFileName().toString()));
            } catch (IOException e) {
                throw new HookApiException("Hook.listDir failed for '" + path + "': " + e.getMessage());
            }
        }
        return arr.toString();
    }

    // ------------------------------------------------------------------
    // Subprocess capability ("subprocess")
    // ------------------------------------------------------------------

    /**
     * Runs an external command and returns a JSON string {@code {"exitCode":N,"stdout":"...",
     * "stderr":"..."}}. The command is passed as a JSON array of strings (program + arguments),
     * e.g. {@code Hook.exec(JSON.stringify(["gh","pr","view","main"]))}. Runs in the project
     * directory and is killed after the hook's configured timeout. Requires the {@code subprocess}
     * capability.
     */
    public @NotNull String exec(@NotNull String commandJsonArray) {
        require(HookCapability.SUBPROCESS, "exec");
        List<String> command = parseStringArray(commandJsonArray);
        if (command.isEmpty()) {
            throw new HookApiException("Hook.exec requires a non-empty JSON array of [program, ...args]");
        }
        try {
            GeneralCommandLine cmd = new GeneralCommandLine(command);
            String base = project.getBasePath();
            if (base != null) cmd.setWorkDirectory(base);

            Process process = cmd.createProcess();
            CompletableFuture<String> out = ProcessStreamUtils.readAsync(process.getInputStream(), MAX_PROCESS_OUTPUT_CHARS);
            CompletableFuture<String> err = ProcessStreamUtils.readAsync(process.getErrorStream(), MAX_PROCESS_OUTPUT_CHARS);

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new HookApiException("Hook.exec timed out after " + timeoutSeconds + "s: " + command.getFirst());
            }
            JsonObject result = new JsonObject();
            result.addProperty("exitCode", process.exitValue());
            result.addProperty("stdout", ProcessStreamUtils.await(out, "stdout"));
            result.addProperty("stderr", ProcessStreamUtils.await(err, "stderr"));
            return result.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HookApiException("Hook.exec interrupted: " + e.getMessage());
        } catch (com.intellij.execution.ExecutionException | IOException e) {
            throw new HookApiException("Hook.exec failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Result recording
    // ------------------------------------------------------------------

    /**
     * Denies the tool call (permission hooks). The reason is shown to the agent.
     */
    public void deny(@NotNull String reason) {
        JsonObject o = new JsonObject();
        o.addProperty("decision", "deny");
        o.addProperty("reason", reason);
        resultJson = o.toString();
    }

    /**
     * Appends text to the tool output (success/failure hooks).
     */
    public void append(@NotNull String text) {
        JsonObject o = new JsonObject();
        o.addProperty("append", text);
        resultJson = o.toString();
    }

    /**
     * Blocks tool execution with an error message (pre hooks).
     */
    public void error(@NotNull String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        resultJson = o.toString();
    }

    /**
     * Rewrites a single tool argument before the tool runs (pre hooks). Multiple calls accumulate
     * into one modified-arguments result, so a hook can override several arguments in sequence.
     */
    public void setArgument(@NotNull String name, @NotNull String value) {
        if (pendingArguments == null) pendingArguments = new JsonObject();
        pendingArguments.addProperty(name, value);
        JsonObject o = new JsonObject();
        o.add("arguments", pendingArguments);
        resultJson = o.toString();
    }

    /**
     * Rewrites the {@code command} argument before the tool runs (pre hooks). Shorthand for
     * {@code setArgument("command", command)}.
     */
    public void setCommand(@NotNull String command) {
        setArgument("command", command);
    }

    /**
     * Writes a diagnostic line to the IDE log (prefixed with {@code [hook.js]}).
     */
    public void log(@NotNull String message) {
        LOG.info("[hook.js] " + message);
    }

    /**
     * The accumulated stdout-equivalent JSON the engine returns to {@code HookExecutor.parseResult},
     * or {@code null} if the script recorded no decision.
     */
    @Nullable
    String resultJson() {
        return resultJson;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private void require(@NotNull HookCapability capability, @NotNull String method) {
        if (!capabilities.contains(capability)) {
            throw new HookApiException("Hook." + method + " requires the '" + capability.jsonValue()
                + "' capability — add \"capabilities\": [\"" + capability.jsonValue()
                + "\"] to this hook's JSON config entry.");
        }
    }

    private @NotNull List<String> parseStringArray(@NotNull String json) {
        List<String> list = new ArrayList<>();
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (parsed.isJsonArray()) {
                for (JsonElement el : parsed.getAsJsonArray()) {
                    if (el.isJsonPrimitive()) list.add(el.getAsString());
                }
            }
        } catch (RuntimeException e) {
            throw new HookApiException("Hook.exec expected a JSON array of strings: " + e.getMessage());
        }
        return list;
    }

    private @NotNull Path resolveFsPath(@NotNull String path) {
        Path p = Paths.get(path);
        if (p.isAbsolute()) return p.normalize();
        String base = project.getBasePath();
        return base != null ? Paths.get(base).resolve(p).normalize() : p.normalize();
    }

    /**
     * Unchecked exception thrown for capability denials and host-side I/O failures; surfaces to the
     * script as a Rhino error and is reported by {@link JsHookEngine} with this message.
     */
    static final class HookApiException extends RuntimeException {
        HookApiException(@NotNull String message) {
            super(message);
        }
    }
}
