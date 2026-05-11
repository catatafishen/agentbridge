package com.github.catatafishen.agentbridge.services.hooks;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Notifies the user when plugin-updated hook files conflict with their local edits.
 *
 * <p>A conflict means: the file on disk differs from the hash recorded when the plugin
 * last wrote it, so the user has customized it. The plugin has a newer bundled version
 * but cannot safely overwrite without asking.
 *
 * <p>Must be triggered via {@link #notify} from a background thread — it schedules
 * the balloon on the EDT via {@code invokeLater}.
 */
public final class HookUpdateNotifier {

    private static final Logger LOG = Logger.getInstance(HookUpdateNotifier.class);

    /**
     * A hook file whose on-disk content differs from the last bundled version.
     *
     * @param relativePath   path relative to the hooks directory (e.g. {@code scripts/run-command-abuse.sh})
     * @param bundledContent UTF-8 text content of the bundled version (script resource or generated JSON)
     * @param diskPath       absolute path of the file on disk
     * @param newHash        SHA-256 of {@code bundledContent}
     */
    public record Conflict(
        @NotNull String relativePath,
        @NotNull String bundledContent,
        @NotNull Path diskPath,
        @NotNull String newHash
    ) {
    }

    private HookUpdateNotifier() {
    }

    public static void notify(@NotNull Project project,
                              @NotNull List<Conflict> conflicts,
                              @NotNull Map<String, String> hashes,
                              @NotNull Path hooksDir) {
        if (conflicts.isEmpty()) return;

        ApplicationManager.getApplication().invokeLater(() -> {
            int n = conflicts.size();
            String content = n == 1
                ? "Hook file <b>" + conflicts.getFirst().relativePath() + "</b> has a plugin update but was modified locally."
                : n + " hook files have plugin updates but were modified locally.";

            Notification notification = PlatformApiCompat.createNotification(
                "AgentBridge hook update", content, NotificationType.WARNING);

            notification.addAction(buildAction("Update all (overwrite my edits)", e -> {
                overwriteAll(conflicts, hashes, hooksDir);
                notification.expire();
            }));

            notification.addAction(buildAction("Keep all (skip update)", e -> {
                keepAll(conflicts, hashes, hooksDir);
                notification.expire();
            }));

            notification.setImportant(true);
            notification.notify(project);
        });
    }

    private static @NotNull AnAction buildAction(@NotNull String text,
                                                 @NotNull java.util.function.Consumer<AnActionEvent> action) {
        return new DumbAwareAction(text) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                action.accept(e);
            }
        };
    }

    private static void overwriteAll(@NotNull List<Conflict> conflicts,
                                     @NotNull Map<String, String> hashes,
                                     @NotNull Path hooksDir) {
        for (Conflict conflict : conflicts) {
            try {
                Files.writeString(conflict.diskPath(), conflict.bundledContent(), java.nio.charset.StandardCharsets.UTF_8);
                if (conflict.relativePath().endsWith(".sh") && !conflict.diskPath().toFile().setExecutable(true)) {
                    LOG.warn("Failed to set executable on: " + conflict.relativePath());
                }
                hashes.put(conflict.relativePath(), conflict.newHash());
                LOG.info("Overwrote modified hook file with plugin version: " + conflict.relativePath());
            } catch (IOException e) {
                LOG.warn("Failed to overwrite hook file " + conflict.relativePath() + ": " + e.getMessage());
            }
        }
        HookHashRegistry.save(hooksDir, hashes);
    }

    private static void keepAll(@NotNull List<Conflict> conflicts,
                                @NotNull Map<String, String> hashes,
                                @NotNull Path hooksDir) {
        // Record the current disk hash so we stop asking until the plugin ships yet another version.
        for (Conflict conflict : conflicts) {
            String diskHash = HookHashRegistry.computeFileHash(conflict.diskPath());
            if (diskHash != null) {
                hashes.put(conflict.relativePath(), diskHash);
            }
        }
        HookHashRegistry.save(hooksDir, hashes);
        LOG.info("User chose to keep their edits for " + conflicts.size() + " hook file(s)");
    }
}
