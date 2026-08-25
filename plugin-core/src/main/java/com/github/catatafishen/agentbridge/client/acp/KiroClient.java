package com.github.catatafishen.agentbridge.client.acp;

import com.github.catatafishen.agentbridge.acp.protocol.NewSessionResponse;
import com.github.catatafishen.agentbridge.client.AbstractClient;
import com.github.catatafishen.agentbridge.model.ContentBlock;
import com.github.catatafishen.agentbridge.model.PromptResponse;
import com.github.catatafishen.agentbridge.model.SessionUpdate;
import com.github.catatafishen.agentbridge.services.AgentProfile;
import com.github.catatafishen.agentbridge.services.AgentProfileManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.SystemProperties;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class KiroClient extends AcpClient {

    private static final Logger LOG = Logger.getInstance(KiroClient.class);
    private static final String KEY_RAW_INPUT = "rawInput";
    private static final String KEY_AGENTBRIDGE = "@agentbridge/";
    private static final String KEY_STATUS = "status";

    /**
     * Matches ANSI escape sequences (e.g. {@code \033[31m}, {@code \033[0m}).
     */
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\x1b\\[[\\d;]*[a-zA-Z]");

    /**
     * Rolling buffer of the last few stderr lines for crash diagnostics.
     */
    private final java.util.Deque<String> recentStderr = new java.util.ArrayDeque<>();
    private static final int STDERR_BUFFER_SIZE = 30;

    /**
     * The first stderr line that looks like a Rust panic header, captured immediately on arrival.
     * With RUST_BACKTRACE=1, the backtrace can be 50+ lines long, evicting the panic header from
     * {@link #recentStderr} before {@link #tryRecoverPromptException} runs. Storing it eagerly
     * ensures the actual crash reason is always surfaced in the UI.
     */
    private volatile @org.jetbrains.annotations.Nullable String capturedPanicLine = null;

    public KiroClient(Project project) {
        super(project);
    }

    @Override
    protected void registerHandlers() {
        // Clear crash state from any previous process lifecycle so stale panic lines
        // don't leak into error messages after a restart.
        capturedPanicLine = null;
        synchronized (recentStderr) {
            recentStderr.clear();
        }

        // Register combined handler for both standard and Kiro-specific notifications
        transport.onNotification(notification -> {
            String method = notification.method();
            if ("session/update".equals(method)) {
                // Delegate to parent's session update handler
                handleSessionUpdate(notification.params());
            } else if (method.startsWith("_kiro.dev/") || method.equals("_session/terminate")) {
                handleKiroNotification(method, notification.params());
            }
        });

        // Register request and stderr handlers from parent
        transport.onRequest(this::handleAgentRequest);
        transport.onStderr(line -> {
            LOG.warn("[" + agentId() + " stderr] " + line);
            synchronized (recentStderr) {
                recentStderr.addLast(line);
                if (recentStderr.size() > STDERR_BUFFER_SIZE) recentStderr.removeFirst();
            }
            // Capture the first panic line immediately for tryRecoverPromptException.
            // With RUST_BACKTRACE=1, the backtrace can exceed the rolling buffer size,
            // evicting the panic header before tryRecoverPromptException is called.
            if (capturedPanicLine == null && isPanicLine(line)) {
                capturedPanicLine = stripAnsi(line.trim());
            }
            // When Kiro's agent-loop thread panics, the Rust panic hook prints the message to
            // stderr but the main process thread stays alive — stdout remains open, so readLoop
            // never gets EOF and pending futures wait until the full inactivity timeout (minutes).
            // Force-kill the process as soon as we detect a panic so readLoop gets EOF immediately
            // and the error surfaces in the UI within ~500ms instead of after the timeout.
            // Match any Rust thread panic, not just threads named "agent".
            if (isPanicLine(line)) {
                LOG.warn("Kiro panic detected — force-killing process to unblock pending futures");
                destroyProcess();
            }
        });
    }

    /**
     * Handles Kiro v3's {@code _kiro/auth/getAccessToken} reverse callback by reading the
     * OIDC access token from the Kiro CLI's local SQLite database and returning it.
     * <p>
     * In v3, the harness no longer holds its own auth state — instead it calls back to the
     * host (IDE extension or, in our case, this plugin) to obtain a fresh token whenever
     * the underlying API credentials need renewal. This mirrors how the Kiro VS Code extension
     * works: the extension holds the OIDC session and hands tokens to the CLI on demand.
     * <p>
     * We read the token from the same SQLite DB the CLI itself populated during {@code kiro login}
     * ({@code ~/Library/Application Support/kiro-cli/data.sqlite3}, table {@code auth_kv},
     * key {@code kirocli:odic:token}). We return {@code accessToken} and {@code expiresAt}, plus the
     * {@code profileArn} of the selected Q Developer profile (from the {@code state} table, key
     * {@code api.codewhisperer.profile}). The harness forwards {@code profileArn} to the backend,
     * which rejects requests without it ({@code "profileArn is required for this request"}).
     * <p>
     * All other requests are delegated to the parent {@link AcpClient#handleAgentRequest}.
     */
    @Override
    protected void handleAgentRequest(com.google.gson.JsonElement id,
                                      com.github.catatafishen.agentbridge.client.acp.transport.JsonRpcTransport.IncomingRequest request) {
        switch (request.method()) {
            case "_kiro/auth/getAccessToken" -> handleGetAccessToken(id);
            case "_kiro/terminal/shell_type" -> handleShellType(id);
            default -> super.handleAgentRequest(id, request);
        }
    }

    /**
     * Handles Kiro v3's {@code _kiro/terminal/shell_type} reverse callback.
     * Returns the name of the user's default shell (e.g. {@code "zsh"}, {@code "bash"}).
     * Reads {@code $SHELL} env var and returns its basename; falls back to {@code "cmd"}
     * on Windows and {@code "bash"} elsewhere.
     */
    private void handleShellType(com.google.gson.JsonElement id) {
        String shellType = resolveShellType();
        JsonObject result = new JsonObject();
        result.addProperty("shellType", shellType);
        transport.sendResponse(id, result);
        LOG.debug("Kiro v3: _kiro/terminal/shell_type — returned " + shellType);
    }

    static String resolveShellType() {
        String shell = System.getenv("SHELL");
        if (shell != null && !shell.isBlank()) {
            // /bin/zsh → "zsh", /usr/bin/fish → "fish", etc.
            return java.nio.file.Path.of(shell).getFileName().toString();
        }
        // Windows: SHELL is not set; COMSPEC points to cmd or powershell
        String comspec = System.getenv("COMSPEC");
        if (comspec != null && !comspec.isBlank()) {
            String name = java.nio.file.Path.of(comspec).getFileName().toString().toLowerCase();
            if (name.endsWith(".exe")) name = name.substring(0, name.length() - 4);
            return name; // "cmd" or "powershell"
        }
        return com.intellij.openapi.util.SystemInfo.isWindows ? "cmd" : "bash";
    }

    private void handleGetAccessToken(com.google.gson.JsonElement id) {
        try {
            KiroTokenRecord token = readKiroToken();
            if (token == null) {
                LOG.warn("Kiro v3: _kiro/auth/getAccessToken — no token found in local DB; " +
                    "run 'kiro login' to authenticate");
                transport.sendError(id, -32000, "No Kiro access token found — run 'kiro login'");
                return;
            }
            JsonObject result = new JsonObject();
            result.addProperty("accessToken", token.accessToken());
            result.addProperty("expiresAt", token.expiresAt());
            if (token.profileArn() != null && !token.profileArn().isBlank()) {
                result.addProperty("profileArn", token.profileArn());
            } else {
                LOG.warn("Kiro v3: _kiro/auth/getAccessToken — no profileArn found in local DB; " +
                    "backend requests requiring a Q Developer profile will fail with " +
                    "'profileArn is required'. Run 'kiro login' to (re)select a profile.");
            }
            transport.sendResponse(id, result);
            LOG.debug("Kiro v3: _kiro/auth/getAccessToken — returned token (expires " + token.expiresAt()
                + ", profileArn " + (token.profileArn() != null ? "present" : "absent") + ")");
        } catch (Exception e) {
            LOG.warn("Kiro v3: _kiro/auth/getAccessToken — failed to read token: " + e.getMessage(), e);
            transport.sendError(id, -32000, "Auth refresh callback failed: " + e.getMessage());
        }
    }

    record KiroTokenRecord(String accessToken, String expiresAt, @org.jetbrains.annotations.Nullable String profileArn) {}

    /**
     * Reads the Kiro CLI OIDC token from its local SQLite database.
     * Returns {@code null} if the DB or token row does not exist.
     */
    @org.jetbrains.annotations.Nullable
    static KiroTokenRecord readKiroToken() throws Exception {
        java.nio.file.Path dbPath = resolveKiroDbPath();
        if (!java.nio.file.Files.exists(dbPath)) {
            return null;
        }
        // Use sqlite3 subprocess to avoid a JDBC dependency. The DB is small and
        // this is called at most once per auth refresh cycle.
        // Returns two tab-separated columns: access_token<TAB>expires_at
        ProcessBuilder pb = new ProcessBuilder(
            "sqlite3", dbPath.toString(),
            "SELECT json_extract(value,'$.access_token') || char(9) || " +
                "json_extract(value,'$.expires_at') " +
                "FROM auth_kv WHERE key='kirocli:odic:token';"
        );
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            output = reader.lines().collect(java.util.stream.Collectors.joining()).trim();
        }
        proc.waitFor(5, TimeUnit.SECONDS);
        if (output.isBlank()) return null;
        String[] parts = output.split("\t", 2);
        if (parts.length < 2 || parts[0].isBlank()) return null;
        return new KiroTokenRecord(parts[0], parts[1], readKiroProfileArn(dbPath));
    }

    /**
     * Reads the Q Developer profile ARN the Kiro CLI selected during {@code kiro login}.
     * <p>
     * v3 backend requests require a {@code profileArn} to identify the user's Q Developer
     * profile — without it the service returns {@code "profileArn is required for this request"}.
     * The CLI stores the selected profile in its {@code state} table under key
     * {@code api.codewhisperer.profile} as JSON ({@code {"arn":"arn:aws:codewhisperer:...",
     * "profile_name":"..."}}); we extract the {@code arn} field.
     * <p>
     * Returns {@code null} if the row is absent (e.g. Builder ID / free-tier accounts that
     * don't use a profile), in which case the callback omits {@code profileArn}.
     */
    @org.jetbrains.annotations.Nullable
    static String readKiroProfileArn(java.nio.file.Path dbPath) throws Exception {
        // The state table stores value as a BLOB, so CAST to TEXT before json_extract.
        ProcessBuilder pb = new ProcessBuilder(
            "sqlite3", dbPath.toString(),
            "SELECT json_extract(CAST(value AS TEXT),'$.arn') " +
                "FROM state WHERE key='api.codewhisperer.profile';"
        );
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            output = reader.lines().collect(java.util.stream.Collectors.joining()).trim();
        }
        proc.waitFor(5, TimeUnit.SECONDS);
        return output.isBlank() ? null : output;
    }

    /**
     * Returns the OS-appropriate path to the Kiro CLI SQLite database.
     * <ul>
     *   <li>macOS: {@code ~/Library/Application Support/kiro-cli/data.sqlite3}</li>
     *   <li>Linux: {@code ~/.local/share/kiro-cli/data.sqlite3}</li>
     *   <li>Windows: {@code %APPDATA%\kiro-cli\data.sqlite3}</li>
     * </ul>
     */
    static java.nio.file.Path resolveKiroDbPath() {
        String home = SystemProperties.getUserHome();
        if (com.intellij.openapi.util.SystemInfo.isMac) {
            return java.nio.file.Path.of(home, "Library", "Application Support", "kiro-cli", "data.sqlite3");
        } else if (com.intellij.openapi.util.SystemInfo.isWindows) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return java.nio.file.Path.of(appData, "kiro-cli", "data.sqlite3");
            }
            return java.nio.file.Path.of(home, "AppData", "Roaming", "kiro-cli", "data.sqlite3");
        } else {
            // Linux: XDG_DATA_HOME or ~/.local/share
            String xdgData = System.getenv("XDG_DATA_HOME");
            if (xdgData != null && !xdgData.isBlank()) {
                return java.nio.file.Path.of(xdgData, "kiro-cli", "data.sqlite3");
            }
            return java.nio.file.Path.of(home, ".local", "share", "kiro-cli", "data.sqlite3");
        }
    }
    private void handleKiroNotification(String method, JsonObject params) {
        switch (method) {
            case "_kiro.dev/commands/available" -> handleCommandsAvailable(params);
            case "_kiro.dev/mcp/oauth_request" -> handleMcpOAuthRequest(params);
            case "_kiro.dev/mcp/server_initialized" -> handleMcpServerInitialized(params);
            case "_kiro.dev/compaction/status" -> handleCompactionStatus(params);
            case "_kiro.dev/clear/status" -> handleClearStatus(params);
            case "_kiro.dev/metadata" -> { /* context usage telemetry — intentionally ignored */ }
            case "_session/terminate" -> handleSessionTerminate(params);
            default -> LOG.debug("Unhandled Kiro notification: " + method);
        }
    }

    private void handleCommandsAvailable(JsonObject params) {
        if (params != null && params.has("commands")) {
            List<NewSessionResponse.AvailableCommand> commands = parseCommandsAvailable(params);
            LOG.info("Kiro slash commands available: " + commands.size());
            updateCommands(commands);
        }
    }

    /**
     * Parses a Kiro {@code _kiro.dev/commands/available} notification payload into
     * {@link NewSessionResponse.AvailableCommand} records, preserving each command's
     * description so the prompt autocomplete can display it. Entries without a usable
     * {@code name} are skipped. Pure and side-effect free for unit testing.
     */
    static List<NewSessionResponse.AvailableCommand> parseCommandsAvailable(JsonObject params) {
        List<NewSessionResponse.AvailableCommand> result = new java.util.ArrayList<>();
        if (params == null || !params.has("commands")) {
            return result;
        }
        JsonArray commands = params.getAsJsonArray("commands");
        for (var el : commands) {
            if (!el.isJsonObject()) continue;
            JsonObject cmd = el.getAsJsonObject();
            if (!cmd.has("name") || cmd.get("name").isJsonNull()) continue;
            String name = cmd.get("name").getAsString();
            if (name.isBlank()) continue;
            String description = cmd.has("description") && !cmd.get("description").isJsonNull()
                ? cmd.get("description").getAsString()
                : "";
            result.add(new NewSessionResponse.AvailableCommand(name, description, null));
        }
        return result;
    }

    public void executeSlashCommand(String command, java.util.function.Consumer<Boolean> callback) {
        JsonObject params = new JsonObject();
        params.addProperty("command", command);
        transport.sendRequest("_kiro.dev/commands/execute", params).thenAccept(response -> {
            boolean success = response != null && response.isJsonObject()
                && response.getAsJsonObject().has("success")
                && response.getAsJsonObject().get("success").getAsBoolean();
            callback.accept(success);
        });
    }

    private void handleMcpOAuthRequest(JsonObject params) {
        if (params != null && params.has("url")) {
            String oauthUrl = params.get("url").getAsString();
            LOG.info("MCP OAuth required: " + oauthUrl);
            // OAuth for MCP servers is not yet exposed via ACP — log and ignore for now.
        }
    }

    private void handleMcpServerInitialized(JsonObject params) {
        if (params != null && params.has("serverName")) {
            String serverName = params.get("serverName").getAsString();
            LOG.info("MCP server initialized: " + serverName);
        }
    }

    private void handleCompactionStatus(JsonObject params) {
        if (params != null && params.has(KEY_STATUS)) {
            String status = params.get(KEY_STATUS).getAsString();
            LOG.debug("Context compaction: " + status);
        }
    }

    private void handleClearStatus(JsonObject params) {
        if (params != null && params.has(KEY_STATUS)) {
            String status = params.get(KEY_STATUS).getAsString();
            LOG.debug("Clear session: " + status);
        }
    }

    private void handleSessionTerminate(JsonObject params) {
        if (params != null && params.has("sessionId")) {
            String sessionId = params.get("sessionId").getAsString();
            LOG.info("Subagent session terminated: " + sessionId);
        }
    }

    @Override
    public String displayName() {
        return "Kiro";
    }

    @Override
    public String agentId() {
        return "kiro";
    }

    @Override
    protected boolean excludeBuiltInTools() {
        return true;
    }

    @Override
    protected String resolveToolId(String protocolTitle) {
        return resolveToolIdStatic(protocolTitle);
    }

    /**
     * Maps a Kiro protocol title to the underlying MCP tool name.
     * Strips the {@code @agentbridge/} or {@code Running: @agentbridge/} prefix,
     * and maps human-readable Kiro titles to tool names.
     */
    static String resolveToolIdStatic(String protocolTitle) {
        if (protocolTitle.startsWith(KEY_AGENTBRIDGE)) {
            return protocolTitle.substring(KEY_AGENTBRIDGE.length());
        }
        String cleaned = protocolTitle.replaceFirst("^Running: @agentbridge/", "");
        return switch (cleaned) {
            case "Searching the web" -> "web_search";
            case "Fetching web content" -> "web_fetch";
            default -> cleaned;
        };
    }

    @Override
    protected boolean isMcpToolTitle(@org.jetbrains.annotations.NotNull String protocolTitle) {
        return isMcpToolTitleStatic(protocolTitle);
    }

    /**
     * Checks whether a Kiro protocol title refers to an agentbridge MCP tool.
     */
    static boolean isMcpToolTitleStatic(String protocolTitle) {
        return protocolTitle.startsWith("Running: " + KEY_AGENTBRIDGE)
            || protocolTitle.startsWith(KEY_AGENTBRIDGE);
    }

    @Override
    protected List<String> buildCommand(String cwd, int mcpPort) {
        AgentProfile profile = AgentProfileManager
            .getInstance().getProfile(AgentProfileManager.KIRO_PROFILE_ID);
        String engine = profile != null ? profile.getKiroAgentEngine() : "v2";
        List<String> cmd = new java.util.ArrayList<>(buildCommandStatic(engine));
        if (profile != null) {
            cmd.addAll(profile.parsedExtraCliArgs());
        }
        return cmd;
    }

    /**
     * Returns the Kiro CLI command for the given engine version.
     * <ul>
     *   <li><b>v2</b>: {@code kiro-cli acp --agent intellij-task --trust-all-tools}
     *       — agent and trust are both CLI flags.</li>
     *   <li><b>v3</b>: {@code kiro-cli acp --agent-engine v3}
     *       — neither {@code --agent} nor {@code --trust-all-tools} are supported as flags.
     *       Trust is set via {@code autopilot:true} in {@code session/new} params
     *       (see {@link #customizeNewSession}).
     *       Agent selection is sent as {@code session/set_mode} after session creation
     *       (see {@link #onSessionCreated}).</li>
     * </ul>
     */
    static List<String> buildCommandStatic(String engine) {
        if ("v3".equals(engine)) {
            // V3 does not support --agent or --trust-all-tools as CLI flags.
            // Trust is handled via "autopilot: true" in session/new (see customizeNewSession).
            // Agent selection is handled via session/set_mode after session/new (see onSessionCreated).
            return List.of("kiro-cli", "acp", "--agent-engine", "v3");
        }
        return List.of("kiro-cli", "acp", "--agent", "intellij-task", "--trust-all-tools");
    }

    /** @deprecated Use {@link #buildCommandStatic(String)} with an explicit engine. */
    @Deprecated
    static List<String> buildCommandStatic() {
        return buildCommandStatic("v2");
    }

    @Override
    protected void beforeLaunch(String cwd, int mcpPort) throws java.io.IOException {
        java.nio.file.Path kiroDir = java.nio.file.Path.of(SystemProperties.getUserHome(), ".kiro", "agents");
        java.nio.file.Files.createDirectories(kiroDir);
        java.nio.file.Path agentPath = kiroDir.resolve("intellij-task.json");

        JsonObject agent = new JsonObject();
        agent.addProperty("name", "intellij-task");
        agent.addProperty("description", "IDE-only agent");

        JsonArray tools = new JsonArray();
        tools.add("@agentbridge/*");
        tools.add("web_fetch");
        tools.add("web_search");
        agent.add("tools", tools);

        JsonArray allowedTools = new JsonArray();
        allowedTools.add("@agentbridge/*");
        agent.add("allowedTools", allowedTools);

        try (java.io.Writer writer = java.nio.file.Files.newBufferedWriter(agentPath)) {
            gson.toJson(agent, writer);
            com.intellij.openapi.diagnostic.Logger.getInstance(KiroClient.class)
                .info("Kiro: wrote agent definition to " + agentPath + " to restrict built-in tools");
        }
    }

    @Override
    protected Map<String, String> buildEnvironment(int mcpPort, String cwd) {
        return buildEnvironmentStatic();
    }

    /**
     * Returns Kiro-specific environment variables.
     * {@code RUST_BACKTRACE=1} enables full stack traces on Rust panics.
     */
    static Map<String, String> buildEnvironmentStatic() {
        return Map.of("RUST_BACKTRACE", "1");
    }

    @Override
    protected void customizeNewSession(String cwd, int mcpPort, JsonObject params) {
        // Kiro requires mcpServers in session/new params (field is mandatory).
        //
        // Kiro 2.14.1 only loads @agentbridge tools when given an HTTP MCP server — it silently
        // ignores STDIO servers over ACP (never emits kiro.dev/mcp/server_initialized). The HTTP
        // entry must be shaped exactly (including a `headers` ARRAY — see
        // AcpClient.buildMcpHttpServerJson); a malformed entry makes Kiro exit cleanly on
        // session/new. (See issue #948.)
        //
        // Transport selection stays version-gated rather than driven by mcpCapabilities.http alone:
        // older Kiro (e.g. 2.10.0) also advertises mcpCapabilities.http:true, but was only ever
        // observed with the earlier (headerless) HTTP payload, which crashed its ACP process on
        // session/new. Whether those versions accept the corrected payload was not verified, so we
        // conservatively require a known-good version (2.14.1+) before sending HTTP and fall back to
        // STDIO otherwise. STDIO leaves the session alive (just without @agentbridge tools), which
        // is no worse than the pre-fix behaviour on those versions — it avoids any risk of
        // regressing an older Kiro from "session works" to "session dies".
        JsonObject server;
        if (advertisesHttpMcp() && supportsHttpMcp(kiroVersion())) {
            server = buildMcpHttpServer("agentbridge", mcpPort);
        } else {
            server = buildMcpStdioServer("agentbridge", mcpPort);
            if (server == null) {
                throw new IllegalStateException(
                    "Cannot configure Kiro MCP server — " + describeMcpStdioServerFailure());
            }
        }
        JsonArray servers = new JsonArray();
        servers.add(server);
        params.add("mcpServers", servers);

        // V3 does not support --trust-all-tools as a CLI flag.
        // Pass autopilot:true in session/new instead — equivalent to the IDE's "auto-approve all
        // tools" setting — so tool calls are never blocked waiting for TTY permission prompts.
        AgentProfile profile = AgentProfileManager
            .getInstance().getProfile(AgentProfileManager.KIRO_PROFILE_ID);
        if (profile != null && "v3".equals(profile.getKiroAgentEngine())) {
            params.addProperty("autopilot", true);
        }
    }

    /**
     * Whether the active Kiro profile is running the v3 agent engine. Agent (mode) selection is
     * only exposed and applied for v3, which drives it through {@code session/set_mode}; v2 pins
     * the agent via the {@code --agent} CLI flag and cannot switch without restarting.
     */
    private boolean isV3Engine() {
        AgentProfile profile = AgentProfileManager
            .getInstance().getProfile(AgentProfileManager.KIRO_PROFILE_ID);
        return profile != null && "v3".equals(profile.getKiroAgentEngine());
    }

    /**
     * On v3, Kiro exposes its agents as standard ACP session modes (e.g. {@code vibe}, {@code spec},
     * {@code plan}) parsed from {@code session/new} into {@link #getAvailableModes()}; surface them
     * as selectable agents. On v2 the agent is fixed at launch via {@code --agent}, so no runtime
     * selection is offered.
     */
    @Override
    public List<AbstractClient.AgentMode> getAvailableAgents() {
        return isV3Engine() ? getAvailableModes() : List.of();
    }

    /**
     * On v3 the default selected agent mirrors Kiro's own reported {@code currentModeId} (parsed
     * into {@link #getCurrentModeSlug()} — e.g. {@code vibe}), so the session menu highlights the
     * agent Kiro actually started with. On v2 agent selection is not exposed, so there is no
     * default agent slug.
     *
     * <p>Never returns a synthetic slug: sending an unknown mode to {@code session/set_mode} is
     * rejected by Kiro, and highlighting a non-existent entry is misleading. {@code intellij-task}
     * (the v2 {@code --agent} identity) is deliberately not used here — it is not one of the v3
     * modes Kiro advertises.</p>
     */
    @Override
    public @org.jetbrains.annotations.Nullable String defaultAgentSlug() {
        return isV3Engine() ? getCurrentModeSlug() : null;
    }

    /**
     * For v3, applies an explicit agent selection made before the session existed via
     * {@code session/set_mode} once the session is created. If no explicit selection was made (or
     * it already matches Kiro's current mode) nothing is sent — Kiro already starts in its own
     * {@code currentModeId}, so forcing a redundant switch is unnecessary.
     */
    @Override
    protected void onSessionCreated(String sessionId) {
        if (!isV3Engine()) {
            return;
        }
        String slug = getCurrentAgentSlug();
        if (isSelectableMode(slug) && !slug.equals(getCurrentModeSlug())) {
            applyKiroMode(sessionId, slug);
        }
    }

    /**
     * For v3, pushes an agent selection made while a session is live to Kiro via
     * {@code session/set_mode}. No-op on v2, when there is no active session (the selection is then
     * applied later by {@link #onSessionCreated}), or when the slug is not one of the modes Kiro
     * advertised (guards against sending an invalid mode that Kiro would reject).
     */
    @Override
    protected void onAgentSlugChanged(@org.jetbrains.annotations.Nullable String slug) {
        if (!isV3Engine() || !isSelectableMode(slug)) {
            return;
        }
        String sessionId = getActiveSessionId();
        if (sessionId != null && !sessionId.isBlank()) {
            applyKiroMode(sessionId, slug);
        }
    }

    /**
     * Whether {@code slug} is a non-blank mode that Kiro actually advertised in {@code session/new}.
     * Only advertised modes are valid arguments to {@code session/set_mode}.
     */
    private boolean isSelectableMode(@org.jetbrains.annotations.Nullable String slug) {
        if (slug == null || slug.isBlank()) {
            return false;
        }
        return getAvailableModes().stream().anyMatch(m -> slug.equals(m.slug()));
    }

    /**
     * Builds the {@code session/set_mode} request params for the given session and mode.
     * The Kiro (standard ACP) field name is {@code modeId}. Pure for unit testing.
     */
    static JsonObject buildSetModeParams(String sessionId, String modeId) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        params.addProperty("modeId", modeId);
        return params;
    }

    private void applyKiroMode(String sessionId, String modeId) {
        transport.sendRequest("session/set_mode", buildSetModeParams(sessionId, modeId))
            .orTimeout(10, TimeUnit.SECONDS)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    LOG.warn("Kiro v3: session/set_mode failed for " + modeId + ": " + ex.getMessage());
                } else {
                    LOG.info("Kiro v3: session/set_mode " + modeId + " applied for session " + sessionId);
                }
            });
    }

    /**
     * The Kiro CLI version reported in the ACP {@code initialize} response, or {@code null}
     * if the agent hasn't initialized yet or didn't report a version.
     */
    private @org.jetbrains.annotations.Nullable String kiroVersion() {
        var caps = getCapabilities();
        return caps != null && caps.agentInfo() != null ? caps.agentInfo().version() : null;
    }

    /**
     * The lowest Kiro CLI version verified to load an HTTP MCP server over ACP with the corrected
     * {@code session/new} payload (see {@link AcpClient#buildMcpHttpServerJson}). Older versions
     * advertise {@code mcpCapabilities.http:true} but were only ever exercised with the earlier
     * headerless payload, which crashed their ACP process; they were not re-verified with the fix,
     * so they conservatively fall back to the STDIO transport instead.
     */
    private static final int[] MIN_HTTP_MCP_VERSION = {2, 14, 1};

    /**
     * Whether the given Kiro CLI version string (e.g. {@code "2.14.1"}) is at least
     * {@link #MIN_HTTP_MCP_VERSION}. Unparseable or {@code null} versions return {@code false}
     * so we conservatively fall back to STDIO.
     */
    static boolean supportsHttpMcp(@org.jetbrains.annotations.Nullable String version) {
        int[] parsed = parseVersion(version);
        if (parsed == null) {
            return false;
        }
        for (int i = 0; i < MIN_HTTP_MCP_VERSION.length; i++) {
            int part = i < parsed.length ? parsed[i] : 0;
            if (part != MIN_HTTP_MCP_VERSION[i]) {
                return part > MIN_HTTP_MCP_VERSION[i];
            }
        }
        return true;
    }

    /**
     * Parses a dotted numeric version string into its {@code major.minor.patch} components.
     * Trailing pre-release/build suffixes (e.g. {@code "-beta"}, {@code "+build"}) on the last
     * numeric segment are ignored. Returns {@code null} if no leading numeric component is present.
     */
    private static int @org.jetbrains.annotations.Nullable [] parseVersion(
        @org.jetbrains.annotations.Nullable String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String[] segments = version.trim().split("\\.");
        int[] parts = new int[segments.length];
        for (int i = 0; i < segments.length; i++) {
            java.util.regex.Matcher m = LEADING_DIGITS.matcher(segments[i]);
            if (!m.find()) {
                return i == 0 ? null : java.util.Arrays.copyOf(parts, i);
            }
            parts[i] = Integer.parseInt(m.group());
        }
        return parts;
    }

    private static final Pattern LEADING_DIGITS = Pattern.compile("^\\d+");

    @Override
    protected JsonObject parseToolCallArguments(@NotNull JsonObject update) {
        // Kiro sends args in "rawInput" (object) instead of "content" (array)
        return update.has(KEY_RAW_INPUT) && update.get(KEY_RAW_INPUT).isJsonObject()
            ? update.getAsJsonObject(KEY_RAW_INPUT)
            : null;
    }

    @Override
    protected SessionUpdate processUpdate(SessionUpdate update) {
        // Kiro sends thinking as agent_message_chunk with ContentBlock.Thinking blocks —
        // convert to agent_thought_chunk for proper UI rendering.
        update = convertThinkingToThought(update);
        if (update instanceof SessionUpdate.ToolCall tc) {
            // Kiro sends multiple tool_call updates for the same toolCallId:
            // 1. First with just title (e.g., "search_text") - NO rawInput
            // 2. Second with full details ("Running: @agentbridge/search_text" + rawInput)
            // We need the rawInput to compute the hash for MCP correlation, so skip the first one
            if (tc.arguments() == null || tc.arguments().isEmpty()) {
                return null;  // Skip - wait for the one with rawInput
            }
            return extractPurpose(tc);
        }
        return update;  // Pass through all other update types unchanged
    }

    /**
     * Converts an {@link SessionUpdate.AgentMessageChunk} containing {@link ContentBlock.Thinking}
     * blocks to an {@link SessionUpdate.AgentThoughtChunk} for proper UI rendering.
     * Returns the original update unchanged if no conversion is needed.
     */
    static SessionUpdate convertThinkingToThought(SessionUpdate update) {
        if (update instanceof SessionUpdate.AgentMessageChunk(var content)) {
            boolean hasThinking = content.stream()
                .anyMatch(block -> block instanceof ContentBlock.Thinking);
            if (hasThinking) {
                return new SessionUpdate.AgentThoughtChunk(content);
            }
        }
        return update;
    }

    /**
     * Returns {@code true} if {@code line} looks like a Rust panic header.
     *
     * <p>Rust 2018 format: {@code thread 'name' panicked at 'msg', file:line}</p>
     * <p>Rust 2021+ format: {@code thread 'name' panicked at file:line:col}</p>
     * <p>Crash-handler format: {@code The application panicked (crash handler installed)}</p>
     */
    private static boolean isPanicLine(@NotNull String line) {
        return line.contains("panicked at") || line.contains("The application panicked");
    }

    /**
     * Strips ANSI escape sequences (color codes, bold, etc.) from a string.
     * Kiro's Rust stderr output includes ANSI codes that would appear as garbled text in the UI.
     */
    static String stripAnsi(@NotNull String s) {
        return ANSI_ESCAPE.matcher(s).replaceAll("");
    }

    /**
     * When Kiro crashes (Rust panic), the process writes the panic message to stderr and the
     * transport stops. The generic "Transport stopped" message is unhelpful; this override
     * inspects the eagerly-captured panic line (or falls back to the rolling buffer) and surfaces
     * the actual panic reason to the UI.
     *
     * <p><b>Why eager capture:</b> With {@code RUST_BACKTRACE=1}, Kiro emits 50+ backtrace lines
     * after the panic header, evicting it from the 30-line rolling buffer before this method runs.
     * {@link #capturedPanicLine} stores the first panic line the moment it arrives so it is never
     * lost regardless of backtrace length.</p>
     */
    @Override
    protected @org.jetbrains.annotations.Nullable PromptResponse
    tryRecoverPromptException(Exception cause) {
        // Prefer the eagerly-captured panic line; fall back to a scan of the rolling buffer.
        String panicLine = capturedPanicLine;
        if (panicLine == null) {
            synchronized (recentStderr) {
                panicLine = recentStderr.stream()
                    .filter(l -> l.contains("panicked") || l.contains("Message:"))
                    .reduce((first, second) -> second) // keep last matching line
                    .map(l -> stripAnsi(l.trim()))
                    .orElse(null);
            }
        }
        if (panicLine == null) return null;
        // Throw an unchecked exception whose message surfaces in the UI via handlePromptError.
        throw new java.io.UncheckedIOException(
            new java.io.IOException("Kiro crashed: " + panicLine.trim(), cause));
    }

    private SessionUpdate.ToolCall extractPurpose(SessionUpdate.ToolCall tc) {
        String purpose = extractPurposeFromArgs(tc.arguments());
        if (purpose != null) {
            return new SessionUpdate.ToolCall(
                tc.toolCallId(), tc.title(), tc.acpName(), tc.kind(), tc.arguments(),
                tc.locations(), tc.agentType(), tc.subAgentDescription(),
                tc.subAgentPrompt(), purpose
            );
        }
        return tc;
    }

    /**
     * Extracts the {@code __tool_use_purpose} value from a JSON arguments string.
     * Uses index-based parsing to avoid a full JSON parse for every tool call.
     *
     * @param args raw tool arguments JSON string
     * @return the purpose string, or {@code null} if not found
     */
    @org.jetbrains.annotations.Nullable
    static String extractPurposeFromArgs(@org.jetbrains.annotations.Nullable String args) {
        if (args == null || !args.contains("__tool_use_purpose")) {
            return null;
        }
        int start = args.indexOf("\"__tool_use_purpose\"");
        if (start < 0) return null;
        int colonIdx = args.indexOf(':', start);
        if (colonIdx < 0) return null;
        int quoteStart = args.indexOf('"', colonIdx + 1);
        if (quoteStart < 0) return null;
        int quoteEnd = args.indexOf('"', quoteStart + 1);
        if (quoteEnd > quoteStart) {
            return args.substring(quoteStart + 1, quoteEnd);
        }
        return null;
    }
}
