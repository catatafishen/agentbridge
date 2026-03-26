package com.github.catatafishen.ideagentforcopilot.session;

import com.github.catatafishen.ideagentforcopilot.agent.claude.ClaudeCliClient;
import com.github.catatafishen.ideagentforcopilot.agent.codex.CodexAppServerClient;
import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
import com.github.catatafishen.ideagentforcopilot.session.exporters.AnthropicMessageExporter;
import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
import com.github.catatafishen.ideagentforcopilot.session.v2.SessionStoreV2;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Project service that orchestrates cross-client session migration when the active
 * agent changes.
 *
 * <p>When {@link #onAgentSwitch(String, String)} is called (always on a pooled thread),
 * the current v2 JSONL session is read and, where possible, exported to the new agent's
 * native session format so the conversation context carries over.</p>
 *
 * <p>Currently supported targets:
 * <ul>
 *   <li>{@value ClaudeCliClient#PROFILE_ID} — exports to
 *       {@code ~/.claude/projects/<sha1>/} as a new {@code .jsonl} file and persists
 *       the session ID via {@link PropertiesComponent}</li>
 *   <li>All other profiles — no-op (ACP clients resume natively, Codex does not
 *       support session injection)</li>
 * </ul>
 * </p>
 */
@Service(Service.Level.PROJECT)
public final class SessionSwitchService implements Disposable {

    private static final Logger LOG = Logger.getInstance(SessionSwitchService.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final String SESSIONS_DIR = "sessions";
    private static final String CLAUDE_HOME = ".claude";
    private static final String CLAUDE_PROJECTS_DIR = "projects";

    private final Project project;
    private final SessionStoreV2 sessionStore = new SessionStoreV2();

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
     * Called when the active agent is switched. Runs the export logic on a pooled thread.
     *
     * @param fromProfileId profile ID of the previously active agent
     * @param toProfileId   profile ID of the newly active agent
     */
    public void onAgentSwitch(@NotNull String fromProfileId, @NotNull String toProfileId) {
        if (fromProfileId.equals(toProfileId)) return;
        ApplicationManager.getApplication().executeOnPooledThread(
            () -> doExport(fromProfileId, toProfileId));
    }

    // ── Export logic ──────────────────────────────────────────────────────────

    private void doExport(@NotNull String fromProfileId, @NotNull String toProfileId) {
        String basePath = project.getBasePath();

        List<SessionMessage> messages = loadCurrentV2Session(basePath);
        if (messages == null || messages.isEmpty()) {
            LOG.info("No v2 session found to migrate from " + fromProfileId + " to " + toProfileId);
            return;
        }

        switch (toProfileId) {
            case ClaudeCliClient.PROFILE_ID -> exportToClaudeCli(messages, basePath);
            case CodexAppServerClient.PROFILE_ID ->
                LOG.info("Codex does not support session import; starting fresh thread");
            default ->
                // ACP-based clients (Copilot, Junie, Kiro, OpenCode) resume natively
                LOG.info("ACP client will resume its own session natively");
        }
    }

    // ── Claude CLI export ─────────────────────────────────────────────────────

    private void exportToClaudeCli(@NotNull List<SessionMessage> messages, @Nullable String basePath) {
        try {
            Path claudeDir = claudeProjectDir(basePath);
            //noinspection ResultOfMethodCallIgnored — best-effort
            claudeDir.toFile().mkdirs();

            String newSessionId = UUID.randomUUID().toString();
            Path targetFile = claudeDir.resolve(newSessionId + ".jsonl");

            AnthropicMessageExporter.exportToFile(messages, targetFile);

            PropertiesComponent.getInstance(project)
                .setValue(ClaudeCliClient.PROFILE_ID + ".cliResumeSessionId", newSessionId);

            LOG.info("Exported v2 session to Claude CLI: " + newSessionId);

        } catch (IOException e) {
            LOG.warn("Failed to export v2 session to Claude CLI", e);
        } catch (Exception e) {
            LOG.warn("Unexpected error exporting v2 session to Claude CLI", e);
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
            if (sessionId.isEmpty()) return null;

            File sessionsDir = sessionsDir(basePath);
            File jsonlFile = new File(sessionsDir, sessionId + ".jsonl");
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
        String home = System.getProperty("user.home", "");
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
