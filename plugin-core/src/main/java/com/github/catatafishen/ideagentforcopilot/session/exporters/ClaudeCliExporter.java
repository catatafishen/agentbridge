package com.github.catatafishen.ideagentforcopilot.session.exporters;

import com.github.catatafishen.ideagentforcopilot.session.exporters.AnthropicClientExporter.AnthropicMessage;
import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
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
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int DEFAULT_MAX_TOKEN_ESTIMATE = 20_000;

    private ClaudeCliExporter() {
    }

    /**
     * Exports v2 messages to a Claude CLI session JSONL file.
     *
     * @param sessionId the UUID that identifies this Claude session
     * @param cwd       the project working directory
     */
    public static void exportToFile(
        @NotNull List<SessionMessage> messages,
        @NotNull Path targetPath,
        @NotNull String sessionId,
        @NotNull String cwd) throws IOException {

        List<SessionMessage> budgeted = AnthropicClientExporter.applyTokenBudget(
            messages, DEFAULT_MAX_TOKEN_ESTIMATE);
        List<AnthropicMessage> anthropicMessages = AnthropicClientExporter.toAnthropicMessages(budgeted);
        anthropicMessages = AnthropicClientExporter.ensureUserFirst(anthropicMessages);

        StringBuilder sb = new StringBuilder();
        Instant now = Instant.now();
        String parentUuid = null;

        sb.append(queueEvent("enqueue", sessionId, now)).append('\n');
        sb.append(queueEvent("dequeue", sessionId, now)).append('\n');

        for (AnthropicMessage msg : anthropicMessages) {
            String uuid = UUID.randomUUID().toString();
            sb.append(messageEvent(msg, uuid, parentUuid, sessionId, cwd, now)).append('\n');
            parentUuid = uuid;
        }

        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(targetPath, sb.toString(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        LOG.info("Exported v2 session to Claude CLI format: " + targetPath);
    }

    @NotNull
    private static String queueEvent(
        @NotNull String operation,
        @NotNull String sessionId,
        @NotNull Instant timestamp) {

        JsonObject event = new JsonObject();
        event.addProperty("type", "queue-operation");
        event.addProperty("operation", operation);
        event.addProperty("timestamp", timestamp.toString());
        event.addProperty("sessionId", sessionId);
        return GSON.toJson(event);
    }

    @NotNull
    private static String messageEvent(
        @NotNull AnthropicMessage msg,
        @NotNull String uuid,
        @Nullable String parentUuid,
        @NotNull String sessionId,
        @NotNull String cwd,
        @NotNull Instant timestamp) {

        JsonObject event = new JsonObject();

        if ("user".equals(msg.role)) {
            event.addProperty("type", "user");
            event.addProperty("promptId", UUID.randomUUID().toString());
            event.addProperty("permissionMode", "bypassPermissions");
            event.addProperty("userType", "external");
            event.addProperty("entrypoint", "sdk-cli");
        } else {
            event.addProperty("type", "assistant");
            event.addProperty("requestId", UUID.randomUUID().toString());
            event.addProperty("userType", "external");
            event.addProperty("entrypoint", "sdk-cli");
        }

        if (parentUuid != null) {
            event.addProperty("parentUuid", parentUuid);
        } else {
            event.add("parentUuid", null);
        }
        event.addProperty("isSidechain", false);

        JsonObject messagePayload = new JsonObject();
        messagePayload.addProperty("role", msg.role);
        JsonArray contentArray = new JsonArray();
        msg.contentBlocks.forEach(contentArray::add);
        messagePayload.add("content", contentArray);
        event.add("message", messagePayload);

        event.addProperty("uuid", uuid);
        event.addProperty("timestamp", timestamp.toString());
        event.addProperty("cwd", cwd);
        event.addProperty("sessionId", sessionId);
        event.addProperty("version", "1");

        return GSON.toJson(event);
    }
}
