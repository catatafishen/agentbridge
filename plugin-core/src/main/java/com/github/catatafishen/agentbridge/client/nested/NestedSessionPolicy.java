package com.github.catatafishen.agentbridge.client.nested;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What a nested session may not do, because nobody is there to answer it.
 *
 * <p>Two rules, both enforced in code rather than left to the prompt:
 * <ul>
 *   <li>{@link #FORBIDDEN_TOOLS}: tools that ask the user something, or start another session, are not
 *       offered to the nested agent and are refused if it calls them anyway.</li>
 *   <li>A tool whose permission is "ask" is denied instead of shown to the user. The tool call carries
 *       the session's key ({@link #newSessionKey()}), which is how the permission check recognises it
 *       ({@link #isNestedSessionKey}).</li>
 * </ul>
 * Permissions the user already granted (allow, "allow for session", autopilot) apply as usual.
 */
public final class NestedSessionPolicy {

    /**
     * Prefix of the MCP session key of a nested session's tool calls.
     */
    public static final String SESSION_KEY_PREFIX = "nested:";

    /**
     * The id of the tool that starts a nested session.
     */
    public static final String TOOL_ID = "run_nested_session";

    /**
     * Tools a nested session must not use: they wait for a person, or start further sessions.
     */
    public static final Set<String> FORBIDDEN_TOOLS = Set.of(
        "prompt_user", "interact_with_modal", TOOL_ID);

    private NestedSessionPolicy() {
    }

    private static final String ENDPOINT_BASE = "/mcp";
    private static final String ENDPOINT_NESTED_PREFIX = ENDPOINT_BASE + "/nested/";
    private static final Pattern TOKEN = Pattern.compile("[0-9A-Za-z-]{8,64}");

    /**
     * The path of the MCP endpoint a nested session's agent connects to. It carries the session's key, which is
     * how the server tells that agent's tool calls from the chat's own, whatever the agent and its transport.
     *
     * @param sessionKey the nested session's key, or {@code null} for the chat's own endpoint
     */
    public static @NotNull String endpointPath(@Nullable String sessionKey) {
        if (!isNestedSessionKey(sessionKey)) {
            return ENDPOINT_BASE;
        }
        String token = sessionKey.substring(SESSION_KEY_PREFIX.length());
        if (!TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException("Not a nested session key: " + sessionKey);
        }
        return ENDPOINT_NESTED_PREFIX + token;
    }

    /**
     * The nested session key an endpoint path stands for, or {@code null} for the chat's own endpoint or any other
     * path. The inverse of {@link #endpointPath}.
     */
    public static @Nullable String sessionKeyFromPath(@Nullable String path) {
        if (path == null || !path.startsWith(ENDPOINT_NESTED_PREFIX)) {
            return null;
        }
        String token = path.substring(ENDPOINT_NESTED_PREFIX.length());
        if (token.endsWith("/")) {
            token = token.substring(0, token.length() - 1);
        }
        return TOKEN.matcher(token).matches() ? SESSION_KEY_PREFIX + token : null;
    }

    /**
     * A new, unique MCP session key for one nested session.
     */
    public static @NotNull String newSessionKey() {
        return SESSION_KEY_PREFIX + UUID.randomUUID();
    }

    /**
     * Whether {@code sessionKey} belongs to a nested session. {@code null}, for a call outside MCP, is not.
     */
    public static boolean isNestedSessionKey(@Nullable String sessionKey) {
        return sessionKey != null && sessionKey.startsWith(SESSION_KEY_PREFIX);
    }

    /**
     * Whether {@code toolName} is refused for the session behind {@code sessionKey}: a nested session may not use
     * {@link #FORBIDDEN_TOOLS}. The chat's own calls ({@code sessionKey} not nested) are never refused here.
     */
    public static boolean isForbidden(@Nullable String sessionKey, @NotNull String toolName) {
        return isNestedSessionKey(sessionKey) && FORBIDDEN_TOOLS.contains(toolName);
    }

    /**
     * What to do with a tool call whose permission is "ask": {@code null} to go on and ask the user, or the
     * error to return instead. A nested session is unattended, so asking would only stall until the prompt
     * times out; the chat's own calls ({@code sessionKey} not nested) are asked as usual.
     */
    public static @Nullable String denialForAsk(@Nullable String sessionKey, @NotNull String toolName) {
        return isNestedSessionKey(sessionKey) ? approvalRequiredMessage(toolName) : null;
    }

    /**
     * The message for a tool call that needs an approval nobody can give.
     */
    public static @NotNull String approvalRequiredMessage(@NotNull String toolName) {
        return "Error: Permission denied: tool '" + toolName + "' needs the user's approval, and this is an "
            + "unattended nested session where nobody can approve. Do it another way, or leave this step "
            + "to the caller and say so in your answer.";
    }

    /**
     * The message for a forbidden tool.
     */
    public static @NotNull String forbiddenToolMessage(@NotNull String toolName) {
        return "Error: " + toolName + " is not available in a nested session: nobody can answer it. "
            + "Make a reasonable assumption and carry on.";
    }
}
