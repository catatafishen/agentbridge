package com.github.catatafishen.agentbridge.memory.wal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * JSONL write-ahead log for all memory write operations.
 * Each line is a JSON object with operation type, timestamp, and payload.
 *
 * <p>File location: the project's configured semantic-memory directory, under
 * {@code wal/write_log.jsonl}
 *
 * <p><b>Attribution:</b> WAL pattern adapted from MemPalace's
 * {@code mcp_server.py _wal_log()} (MIT License).
 */
public final class WriteAheadLog {

    private static final Logger LOG = Logger.getInstance(WriteAheadLog.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path logFile;

    public WriteAheadLog(@NotNull Path walDirectory) {
        this.logFile = walDirectory.resolve("write_log.jsonl");
    }

    /**
     * Initialize the WAL directory and file.
     */
    public void initialize() throws IOException {
        Files.createDirectories(logFile.getParent());
        if (!Files.exists(logFile)) {
            Files.createFile(logFile);
        }
    }

    /**
     * Append a write operation entry to the log.
     *
     * @param operation operation type (e.g., "add_drawer", "kg_add", "diary_write")
     * @param drawerId  the drawer or entity ID involved
     * @param payload   additional data (wing, room, content summary, etc.)
     */
    public void log(@NotNull String operation, @NotNull String drawerId, @NotNull JsonObject payload) {
        JsonObject entry = new JsonObject();
        entry.addProperty("timestamp", Instant.now().toString());
        entry.addProperty("operation", operation);
        entry.addProperty("id", drawerId);
        entry.add("payload", payload);

        String line = GSON.toJson(entry) + "\n";
        try {
            Files.writeString(logFile, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warn("Failed to write WAL entry for " + operation + " " + drawerId, e);
        }
    }

    /**
     * Returns the path to the WAL file (for diagnostics / status reporting).
     */
    public @NotNull Path getLogFile() {
        return logFile;
    }
}
