package com.github.catatafishen.agentbridge.agent.codex;

import com.github.catatafishen.agentbridge.acp.model.SessionUpdate;
import com.github.catatafishen.agentbridge.agent.AbstractAgentClient.PermissionPrompt;
import com.github.catatafishen.agentbridge.bridge.PermissionPromptProvider;
import com.github.catatafishen.agentbridge.bridge.PermissionResponse;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.tools.infrastructure.PromptUserTool;
import com.github.catatafishen.agentbridge.services.ToolPermission;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Handles all tool approval and user-input requests from the Codex app-server.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>MCP tool approval questions (auto-approve ALLOW/ASK, decline DENY)</li>
 *   <li>Native command/file-change approval requests (ALLOW/ASK/DENY flow)</li>
 *   <li>Generic user-input questions via PromptUserTool</li>
 *   <li>Session-level approval caching</li>
 *   <li>Pending MCP tool name tracking (for permission resolution)</li>
 * </ul>
 */
public final class CodexApprovalHandler {
    private static final Logger LOG = Logger.getInstance(CodexApprovalHandler.class);

    private static final String F_ID = "id";
    private static final String F_QUESTION = "question";
    private static final String F_OPTIONS = "options";
    private static final String F_ARGUMENTS = "arguments";
    private static final String F_COMMAND = "command";
    private static final String F_REASON = "reason";
    private static final String F_TYPE = "type";
    private static final String F_TEXT = "text";
    private static final String F_MESSAGE = "message";
    private static final String MCP_TOOL_APPROVAL_QUESTION_ID_PREFIX = "mcp_tool_call_approval_";
    private static final String DECISION_ACCEPT = "accept";

    /**
     * Callback interface for sending JSON-RPC responses back to the Codex app-server.
     */
    public interface ResponseSender {
        void sendResponse(@NotNull JsonElement id, @NotNull JsonElement result);
    }

    private final Project project;
    private final ResponseSender sender;
    private final AtomicReference<Consumer<PermissionPrompt>> permissionRequestListener = new AtomicReference<>();

    /**
     * Session-level approval cache: sessionId → set of approved permission keys.
     */
    private final Map<String, java.util.Set<String>> sessionApprovalAllows = new ConcurrentHashMap<>();

    /**
     * Tracks in-flight MCP tool calls: callId → toolName.
     * Populated from item/started notifications, consumed by tool approval questions.
     */
    private final Map<String, String> pendingMcpToolNames = new ConcurrentHashMap<>();

    public CodexApprovalHandler(@NotNull Project project, @NotNull ResponseSender sender) {
        this.project = project;
        this.sender = sender;
    }

    public void setPermissionRequestListener(@Nullable Consumer<PermissionPrompt> listener) {
        permissionRequestListener.set(listener);
    }

    // ── MCP tool name tracking ────────────────────────────────────────────────

    public void trackPendingMcpTool(@NotNull String callId, @NotNull String toolName) {
        pendingMcpToolNames.put(callId, toolName);
    }

    public void removePendingMcpTool(@NotNull String callId) {
        pendingMcpToolNames.remove(callId);
    }

    @Nullable
    public String getPendingMcpToolName(@NotNull String callId) {
        return pendingMcpToolNames.get(callId);
    }

    // ── Session approval cache ────────────────────────────────────────────────

    public boolean isSessionApprovalAllowed(@NotNull String sessionId, @NotNull String permissionKey) {
        java.util.Set<String> allowed = sessionApprovalAllows.get(sessionId);
        return allowed != null && allowed.contains(permissionKey);
    }

    public void allowSessionApproval(@NotNull String sessionId, @NotNull String permissionKey) {
        sessionApprovalAllows.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(permissionKey);
    }

    public void clearSessionApprovals(@NotNull String sessionId) {
        sessionApprovalAllows.remove(sessionId);
    }

    public void clearAllApprovals() {
        sessionApprovalAllows.clear();
    }

    // ── User input request handling ───────────────────────────────────────────

