package com.github.catatafishen.agentbridge.client.acp;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the text OpenCode's {@code task} tool returns when a subagent finishes:
 *
 * <pre>
 * &lt;task id="ses_…" state="completed"&gt;
 * &lt;task_result&gt;
 * …subagent's final message…
 * &lt;/task_result&gt;
 * &lt;/task&gt;
 * </pre>
 * <p>
 * The {@code id} is the ID of the subagent's child session, which is what the OpenCode HTTP API
 * ({@link OpenCodeSessionHttpClient}) needs to load its transcript. The same ID is also sent in
 * {@code rawOutput.metadata.sessionId}; it is read from the text here because that is what reaches
 * the chat UI, and it keeps the ID available when a stored conversation is replayed.
 */
public final class OpenCodeTaskOutput {

    private static final Pattern TASK_WRAPPER = Pattern.compile(
        "\\A\\s*<task\\s+id=\"([^\"]+)\"[^>]*>(.*?)</task>\\s*\\z", Pattern.DOTALL);
    private static final Pattern RESULT_WRAPPER_LINE = Pattern.compile(
        "^[ \\t]*</?task_result>[ \\t]*\\r?\\n?", Pattern.MULTILINE);

    private final String childSessionId;
    private final String body;

    private OpenCodeTaskOutput(@NotNull String childSessionId, @NotNull String body) {
        this.childSessionId = childSessionId;
        this.body = body;
    }

    /**
     * @return the parsed output, or {@code null} when [text] is not in the {@code <task id="…">} format
     * (for example output from another agent's sub-agent tool)
     */
    public static @Nullable OpenCodeTaskOutput parse(@Nullable String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = TASK_WRAPPER.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String body = RESULT_WRAPPER_LINE.matcher(matcher.group(2)).replaceAll("").trim();
        return new OpenCodeTaskOutput(matcher.group(1), body);
    }

    /**
     * ID of the subagent's child session.
     */
    public @NotNull String childSessionId() {
        return childSessionId;
    }

    /**
     * The subagent's final message, without the {@code <task>} / {@code <task_result>} wrapper lines.
     */
    public @NotNull String body() {
        return body;
    }
}
