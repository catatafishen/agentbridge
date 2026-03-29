package com.github.catatafishen.ideagentforcopilot.session.exporters;

import com.github.catatafishen.ideagentforcopilot.session.exporters.AnthropicClientExporter.AnthropicMessage;
import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Exports v2 {@link SessionMessage} list into Claude CLI's native session JSONL format.
 *
 * <p>Claude CLI stores sessions as event lines — NOT as bare Anthropic API messages.
 * Each line is a JSON object with {@code type}, {@code uuid}, {@code parentUuid},
 * {@code message} (the Anthropic payload), {@code timestamp}, {@code sessionId}, etc.
 * This is fundamentally different from the Anthropic API format that
 * {@link AnthropicClientExporter} writes (which is correct for Kiro/Junie).</p>
 */
public final class ClaudeCliExporter {

    private static final Logger LOG = Logger.getInstance(ClaudeCliExporter.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final String FIELD_SESSION_ID = "sessionId";
    private static final String FIELD_VERSION = "version";

    /**
     * Fallback version used in exported events when the real CLI version cannot be
     * detected from existing native session files.  Must be a version the CLI
     * recognises when loading sessions for {@code --resume}.
     */
    private static final String CLAUDE_CLI_VERSION_FALLBACK = "2.1.78";

    private ClaudeCliExporter() {
    }

    public static void exportToFile(
        @NotNull List<SessionMessage> messages,
        @NotNull Path targetPath,
        @NotNull String sessionId,
        @NotNull String cwd) throws IOException {

        List<AnthropicMessage> anthropicMessages = AnthropicClientExporter.toAnthropicMessages(messages);
        anthropicMessages = AnthropicClientExporter.ensureUserFirst(anthropicMessages);

        String cliVersion = detectCliVersion(targetPath.getParent());
        String gitBranch = detectGitBranch(cwd);

        // Use the earliest message timestamp for queue events, falling back to now
        Instant sessionStart = anthropicMessages.isEmpty() || anthropicMessages.getFirst().createdAt == 0
            ? Instant.now()
            : Instant.ofEpochMilli(anthropicMessages.getFirst().createdAt);

        var ctx = new EventContext(sessionId, cwd, cliVersion, gitBranch);
        StringBuilder sb = new StringBuilder();
        String parentUuid = null;
        // Track the UUID of the most recent assistant message that contained tool_use blocks,
        // so we can set sourceToolAssistantUUID on the subsequent user message.
        String lastToolAssistantUuid = null;

        sb.append(queueEvent("enqueue", sessionId, sessionStart)).append('\n');
        sb.append(queueEvent("dequeue", sessionId, sessionStart)).append('\n');

        for (AnthropicMessage msg : anthropicMessages) {
            String uuid = UUID.randomUUID().toString();
            Instant msgTimestamp = msg.createdAt > 0
                ? Instant.ofEpochMilli(msg.createdAt)
                : sessionStart;

            boolean hasToolUse = "assistant".equals(msg.role) && msg.contentBlocks.stream()
                .anyMatch(b -> b.has("type") && "tool_use".equals(b.get("type").getAsString()));
            boolean hasToolResult = "user".equals(msg.role) && msg.contentBlocks.stream()
                .anyMatch(b -> b.has("type") && "tool_result".equals(b.get("type").getAsString()));

            String sourceAssistantUuid = hasToolResult ? lastToolAssistantUuid : null;
            sb.append(messageEvent(msg, uuid, parentUuid, sourceAssistantUuid, ctx, msgTimestamp)).append('\n');
            parentUuid = uuid;
            if (hasToolUse) {
                lastToolAssistantUuid = uuid;
            } else if (hasToolResult) {
                lastToolAssistantUuid = null; // reset after tool results are consumed
            }
        }

        // Append last-prompt so Claude CLI can identify the conversation head for resume.
        // Uses the LAST user message text, matching native CLI behavior — the CLI writes
        // last-prompt after each prompt cycle with the text of the prompt it just processed.
        String lastUserPromptText = extractLastUserPromptText(anthropicMessages);
        if (!lastUserPromptText.isEmpty()) {
            JsonObject lastPromptEvent = new JsonObject();
            lastPromptEvent.addProperty("type", "last-prompt");
            lastPromptEvent.addProperty("lastPrompt", lastUserPromptText);
            lastPromptEvent.addProperty(FIELD_SESSION_ID, sessionId);
            sb.append(GSON.toJson(lastPromptEvent)).append('\n');
        }

        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(targetPath, sb.toString(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        LOG.info("Exported v2 session to Claude CLI format: " + targetPath
            + " (version=" + cliVersion + ", branch=" + gitBranch + ")");
    }

    // ------------------------------------------------------------------
    // Version & branch detection
    // ------------------------------------------------------------------

    /**
     * Detects the CLI version by reading the most recent native session file in the
     * same Claude projects directory.  Native sessions written by the CLI include a
     * {@code version} field on every event.
     *
     * @param claudeProjectDir the {@code ~/.claude/projects/<project>/} directory
     *                         (parent of the target file)
     * @return the detected version, or {@link #CLAUDE_CLI_VERSION_FALLBACK}
     */
    @NotNull
    private static String detectCliVersion(@Nullable Path claudeProjectDir) {
        if (claudeProjectDir == null || !Files.isDirectory(claudeProjectDir)) {
            return CLAUDE_CLI_VERSION_FALLBACK;
        }
        try (var stream = Files.list(claudeProjectDir)) {
            Path latest = stream
                .filter(p -> p.toString().endsWith(".jsonl"))
                .max(Comparator.comparing(ClaudeCliExporter::lastModifiedSafe))
                .orElse(null);
            if (latest == null) return CLAUDE_CLI_VERSION_FALLBACK;
            return extractVersionFromSessionFile(latest);
        } catch (Exception e) {
            LOG.debug("Could not detect Claude CLI version from project dir: " + e.getMessage());
        }
        return CLAUDE_CLI_VERSION_FALLBACK;
    }

    @NotNull
    private static FileTime lastModifiedSafe(@NotNull Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    /**
     * Reads the last few lines of a session file and extracts the CLI version string.
     */
    @NotNull
    private static String extractVersionFromSessionFile(@NotNull Path sessionFile) {
        try {
            List<String> allLines = Files.readAllLines(sessionFile, StandardCharsets.UTF_8);
            for (int i = allLines.size() - 1; i >= Math.max(0, allLines.size() - 10); i--) {
                String line = allLines.get(i).trim();
                if (line.isEmpty()) continue;
                JsonObject obj = GSON.fromJson(line, JsonObject.class);
                if (obj.has(FIELD_VERSION) && obj.get(FIELD_VERSION).isJsonPrimitive()) {
                    String ver = obj.get(FIELD_VERSION).getAsString();
                    if (ver.contains(".") && !ver.equals("1.0.0")) {
                        return ver;
                    }
                }
            }
        } catch (Exception e) {
            LOG.debug("Could not extract version from " + sessionFile + ": " + e.getMessage());
        }
        return CLAUDE_CLI_VERSION_FALLBACK;
    }

    @Nullable
    private static String detectGitBranch(@NotNull String cwd) {
        try {
            Path head = Path.of(cwd, ".git", "HEAD");
            if (!Files.exists(head)) return null;
            String content = Files.readString(head, StandardCharsets.UTF_8).trim();
            String prefix = "ref: refs/heads/";
            if (content.startsWith(prefix)) {
                return content.substring(prefix.length());
            }
            return content.substring(0, Math.min(12, content.length()));
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Message extraction
    // ------------------------------------------------------------------

    private static String extractLastUserPromptText(@NotNull List<AnthropicMessage> messages) {
        String lastText = "";
        for (AnthropicMessage msg : messages) {
            if (!"user".equals(msg.role)) continue;
            StringBuilder sb = new StringBuilder();
            for (JsonObject block : msg.contentBlocks) {
                if (block.has("text")) {
                    sb.append(block.get("text").getAsString());
                }
            }
            if (!sb.isEmpty()) {
                lastText = sb.toString();
            }
        }
        return lastText;
    }

    // ------------------------------------------------------------------
    // JSONL event builders
    // ------------------------------------------------------------------

    @NotNull
    private static String queueEvent(
        @NotNull String operation,
        @NotNull String sessionId,
        @NotNull Instant timestamp) {

        JsonObject event = new JsonObject();
        event.addProperty("type", "queue-operation");
        event.addProperty("operation", operation);
        event.addProperty("timestamp", timestamp.toString());
        event.addProperty(FIELD_SESSION_ID, sessionId);
        return GSON.toJson(event);
    }

    @NotNull
    private static String messageEvent(
        @NotNull AnthropicMessage msg,
        @NotNull String uuid,
        @Nullable String parentUuid,
        @Nullable String sourceToolAssistantUuid,
        @NotNull EventContext ctx,
        @NotNull Instant timestamp) {

        JsonObject event = new JsonObject();

        if ("user".equals(msg.role)) {
            event.addProperty("type", "user");
            event.addProperty("promptId", UUID.randomUUID().toString());
            event.addProperty("permissionMode", "bypassPermissions");
            event.addProperty("userType", "external");
            event.addProperty("entrypoint", "sdk-cli");
            // sourceToolAssistantUUID links tool_result blocks back to the assistant that
            // issued the corresponding tool_use.  Claude CLI uses this for local validation
            // of tool-use concurrency before calling the Anthropic API.
            if (sourceToolAssistantUuid != null) {
                event.addProperty("sourceToolAssistantUUID", sourceToolAssistantUuid);
            }
        } else {
            event.addProperty("type", "assistant");
            event.addProperty("requestId", UUID.randomUUID().toString());
            event.addProperty("userType", "external");
            event.addProperty("entrypoint", "sdk-cli");
        }

        // Root node must have "parentUuid": null explicitly — the CLI uses this to
        // identify the conversation root when building the message tree for --resume.
        // Requires serializeNulls() on the Gson instance; without it, JsonNull entries
        // are silently dropped and the CLI branches from the wrong message.
        if (parentUuid != null) {
            event.addProperty("parentUuid", parentUuid);
        } else {
            event.add("parentUuid", JsonNull.INSTANCE);
        }
        event.addProperty("isSidechain", false);

        JsonObject messagePayload = new JsonObject();
        messagePayload.addProperty("role", msg.role);
        JsonArray contentArray = new JsonArray();
        msg.contentBlocks.forEach(contentArray::add);
        messagePayload.add("content", contentArray);

        // Assistant messages must match the Anthropic API response structure that Claude CLI
        // expects when rebuilding conversation context during --resume.
        if ("assistant".equals(msg.role)) {
            messagePayload.addProperty("id", "msg_" + uuid.replace("-", "").substring(0, 24));
            messagePayload.addProperty("type", "message");
            messagePayload.addProperty("model", "claude-sonnet-4-6");

            // stop_reason must be "tool_use" when the message contains tool_use blocks,
            // "end_turn" otherwise.  Claude CLI uses this to determine conversation flow
            // when rebuilding context for --resume.
            boolean hasToolUse = msg.contentBlocks.stream()
                .anyMatch(b -> b.has("type") && "tool_use".equals(b.get("type").getAsString()));
            messagePayload.addProperty("stop_reason", hasToolUse ? "tool_use" : "end_turn");
            messagePayload.add("stop_sequence", JsonNull.INSTANCE);
        }

        event.add("message", messagePayload);

        event.addProperty("uuid", uuid);
        event.addProperty("timestamp", timestamp.toString());
        event.addProperty("cwd", ctx.cwd);
        event.addProperty(FIELD_SESSION_ID, ctx.sessionId);
        event.addProperty(FIELD_VERSION, ctx.cliVersion);
        if (ctx.gitBranch != null) {
            event.addProperty("gitBranch", ctx.gitBranch);
        }

        return GSON.toJson(event);
    }

    private record EventContext(
        @NotNull String sessionId,
        @NotNull String cwd,
        @NotNull String cliVersion,
        @Nullable String gitBranch) {
    }
}
