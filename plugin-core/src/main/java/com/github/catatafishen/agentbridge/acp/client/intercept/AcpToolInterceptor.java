package com.github.catatafishen.agentbridge.acp.client.intercept;

import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intercepts ACP file system and terminal requests, redirecting them to equivalent
 * MCP tools when a safe 1:1 mapping exists.
 *
 * <p><b>Why this exists:</b> ACP agents (Copilot, Junie, Kiro, OpenCode) execute their
 * built-in tools by sending {@code fs/read_text_file}, {@code fs/write_text_file}, and
 * {@code terminal/create} requests <em>back</em> to the client. Some agents ignore tool
 * exclusion lists (Copilot bug #556) or have no exclusion mechanism at all (Junie).
 * By intercepting these requests at the client, we transparently redirect them to our
 * MCP equivalents — getting editor-buffer reads/writes, undo stack, VCS sync, etc. —
 * regardless of how the agent decides to call the tool.
 *
 * <p>For commands that don't map cleanly, the request falls through to the original
 * handler so the user can still see the command run in a visible IDE terminal.
 */
public final class AcpToolInterceptor {

    private static final Logger LOG = Logger.getInstance(AcpToolInterceptor.class);
    private static final String SYNTHETIC_TERMINAL_PREFIX = "intercept_";

    private final @Nullable Project project;
    private final Map<String, InterceptedTerminal> terminals = new ConcurrentHashMap<>();

    /**
     * Cached tool result + exit code for a synthesized terminal.
     */
    private record InterceptedTerminal(String output, int exitCode) {
    }

    public AcpToolInterceptor(@Nullable Project project) {
        this.project = project;
    }

    // ─── fs/read_text_file ────────────────────────────────────────────────

    /**
     * Tries to intercept {@code fs/read_text_file} by routing it through the
     * {@code read_file} MCP tool. The two are 1:1 in semantics, so this gives
     * the agent editor-buffer reads (with unsaved changes) and proper line-range
     * support for free.
     *
     * @return the synthesized response, or {@code null} when the MCP tool failed
     * and the caller should fall back to the original handler
     */
    public @Nullable JsonObject interceptRead(@NotNull JsonObject params) {
        JsonObject mcpArgs = new JsonObject();
        if (params.has("path")) mcpArgs.add("path", params.get("path"));
        if (params.has("line")) mcpArgs.addProperty("start_line", params.get("line").getAsInt());
        if (params.has("limit")) {
            int start = params.has("line") ? params.get("line").getAsInt() : 1;
            int end = start + params.get("limit").getAsInt() - 1;
            mcpArgs.addProperty("end_line", end);
        }

        String result = callMcp("read_file", mcpArgs);
        if (isMcpError(result)) {
            LOG.warn("read_file MCP redirect failed, falling back to direct VFS read: " + result);
            return null;
        }

        JsonObject response = new JsonObject();
        response.addProperty("content", result);
        return response;
    }

    // ─── fs/write_text_file ───────────────────────────────────────────────

    /**
     * Tries to intercept {@code fs/write_text_file} by routing it through the
     * {@code write_file} MCP tool. This adds undo support, deferred auto-format,
     * and VFS notifications that direct file writes bypass.
     *
     * @return an empty response on success (ACP returns null but the dispatch layer
     * treats an empty {@link JsonObject} the same), or {@code null} when the
     * MCP tool failed and the caller should fall back to the original handler
     */
    public @Nullable JsonObject interceptWrite(@NotNull JsonObject params) {
        JsonObject mcpArgs = new JsonObject();
        if (params.has("path")) mcpArgs.add("path", params.get("path"));
        if (params.has("content")) mcpArgs.add("content", params.get("content"));

        String result = callMcp("write_file", mcpArgs);
        if (isMcpError(result)) {
            LOG.warn("write_file MCP redirect failed, falling back to direct VFS write: " + result);
            return null;
        }
        return new JsonObject();
    }

    // ─── terminal/create ──────────────────────────────────────────────────

    /**
     * Try to redirect a {@code terminal/create} request to an MCP tool.
     *
     * @return a synthetic {@code {terminalId}} response when the command was redirected
     * (the result is buffered and served by {@link #output}/{@link #waitForExit}),
     * or {@code null} when the command should run in a real terminal
     */
    public @Nullable JsonObject tryInterceptTerminalCreate(@NotNull JsonObject params) {
        String command = params.has("command") && params.get("command").isJsonPrimitive()
            ? params.get("command").getAsString() : null;
        if (command == null || command.isBlank()) return null;

        // Build full argv: [command, ...args] then re-join for tokenization safety.
        // Agents pass either {command: "git status"} or {command: "git", args: ["status"]}.
        StringBuilder full = new StringBuilder(command);
        if (params.has("args") && params.get("args").isJsonArray()) {
            for (var el : params.getAsJsonArray("args")) {
                if (el.isJsonPrimitive()) full.append(' ').append(el.getAsString());
            }
        }

        List<String> tokens = ShellCommandSplitter.tokenize(full.toString());
        if (tokens == null || tokens.isEmpty()) {
            // Shell metacharacters present, or unbalanced quotes — too risky to redirect
            return null;
        }

        String redirectResult = tryRedirect(tokens);
        if (redirectResult == null) return null;

        String terminalId = SYNTHETIC_TERMINAL_PREFIX + UUID.randomUUID().toString().substring(0, 12);
        int exitCode = isMcpError(redirectResult) ? 1 : 0;
        terminals.put(terminalId, new InterceptedTerminal(redirectResult, exitCode));
        LOG.info("Intercepted terminal/create: '" + full + "' -> synthetic " + terminalId);

        JsonObject result = new JsonObject();
        result.addProperty("terminalId", terminalId);
        return result;
    }

    /**
     * Resolve {@code argv} to an MCP tool result, or {@code null} if no safe mapping exists.
     * Each redirect must be unambiguous — the tokens before the file/path/refspec arguments
     * must exactly identify a single MCP tool.
     */
    private @Nullable String tryRedirect(@NotNull List<String> argv) {
        String head = argv.get(0).toLowerCase(Locale.ROOT);

        // Strip leading "/usr/bin/" or "./" style prefixes from the binary name
        int slash = head.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < head.length()) head = head.substring(slash + 1);

        return switch (head) {
            case "cat", "head", "tail" -> redirectFileRead(argv, head);
            // Git is well-defined; we only redirect the simplest read-only forms here. Mutating
            // git operations (commit, push, rebase) are deliberately left to the visible terminal
            // so the user sees them and the existing GitCommitTool review-gate behaviour is not
            // bypassed for agent-issued commits.
            case "git" -> redirectGit(argv);
            default -> null;
        };
    }

    private @Nullable String redirectFileRead(@NotNull List<String> argv, @NotNull String head) {
        // Only redirect the simple `cat <file>` form. Any flag (-n, --number, -A, ...) means
        // the agent wants formatting we don't replicate; let it run in the real terminal.
        if (argv.size() != 2) return null;
        String path = argv.get(1);
        if (path.startsWith("-")) return null;

        JsonObject mcpArgs = new JsonObject();
        mcpArgs.addProperty("path", path);
        if ("head".equals(head)) {
            mcpArgs.addProperty("start_line", 1);
            mcpArgs.addProperty("end_line", 10);
        }
        // tail: without a default line count it would behave like cat; let it through to terminal
        if ("tail".equals(head)) return null;
        return callMcp("read_file", mcpArgs);
    }

    private @Nullable String redirectGit(@NotNull List<String> argv) {
        if (argv.size() < 2) return null;
        String sub = argv.get(1).toLowerCase(Locale.ROOT);

        // Read-only operations only — see comment in tryRedirect. Each entry must accept no
        // extra positional args (or the args we forward must be safe for the matching MCP tool).
        return switch (sub) {
            case "status" -> callMcp("git_status", new JsonObject());
            // git log / git diff / git show / git branch / git remote / git tag / git stash list
            // could be added here once we have a clean argument-mapping helper. Holding off in this
            // first pass to keep the surface area small and predictable.
            default -> null;
        };
    }

    // ─── synthetic terminal lifecycle ────────────────────────────────────

    public boolean ownsTerminal(@NotNull String terminalId) {
        return terminals.containsKey(terminalId);
    }

    public @NotNull JsonObject output(@NotNull String terminalId) {
        InterceptedTerminal t = requireTerminal(terminalId);
        JsonObject result = new JsonObject();
        result.addProperty("output", t.output());
        result.addProperty("truncated", false);
        JsonObject exitStatus = new JsonObject();
        exitStatus.addProperty("exitCode", t.exitCode());
        exitStatus.add("signal", null);
        result.add("exitStatus", exitStatus);
        return result;
    }

    public @NotNull JsonObject waitForExit(@NotNull String terminalId) {
        InterceptedTerminal t = requireTerminal(terminalId);
        JsonObject result = new JsonObject();
        result.addProperty("exitCode", t.exitCode());
        result.add("signal", null);
        return result;
    }

    public @NotNull JsonObject kill(@NotNull String terminalId) {
        requireTerminal(terminalId);
        // Synthetic terminals complete synchronously during create; nothing to kill.
        return new JsonObject();
    }

    public @NotNull JsonObject release(@NotNull String terminalId) {
        if (terminals.remove(terminalId) == null) {
            throw new IllegalArgumentException("Unknown terminal: " + terminalId);
        }
        return new JsonObject();
    }

    public void releaseAll() {
        terminals.clear();
    }

    private @NotNull InterceptedTerminal requireTerminal(@NotNull String terminalId) {
        InterceptedTerminal t = terminals.get(terminalId);
        if (t == null) {
            throw new IllegalArgumentException("Unknown terminal: " + terminalId);
        }
        return t;
    }

    // ─── MCP plumbing ────────────────────────────────────────────────────

    private @NotNull String callMcp(@NotNull String toolName, @NotNull JsonObject args) {
        if (project == null) {
            return "Error: AcpToolInterceptor has no Project — MCP redirection unavailable";
        }
        try {
            String result = PsiBridgeService.getInstance(project).callTool(toolName, args);
            return result != null ? result : "";
        } catch (Exception e) {
            LOG.warn("MCP tool '" + toolName + "' threw during ACP interception", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Returns true when an MCP tool result represents a failure. MCP tools signal errors
     * by prefixing the result with {@code "Error"} (see {@code McpProtocolHandler}).
     */
    static boolean isMcpError(@Nullable String result) {
        return result != null && result.startsWith("Error");
    }
}
