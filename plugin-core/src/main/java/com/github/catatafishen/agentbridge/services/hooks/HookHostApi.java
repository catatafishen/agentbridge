package com.github.catatafishen.agentbridge.services.hooks;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

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
 */
public final class HookHostApi {

    private static final Logger LOG = Logger.getInstance(HookHostApi.class);

    private final Project project;
    private final HookPayload payload;
    private @Nullable String resultJson;

    HookHostApi(@NotNull Project project, @NotNull HookPayload payload) {
        this.project = project;
        this.payload = payload;
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
        Path p = Paths.get(path);
        Path resolved;
        if (p.isAbsolute()) {
            resolved = p.normalize();
        } else {
            String base = project.getBasePath();
            resolved = base != null ? Paths.get(base).resolve(p).normalize() : p.normalize();
        }
        return resolved.toString().replace('\\', '/');
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
     * Rewrites the {@code command} argument before the tool runs (pre hooks).
     */
    public void setCommand(@NotNull String command) {
        JsonObject args = new JsonObject();
        args.addProperty("command", command);
        JsonObject o = new JsonObject();
        o.add("arguments", args);
        resultJson = o.toString();
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
}
