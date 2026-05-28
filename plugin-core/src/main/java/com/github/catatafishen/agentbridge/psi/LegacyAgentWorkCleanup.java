package com.github.catatafishen.agentbridge.psi;

import com.github.catatafishen.agentbridge.session.exporters.ExportUtils;
import com.github.catatafishen.agentbridge.session.migration.V1ToV2Migrator;
import com.github.catatafishen.agentbridge.settings.AgentBridgeStorageSettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * One-shot cleanup of legacy paths under {@code .agent-work/} that the plugin used to
 * create but no longer needs.
 *
 * <p>Runs once per project open on a pooled thread. Operations are best-effort and never
 * throw — a missing or unwritable path is silently ignored.</p>
 *
 * <p><b>Migration ordering:</b> The cleanup invokes {@link V1ToV2Migrator#migrateIfNeeded(Project)}
 * synchronously before touching any V1 source files, so legacy chat history is migrated
 * into the v2 store even if the user opens the chat panel later (which would otherwise be
 * the only trigger via {@code ConversationPersistenceManager.restoreConversation}).</p>
 *
 * <p>What is cleaned up:</p>
 * <ul>
 *   <li>{@code .agent-work/session-state/} and {@code .agent-work/files/} — empty
 *       directories created by an older startup hook; nothing reads them.</li>
 *   <li>{@code .agent-work/usage-stats.jsonl} — write-only log; never read.</li>
 *   <li>{@code .agent-work/conversation.json} and {@code .agent-work/conversations/} —
 *       v1 chat history; deleted only after {@link V1ToV2Migrator} has run.</li>
 *   <li>{@code .agent-work/sessions/} — legacy v2 chat history. Moved into the configured
 *       storage root ({@link AgentBridgeStorageSettings#getProjectStorageDir(Project)}/sessions/)
 *       if and only if the destination is empty <b>and</b> every entry moved successfully;
 *       left in place otherwise so no data is lost.</li>
 * </ul>
 *
 * <p>What is deliberately left alone:</p>
 * <ul>
 *   <li>{@code .agent-work/copilot|opencode|junie|kiro/} — user-managed agent definitions.</li>
 *   <li>{@code .agent-work/<alias>.iml} — transient external-dir module files.</li>
 *   <li>{@code .agent-work/mcp-configs/} — Claude CLI MCP config dir.</li>
 *   <li>{@code .agent-work/<agent>/session-state/} — handled by
 *       {@code CopilotClient.migrateResumeSessionFromLegacyPath}.</li>
 * </ul>
 */
public final class LegacyAgentWorkCleanup {

    private static final Logger LOG = Logger.getInstance(LegacyAgentWorkCleanup.class);
    private static final String AGENT_WORK = ".agent-work";

    private LegacyAgentWorkCleanup() {
    }

    /** Schedules cleanup on a pooled thread. Safe to call on every project open. */
    public static void cleanupAsync(@NotNull Project project) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> cleanup(project));
    }

    static void cleanup(@NotNull Project project) {
        String basePath = project.getBasePath();
        if (basePath == null) return;
        Path agentWork = Path.of(basePath, AGENT_WORK);
        if (!Files.isDirectory(agentWork)) return;

        // Empty placeholder dirs created by the old PsiBridgeStartup.createAgentWorkspace().
        deleteIfEmptyDir(agentWork.resolve("session-state"));
        deleteIfEmptyDir(agentWork.resolve("files"));

        // Write-only file with no readers.
        deleteIfExists(agentWork.resolve("usage-stats.jsonl"));

        // V1 → V2 migration must run BEFORE we delete the V1 source files. Idempotent:
        // skips if the v2 sessions-index.json already exists.
        runV1ToV2Migration(project);

        // V1 chat history — safe to delete now that the migrator has had a chance to read it.
        deleteIfExists(agentWork.resolve("conversation.json"));
        deleteDirectoryIfExists(agentWork.resolve("conversations"));

        // Move legacy v2 sessions/ into configured storage root if destination is empty.
        moveLegacySessionsIntoConfiguredStorage(project, agentWork);

        // If .agent-work/ ended up empty, remove it too.
        deleteIfEmptyDir(agentWork);
    }

    private static void runV1ToV2Migration(@NotNull Project project) {
        try {
            V1ToV2Migrator.migrateIfNeeded(project);
        } catch (Exception e) {
            // Migration failed — keep V1 files in place so the user can recover them.
            // ConversationPersistenceManager will retry on chat panel open.
            LOG.warn("V1 → V2 chat history migration failed; legacy files retained for retry", e);
        }
    }

    private static void moveLegacySessionsIntoConfiguredStorage(@NotNull Project project,
                                                                @NotNull Path agentWork) {
        Path legacySessions = agentWork.resolve("sessions");
        if (!Files.isDirectory(legacySessions)) return;

        Path configuredSessions;
        try {
            configuredSessions = ExportUtils.sessionsDir(project).toPath();
        } catch (Exception e) {
            LOG.warn("Could not resolve configured storage dir; leaving legacy sessions in place", e);
            return;
        }

        // Same path (project-local mode pointing back at .agent-work) — nothing to move.
        if (legacySessions.toAbsolutePath().normalize()
            .equals(configuredSessions.toAbsolutePath().normalize())) {
            return;
        }

        if (Files.exists(configuredSessions) && !isEmptyDir(configuredSessions)) {
            // Destination already has data; leave legacy as-is to avoid overwriting.
            LOG.info("Legacy " + legacySessions + " kept because " + configuredSessions
                + " already has data");
            return;
        }

        try {
            // Create the target directory itself (not just its parent) so per-entry moves
            // have somewhere to land. createDirectories is a no-op if it already exists.
            Files.createDirectories(configuredSessions);
        } catch (IOException e) {
            LOG.warn("Could not create destination " + configuredSessions
                + "; leaving legacy sessions in place", e);
            return;
        }

        // Move each entry, tracking whether every move succeeded. We only remove the
        // legacy directory if every entry was migrated — otherwise a partial-failure
        // would erase data on the next call.
        boolean allMoved;
        try (Stream<Path> entries = Files.list(legacySessions)) {
            allMoved = entries.allMatch(src ->
                moveQuietly(src, configuredSessions.resolve(src.getFileName())));
        } catch (IOException e) {
            LOG.warn("Could not list " + legacySessions + "; leaving legacy in place", e);
            return;
        }

        if (!allMoved) {
            LOG.warn("Some entries under " + legacySessions + " could not be moved to "
                + configuredSessions + "; leaving legacy directory in place for retry");
            return;
        }

        // Every entry moved — safe to remove the now-empty legacy dir and leave a tombstone.
        deleteDirectoryIfExists(legacySessions);
        writeTombstone(legacySessions, configuredSessions);
        LOG.info("Migrated legacy " + legacySessions + " -> " + configuredSessions);
    }

    private static void writeTombstone(@NotNull Path legacy, @NotNull Path moved) {
        // Only write if the parent (.agent-work) still exists.
        Path parent = legacy.getParent();
        if (parent == null || !Files.isDirectory(parent)) return;
        Path note = parent.resolve("MOVED-sessions.txt");
        try {
            Files.writeString(note,
                "Legacy " + legacy.getFileName() + "/ moved to:\n" + moved + "\n",
                StandardCharsets.UTF_8);
        } catch (IOException ignored) { /* best-effort */ }
    }

    /** @return true if the move succeeded, false otherwise. Never throws. */
    private static boolean moveQuietly(@NotNull Path src, @NotNull Path dst) {
        try {
            Files.move(src, dst);
            return true;
        } catch (IOException e) {
            LOG.warn("Could not move " + src + " -> " + dst, e);
            return false;
        }
    }

    private static boolean isEmptyDir(@NotNull Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteIfEmptyDir(@NotNull Path dir) {
        if (!Files.isDirectory(dir)) return;
        if (!isEmptyDir(dir)) return;
        try {
            Files.delete(dir);
        } catch (IOException ignored) { /* best-effort */ }
    }

    private static void deleteIfExists(@NotNull Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) { /* best-effort */ }
    }

    private static void deleteDirectoryIfExists(@NotNull Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(f -> {
                    if (!f.delete()) {
                        LOG.debug("Could not delete during legacy cleanup: " + f);
                    }
                });
        } catch (IOException ignored) { /* best-effort */ }
    }
}
