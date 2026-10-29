package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.services.InFlightMcpToolRegistry;
import com.github.catatafishen.agentbridge.ui.BroadcastChatPanel;
import com.github.catatafishen.agentbridge.ui.ButtonSpecParser;
import com.github.catatafishen.agentbridge.ui.ButtonStrip;
import com.github.catatafishen.agentbridge.ui.ButtonStyle;
import com.github.catatafishen.agentbridge.ui.PendingButtons;
import com.github.catatafishen.agentbridge.ui.RegistryToolCatalog;
import com.github.catatafishen.agentbridge.ui.ReplyButton;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.AppIcon;
import com.intellij.ui.SystemNotifications;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Asks the user a question and waits for a response.
 */
public final class PromptUserTool extends InfrastructureTool {

    private static final String PARAM_QUESTION = "question";
    private static final String PARAM_WAIT = "wait";
    private static final String PARAM_REPLIES = ButtonSpecParser.KEY_REPLIES;
    private static final String PARAM_ACTIONS = ButtonSpecParser.KEY_ACTIONS;
    private static final long RESPONSE_TIMEOUT_MS = 120_000L;
    private static final String NOTIFICATION_GROUP_ID = "AgentBridge Notifications";

    public PromptUserTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "prompt_user";
    }

    @Override
    public @NotNull String displayName() {
        return "Prompt User";
    }

    @Override
    public @NotNull String description() {
        return "Ask the user a question, or end your turn with buttons.\n" +
            "Default: blocks until the user answers.\n" +
            "With wait=false: returns at once; buttons appear when your turn ends, so finish your turn.\n" +
            "`replies` buttons send text back to you.\n" +
            "`actions` buttons run a tool call you specify directly, with no LLM turn; " +
            "prefer them when the next step is a single tool call the user is likely to want next (e.g. push after a rebase).";
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
            Param.optional(PARAM_QUESTION, TYPE_STRING, "Question to show. Required unless wait=false."),
            Param.optional(PARAM_WAIT, TYPE_BOOLEAN, "Block for the answer (default) or return at once and end your turn.", true),
            Param.optional(PARAM_REPLIES, TYPE_ARRAY, "Buttons that send text back to you. Items: label, optional text (sent instead of label), style, dismiss (wait=false only: just closes the buttons; when blocking, every reply answers). Strings accepted."),
            Param.optional(PARAM_ACTIONS, TYPE_ARRAY, "Buttons that run a tool directly. Items: label, tool, arguments, style.")
        );
        JsonObject props = schema.getAsJsonObject("properties");
        props.getAsJsonObject(PARAM_REPLIES).add("items", buttonItemSchema(false));
        props.getAsJsonObject(PARAM_REPLIES).addProperty("maxItems", ButtonSpecParser.MAX_BUTTONS);
        props.getAsJsonObject(PARAM_ACTIONS).add("items", buttonItemSchema(true));
        props.getAsJsonObject(PARAM_ACTIONS).addProperty("maxItems", ButtonSpecParser.MAX_BUTTONS);
        return schema;
    }

    private static @NotNull JsonObject buttonItemSchema(boolean action) {
        JsonObject item = new JsonObject();
        item.addProperty("type", TYPE_OBJECT);
        JsonObject props = new JsonObject();
        JsonObject label = new JsonObject();
        label.addProperty("type", TYPE_STRING);
        label.addProperty("maxLength", ButtonSpecParser.MAX_LABEL_LENGTH);
        props.add("label", label);
        JsonObject style = new JsonObject();
        style.addProperty("type", TYPE_STRING);
        JsonArray styles = new JsonArray();
        for (String s : ButtonStyle.Companion.getNAMES()) styles.add(s);
        style.add("enum", styles);
        props.add("style", style);
        if (action) {
            JsonObject tool = new JsonObject();
            tool.addProperty("type", TYPE_STRING);
            props.add("tool", tool);
            JsonObject arguments = new JsonObject();
            arguments.addProperty("type", TYPE_OBJECT);
            props.add("arguments", arguments);
        } else {
            JsonObject text = new JsonObject();
            text.addProperty("type", TYPE_STRING);
            props.add("text", text);
            JsonObject dismiss = new JsonObject();
            dismiss.addProperty("type", TYPE_BOOLEAN);
            props.add("dismiss", dismiss);
        }
        item.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("label");
        if (action) required.add("tool");
        item.add("required", required);
        return item;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String question = args.has(PARAM_QUESTION) && !args.get(PARAM_QUESTION).isJsonNull()
            ? args.get(PARAM_QUESTION).getAsString().trim() : "";
        boolean explicitWait = args.has(PARAM_WAIT) && !args.get(PARAM_WAIT).isJsonNull();
        boolean wait = !explicitWait || args.get(PARAM_WAIT).getAsBoolean();
        if (wait && question.isEmpty()) {
            return "Error: question is required unless wait=false";
        }

        ButtonSpecParser.Result parsed = ButtonSpecParser.INSTANCE.parse(args, new RegistryToolCatalog(project));
        if (!parsed.getOk()) {
            return "Error: invalid buttons:\n- " + String.join("\n- ", parsed.getErrors());
        }
        ButtonStrip strip = new ButtonStrip(question.isEmpty() ? null : question, parsed.getReplies(), parsed.getActions());

        if (!wait) {
            if (strip.isEmpty())
                return "Error: provide at least one button (replies/actions) when wait=false.";
            PendingButtons.Companion.getInstance(project).set(strip);
            return "Buttons registered (" + strip.getActions().size() + " actions, " + strip.getReplies().size()
                + " replies). They appear when your turn ends. End your turn now and do not repeat them in text.";
        }

        List<String> options = new ArrayList<>();
        for (ReplyButton r : strip.getReplies()) options.add(r.getText());

        BroadcastChatPanel panel = BroadcastChatPanel.getInstance(project);
        if (panel == null) {
            return askViaDialog(question, options);
        }

        notifyIfUnfocused(question);

        CompletableFuture<String> responseFuture = new CompletableFuture<>();
        // Deadline is shared mutable state — written by the EDT extend handler, read by this pooled thread.
        // System.currentTimeMillis() is used (not nanoTime) so JS and Java agree on the absolute deadline.
        final long[] deadlineMs = {System.currentTimeMillis() + RESPONSE_TIMEOUT_MS};
        String reqId = UUID.randomUUID().toString();

        // Register so that an agent-process crash can unblock the waiter immediately rather than
        // leaving this pooled thread stuck for the full 2-minute timeout (see issue #749).
        InFlightMcpToolRegistry registry = InFlightMcpToolRegistry.getInstance(project);
        registry.register(reqId, responseFuture);

        EdtUtil.invokeLater(() -> showAskUserCompat(panel, reqId, question, strip, deadlineMs, responseFuture));

        try {
            return awaitWithExtensibleDeadline(responseFuture, deadlineMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: ask-user request interrupted";
        } catch (ExecutionException e) {
            return "Error: failed to read user response";
        } finally {
            registry.unregister(reqId);
        }
    }

    /**
     * Calls the ask-user panel API through reflection so Java compilation does not depend on
     * javac understanding the Kotlin function-type signature. This has been observed to drift
     * between the source model and the Java compiler stub view during incremental builds.
     */
    private static void showAskUserCompat(
        @NotNull com.github.catatafishen.agentbridge.ui.ChatPanelApi panel,
        @NotNull String reqId,
        @NotNull String question,
        @NotNull ButtonStrip strip,
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
                "showAskUserButtons",
                String.class,
                String.class,
                ButtonStrip.class,
                long.class,
                kotlin.jvm.functions.Function1.class,
                kotlin.jvm.functions.Function0.class,
                kotlin.jvm.functions.Function0.class
            ).invoke(panel, reqId, question, strip, deadlineMs[0], onRespond, onExtend, onSuperseded);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke ask-user panel API", e);
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
            } catch (CancellationException ce) {
                // future.completeExceptionally(new CancellationException(reason)) surfaces
                // here (not wrapped in ExecutionException). CompletableFuture wraps the original
                // in a new "get"-message CancellationException; the real reason is in getCause().
                // InFlightMcpToolRegistry uses this to release prompt_user waiters when the
                // agent process stops.
                Throwable cause = ce.getCause();
                String reason = cause != null ? cause.getMessage() : ce.getMessage();
                return "Error: " + (reason == null || reason.isBlank() ? "ask-user request cancelled" : reason);
            } catch (ExecutionException ee) {
                if (ee.getCause() instanceof CancellationException ce) {
                    String reason = ce.getMessage();
                    return "Error: " + (reason == null || reason.isBlank() ? "ask-user request cancelled" : reason);
                }
                throw ee;
            }
        }
    }

    private void notifyIfUnfocused(@NotNull String question) {
        String title = "Agent Needs Your Input";
        String content = question.length() > 80 ? question.substring(0, 80) + "…" : question;
        // Notification.notify and SystemNotifications are background-safe; AppIcon and
        // frame.isActive() require EDT — run the whole block there to keep it consistent.
        new Notification(NOTIFICATION_GROUP_ID, title, content, NotificationType.INFORMATION)
            .notify(project);
        SystemNotifications.getInstance().notify(NOTIFICATION_GROUP_ID, title, content);
        EdtUtil.invokeLater(() -> {
            var frame = WindowManager.getInstance().getFrame(project);
            if (frame == null || frame.isActive()) return;
            AppIcon.getInstance().requestAttention(project, false);
        });
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
