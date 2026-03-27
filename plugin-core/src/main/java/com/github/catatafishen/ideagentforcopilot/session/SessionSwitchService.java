package com.github.catatafishen.ideagentforcopilot.session;

import com.github.catatafishen.ideagentforcopilot.agent.claude.ClaudeCliClient;
import com.github.catatafishen.ideagentforcopilot.agent.codex.CodexAppServerClient;
import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
import com.github.catatafishen.ideagentforcopilot.services.AgentProfileManager;
import com.github.catatafishen.ideagentforcopilot.services.GenericSettings;
import com.github.catatafishen.ideagentforcopilot.session.exporters.AnthropicClientExporter;
import com.github.catatafishen.ideagentforcopilot.session.exporters.CodexClientExporter;
import com.github.catatafishen.ideagentforcopilot.session.exporters.CopilotClientExporter;
import com.github.catatafishen.ideagentforcopilot.session.exporters.OpenCodeClientExporter;
import com.github.catatafishen.ideagentforcopilot.session.importers.AnthropicClientImporter;
import com.github.catatafishen.ideagentforcopilot.session.importers.CodexClientImporter;
import com.github.catatafishen.ideagentforcopilot.session.importers.CopilotClientImporter;
import com.github.catatafishen.ideagentforcopilot.session.importers.OpenCodeClientImporter;
import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
import com.github.catatafishen.ideagentforcopilot.session.v2.SessionStoreV2;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
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
 *   <li>Copilot CLI — {@code <project>/.agent-work/copilot/session-state/<uuid>/events.jsonl}</li>
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
 *   <li>Copilot CLI — writes to {@code <project>/.agent-work/copilot/session-state/<uuid>/events.jsonl} + sets resumeSessionId</li>
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
    private static final String KIRO_HOME = ".kiro";
    private static final String KIRO_SESSIONS_DIR = "sessions";
    private static final String JUNIE_HOME = ".junie";
    private static final String JUNIE_SESSIONS_DIR = "sessions";
    private static final String USER_HOME_PROPERTY = "user.home";
    private static final String JSONL_EXT = ".jsonl";
    private static final String WORKSPACE_PATHS_KEY = "workspacePaths";
    private static final String COPILOT_ID_PREFIX = "copilot";
    private static final String LOG_PRE_IMPORTED = "Pre-imported ";

    private final Project project;
    private final SessionStoreV2 sessionStore = new SessionStoreV2();
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
        } catch (TimeoutException e) {
            LOG.warn("Timed out waiting for session export (" + timeoutMs + " ms) — resumeSessionId may be stale");
        } catch (Exception e) {
            LOG.warn("Error waiting for session export", e);
        }
    }

    // ── Export logic ──────────────────────────────────────────────────────────

    private void doExport(@NotNull String fromProfileId, @NotNull String toProfileId) {
        String basePath = project.getBasePath();

        // Step 1: Import from the previous client in case the user ran it directly outside
        // the plugin (best-effort; failures are warned and ignored).
        importFromPreviousClient(fromProfileId, basePath);

        // Step 2: Load v2 session (may have been updated by step 1).
        List<SessionMessage> messages = loadCurrentV2Session(basePath);
        if (messages == null || messages.isEmpty()) {
            LOG.info("No v2 session found to migrate from " + fromProfileId + " to " + toProfileId);
            return;
        }

        // Step 3: Export to the new client's native format.
        switch (toProfileId) {
            case ClaudeCliClient.PROFILE_ID -> exportToClaudeCli(messages, basePath);
            case CodexAppServerClient.PROFILE_ID -> exportToCodex(messages, basePath);
            default -> {
                if (toProfileId.equals(AgentProfileManager.KIRO_PROFILE_ID)) {
                    exportToKiro(messages, basePath, toProfileId);
                } else if (toProfileId.equals(AgentProfileManager.JUNIE_PROFILE_ID)) {
                    exportToJunie(messages, basePath, toProfileId);
                } else if (toProfileId.equals(AgentProfileManager.OPENCODE_PROFILE_ID)) {
                    exportToOpenCode(messages, basePath);
                } else if (toProfileId.startsWith(COPILOT_ID_PREFIX)) {
                    exportToCopilot(messages, basePath, toProfileId);
                } else {
                    LOG.info("ACP client '" + toProfileId
                        + "' — no native export format; will resume via resumeSessionId");
                }
            }
        }
    }

    // ── Import from previous client ───────────────────────────────────────────

    /**
     * Attempts to import the previous client's latest native session into v2 storage.
     * This handles the case where the user ran the client directly outside the plugin.
     * All failures are best-effort: warned and ignored.
     *
     * @param fromProfileId profile ID of the previously active agent
     * @param basePath      project base path (may be null)
     */
    private void importFromPreviousClient(@NotNull String fromProfileId, @Nullable String basePath) {
        try {
            if (fromProfileId.equals(ClaudeCliClient.PROFILE_ID)) {
                importFromClaudeCli(basePath);
            } else if (fromProfileId.equals(CodexAppServerClient.PROFILE_ID)) {
                importFromCodex(basePath);
            } else if (fromProfileId.equals(AgentProfileManager.KIRO_PROFILE_ID)) {
                importFromKiro(basePath);
            } else if (fromProfileId.equals(AgentProfileManager.JUNIE_PROFILE_ID)) {
                importFromJunie(basePath);
            } else if (fromProfileId.equals(AgentProfileManager.COPILOT_PROFILE_ID)) {
                importFromCopilotCli(basePath);
            } else if (fromProfileId.equals(AgentProfileManager.OPENCODE_PROFILE_ID)) {
                importFromOpenCode(basePath);
            } else {
                LOG.info("No native import path for profile '" + fromProfileId + "'; skipping pre-import");
            }
        } catch (Exception e) {
            LOG.warn("Unexpected error during pre-import from '" + fromProfileId + "'", e);
        }
    }

    /**
     * Imports the most-recent Claude CLI {@code .jsonl} file if it has more messages than v2.
     */
    private void importFromClaudeCli(@Nullable String basePath) {
        try {
            Path claudeDir = claudeProjectDir(basePath);
            if (!claudeDir.toFile().isDirectory()) return;

            List<SessionMessage> currentV2 = loadCurrentV2Session(basePath);
            int currentCount = currentV2 != null ? currentV2.size() : 0;

            try (var stream = Files.list(claudeDir)) {
                stream.filter(p -> p.toString().endsWith(JSONL_EXT))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .ifPresent(mostRecent -> {
                        try {
                            List<SessionMessage> imported = AnthropicClientImporter.importFile(mostRecent);
                            if (imported.size() > currentCount) {
                                String sessionId = sessionStore.getCurrentSessionId(basePath);
                                writeV2Session(basePath, sessionId, imported);
                                LOG.info(LOG_PRE_IMPORTED + imported.size()
                                    + " messages from Claude CLI: " + mostRecent.getFileName());
                            }
                        } catch (IOException e) {
                            LOG.warn("Failed to import Claude CLI session from " + mostRecent, e);
                        }
                    });
            }
        } catch (IOException e) {
            LOG.warn("Failed to list Claude CLI project dir for import", e);
        }
    }

    /**
     * Imports from Kiro's local session storage ({@code ~/.kiro/sessions/}).
     * Delegates to {@link #importFromAcpLocalSessions(String, Path, String)}.
     */
    private void importFromKiro(@Nullable String basePath) {
        Path dir = Path.of(System.getProperty(USER_HOME_PROPERTY), KIRO_HOME, KIRO_SESSIONS_DIR);
        importFromAcpLocalSessions(basePath, dir, "Kiro");
    }

    /**
     * Imports from Junie's local session storage ({@code ~/.junie/sessions/}).
     * Delegates to {@link #importFromAcpLocalSessions(String, Path, String)}.
     */
    private void importFromJunie(@Nullable String basePath) {
        Path dir = Path.of(System.getProperty(USER_HOME_PROPERTY), JUNIE_HOME, JUNIE_SESSIONS_DIR);
        importFromAcpLocalSessions(basePath, dir, "Junie");
    }

    /**
     * Imports from an ACP client's local session storage (session.json + messages.jsonl).
     * Used for both Kiro ({@code ~/.kiro/sessions/}) and Junie ({@code ~/.junie/sessions/}).
     */
    private void importFromAcpLocalSessions(@Nullable String basePath, @NotNull Path sessionsBaseDir, @NotNull String clientName) {
        try {
            if (!sessionsBaseDir.toFile().isDirectory()) return;

            String projectPath = basePath != null ? basePath : "";
            List<SessionMessage> currentV2 = loadCurrentV2Session(basePath);
            int currentCount = currentV2 != null ? currentV2.size() : 0;

            try (var stream = Files.list(sessionsBaseDir)) {
                stream.filter(Files::isDirectory)
                    .forEach(sessionDir -> processAcpSessionDir(sessionDir, basePath, projectPath, currentCount, clientName));
            }
        } catch (IOException e) {
            LOG.warn("Failed to list " + clientName + " sessions dir for import", e);
        }
    }

    /**
     * Processes a single ACP session directory: checks if it belongs to the current project
     * and, if so, imports its messages if they outnumber the current v2 session.
     */
    private void processAcpSessionDir(
        @NotNull Path sessionDir,
        @Nullable String basePath,
        @NotNull String projectPath,
        int currentCount,
        @NotNull String clientName) {
        try {
            Path sessionJsonPath = sessionDir.resolve("session.json");
            if (!sessionJsonPath.toFile().exists()) return;

            String sessionJsonContent = Files.readString(sessionJsonPath, StandardCharsets.UTF_8);
            JsonObject sessionJson = JsonParser.parseString(sessionJsonContent).getAsJsonObject();

            if (!sessionJson.has(WORKSPACE_PATHS_KEY)) return;
            JsonArray workspacePaths = sessionJson.getAsJsonArray(WORKSPACE_PATHS_KEY);
            if (!containsPath(workspacePaths, projectPath)) return;

            Path messagesPath = sessionDir.resolve("messages.jsonl");
            if (!messagesPath.toFile().exists()) return;

            List<SessionMessage> imported = AnthropicClientImporter.importFile(messagesPath);
            if (imported.size() > currentCount) {
                String sessionId = sessionStore.getCurrentSessionId(basePath);
                writeV2Session(basePath, sessionId, imported);
                LOG.info(LOG_PRE_IMPORTED + imported.size()
                    + " messages from " + clientName + " session: " + sessionDir.getFileName());
            }
        } catch (Exception e) {
            LOG.warn("Failed to read " + clientName + " session in " + sessionDir, e);
        }
    }

    /**
     * Returns true if {@code paths} JSON array contains the given path string.
     */
    private static boolean containsPath(@NotNull JsonArray paths, @NotNull String path) {
        for (var el : paths) {
            if (path.equals(el.getAsString())) return true;
        }
        return false;
    }

    /**
     * Imports the most recent Codex thread from {@code ~/.codex/codex.db} and its rollout JSONL
     * if it has more messages than the current v2 session.
     */
    private void importFromCodex(@Nullable String basePath) {
        Path dbPath = CodexClientImporter.defaultDbPath();
        List<SessionMessage> imported = CodexClientImporter.importLatestThread(dbPath);
        if (imported.isEmpty()) {
            LOG.info("No Codex session to import");
            return;
        }

        String sessionId = sessionStore.getCurrentSessionId(basePath);
        if (sessionId == null) return;

        List<SessionMessage> current = loadCurrentV2Session(basePath);
        if (imported.size() > (current != null ? current.size() : 0)) {
            writeV2Session(basePath, sessionId, imported);
            LOG.info(LOG_PRE_IMPORTED + imported.size() + " messages from Codex");
        }
    }

    /**
     * Imports the most recent OpenCode session from {@code ~/.local/share/opencode/opencode.db}
     * matching the current project directory, if it has more messages than the current v2 session.
     */
    private void importFromOpenCode(@Nullable String basePath) {
        Path dbPath = OpenCodeClientImporter.defaultDbPath();
        List<SessionMessage> imported = OpenCodeClientImporter.importLatestSession(dbPath, basePath != null ? basePath : "");
        if (imported.isEmpty()) {
            LOG.info("No OpenCode session to import");
            return;
        }

        String sessionId = sessionStore.getCurrentSessionId(basePath);
        if (sessionId == null) return;

        List<SessionMessage> current = loadCurrentV2Session(basePath);
        if (imported.size() > (current != null ? current.size() : 0)) {
            writeV2Session(basePath, sessionId, imported);
            LOG.info(LOG_PRE_IMPORTED + imported.size() + " messages from OpenCode");
        }
    }

    /**
     * Imports from Copilot CLI session state ({@code <basePath>/.agent-work/copilot/session-state/}).
     * Finds the most-recent {@code events.jsonl} and imports it if it has more messages than v2.
     */
    private void importFromCopilotCli(@Nullable String basePath) {
        try {
            String base = basePath != null ? basePath : "";
            Path copilotSessionsDir = Path.of(base, ".agent-work", COPILOT_ID_PREFIX, "session-state");
            if (!copilotSessionsDir.toFile().isDirectory()) return;

            List<SessionMessage> currentV2 = loadCurrentV2Session(basePath);
            int currentCount = currentV2 != null ? currentV2.size() : 0;

            // Find the most recently modified events.jsonl across session subdirs.
            try (var stream = Files.walk(copilotSessionsDir, 2)) {
                stream.filter(p -> p.getFileName() != null && p.getFileName().toString().equals("events.jsonl"))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .ifPresent(eventsFile -> {
                        try {
                            List<SessionMessage> imported = CopilotClientImporter.importFile(eventsFile);
                            if (imported.size() > currentCount) {
                                String sessionId = sessionStore.getCurrentSessionId(basePath);
                                writeV2Session(basePath, sessionId, imported);
                                LOG.info(LOG_PRE_IMPORTED + imported.size()
                                    + " messages from Copilot CLI: " + eventsFile);
                            }
                        } catch (IOException e) {
                            LOG.warn("Failed to import Copilot CLI session from " + eventsFile, e);
                        }
                    });
            }
        } catch (IOException e) {
            LOG.warn("Failed to search Copilot CLI session-state dir for import", e);
        }
    }

    // ── Claude CLI export ─────────────────────────────────────────────────────

    private void exportToClaudeCli(@NotNull List<SessionMessage> messages, @Nullable String basePath) {
        try {
            Path claudeDir = claudeProjectDir(basePath);
            //noinspection ResultOfMethodCallIgnored — best-effort
            claudeDir.toFile().mkdirs();

            String newSessionId = UUID.randomUUID().toString();
            Path targetFile = claudeDir.resolve(newSessionId + JSONL_EXT);

            AnthropicClientExporter.exportToFile(messages, targetFile);

            PropertiesComponent.getInstance(project)
                .setValue(ClaudeCliClient.PROFILE_ID + ".cliResumeSessionId", newSessionId);

            LOG.info("Exported v2 session to Claude CLI: " + newSessionId);

        } catch (IOException e) {
            LOG.warn("Failed to export v2 session to Claude CLI", e);
        } catch (Exception e) {
            LOG.warn("Unexpected error exporting v2 session to Claude CLI", e);
        }
    }

    // ── ACP local session export (Kiro / Junie) ─────────────────────────────

    /**
     * Exports v2 session messages to a Kiro local session directory.
     * Delegates to {@link #exportToAcpLocalSession}.
     */
    private void exportToKiro(
        @NotNull List<SessionMessage> messages,
        @Nullable String basePath,
        @NotNull String toProfileId) {
        Path dir = Path.of(System.getProperty(USER_HOME_PROPERTY), KIRO_HOME, KIRO_SESSIONS_DIR);
        exportToAcpLocalSession(messages, basePath, toProfileId, dir, "Kiro");
    }

    /**
     * Exports v2 session messages to a Junie local session directory.
     * Delegates to {@link #exportToAcpLocalSession}.
     */
    private void exportToJunie(
        @NotNull List<SessionMessage> messages,
        @Nullable String basePath,
        @NotNull String toProfileId) {
        Path dir = Path.of(System.getProperty(USER_HOME_PROPERTY), JUNIE_HOME, JUNIE_SESSIONS_DIR);
        exportToAcpLocalSession(messages, basePath, toProfileId, dir, "Junie");
    }

    /**
     * Exports v2 session messages to an ACP client's local session directory
     * and sets {@code resumeSessionId} so AcpClient sends it on next {@code session/new}.
     *
     * @param messages        messages to export
     * @param basePath        project base path (may be null)
     * @param toProfileId     profile ID of the target ACP client
     * @param sessionsBaseDir base sessions directory (e.g. {@code ~/.kiro/sessions/})
     * @param clientName      human-readable client name for log messages
     */
    private void exportToAcpLocalSession(
        @NotNull List<SessionMessage> messages,
        @Nullable String basePath,
        @NotNull String toProfileId,
        @NotNull Path sessionsBaseDir,
        @NotNull String clientName) {
        try {
            String newSessionId = UUID.randomUUID().toString();
            Path sessionDir = sessionsBaseDir.resolve(newSessionId);
            Files.createDirectories(sessionDir);

            // Write session.json
            JsonObject sessionJson = new JsonObject();
            sessionJson.addProperty("id", newSessionId);
            JsonArray workspacePaths = new JsonArray();
            workspacePaths.add(basePath != null ? basePath : "");
            sessionJson.add(WORKSPACE_PATHS_KEY, workspacePaths);
            sessionJson.addProperty("title", "Imported from AgentBridge");
            long now = System.currentTimeMillis();
            sessionJson.addProperty("createdAt", Instant.ofEpochMilli(now).toString());
            sessionJson.addProperty("lastModifiedAt", Instant.ofEpochMilli(now).toString());
            sessionJson.addProperty("schemaVersion", 1);
            Files.writeString(
                sessionDir.resolve("session.json"),
                GSON.toJson(sessionJson),
                StandardCharsets.UTF_8);

            // Write messages.jsonl via AnthropicMessageExporter
            AnthropicClientExporter.exportToFile(messages, sessionDir.resolve("messages.jsonl"));

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
    private void exportToCodex(@NotNull List<SessionMessage> messages, @Nullable String basePath) {
        Path sessionsDir = CodexClientExporter.defaultSessionsDir();
        Path dbPath = CodexClientExporter.defaultDbPath();
        String threadId = CodexClientExporter.exportSession(messages, sessionsDir, dbPath);
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
     * <p>Copilot CLI stores sessions under {@code .agent-work/copilot/session-state/<uuid>/events.jsonl}.
     * We create a new UUID session folder, write the events there, and persist the UUID
     * as the resume target — mirroring what {@link #exportToAcpLocalSession} does for Kiro/Junie.</p>
     */
    private void exportToCopilot(
        @NotNull List<SessionMessage> messages,
        @Nullable String basePath,
        @NotNull String toProfileId) {
        try {
            String base = basePath != null ? basePath : "";
            String newSessionId = UUID.randomUUID().toString();
            Path sessionDir = Path.of(base, ".agent-work", "copilot", "session-state", newSessionId);
            Files.createDirectories(sessionDir);

            Path eventsFile = sessionDir.resolve("events.jsonl");
            CopilotClientExporter.exportToFile(messages, eventsFile);

            new GenericSettings(toProfileId, project).setResumeSessionId(newSessionId);

            LOG.info("Exported v2 session to Copilot CLI: " + newSessionId);
        } catch (IOException e) {
            LOG.warn("Failed to export v2 session to Copilot CLI", e);
        }
    }

    // ── OpenCode export ───────────────────────────────────────────────────────

    /**
     * Exports v2 session messages to OpenCode's native SQLite format.
     */
    private void exportToOpenCode(@NotNull List<SessionMessage> messages, @Nullable String basePath) {
        Path dbPath = OpenCodeClientExporter.defaultDbPath();
        String projectDir = basePath != null ? basePath : "";
        String sessionId = OpenCodeClientExporter.exportSession(messages, dbPath, projectDir);
        if (sessionId != null) {
            LOG.info("Exported v2 session to OpenCode: " + sessionId);
        }
    }

    // ── v2 session write helper ───────────────────────────────────────────────

    /**
     * Writes a list of {@link SessionMessage}s to the v2 JSONL file for the given session ID,
     * overwriting any existing content.
     *
     * @param basePath  project base path (may be null)
     * @param sessionId session UUID
     * @param messages  messages to write
     */
    private void writeV2Session(
        @Nullable String basePath,
        @NotNull String sessionId,
        @NotNull List<SessionMessage> messages) {
        try {
            File dir = sessionsDir(basePath);
            //noinspection ResultOfMethodCallIgnored — best-effort
            dir.mkdirs();
            File jsonlFile = new File(dir, sessionId + JSONL_EXT);
            StringBuilder sb = new StringBuilder();
            for (SessionMessage msg : messages) {
                sb.append(GSON.toJson(msg)).append('\n');
            }
            Files.writeString(jsonlFile.toPath(), sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            LOG.warn("Failed to write v2 session for sessionId=" + sessionId, e);
        }
    }

    // ── v2 session reading ────────────────────────────────────────────────────

    /**
     * Loads the current v2 JSONL session messages from disk.
     * Returns {@code null} if no session exists or it cannot be read.
     */
    @Nullable
    private List<SessionMessage> loadCurrentV2Session(@Nullable String basePath) {
        try {
            String sessionId = sessionStore.getCurrentSessionId(basePath);

            File sessionsDir = sessionsDir(basePath);
            File jsonlFile = new File(sessionsDir, sessionId + JSONL_EXT);
            if (!jsonlFile.exists() || jsonlFile.length() < 2) return null;

            String content = Files.readString(jsonlFile.toPath(), StandardCharsets.UTF_8);
            return parseJsonlMessages(content);
        } catch (IOException e) {
            LOG.warn("Could not read current v2 session", e);
            return null;
        }
    }

    @NotNull
    private List<SessionMessage> parseJsonlMessages(@NotNull String content) {
        List<SessionMessage> messages = new ArrayList<>();
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                SessionMessage msg = GSON.fromJson(line, SessionMessage.class);
                if (msg != null) messages.add(msg);
            } catch (Exception e) {
                LOG.warn("Skipping malformed JSONL line in v2 session: " + line, e);
            }
        }
        return messages;
    }

    // ── Path helpers ──────────────────────────────────────────────────────────

    /**
     * Returns the Claude CLI projects directory for this project:
     * {@code ~/.claude/projects/<sha1-of-absolute-project-path>/}
     *
     * <p>The SHA-1 hash is computed over the project's absolute base path encoded as
     * UTF-8 bytes and formatted as 40 lower-case hex characters.</p>
     *
     * @param basePath absolute project base path; {@code null} falls back to empty string
     * @return path to the project-specific Claude directory (may not yet exist on disk)
     */
    @NotNull
    private static Path claudeProjectDir(@Nullable String basePath) {
        String projectPath = basePath != null ? basePath : "";
        String hash = sha1Hex(projectPath);
        String home = System.getProperty(USER_HOME_PROPERTY, "");
        return Path.of(home, CLAUDE_HOME, CLAUDE_PROJECTS_DIR, hash);
    }

    /**
     * Computes the SHA-1 hex digest of a string (UTF-8 encoded).
     *
     * @param input the string to hash
     * @return 40-character lowercase hex string
     */
    @NotNull
    private static String sha1Hex(@NotNull String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is guaranteed to be present in every JVM
            throw new IllegalStateException("SHA-1 algorithm not available", e);
        }
    }

    @NotNull
    private static File sessionsDir(@Nullable String basePath) {
        String base = basePath != null ? basePath : "";
        return new File(base + "/.agent-work/" + SESSIONS_DIR);
    }

    // ── Disposable ────────────────────────────────────────────────────────────

    @Override
    public void dispose() {
        // Nothing to dispose — all work is on pooled threads
    }
}
