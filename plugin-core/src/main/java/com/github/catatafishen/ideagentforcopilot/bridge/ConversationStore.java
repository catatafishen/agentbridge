package com.github.catatafishen.ideagentforcopilot.bridge;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Handles all file I/O for conversation persistence.
 *
 * <p>Conversation turns are stored in {@code <projectBase>/.agent-work/conversation.json}.
 * When a session resets, the current file is archived to
 * {@code .agent-work/conversations/conversation-<timestamp>.json}.
 */
public final class ConversationStore {

    private static final String AGENT_WORK_DIR = ".agent-work";
    private static final String CONVERSATIONS_DIR = "conversations";
    private static final String CURRENT_FILE = "conversation.json";
    private static final String ARCHIVE_PREFIX = "conversation-";
    private static final String ARCHIVE_SUFFIX = ".json";
    private static final DateTimeFormatter ARCHIVE_TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");
    private static final int MIN_VALID_SIZE = 10;

    @NotNull
    public File conversationFile(@Nullable String basePath) {
        File dir = agentWorkDir(basePath);
        // mkdirs() result intentionally ignored — best-effort; downstream write will fail if dir missing
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, CURRENT_FILE);
    }

    @NotNull
    public File archivesDir(@Nullable String basePath) {
        return new File(agentWorkDir(basePath), CONVERSATIONS_DIR);
    }

    /**
     * Moves {@code conversation.json} to the archive directory with a timestamp suffix.
     * No-op if the file does not exist or is too small to be valid.
     */
    public void archive(@Nullable String basePath) {
        try {
            File src = conversationFile(basePath);
            if (!src.exists() || src.length() < MIN_VALID_SIZE) return;
            File dir = archivesDir(basePath);
            // mkdirs() result intentionally ignored — best-effort
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            String stamp = LocalDateTime.now().format(ARCHIVE_TIMESTAMP);
            File dest = new File(dir, ARCHIVE_PREFIX + stamp + ARCHIVE_SUFFIX);
            if (!src.renameTo(dest)) {
                // Fallback: copy + delete (Files.delete throws on failure, giving better diagnostics)
                Files.copy(src.toPath(), dest.toPath());
                Files.delete(src.toPath());
            }
        } catch (Exception ignored) { /* best-effort */ }
    }

    /**
     * Reads the current conversation file, falling back to the most-recently archived file.
     *
     * @return JSON string, or {@code null} if no valid file exists
     */
    @Nullable
    public String loadJson(@Nullable String basePath) {
        try {
            File primary = conversationFile(basePath);
            if (primary.exists() && primary.length() >= MIN_VALID_SIZE) {
                return Files.readString(primary.toPath(), StandardCharsets.UTF_8);
            }
            // Fallback: most-recent archive
            File dir = archivesDir(basePath);
            if (!dir.isDirectory()) return null;
            File[] archives = dir.listFiles(
                (d, name) -> name.startsWith(ARCHIVE_PREFIX) && name.endsWith(ARCHIVE_SUFFIX));
            if (archives == null || archives.length == 0) return null;
            File latest = archives[0];
            for (File f : archives) {
                if (f.getName().compareTo(latest.getName()) > 0) latest = f;
            }
            if (latest.length() < MIN_VALID_SIZE) return null;
            return Files.readString(latest.toPath(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return null;
        }
    }

    @NotNull
    private static File agentWorkDir(@Nullable String basePath) {
        return new File(basePath != null ? basePath : "", AGENT_WORK_DIR);
    }
}