    /**
     * Handles {@code item/tool/requestUserInput} — Codex's generic user-input mechanism.
     *
     * <p>MCP tool approval questions (identified by ID prefix {@code mcp_tool_call_approval_})
     * are auto-approved for ALLOW/ASK tools and declined for DENY tools. Non-approval questions
     * are forwarded to the user via PromptUserTool.</p>
     */
    public void handleUserInputRequest(@NotNull JsonElement id, @NotNull JsonObject params) {
        JsonArray questions;
        if (params.has("questions") && params.get("questions").isJsonArray()) {
            questions = params.getAsJsonArray("questions");
        } else {
            questions = new JsonArray();
        }

        JsonObject answers = new JsonObject();
        for (JsonElement elem : questions) {
            if (!elem.isJsonObject()) continue;
            JsonObject q = elem.getAsJsonObject();
            String qId = q.has(F_ID) ? q.get(F_ID).getAsString() : "";

            String answerLabel;
            if (isMcpToolApprovalQuestion(qId)) {
                String itemId = params.has("itemId") ? params.get("itemId").getAsString() : "";
                answerLabel = resolveMcpToolApprovalAnswer(itemId, qId);
            } else {
                answerLabel = askUserForQuestionAnswer(q);
            }

            JsonObject answerObj = new JsonObject();
            JsonArray answerArr = new JsonArray();
            answerArr.add(answerLabel);
            answerObj.add("answers", answerArr);
            answers.add(qId, answerObj);
        }
        JsonObject result = new JsonObject();
        result.add("answers", answers);
        sender.sendResponse(id, result);
    }

    /**
     * Handles native ask-user request ({@code item/tool/call} with {@code request_user_input}).
     */
    public void handleNativeAskUserRequest(
        @NotNull JsonElement id,
        @NotNull JsonObject params,
        @Nullable Consumer<SessionUpdate> turnCallback
    ) {
        JsonObject arguments = params.has(F_ARGUMENTS) && params.get(F_ARGUMENTS).isJsonObject()
            ? params.getAsJsonObject(F_ARGUMENTS) : new JsonObject();

        String question = findQuestionTextInArgs(arguments);
        if (question == null || question.isEmpty()) {
            question = "The agent has a question for you. Please provide your response.";
        }

        JsonObject toolArgs = new JsonObject();
        toolArgs.addProperty(F_QUESTION, question);
        JsonArray options = extractOptionsArray(arguments);
        toolArgs.add(F_OPTIONS, options);

        // Emit a ToolCall chip so the user sees the prompt_user tool being invoked
        String chipId = UUID.randomUUID().toString();
        if (turnCallback != null) {
            turnCallback.accept(new SessionUpdate.ToolCall(chipId, "prompt_user", "prompt_user",
                SessionUpdate.ToolKind.OTHER, toolArgs.toString(), null, null, null, null, null));
        }

        String userResponse;
        try {
            userResponse = new PromptUserTool(project).execute(toolArgs);
        } catch (Exception e) {
            LOG.warn("PromptUserTool failed during Codex native request_user_input", e);
            userResponse = "Error: failed to get user input";
        }

        if (turnCallback != null) {
            boolean success = !userResponse.startsWith("Error");
            turnCallback.accept(new SessionUpdate.ToolCallUpdate(chipId,
                success ? SessionUpdate.ToolCallStatus.COMPLETED : SessionUpdate.ToolCallStatus.FAILED,
                success ? userResponse : null, success ? null : userResponse, null));
        }

        // Send response back to Codex
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty(F_TYPE, F_TEXT);
        textBlock.addProperty(F_TEXT, userResponse);
        JsonArray content = new JsonArray();
        content.add(textBlock);
        JsonObject resp = new JsonObject();
        resp.add("content", content);
        sender.sendResponse(id, resp);
    }

    // ── Native approval request handling ──────────────────────────────────────

