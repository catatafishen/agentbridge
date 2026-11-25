package com.github.catatafishen.agentbridge.session

import com.github.catatafishen.agentbridge.client.MessageBreaks

/**
 * Where a sub-agent's streamed text starts a new block, as the main agent's does: text after the sub-agent's tool
 * calls or reasoning starts one, unless it goes on with the same message, which OpenCode interrupts with a tool call
 * in mid-sentence. Chunks without a message id (Claude) never go on across a tool call. One per sub-agent.
 */
class SubAgentTextBreaks {

    private var hasText = false
    private var chipsSinceText = false
    private var lastMessageId: String? = null

    /** Whether a text chunk of message [messageId] starts a new block. */
    fun startsNewBlock(messageId: String?): Boolean {
        val starts = !hasText || chipsSinceText && (messageId == null || messageId != lastMessageId)
        hasText = true
        chipsSinceText = false
        lastMessageId = messageId
        return starts
    }

    /** Records a tool call or a reasoning chunk of the sub-agent. */
    fun chipAdded() {
        if (hasText) chipsSinceText = true
    }

    companion object {
        /** [chunk] as the first text of a block, which needs no break from the message before it. */
        @JvmStatic
        fun blockStart(chunk: String): String = chunk.removePrefix(MessageBreaks.PARAGRAPH_BREAK)
    }
}
