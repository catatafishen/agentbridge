package com.github.catatafishen.agentbridge.psi.graph;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.session.db.ConversationDatabase;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Indexes git commit history into the code graph database.
 * Stores commit metadata (hash, message, author, timestamp) and per-file change records.
 *
 * <p>Incremental: only fetches commits newer than the most recently indexed commit.
 * Uses IntelliJ's git4idea layer via {@link PlatformApiCompat#runIdeGitCommand}.
 */
@Service(Service.Level.PROJECT)
public final class GitCommitIndexer {

    private static final Logger LOG = Logger.getInstance(GitCommitIndexer.class);

    private static final int BATCH_SIZE = 500;
    private static final String RECORD_SEPARATOR = "\u001E";
    private static final String UNIT_SEPARATOR = "\u001F";

    private final Project project;

    @SuppressWarnings("unused") // IntelliJ service container
    public GitCommitIndexer(@NotNull Project project) {
        this.project = project;
    }

    public static GitCommitIndexer getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, GitCommitIndexer.class);
    }

    /**
     * Index all commits since the last indexed one. Safe to call from a background thread.
     * Returns the number of new commits indexed.
     */
    public int indexCommits(@Nullable ProgressIndicator indicator) {
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        if (!db.isReady()) {
            try {
                db.initialize();
            } catch (Exception e) {
                LOG.debug("ConversationDatabase not available: " + e.getMessage());
                return 0;
            }
        }

        String lastHash = getLastIndexedCommitHash(db);
        String gitOutput = fetchGitLog(lastHash);
        if (gitOutput == null || gitOutput.isBlank()) {
            LOG.debug("No new commits to index" + (lastHash != null ? " since " + lastHash : ""));
            return 0;
        }

        List<CommitRecord> records = parseGitLog(gitOutput);
        if (records.isEmpty()) return 0;

        if (indicator != null) {
            indicator.setText2("Indexing " + records.size() + " git commits…");
        }

        int indexed = insertCommits(db, records);
        LOG.info("Indexed " + indexed + " new git commits");
        return indexed;
    }

    /**
     * Gets the current branch name, or null if unavailable.
     */
    @Nullable
    private String getCurrentBranch() {
        String result = PlatformApiCompat.runIdeGitCommand(project,
            new String[]{"rev-parse", "--abbrev-ref", "HEAD"});
        if (result == null || result.startsWith("Error")) return null;
        return result.trim();
    }

    @Nullable
    private String getLastIndexedCommitHash(@NotNull ConversationDatabase db) {
        try {
            return db.withConnection(conn -> {
                try (var stmt = conn.prepareStatement(
                    "SELECT hash FROM graph_commits ORDER BY indexed_at DESC LIMIT 1");
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) return rs.getString(1);
                }
                return null;
            });
        } catch (SQLException e) {
            LOG.debug("No existing commits in graph_commits: " + e.getMessage());
            return null;
        }
    }

    /**
     * Fetches git log output with a custom format that's easy to parse.
     * Format per commit: hash{US}short_hash{US}author{US}email{US}timestamp{US}message{RS}file_changes
     * where file_changes use --name-status format (tab-separated type + path per line).
     */
    @Nullable
    private String fetchGitLog(@Nullable String sinceHash) {
        List<String> args = new ArrayList<>();
        args.add("log");
        args.add("--name-status");
        args.add("--format=" + RECORD_SEPARATOR
            + "%H" + UNIT_SEPARATOR
            + "%h" + UNIT_SEPARATOR
            + "%an" + UNIT_SEPARATOR
            + "%ae" + UNIT_SEPARATOR
            + "%aI" + UNIT_SEPARATOR
            + "%s");
        if (sinceHash != null) {
            args.add(sinceHash + "..HEAD");
        } else {
            args.add("-n");
            args.add("1000");
        }
        String result = PlatformApiCompat.runIdeGitCommand(project, args.toArray(String[]::new));
        if (result != null && result.startsWith("Error")) {
            LOG.warn("git log failed: " + result);
            return null;
        }
        return result;
    }

    static List<CommitRecord> parseGitLog(@NotNull String output) {
        List<CommitRecord> records = new ArrayList<>();
        String[] entries = output.split(RECORD_SEPARATOR);
        for (String entry : entries) {
            if (entry.isBlank()) continue;
            CommitRecord record = parseEntry(entry);
            if (record != null) records.add(record);
        }
        return records;
    }

    @Nullable
    private static CommitRecord parseEntry(@NotNull String entry) {
        String[] parts = entry.split("\n", 2);
        String header = parts[0].trim();
        String[] fields = header.split(UNIT_SEPARATOR);
        if (fields.length < 6) return null;

        String hash = fields[0].trim();
        String shortHash = fields[1].trim();
        String author = fields[2].trim();
        String email = fields[3].trim();
        String timestamp = fields[4].trim();
        String message = fields[5].trim();

        List<FileChange> files = parts.length > 1 ? parseFileChanges(parts[1]) : List.of();
        return new CommitRecord(hash, shortHash, author, email, timestamp, message, files);
    }

    @NotNull
    private static List<FileChange> parseFileChanges(@NotNull String body) {
        List<FileChange> files = new ArrayList<>();
        String[] lines = body.split("\n");
        for (String line : lines) {
            if (line.isBlank()) continue;
            String[] fileParts = line.split("\t", 2);
            if (fileParts.length < 2) continue;
            String changeType = fileParts[0].trim();
            String filePath = fileParts[1].trim();
            // Handle renames: "R100\told\tnew" — take the new path
            if (changeType.startsWith("R") && filePath.contains("\t")) {
                filePath = filePath.split("\t")[1].trim();
                changeType = "R";
            } else if (changeType.length() > 1) {
                changeType = changeType.substring(0, 1);
            }
            files.add(new FileChange(filePath, changeType));
        }
        return files;
    }

    /**
     * Inserts commit records and their file changes into the database.
     * Uses {@link ConversationDatabase#withConnection} for thread-safe access.
     */
    private int insertCommits(@NotNull ConversationDatabase db, @NotNull List<CommitRecord> records) {
        String branch = getCurrentBranch();
        long now = System.currentTimeMillis();

        try {
            Integer result = db.withConnection(conn -> {
                int count = 0;
                conn.setAutoCommit(false);
                try (PreparedStatement commitStmt = conn.prepareStatement("""
                    INSERT OR IGNORE INTO graph_commits
                    (hash, short_hash, message, author, author_email, timestamp, branch, indexed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """);
                     PreparedStatement fileStmt = conn.prepareStatement("""
                         INSERT OR IGNORE INTO graph_commit_files (commit_hash, file_path, change_type)
                         VALUES (?, ?, ?)
                         """)) {

                    // Loop-invariant parameters — set once; they persist across addBatch() calls.
                    commitStmt.setString(7, branch);
                    commitStmt.setLong(8, now);

                    for (CommitRecord entry : records) {
                        commitStmt.setString(1, entry.hash);
                        commitStmt.setString(2, entry.shortHash);
                        commitStmt.setString(3, entry.message);
                        commitStmt.setString(4, entry.author);
                        commitStmt.setString(5, entry.email);
                        commitStmt.setString(6, entry.timestamp);
                        commitStmt.addBatch();

                        for (FileChange file : entry.files) {
                            fileStmt.setString(1, entry.hash);
                            fileStmt.setString(2, file.path);
                            fileStmt.setString(3, file.changeType);
                            fileStmt.addBatch();
                        }

                        count++;
                        if (count % BATCH_SIZE == 0) {
                            commitStmt.executeBatch();
                            fileStmt.executeBatch();
                        }
                    }
                    commitStmt.executeBatch();
                    fileStmt.executeBatch();
                    conn.commit();
                } catch (SQLException e) {
                    try {
                        conn.rollback();
                    } catch (SQLException rollbackEx) {
                        LOG.warn("Rollback failed", rollbackEx);
                    }
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
                return count;
            });
            return result != null ? result : 0;
        } catch (SQLException e) {
            LOG.warn("Failed to insert commits: " + e.getMessage());
            return 0;
        }
    }

    record CommitRecord(
        String hash,
        String shortHash,
        String author,
        String email,
        String timestamp,
        String message,
        List<FileChange> files
    ) {
    }

    record FileChange(String path, String changeType) {
    }
}
