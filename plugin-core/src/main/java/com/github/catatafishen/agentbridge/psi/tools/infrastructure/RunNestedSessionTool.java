package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.github.catatafishen.agentbridge.client.AbstractClient;
import com.github.catatafishen.agentbridge.client.ClientRegistry;
import com.github.catatafishen.agentbridge.client.nested.NestedSessionChatView;
import com.github.catatafishen.agentbridge.client.nested.NestedSessionPolicy;
import com.github.catatafishen.agentbridge.client.nested.NestedSessionRunner;
import com.github.catatafishen.agentbridge.psi.tools.McpRequestDeadline;
import com.github.catatafishen.agentbridge.services.ActiveAgentManager;
import com.github.catatafishen.agentbridge.ui.BroadcastChatPanel;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Runs a prompt in a nested agent session of its own and returns the agent's final answer.
 *
 * <p>The session is a second client of the chosen agent, started next to the chat's own and closed
 * when the prompt is done. It is not meant to ask questions: the prompt tells the agent so, and the
 * agent's own permission requests are denied. It shows up in the chat as a sub-agent entry.
 *
 * <p>Only one nested session runs per project at a time. That also stops a nested session from
 * starting another one, which would otherwise multiply agent processes without bound.
 */
public final class RunNestedSessionTool extends InfrastructureTool {

    private static final String PARAM_PROMPT = "prompt";
    private static final String PARAM_AGENT = "agent";
    private static final String PARAM_MODEL = "model";
    private static final String PARAM_DESCRIPTION = "description";
    private static final String PARAM_TIMEOUT = "timeout_seconds";
    private static final int DEFAULT_TIMEOUT_SECONDS = 160;
    private static final String DEFAULT_DESCRIPTION = "Nested session";

    /**
     * Guards the one-nested-session-at-a-time rule. Per tool instance, and the registry creates one
     * per project.
     */
    private final AtomicBoolean running = new AtomicBoolean();

    public RunNestedSessionTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return NestedSessionPolicy.TOOL_ID;
    }

    @Override
    public @NotNull String displayName() {
        return "Run Nested Session";
    }

    @Override
    public @NotNull String description() {
        return "Run a prompt in a separate, nested agent session and return the agent's final answer. "
            + "Use it to hand a self-contained task (research, review, a multi-step change) to a fresh "
            + "session without filling your own context. The nested session is unattended: it cannot ask "
            + "questions, so the prompt must contain everything it needs, and its own permission requests "
            + "are denied. It may change files, like any agent. It appears in the chat as a sub-agent. "
            + "`agent` picks the agent (default: the active one); `model` and `description` are optional. "
            + "Blocks until the session ends or `timeout_seconds` (default " + DEFAULT_TIMEOUT_SECONDS
            + ", at most " + McpRequestDeadline.MAX_TIMEOUT_SECONDS + ") passes. Only one nested session "
            + "runs at a time. Returns the answer text, or an error that says why there is none.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.OTHER;
    }

    @Override
    public boolean managesOwnTimeout() {
        return true;
    }

    /**
     * The session runs for minutes and its own tools write files, which need the global write semaphore. Holding
     * it here would make every write of the nested session wait for this call to end, and fail after 60 seconds.
     */
    @Override
    public boolean needsWriteLock() {
        return false;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Run a nested {agent} session: {description}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_PROMPT, TYPE_STRING,
                "The complete task for the nested session. It cannot ask follow-up questions."),
            Param.optional(PARAM_AGENT, TYPE_STRING,
                "Agent id to run it with, for example koog or copilot. Default: the active agent."),
            Param.optional(PARAM_MODEL, TYPE_STRING, "Model id. Default: the agent's own default."),
            Param.optional(PARAM_DESCRIPTION, TYPE_STRING, "Short title shown in the chat."),
            Param.optional(PARAM_TIMEOUT, TYPE_INTEGER,
                "Seconds to wait for the session (default " + DEFAULT_TIMEOUT_SECONDS + ", at most "
                    + McpRequestDeadline.MAX_TIMEOUT_SECONDS + ").")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        String prompt = text(args.get(PARAM_PROMPT));
        if (prompt == null) {
            return err("prompt is required and must not be blank");
        }
        int requested = args.has(PARAM_TIMEOUT) && !args.get(PARAM_TIMEOUT).isJsonNull()
            ? args.get(PARAM_TIMEOUT).getAsInt() : DEFAULT_TIMEOUT_SECONDS;
        String rejected = McpRequestDeadline.rejectNonPositive(requested);
        if (rejected != null) {
            return rejected;
        }
        String agentId = text(args.get(PARAM_AGENT));
        if (agentId == null) {
            agentId = ActiveAgentManager.getInstance(project).getActiveProfileId();
        }
        AbstractClient client = ClientRegistry.create(agentId, project);
        if (client == null) {
            return err("Agent '" + agentId + "' cannot run nested sessions (yet). Available: " + available());
        }
        if (!running.compareAndSet(false, true)) {
            return err("Another nested session is already running. Wait for it to finish; a nested "
                + "session cannot start another one.");
        }
        try {
            return runNested(client, prompt, args, McpRequestDeadline.clamp(requested), requested);
        } finally {
            running.set(false);
        }
    }

    private @NotNull String runNested(@NotNull AbstractClient client,
                                      @NotNull String prompt, @NotNull JsonObject args,
                                      int timeoutSeconds, int requestedSeconds) {
        client.setEphemeral(true);
        client.setNestedSessionKey(NestedSessionPolicy.newSessionKey());
        String description = text(args.get(PARAM_DESCRIPTION));
        if (description == null) {
            description = DEFAULT_DESCRIPTION;
        }
        NestedSessionChatView view = chatView(client, description, prompt);
        String cwd = project.getBasePath();
        NestedSessionRunner.Request request = new NestedSessionRunner.Request(
            prompt, cwd != null ? cwd : System.getProperty("user.home"), text(args.get(PARAM_MODEL)),
            timeoutSeconds);
        NestedSessionRunner.Result result = new NestedSessionRunner(AppExecutorUtil.getAppExecutorService())
            .run(client, request, view);
        if (view != null) {
            view.finish(result);
        }
        String body = result.status() == NestedSessionRunner.Status.COMPLETED
            ? result.text()
            : err(result.error() + (result.text().isEmpty() ? "" : "\n\nPartial answer:\n" + result.text()));
        return McpRequestDeadline.prependNotice(McpRequestDeadline.clampNotice(requestedSeconds), body);
    }

    private @Nullable NestedSessionChatView chatView(@NotNull AbstractClient client,
                                                     @NotNull String description, @NotNull String prompt) {
        BroadcastChatPanel panel = BroadcastChatPanel.getInstance(project);
        if (panel == null) {
            return null;
        }
        NestedSessionChatView view = new NestedSessionChatView(panel);
        view.start(client.displayName(), description, prompt);
        return view;
    }

    private static @NotNull String available() {
        return ClientRegistry.getAll().stream()
            .map(ClientRegistry.AgentDescriptor::id)
            .collect(Collectors.joining(", "));
    }

    private static @Nullable String text(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        String value = element.getAsString().trim();
        return value.isEmpty() ? null : value;
    }
}
