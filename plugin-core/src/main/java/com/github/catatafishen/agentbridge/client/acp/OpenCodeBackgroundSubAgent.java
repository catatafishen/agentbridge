package com.github.catatafishen.agentbridge.client.acp;

import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises the result OpenCode 2.3's {@code subagent} tool returns when it starts a sub-agent in the
 * background instead of waiting for it:
 *
 * <pre>
 * The subagent is working in the background (sessionID: ses_…). You will receive a &lt;subagent&gt;
 * message when it goes idle. …
 * </pre>
 * <p>
 * The tool call completes at once, but the sub-agent is still working, in the child session the note
 * names; {@link OpenCodeChildSessions} reports when it finishes.
 */
public final class OpenCodeBackgroundSubAgent {

    private static final Pattern LAUNCH_RESULT = Pattern.compile(
        "\\A\\s*The subagent is working in the background \\(sessionID: ([^)\\s]+)\\)");

    private OpenCodeBackgroundSubAgent() {
    }

    /**
     * @return the sub-agent's session id when {@code result} is the note of a sub-agent started in the
     * background; null for a sub-agent's final answer, even one that quotes the note
     */
    public static @Nullable String sessionIdOfLaunch(@Nullable String result) {
        if (result == null) return null;
        Matcher matcher = LAUNCH_RESULT.matcher(result);
        return matcher.find() ? matcher.group(1) : null;
    }
}
