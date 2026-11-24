package com.github.catatafishen.agentbridge.client;

import com.github.catatafishen.agentbridge.model.SessionUpdate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds where one agent message ends and the next begins in a stream of text or thought chunks, so the two do
 * not run together in one bubble. A stream is the main agent or one sub-agent; each keeps the id of the message
 * whose text, and apart from it whose thought, it showed last. A tool call between two messages already
 * separates them; text and thoughts do not separate each other, because chunks that arrive after the turn join
 * the previous bubble or thinking block even across the other kind.
 */
public final class MessageBreaks {

    /** What goes between two messages of one stream. */
    public static final String PARAGRAPH_BREAK = "\n\n";

    /** Stream key of the main agent; sub-agent ids never contain a space. */
    private static final String MAIN_STREAM = "main agent";

    private final Map<String, String> lastTextMessage = new ConcurrentHashMap<>();
    private final Map<String, String> lastThoughtMessage = new ConcurrentHashMap<>();

    /**
     * Whether text of message {@code messageId} starts a new message right after another message's text in the
     * stream of sub-agent {@code childId}, or of the main agent when it is null. Without a message id the answer
     * is no.
     */
    public boolean startsNewMessage(@Nullable String childId, @Nullable String messageId) {
        return startsNew(lastTextMessage, childId, messageId);
    }

    /** A tool call of sub-agent {@code childId}, or of the main agent when it is null, ends the text before it. */
    public void toolCalled(@Nullable String childId) {
        lastTextMessage.remove(stream(childId));
        lastThoughtMessage.remove(stream(childId));
    }

    /** Forgets every stream, when a new prompt starts. */
    public void clear() {
        lastTextMessage.clear();
        lastThoughtMessage.clear();
    }

    /**
     * {@code update} with a paragraph break in front when its text or thought starts a new message of its stream:
     * the main agent's, or a sub-agent's when it is a {@link SessionUpdate.ChildSessionUpdate}.
     */
    @NotNull
    public SessionUpdate separate(@NotNull SessionUpdate update) {
        if (update instanceof SessionUpdate.ChildSessionUpdate child) {
            SessionUpdate inner = separate(child.childSessionId(), child.update());
            return inner == child.update()
                ? update
                : new SessionUpdate.ChildSessionUpdate(child.childSessionId(), child.title(), inner);
        }
        return separate(null, update);
    }

    private SessionUpdate separate(@Nullable String childId, SessionUpdate update) {
        if (update instanceof SessionUpdate.ToolCall) {
            toolCalled(childId);
        } else if (update instanceof SessionUpdate.AgentMessageChunk chunk
            && startsNewMessage(childId, chunk.messageId())) {
            return chunk.withLeadingText(PARAGRAPH_BREAK);
        } else if (update instanceof SessionUpdate.AgentThoughtChunk chunk
            && startsNew(lastThoughtMessage, childId, chunk.messageId())) {
            return chunk.withLeadingText(PARAGRAPH_BREAK);
        }
        return update;
    }

    private static boolean startsNew(Map<String, String> lastMessage, @Nullable String childId,
                                     @Nullable String messageId) {
        if (messageId == null) return false;
        String previous = lastMessage.put(stream(childId), messageId);
        return previous != null && !previous.equals(messageId);
    }

    private static String stream(@Nullable String childId) {
        return childId != null ? childId : MAIN_STREAM;
    }
}