    /**
     * Handles {@code item/commandExecution/requestApproval} and {@code item/fileChange/requestApproval}.
     */
    public void handleNativeApprovalRequest(
        @NotNull JsonElement id,
        @NotNull String method,
        @NotNull JsonObject params,
        @Nullable String sessionId,
        @Nullable Consumer<SessionUpdate> turnCallback
    ) {
        String permissionKey = method.contains("commandExecution") ? "run_command" : "write_file";
        ToolPermission permission = resolveNativeApprovalPermission(permissionKey);

        if (sessionId != null && isSessionApprovalAllowed(sessionId, permissionKey)) {
            LOG.info("Allowing native approval from session cache: " + method + " -> " + permissionKey);
            sendNativeApprovalDecision(id, DECISION_ACCEPT);
            return;
        }

        switch (permission) {
            case ALLOW -> {
                LOG.info("Allowing native approval from settings: " + method + " -> " + permissionKey);
                sendNativeApprovalDecision(id, DECISION_ACCEPT);
                if (sessionId != null) allowSessionApproval(sessionId, permissionKey);
            }
            case DENY -> {
                LOG.info("Denying native approval from settings: " + method + " -> " + permissionKey);
                sendNativeApprovalDecision(id, "decline");
                emitToolDeclinedBanner(method, params, turnCallback);
            }
            case ASK -> {
                PermissionResponse response = requestNativeApproval(method, permissionKey, params);
                switch (response) {
                    case ALLOW_SESSION -> {
                        if (sessionId != null) allowSessionApproval(sessionId, permissionKey);
                        sendNativeApprovalDecision(id, "acceptForSession");
                    }
                    case ALLOW_ONCE -> sendNativeApprovalDecision(id, DECISION_ACCEPT);
                    case DENY -> {
                        sendNativeApprovalDecision(id, "decline");
                        emitToolDeclinedBanner(method, params, turnCallback);
                    }
                }
            }
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    @NotNull
    ToolPermission resolveNativeApprovalPermission(@NotNull String permissionKey) {
        ToolLayerSettings settings = ToolLayerSettings.getInstance(project);
        return settings.getToolPermission(permissionKey);
    }

    @NotNull
    String resolveMcpToolApprovalAnswer(@NotNull String itemId, @NotNull String questionId) {
        String toolName = pendingMcpToolNames.get(itemId);
        if (toolName != null) {
            ToolPermission permission = resolveNativeApprovalPermission(toolName);
            if (permission == ToolPermission.DENY) {
                LOG.info("Declining MCP tool at Codex protocol level: " + toolName + " (DENY in settings)");
                return "Cancel";
            }
        }
        LOG.info("Auto-approving MCP tool at Codex protocol level: " + questionId
            + (toolName != null ? " (tool=" + toolName + ")" : " (tool name not cached)"));
        return "Allow for this session";
    }

    static boolean isMcpToolApprovalQuestion(@NotNull String questionId) {
        return questionId.startsWith(MCP_TOOL_APPROVAL_QUESTION_ID_PREFIX);
    }

    @NotNull
    private String askUserForQuestionAnswer(@NotNull JsonObject question) {
        String questionText = question.has(F_QUESTION) ? question.get(F_QUESTION).getAsString() : "";
        List<String> optionLabels = extractOptionLabels(question);

        JsonObject toolArgs = new JsonObject();
        toolArgs.addProperty(F_QUESTION, questionText);
        if (!optionLabels.isEmpty()) {
            JsonArray opts = new JsonArray();
            optionLabels.forEach(opts::add);
            toolArgs.add(F_OPTIONS, opts);
        }

        try {
            String response = new PromptUserTool(project).execute(toolArgs);
            for (String label : optionLabels) {
                if (label.equalsIgnoreCase(response.trim())) return label;
            }
            return response.trim();
        } catch (Exception e) {
            LOG.warn("PromptUserTool failed for Codex requestUserInput question", e);
            return optionLabels.isEmpty() ? "Cancel" : optionLabels.getLast();
        }
    }

    @NotNull
    static List<String> extractOptionLabels(@NotNull JsonObject question) {
        List<String> labels = new java.util.ArrayList<>();
        if (question.has(F_OPTIONS) && question.get(F_OPTIONS).isJsonArray()) {
            for (JsonElement opt : question.getAsJsonArray(F_OPTIONS)) {
                if (opt.isJsonObject() && opt.getAsJsonObject().has("label")) {
                    labels.add(opt.getAsJsonObject().get("label").getAsString());
                }
            }
        }
        return labels;
    }

    @Nullable
    static String findQuestionTextInArgs(@NotNull JsonObject arguments) {
        for (String key : List.of(F_QUESTION, "prompt", F_MESSAGE, F_TEXT)) {
            if (arguments.has(key) && arguments.get(key).isJsonPrimitive()) {
                String val = arguments.get(key).getAsString().trim();
                if (!val.isEmpty()) return val;
            }
        }
        return null;
    }

    @NotNull
    static JsonArray extractOptionsArray(@NotNull JsonObject arguments) {
        if (arguments.has(F_OPTIONS) && arguments.get(F_OPTIONS).isJsonArray()) {
            return arguments.getAsJsonArray(F_OPTIONS);
        }
        JsonArray defaultOpts = new JsonArray();
        defaultOpts.add("Continue");
        return defaultOpts;
    }

    private PermissionResponse requestNativeApproval(@NotNull String method,
                                                     @NotNull String permissionKey,
                                                     @NotNull JsonObject params) {
        String displayName = "item/commandExecution/requestApproval".equals(method)
            ? "Run command"
            : "Edit file";
        String description = CodexMessageParser.buildNativeApprovalDescription(method, params);
        CompletableFuture<PermissionResponse> future = new CompletableFuture<>();

        String promptId = method + ":" + permissionKey + ":" + UUID.randomUUID();
        PermissionPrompt prompt = new PermissionPrompt() {
            @Override
            public String toolCallId() {
                return promptId;
            }

            @Override
            public String toolName() {
                return displayName;
            }

            @Override
            public @Nullable String arguments() {
                return description;
            }

            @Override
            public List<String> options() {
                return List.of("allow_once", "allow_session", "deny");
            }

            @Override
            public void allow(String optionId) {
                if (optionId != null && optionId.contains("session")) {
                    future.complete(PermissionResponse.ALLOW_SESSION);
                } else {
                    future.complete(PermissionResponse.ALLOW_ONCE);
                }
            }

            @Override
            public void deny(String reason) {
                future.complete(PermissionResponse.DENY);
            }
        };

        Consumer<PermissionPrompt> listener = permissionRequestListener.get();
        if (listener != null) {
            listener.accept(prompt);
        } else {
            PermissionPromptProvider promptProvider = PermissionPromptProvider.getInstance(project);
            if (promptProvider != null) {
                String reqId = UUID.randomUUID().toString();
                promptProvider.showPermissionPrompt(reqId, displayName, description, future::complete);
            }
        }

        try {
            PermissionResponse response = future.get(120, TimeUnit.SECONDS);
            return response != null ? response : PermissionResponse.DENY;
        } catch (java.util.concurrent.TimeoutException e) {
            LOG.info("Native approval timed out for " + method);
            return PermissionResponse.DENY;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PermissionResponse.DENY;
        } catch (java.util.concurrent.ExecutionException e) {
            LOG.warn("Native approval failed for " + method, e);
            return PermissionResponse.DENY;
        }
    }

    private void sendNativeApprovalDecision(@NotNull JsonElement id, @NotNull String decision) {
        JsonObject result = new JsonObject();
        result.addProperty("decision", decision);
        sender.sendResponse(id, result);
    }

    private void emitToolDeclinedBanner(@NotNull String method, @NotNull JsonObject params,
                                        @Nullable Consumer<SessionUpdate> turnCallback) {
        if (turnCallback == null) return;
        String detail;
        if (params.has(F_COMMAND) && !params.get(F_COMMAND).isJsonNull()) {
            JsonElement command = params.get(F_COMMAND);
            detail = command.isJsonPrimitive() ? command.getAsString() : command.toString();
        } else if (params.has(F_REASON) && !params.get(F_REASON).isJsonNull()) {
            JsonElement reason = params.get(F_REASON);
            detail = reason.isJsonPrimitive() ? reason.getAsString() : reason.toString();
        } else {
            detail = method;
        }
        turnCallback.accept(new SessionUpdate.Banner(
            "Native tool declined: " + detail + ". Use MCP tools instead.",
            SessionUpdate.BannerLevel.WARNING,
            SessionUpdate.ClearOn.NEXT_SUCCESS));
    }
}
