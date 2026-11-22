package com.github.catatafishen.agentbridge.client.acp;

import org.jetbrains.annotations.Nullable;

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
 * The tool call completes at once, but the sub-agent is still working. Its own tool calls are
 * forwarded over ACP with the title {@code "<description>: <tool>"} and never complete, and its report
 * reaches the chat only as a message the main agent sends after its turn has ended.
 */
public final class OpenCodeBackgroundSubAgent {

    private static final Pattern LAUNCH_RESULT = Pattern.compile(
        "\\A\\s*The subagent is working in the background \\(sessionID: [^)\\s]+\\)");

    private OpenCodeBackgroundSubAgent() {
    }

    /**
     * @return whether {@code result} is the note of a sub-agent started in the background; false for a
     * sub-agent's final answer, even one that quotes the note
     */
    public static boolean isLaunchResult(@Nullable String result) {
        return result != null && LAUNCH_RESULT.matcher(result).find();
    }
}
