package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.ui.ChatConsolePanel;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.AppIcon;
import com.intellij.ui.SystemNotifications;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Asks the user a question and waits for a response.
 */
public final class AskUserTool extends InfrastructureTool {

    private static final String PARAM_QUESTION = "question";
    private static final String PARAM_OPTIONS = "options";
    private static final long RESPONSE_TIMEOUT_MS = 120_000L;
    private static final String NOTIFICATION_GROUP_ID = "AgentBridge Notifications";

    public AskUserTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "ask_user";
    }

    @Override
    public @NotNull String displayName() {
        return "Ask User";
    }

    @Override
    public @NotNull String description() {
        return "Ask the user a question and wait for their response. Blocks until the user replies (timeout: 120s). " +
            "Use options parameter to show quick-reply buttons.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.OTHER;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        JsonObject schema = schema(
            Param.required(PARAM_QUESTION, TYPE_STRING, "Question to ask the user"),
            Param.required(PARAM_OPTIONS, TYPE_ARRAY, "Reply options shown as quick-reply buttons")
        );
        addArrayItems(schema, PARAM_OPTIONS);
        return schema;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String question = args.has(PARAM_QUESTION) ? args.get(PARAM_QUESTION).getAsString().trim() : "";
        if (question.isEmpty()) {
            return "Error: question is required";
        }

        List<String> options = parseOptions(args);
        if (options.isEmpty()) {
            return "Error: at least one reply option is required";
        }

        ChatConsolePanel panel = ChatConsolePanel.Companion.getInstance(project);
        if (panel == null) {
            return askViaDialog(question, options);
        }

        notifyIfUnfocused(question);

        CompletableFuture<String> responseFuture = new CompletableFuture<>();
        // Deadline is shared mutable state — written by the EDT extend handler, read by this pooled thread.
        // System.currentTimeMillis() is used (not nanoTime) so JS and Java agree on the absolute deadline.
        final long[] deadlineMs = {System.currentTimeMillis() + RESPONSE_TIMEOUT_MS};
        String reqId = UUID.randomUUID().toString();

        EdtUtil.invokeLater(() -> showAskUserRequestCompat(panel, reqId, question, options, deadlineMs, responseFuture));

        try {
            return awaitWithExtensibleDeadline(responseFuture, deadlineMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: ask-user request interrupted";
        } catch (ExecutionException e) {
            return "Error: failed to read user response";
        } finally {
            EdtUtil.invokeLater(() -> panel.clearPendingAskUserRequest(reqId));
        }
    }

    /**
     * Calls the ask-user panel API through reflection so Java compilation does not depend on
     * javac understanding the Kotlin function-type signature. This has been observed to drift
     * between the source model and the Java compiler stub view during incremental builds.
     *
     * <p>We prefer the new 7-argument API (deadline + extend + supersede callbacks). If the
     * runtime class only exposes the older 4-argument ABI, we still show the prompt and keep
     * the backend waiter functional, but deadline extension/supersede notifications are not
     * available from that older contract.
     */
    private static void showAskUserRequestCompat(
        @NotNull ChatConsolePanel panel,
        @NotNull String reqId,
        @NotNull String question,
        @NotNull List<String> options,
        long @NotNull [] deadlineMs,
        @NotNull CompletableFuture<String> responseFuture
    ) {
        kotlin.jvm.functions.Function1<String, kotlin.Unit> onRespond = response -> {
            responseFuture.complete(response);
            return kotlin.Unit.INSTANCE;
        };
        kotlin.jvm.functions.Function0<Long> onExtend = () -> {
            long newDeadline = System.currentTimeMillis() + RESPONSE_TIMEOUT_MS;
            deadlineMs[0] = newDeadline;
            return newDeadline;
        };
        kotlin.jvm.functions.Function0<kotlin.Unit> onSuperseded = () -> {
            if (!responseFuture.isDone()) {
                responseFuture.complete("__cancelled__");
            }
            return kotlin.Unit.INSTANCE;
        };

        try {
            panel.getClass().getMethod(
                "showAskUserRequest",
                String.class,
                String.class,
                List.class,
                long.class,
                kotlin.jvm.functions.Function1.class,
                kotlin.jvm.functions.Function0.class,
                kotlin.jvm.functions.Function0.class
            ).invoke(panel, reqId, question, options, deadlineMs[0], onRespond, onExtend, onSuperseded);
            return;
        } catch (NoSuchMethodException ignored) {
            // Fall through to the older ABI below.
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke ask-user panel API", e);
        }

        try {
            panel.getClass().getMethod(
                "showAskUserRequest",
                String.class,
                String.class,
                List.class,
                kotlin.jvm.functions.Function1.class
            ).invoke(panel, reqId, question, options, onRespond);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke legacy ask-user panel API", e);
        }
    }

    /**
     * Wait for {@code future} to complete, retrying on TimeoutException so that deadline extensions
     * made by the user via the "I need more time" button are picked up by the wait loop.
     *
     * @param deadlineMs single-element array used as a writable shared cell; index 0 holds the
     *                   absolute epoch-millis deadline that the EDT extend handler updates.
     */
    private static @NotNull String awaitWithExtensibleDeadline(
        @NotNull CompletableFuture<String> future,
        long @NotNull [] deadlineMs
    ) throws InterruptedException, ExecutionException {
        while (true) {
            long remaining = deadlineMs[0] - System.currentTimeMillis();
            if (remaining <= 0) {
                return "Error: user response timed out";
            }
            try {
                String result = future.get(remaining, TimeUnit.MILLISECONDS);
                if ("__cancelled__".equals(result)) {
                    return "Error: ask-user request cancelled (superseded by another request)";
                }
                return result;
            } catch (TimeoutException ignored) {
                // Deadline may have been extended in flight — re-check the loop condition.
            }
        }
    }

    private @NotNull List<String> parseOptions(@NotNull JsonObject args) {
        JsonArray arr = args.has(PARAM_OPTIONS) && args.get(PARAM_OPTIONS).isJsonArray()
            ? args.getAsJsonArray(PARAM_OPTIONS) : new JsonArray();
        List<String> options = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonNull()) {
                String option = el.getAsString().trim();
                if (!option.isEmpty()) {
                    options.add(option);
                }
            }
        }
        return options;
    }

    private void notifyIfUnfocused(@NotNull String question) {
        var frame = WindowManager.getInstance().getFrame(project);
        if (frame == null || frame.isActive()) return;
        String title = "Agent Needs Your Input";
        String content = question.length() > 80 ? question.substring(0, 80) + "…" : question;
        new Notification(NOTIFICATION_GROUP_ID, title, content, NotificationType.INFORMATION)
            .notify(project);
        SystemNotifications.getInstance().notify(NOTIFICATION_GROUP_ID, title, content);
        AppIcon.getInstance().requestAttention(project, false);
    }

    private @NotNull String askViaDialog(@NotNull String question, @NotNull List<String> options) {
        CompletableFuture<String> response = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            StringBuilder message = new StringBuilder(question);
            if (!options.isEmpty()) {
                message.append("\n\nOptions:\n");
                for (String option : options) {
                    message.append("- ").append(option).append('\n');
                }
            }
            String answer = Messages.showInputDialog(
                project,
                message.toString(),
                displayName(),
                null,
                null,
                null
            );
            response.complete(answer != null ? answer.trim() : "");
        });

        try {
            String answer = response.get(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (answer.isEmpty()) {
                return "Error: user cancelled";
            }
            return answer;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: ask-user request interrupted";
        } catch (TimeoutException e) {
            return "Error: user response timed out";
        } catch (ExecutionException e) {
            return "Error: failed to read user response";
        }
    }
}
