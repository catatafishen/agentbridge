package com.github.catatafishen.agentbridge.session;

import com.github.catatafishen.agentbridge.agent.claude.ClaudeCliClient;
import com.github.catatafishen.agentbridge.agent.codex.CodexAppServerClient;
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.services.AgentProfileManager;
import com.github.catatafishen.agentbridge.services.GenericSettings;
import com.github.catatafishen.agentbridge.session.db.ConversationService;
import com.github.catatafishen.agentbridge.session.exporters.AnthropicClientExporter;
import com.github.catatafishen.agentbridge.session.exporters.ClaudeCliExporter;
import com.github.catatafishen.agentbridge.session.exporters.CodexClientExporter;
import com.github.catatafishen.agentbridge.session.exporters.CopilotClientExporter;
import com.github.catatafishen.agentbridge.session.exporters.ExportUtils;
import com.github.catatafishen.agentbridge.session.exporters.KiroClientExporter;
import com.github.catatafishen.agentbridge.session.exporters.OpenCodeClientExporter;
import com.github.catatafishen.agentbridge.settings.JunieClientConfigurable;
import com.github.catatafishen.agentbridge.settings.KiroClientConfigurable;
import com.github.catatafishen.agentbridge.settings.OpenCodeClientConfigurable;
import com.github.catatafishen.agentbridge.ui.EntryData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Project service that orchestrates cross-client session migration when the active
 * agent changes.
 *
 * <p>When {@link #onAgentSwitch(String, String)} is called (always on a pooled thread),
 * the service first tries to import the previous client's latest native session into v2
 * (in case the user ran the client directly outside the plugin), then exports to the new
 * agent's native session format so the conversation context carries over.</p>
 *
 * <h3>Import support (client native → v2):</h3>
 * <ul>
 *   <li>Claude CLI / Claude Code — {@code ~/.claude/projects/<sha1>/*.jsonl}</li>
 *   <li>Kiro — {@code ~/.kiro/sessions/<uuid>/messages.jsonl}</li>
 *   <li>Junie — {@code ~/.junie/sessions/<uuid>/messages.jsonl}</li>
 *   <li>Copilot CLI — {@code ~/.copilot/session-state/<uuid>/events.jsonl}</li>
 *   <li>Codex — {@code ~/.codex/codex.db} + rollout JSONL</li>
 *   <li>OpenCode — {@code ~/.local/share/opencode/opencode.db}</li>
 * </ul>
 *
 * <h3>Export support (v2 → client native):</h3>
 * <ul>
 *   <li>Claude CLI — writes to {@code ~/.claude/projects/<sha1>/}</li>
 *   <li>Kiro — writes to {@code ~/.kiro/sessions/<uuid>/} + sets resumeSessionId</li>
 *   <li>Junie — writes to {@code ~/.junie/sessions/<uuid>/} + sets resumeSessionId</li>
 *   <li>Codex — writes rollout JSONL + updates codex.db + sets codexThreadId</li>
 *   <li>Copilot CLI — writes to {@code ~/.copilot/session-state/<uuid>/events.jsonl} + sets resumeSessionId</li>
 *   <li>OpenCode — writes to {@code opencode.db} (session/message/part tables)</li>
 * </ul>
 */
@Service(Service.Level.PROJECT)
public final class SessionSwitchService implements Disposable {

    private static final Logger LOG = Logger.getInstance(SessionSwitchService.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final String SESSIONS_DIR = "sessions";
    private static final String CLAUDE_HOME = ".claude";
    private static final String CLAUDE_PROJECTS_DIR = "projects";
    private static final String JUNIE_HOME = ".junie";
    private static final String JUNIE_SESSIONS_DIR = "sessions";
    private static final String USER_HOME_PROPERTY = "user.home";
    private static final String JSONL_EXT = ".jsonl";
    private static final String WORKSPACE_PATHS_KEY = "workspacePaths";
    private static final String COPILOT_ID_PREFIX = "copilot";
    private static final String UNKNOWN_BRANCH = "unknown";
    private static final String AGENT_WORK_DIR = ".agent-work";
    private static final String SESSION_STATE_DIR = "session-state";
    private static final String CLAUDE_RESUME_ID_FILE = "claude-resume-id.txt";

    private final Project project;
    private volatile CompletableFuture<Void> pendingExport = CompletableFuture.completedFuture(null);

    public SessionSwitchService(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Returns the {@link SessionSwitchService} instance for the given project.
     *
     * @param project the IntelliJ project
     * @return the service instance (never null)
     */
    @NotNull
    public static SessionSwitchService getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, SessionSwitchService.class);
    }

    // ── Main entry point ──────────────────────────────────────────────────────

    /**
     * Called when the active agent is switched. Runs the export logic on a pooled thread
     * and stores the future so callers can wait for completion via {@link #awaitPendingExport}.
     *
     * @param fromProfileId profile ID of the previously active agent
     * @param toProfileId   profile ID of the newly active agent
     */
    public void onAgentSwitch(@NotNull String fromProfileId, @NotNull String toProfileId) {
        if (fromProfileId.equals(toProfileId)) return;
        pendingExport = CompletableFuture.runAsync(
            () -> doExport(fromProfileId, toProfileId),
            AppExecutorUtil.getAppExecutorService());
    }

    /**
     * Prepares the native session files for a same-agent restart (e.g., plugin restart).
     *
     * <p>When the CLI process is killed and restarted for the same agent, its own
     * {@code events.jsonl} may not have been flushed to disk. This method exports
     * the current v2 session (our source of truth) to the agent's native format,
     * creating a fresh session directory with a valid {@code events.jsonl} so the
     * CLI can resume via {@code --resume=<id>}.</p>
     *
     * <p>Runs asynchronously — callers should use {@link #awaitPendingExport(long)}
     * before starting the new process.</p>
     *
     * @param profileId profile ID of the agent being restarted
     */
    public void exportForRestart(@NotNull String profileId) {
        pendingExport = CompletableFuture.runAsync(
            () -> doExport(profileId, profileId),
            AppExecutorUtil.getAppExecutorService());
    }

    /**
     * Blocks until any in-progress session export completes, or until {@code timeoutMs} elapses.
     * Safe to call when no export is running — returns immediately.
     * <p>
     * Call this before starting the new agent process so that {@code resumeSessionId} is
     * guaranteed to be set before {@code createSession()} reads it.
     *
     * @param timeoutMs maximum wait in milliseconds
     */
    public void awaitPendingExport(long timeoutMs) {
        CompletableFuture<Void> future = pendingExport;
        if (future.isDone()) return;
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while waiting for session export", e);
        } catch (TimeoutException e) {
            LOG.warn("Timed out waiting for session export (" + timeoutMs + " ms) — resumeSessionId may be stale");
        } catch (Exception e) {
            LOG.warn("Error waiting for session export", e);
        }
    }

    // ── Export logic ──────────────────────────────────────────────────────────

    private void doExport(@NotNull String fromProfileId, @NotNull String toProfileId) {
        String basePath = project.getBasePath();

        // Wait for any in-flight saveAsync to flush the v2 JSONL to disk before we read it.
        // Without this, the export may read stale data if the user switches agents immediately
        // after a conversation turn completes.
        ConversationService.getInstance(project).awaitPendingSave(5_000);

        // Load v2 session — this is our source of truth, kept up-to-date by the plugin
        // on every conversation save. No need to re-import from the previous client's
        // native format; that would introduce round-trip conversion bugs.
        List<EntryData> entries = loadCurrentV2Session(basePath);
        if (entries.isEmpty()) {
            LOG.info("No v2 session found to migrate from " + fromProfileId + " to " + toProfileId);
            return;
        }

        // Save the latest plan.md to the v2 store so per-agent exporters can pick it up.
        Path planFile = findLatestPlanFile(basePath);
        if (planFile != null) {
            savePlanToV2Store(planFile, basePath);
        }

        // Export to the new client's native format.
        switch (classifyExportTarget(toProfileId)) {
            case "claude" -> exportToClaudeCli(entries, basePath);
            case "codex" -> exportToCodex(entries, basePath);
            case "kiro" -> exportToKiro(entries, basePath, toProfileId);
            case "junie" -> exportToJunie(entries, basePath, toProfileId);
            case "opencode" -> exportToOpenCode(entries, basePath);
            case COPILOT_ID_PREFIX -> exportToCopilot(entries, basePath, toProfileId);
            default -> LOG.info("ACP client '" + toProfileId
                + "' — no native export format; will resume via resumeSessionId");
        }
    }

    // ── Import from previous client ───────────────────────────────────────────

    // ── Claude CLI export ─────────────────────────────────────────────────────

    private void exportToClaudeCli(@NotNull List<EntryData> entries, @Nullable String basePath) {
        try {
            Path claudeDir = claudeProjectDir(basePath);
            if (!claudeDir.toFile().mkdirs() && !claudeDir.toFile().isDirectory()) {
                LOG.warn("Failed to create Claude project directory: " + claudeDir);
                return;
            }

            String newSessionId = UUID.randomUUID().toString();
            Path targetFile = claudeDir.resolve(newSessionId + JSONL_EXT);
            String cwd = basePath != null ? basePath : "";

            ClaudeCliExporter.exportToFile(entries, targetFile, newSessionId, cwd);

            if (!Files.exists(targetFile)) {
                LOG.warn("Claude session file not found after export: " + targetFile);
                return;
            }

            // Store the resume ID in PropertiesComponent for same-process agent switches.
            PropertiesComponent.getInstance(project)
                .setValue(ClaudeCliClient.PROFILE_ID + ".cliResumeSessionId", newSessionId);

            // Also persist to a file — PropertiesComponent values set during dispose()
            // are lost on plugin hot-reload because IntelliJ flushes project state to disk
            // before calling dispose(), so in-memory-only changes are discarded.
            writeClaudeResumeIdFile(basePath, newSessionId);

            LOG.info("Exported v2 session to Claude CLI: " + newSessionId
                + " (" + targetFile + ", " + Files.size(targetFile) + " bytes)");

        } catch (IOException e) {
            LOG.warn("Failed to export v2 session to Claude CLI", e);
        } catch (Exception e) {
            LOG.warn("Unexpected error exporting v2 session to Claude CLI", e);
        }
    }

    // ── ACP local session export (Kiro / Junie) ─────────────────────────────

    /**
     * Exports v2 session messages to Kiro's native CLI session format.
     *
     * <p>Kiro stores sessions as flat files in {@code ~/.kiro/sessions/cli/}
     * ({@code <uuid>.json} + {@code <uuid>.jsonl}), not as subdirectories.
     * Delegates to {@link KiroClientExporter} which handles the Kiro-specific format.
     */
    private void exportToKiro(
        @NotNull List<EntryData> entries,
        @Nullable String basePath,
        @NotNull String toProfileId) {
        Path sessionsDir = KiroClientExporter.defaultSessionsDir();
        int limit = new GenericSettings(AgentProfileManager.KIRO_PROFILE_ID, project)
            .getContextHistoryLimit(KiroClientConfigurable.DEFAULT_CONTEXT_LIMIT_CHARS);
        String sessionId = KiroClientExporter.exportSession(entries, basePath, sessionsDir, limit);
        if (sessionId != null) {
            new GenericSettings(toProfileId, project).setResumeSessionId(sessionId);
            LOG.info("Exported v2 session to Kiro: " + sessionId + " for profile " + toProfileId);
        }
    }

    /**
     * Exports v2 session messages to a Junie local session directory.
     * Delegates to {@link #exportToAcpLocalSession}.
     */
    private void exportToJunie(
        @NotNull List<EntryData> entries,
        @Nullable String basePath,
        @NotNull String toProfileId) {
        Path dir = Path.of(System.getProperty(USER_HOME_PROPERTY), JUNIE_HOME, JUNIE_SESSIONS_DIR);
        int limit = new GenericSettings(AgentProfileManager.JUNIE_PROFILE_ID, project)
            .getContextHistoryLimit(JunieClientConfigurable.DEFAULT_CONTEXT_LIMIT_CHARS);
        exportToAcpLocalSession(entries, basePath, toProfileId, dir, "Junie", limit);
    }

    /**
     * Exports v2 session messages to an ACP client's local session directory
     * and sets {@code resumeSessionId} so AcpClient sends it on next {@code session/new}.
     *
     * @param entries         entries to export
     * @param basePath        project base path (may be null)
     * @param toProfileId     profile ID of the target ACP client
     * @param sessionsBaseDir base sessions directory (e.g. {@code ~/.kiro/sessions/})
     * @param clientName      human-readable client name for log messages
     */
    private void exportToAcpLocalSession(
        @NotNull List<EntryData> entries,
        @Nullable String basePath,
        @NotNull String toProfileId,
        @NotNull Path sessionsBaseDir,
        @NotNull String clientName,
        int maxTotalChars) {
        try {
            String newSessionId = UUID.randomUUID().toString();
            Path sessionDir = sessionsBaseDir.resolve(newSessionId);
            Files.createDirectories(sessionDir);

            // Write session.json
            Files.writeString(
                sessionDir.resolve("session.json"),
                buildAcpSessionJson(newSessionId, basePath, System.currentTimeMillis()),
                StandardCharsets.UTF_8);

            // Write messages.jsonl via AnthropicMessageExporter
            AnthropicClientExporter.exportToFile(entries, sessionDir.resolve("messages.jsonl"), maxTotalChars);

            // Set resumeSessionId so AcpClient sends it on the next session/new
            new GenericSettings(toProfileId, project).setResumeSessionId(newSessionId);

            LOG.info("Exported v2 session to " + clientName + ": " + newSessionId + " for profile " + toProfileId);
        } catch (IOException e) {
            LOG.warn("Failed to export v2 session to " + clientName, e);
        }
    }

    // ── Codex export ──────────────────────────────────────────────────────────

    /**
     * Exports v2 session messages to a new Codex session (rollout JSONL + SQLite entry)
     * and sets the Codex thread ID for resume on next startup.
     */
    private void exportToCodex(@NotNull List<EntryData> entries, @Nullable String basePath) {
        Path sessionsDir = CodexClientExporter.defaultSessionsDir();
        Path dbPath = CodexClientExporter.defaultDbPath();
        String threadId = CodexClientExporter.exportSession(entries, sessionsDir, dbPath, basePath);
        if (threadId != null) {
            // Set the thread ID so CodexAppServerClient will try thread/resume on next startup
            PropertiesComponent.getInstance(project)
                .setValue(CodexAppServerClient.PROFILE_ID + ".codexThreadId", threadId);
            LOG.info("Exported v2 session to Codex thread: " + threadId);
        }
    }

    // ── Copilot CLI export ────────────────────────────────────────────────────

    /**
     * Exports v2 session messages to a new Copilot CLI session directory
     * and sets {@code resumeSessionId} so AcpClient sends it on next {@code session/new}.
     *
     * <p>Copilot CLI stores sessions under {@code ~/.copilot/session-state/<uuid>/}.
     * A valid resumable session requires at minimum:
     * <ul>
     *   <li>{@code events.jsonl} — the conversation event log</li>
     *   <li>{@code workspace.yaml} — session metadata (id, cwd, git info)</li>
     *   <li>{@code checkpoints/}, {@code files/}, {@code research/} — empty subdirectories</li>
     * </ul>
     * Without {@code workspace.yaml}, the CLI ignores {@code --resume} and creates a new session.</p>
     */
    private void exportToCopilot(
        @NotNull List<EntryData> entries,
        @Nullable String basePath,
        @NotNull String toProfileId) {
        try {
            String base = basePath != null ? basePath : "";
            String newSessionId = UUID.randomUUID().toString();
            Path sessionDir = copilotSessionDir(newSessionId);
            Files.createDirectories(sessionDir);

            // Create required subdirectories that Copilot CLI expects
            Files.createDirectories(sessionDir.resolve("checkpoints"));
            Files.createDirectories(sessionDir.resolve("files"));
            Files.createDirectories(sessionDir.resolve("research"));

            // Write workspace.yaml — required for Copilot CLI to recognize this as a valid session
            writeWorkspaceYaml(sessionDir, newSessionId, base);

            Path eventsFile = sessionDir.resolve("events.jsonl");
            CopilotClientExporter.exportToFile(entries, eventsFile, newSessionId, base);

            // Copy plan.md from v2 store into this Copilot session dir
            copyPlanFromV2Store(base, sessionDir);

            new GenericSettings(toProfileId, project).setResumeSessionId(newSessionId);

            LOG.info("Exported v2 session to Copilot CLI: " + newSessionId);
        } catch (IOException e) {
            LOG.warn("Failed to export v2 session to Copilot CLI", e);
        }
    }

    // ── OpenCode export ───────────────────────────────────────────────────────

    /**
     * Parses the branch name from the contents of a {@code .git/HEAD} file.
     * Returns the branch name for symbolic refs (e.g. {@code "ref: refs/heads/main"} → {@code "main"}),
     * or {@code "unknown"} for detached HEAD or unrecognised formats.
     */
    static String parseGitBranchFromHead(@Nullable String headContent) {
        if (headContent == null || headContent.isBlank()) return UNKNOWN_BRANCH;
        String trimmed = headContent.trim();
        if (trimmed.startsWith("ref: refs/heads/")) {
            return trimmed.substring("ref: refs/heads/".length());
        }
        return UNKNOWN_BRANCH;
    }

    /**
     * Builds the YAML content for a Copilot CLI {@code workspace.yaml} file.
     */
    static String buildWorkspaceYamlContent(@NotNull String sessionId, @NotNull String basePath,
                                            @NotNull String branch, @NotNull String timestamp) {
        return "id: " + sessionId + "\n"
            + "cwd: " + basePath + "\n"
            + "git_root: " + basePath + "\n"
            + "branch: " + branch + "\n"
            + "summary_count: 0\n"
            + "created_at: " + timestamp + "\n"
            + "updated_at: " + timestamp + "\n";
    }

    /**
     * Builds the JSON content for an ACP local session's {@code session.json} file.
     *
     * <p>The JSON includes the session ID, workspace paths, title, timestamps,
     * and schema version. This is used by Kiro and Junie for session import.</p>
     *
     * @param sessionId   unique session identifier
     * @param basePath    project base path (may be null)
     * @param epochMillis timestamp in milliseconds since epoch
     * @return JSON string for session.json
     */
    static String buildAcpSessionJson(@NotNull String sessionId, @Nullable String basePath, long epochMillis) {
        JsonObject sessionJson = new JsonObject();
        sessionJson.addProperty("id", sessionId);
        JsonArray workspacePaths = new JsonArray();
        workspacePaths.add(basePath != null ? basePath : "");
        sessionJson.add(WORKSPACE_PATHS_KEY, workspacePaths);
        sessionJson.addProperty("title", "Imported from AgentBridge");
        String ts = Instant.ofEpochMilli(epochMillis).toString();
        sessionJson.addProperty("createdAt", ts);
        sessionJson.addProperty("lastModifiedAt", ts);
        sessionJson.addProperty("schemaVersion", 1);
        return GSON.toJson(sessionJson);
    }

    /**
     * Classifies a profile ID into an export target category.
     *
     * <p>Returns one of: {@code "claude"}, {@code "codex"}, {@code "kiro"},
     * {@code "junie"}, {@code "opencode"}, {@code "copilot"}, or {@code "generic"}.</p>
     *
     * @param profileId the agent profile ID to classify
     * @return the export target category string
     */
    static String classifyExportTarget(@NotNull String profileId) {
        if (ClaudeCliClient.PROFILE_ID.equals(profileId)) return "claude";
        if (CodexAppServerClient.PROFILE_ID.equals(profileId)) return "codex";
        if (AgentProfileManager.KIRO_PROFILE_ID.equals(profileId)) return "kiro";
        if (AgentProfileManager.JUNIE_PROFILE_ID.equals(profileId)) return "junie";
        if (AgentProfileManager.OPENCODE_PROFILE_ID.equals(profileId)) return "opencode";
        if (profileId.startsWith(COPILOT_ID_PREFIX)) return COPILOT_ID_PREFIX;
        return "generic";
    }

    /**
     * Returns the path to a Copilot CLI session directory:
     * {@code ~/.copilot/session-state/<sessionId>/}.
     *
     * @param sessionId the session UUID
     * @return path to the session directory (may not exist on disk)
     */
    @NotNull
    static Path copilotSessionDir(@NotNull String sessionId) {
        return Path.of(System.getProperty(USER_HOME_PROPERTY, ""), ".copilot", SESSION_STATE_DIR, sessionId);
    }

    /**
     * Writes a minimal {@code workspace.yaml} that Copilot CLI requires to recognize a session.
     * Fields mirror what the CLI writes natively when creating a session.
     */
    private void writeWorkspaceYaml(@NotNull Path sessionDir, @NotNull String sessionId, @NotNull String basePath) throws IOException {
        String branch = UNKNOWN_BRANCH;
        try {
            Path gitDir = Path.of(basePath, ".git");
            if (Files.isDirectory(gitDir)) {
                Path headFile = gitDir.resolve("HEAD");
                if (Files.exists(headFile)) {
                    String headContent = Files.readString(headFile, StandardCharsets.UTF_8);
                    branch = parseGitBranchFromHead(headContent);
                }
            }
        } catch (Exception e) {
            LOG.info("Could not read git info for workspace.yaml: " + e.getMessage());
        }

        String now = Instant.now().toString();
        String yaml = buildWorkspaceYamlContent(sessionId, basePath, branch, now);

        Files.writeString(sessionDir.resolve("workspace.yaml"), yaml, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    // ── Plan file transfer ──────────────────────────────────────────────────

    private static final String PLAN_FILE_NAME = "plan.md";

    /**
     * Finds the most recently modified plan.md across all known agent session directories.
     * Scans all locations regardless of which agent is active — the v2 store is the intermediary.
     */
    @Nullable
    private Path findLatestPlanFile(@Nullable String basePath) {
        List<Path> candidates = new ArrayList<>();
        String base = basePath != null ? basePath : "";

        // Copilot session directories (new location: ~/.copilot/session-state/)
        collectPlanFiles(Path.of(System.getProperty(USER_HOME_PROPERTY), ".copilot", SESSION_STATE_DIR), candidates);

        // Legacy: Copilot session directories (old location: .agent-work/copilot/session-state/)
        collectPlanFiles(Path.of(base, AGENT_WORK_DIR, COPILOT_ID_PREFIX, SESSION_STATE_DIR), candidates);

        // Plugin-managed session directories (Claude, Kiro, Junie, etc.)
        collectPlanFiles(Path.of(base, AGENT_WORK_DIR, SESSION_STATE_DIR), candidates);

        // Top-level fallback
        Path topLevel = Path.of(base, AGENT_WORK_DIR, PLAN_FILE_NAME);
        if (Files.isRegularFile(topLevel)) {
            candidates.add(topLevel);
        }

        // v2 sessions directory (may already have a plan from a previous switch)
        Path v2Plan = Path.of(base, AGENT_WORK_DIR, SESSIONS_DIR, PLAN_FILE_NAME);
        if (Files.isRegularFile(v2Plan)) {
            candidates.add(v2Plan);
        }

        return candidates.stream()
            .max(Comparator.comparingLong(p -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis();
                } catch (IOException e) {
                    return 0L;
                }
            }))
            .orElse(null);
    }

    private static void collectPlanFiles(@NotNull Path sessionsDir, @NotNull List<Path> out) {
        if (!Files.isDirectory(sessionsDir)) return;
        try (var dirs = Files.list(sessionsDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path plan = dir.resolve(PLAN_FILE_NAME);
                if (Files.isRegularFile(plan)) {
                    out.add(plan);
                }
            });
        } catch (IOException e) {
            // Best-effort — skip unreadable directories
        }
    }

    /**
     * Copies the plan file to the v2 sessions directory as the universal intermediary.
     * Per-agent exporters pull from here when placing plan.md in their session dirs.
     */
    private void savePlanToV2Store(@NotNull Path planFile, @Nullable String basePath) {
        String base = basePath != null ? basePath : "";
        try {
            Path v2Dir = Path.of(base, AGENT_WORK_DIR, SESSIONS_DIR);
            if (Files.isDirectory(v2Dir)) {
                Files.copy(planFile, v2Dir.resolve(PLAN_FILE_NAME),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                LOG.info("Saved plan.md to v2 store from: " + planFile);
            }
        } catch (IOException e) {
            LOG.warn("Failed to save plan.md to v2 store", e);
        }
    }

    /**
     * Copies plan.md from the v2 store into a target agent's session directory.
     * This is the import half of the plan transfer: v2 store → agent session.
     */
    private static void copyPlanFromV2Store(@NotNull String basePath, @NotNull Path targetDir) {
        Path v2Plan = Path.of(basePath, AGENT_WORK_DIR, SESSIONS_DIR, PLAN_FILE_NAME);
        if (!Files.isRegularFile(v2Plan)) return;
        try {
            Files.copy(v2Plan, targetDir.resolve(PLAN_FILE_NAME),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOG.info("Copied plan.md from v2 store to: " + targetDir.getFileName());
        } catch (IOException e) {
            LOG.warn("Failed to copy plan.md to agent session dir", e);
        }
    }

    /**
     * Exports v2 session messages to OpenCode's native SQLite format
     * and sets {@code resumeSessionId} so AcpClient sends it in the next {@code session/new}.
     */
    private void exportToOpenCode(@NotNull List<EntryData> entries, @Nullable String basePath) {
        Path dbPath = OpenCodeClientExporter.defaultDbPath();
        String projectDir = basePath != null ? basePath : "";
        int limit = new GenericSettings(AgentProfileManager.OPENCODE_PROFILE_ID, project)
            .getContextHistoryLimit(OpenCodeClientConfigurable.DEFAULT_CONTEXT_LIMIT_CHARS);
        String sessionId = OpenCodeClientExporter.exportSession(entries, dbPath, projectDir, limit);
        if (sessionId != null) {
            new GenericSettings(AgentProfileManager.OPENCODE_PROFILE_ID, project)
                .setResumeSessionId(sessionId);
            LOG.info("Exported v2 session to OpenCode: " + sessionId);
        }
    }

    // ── v2 session reading ────────────────────────────────────────────────────

    @NotNull
    private List<EntryData> loadCurrentV2Session(@Nullable String basePath) {
        ConversationService.RecentEntriesResult result =
            ConversationService.getInstance(project).loadRecentEntries(basePath);
        return result != null ? result.entries() : List.of();
    }

    // ── Path helpers ──────────────────────────────────────────────────────────

// ── Claude resume ID file ─────────────────────────────────────────────────

    /**
     * Writes the Claude CLI resume session ID to a file on disk.
     *
     * <p>PropertiesComponent values set during {@code dispose()} are lost on plugin
     * hot-reload because IntelliJ flushes project state to disk <b>before</b> calling
     * dispose — any in-memory-only changes from dispose are discarded when the new
     * plugin instance reads the properties. This file-based approach survives hot-reload.</p>
     */
    private static void writeClaudeResumeIdFile(@Nullable String basePath, @NotNull String sessionId) {
        try {
            File dir = sessionsDir(basePath);
            //noinspection ResultOfMethodCallIgnored — best-effort
            dir.mkdirs();
            Path resumeFile = dir.toPath().resolve(CLAUDE_RESUME_ID_FILE);
            Files.writeString(resumeFile, sessionId, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            LOG.warn("Failed to write Claude resume ID file", e);
        }
    }

    /**
     * Clears the Claude CLI resume session ID from both in-memory storage
     * ({@link PropertiesComponent}) and the file-based fallback on disk.
     *
     * <p>Call this when the user explicitly requests a fresh session (no resume),
     * so that {@link com.github.catatafishen.agentbridge.agent.claude.ClaudeCliClient#createSession}
     * does not find a stale resume ID and pass {@code --resume} to the CLI.</p>
     */
    public void clearClaudeResumeState() {
        PropertiesComponent.getInstance(project)
            .unsetValue(ClaudeCliClient.PROFILE_ID + ".cliResumeSessionId");
        String basePath = project.getBasePath();
        if (basePath != null) {
            try {
                Path resumeFile = sessionsDir(basePath).toPath().resolve(CLAUDE_RESUME_ID_FILE);
                Files.deleteIfExists(resumeFile);
            } catch (IOException e) {
                LOG.warn("Failed to delete Claude resume ID file", e);
            }
        }
    }

    /**
     * Reads and consumes the Claude CLI resume session ID from the file on disk.
     * Returns {@code null} if the file does not exist or is empty.
     * The file is deleted after reading to ensure one-time consumption.
     */
    @Nullable
    public static String readAndConsumeClaudeResumeIdFile(@Nullable String basePath) {
        try {
            File dir = sessionsDir(basePath);
            Path resumeFile = dir.toPath().resolve(CLAUDE_RESUME_ID_FILE);
            if (!Files.exists(resumeFile)) return null;

            String id = Files.readString(resumeFile, StandardCharsets.UTF_8).trim();
            Files.deleteIfExists(resumeFile);
            return id.isEmpty() ? null : id;
        } catch (IOException e) {
            LOG.warn("Failed to read Claude resume ID file", e);
            return null;
        }
    }

    /**
     * Returns the Claude CLI projects directory for this project:
     * {@code ~/.claude/projects/<dash-separated-path>/}
     *
     * <p>Claude CLI uses the absolute project path with all forward slashes replaced by
     * dashes as the per-project directory name. For example, the project path
     * {@code /home/user/my-project} becomes {@code -home-user-my-project}.</p>
     *
     * @param basePath absolute project base path; {@code null} falls back to empty string
     * @return path to the project-specific Claude directory (may not yet exist on disk)
     */
    @NotNull
    private static Path claudeProjectDir(@Nullable String basePath) {
        String projectPath = basePath != null ? basePath : "";
        String dirName = projectPath.replace('/', '-');
        String home = System.getProperty(USER_HOME_PROPERTY, "");
        return Path.of(home, CLAUDE_HOME, CLAUDE_PROJECTS_DIR, dirName);
    }

    @NotNull
    private static File sessionsDir(@Nullable String basePath) {
        return ExportUtils.sessionsDir(basePath);
    }

    // ── Disposable ────────────────────────────────────────────────────────────

    @Override
    public void dispose() {
        // Nothing to dispose — all work is on pooled threads
    }
}
