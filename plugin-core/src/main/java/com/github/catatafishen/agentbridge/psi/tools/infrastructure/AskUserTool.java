package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.ui.ChatConsolePanel;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindowManager;
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
    private static final int RESPONSE_TIMEOUT_SECONDS = 120;

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
        JsonObject schema = schema(new Object[][]{
            {PARAM_QUESTION, TYPE_STRING, "Question to ask the user"},
            {PARAM_OPTIONS, TYPE_ARRAY, "Reply options shown as quick-reply buttons"}
        }, PARAM_QUESTION, PARAM_OPTIONS);
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
        String reqId = UUID.randomUUID().toString();
        EdtUtil.invokeLater(() ->
            panel.showAskUserRequest(reqId, question, options, response -> {
                responseFuture.complete(response);
                return kotlin.Unit.INSTANCE;
            })
        );

        try {
            return responseFuture.get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return "Error: user response timed out";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: ask-user request interrupted";
        } catch (ExecutionException e) {
            return "Error: failed to read user response";
        } finally {
            EdtUtil.invokeLater(() -> panel.clearPendingAskUserRequest(reqId));
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
        ToolWindowManager.getInstance(project)
            .notifyByBalloon("AgentBridge", MessageType.INFO, "<b>" + title + "</b><br>" + content);
        SystemNotifications.getInstance().notify("AgentBridge Notifications", title, content);
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
            String answer = response.get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
