package com.github.catatafishen.agentbridge.client.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart

/**
 * Keeps a long conversation inside the model's context window.
 *
 * Without this the conversation grows until the provider rejects a request and the user has to start over. This is
 * deliberately not a summary: a summary needs another model call, costs tokens and can quietly lose a detail the work
 * depends on. Instead the cheapest thing that is certain not to corrupt the history goes first, and the user is told.
 *
 * 1. Old tool results (usually whole files and command output, by far the biggest part) are cut to a short head with a
 *    visible marker, oldest first. The result of the latest step is left alone: the model is about to read it.
 * 2. If that is not enough, whole turns are dropped, oldest first, never the latest one. A turn is a user prompt and
 *    everything after it, so a tool call is never separated from its result.
 *
 * Sizes are estimated from characters; the estimate is deliberately pessimistic so the real limit is not hit first.
 */
object KoogCompaction {
    /** Code and JSON tokenise densely, so assume few characters per token. */
    const val CHARS_PER_TOKEN = 3

    /** Compaction starts once the request would use more than this share of the window... */
    const val TRIGGER_SHARE = 0.8

    /** ...and shrinks it to this share, so it does not run again on the very next step. */
    const val TARGET_SHARE = 0.6

    /** What is left of a tool result that was cut. */
    const val KEPT_RESULT_CHARS = 400

    /** An attachment has no text to count; this stands in for what an image costs. */
    private const val ATTACHMENT_CHARS = 2_000

    data class Compacted(val messages: List<Message>, val shrunkResults: Int, val droppedTurns: Int) {
        val changed: Boolean get() = shrunkResults > 0 || droppedTurns > 0
    }

    /**
     * @param windowTokens the model's context window
     * @param overheadChars what every request carries besides the history: the system prompt and the tool descriptions
     */
    @JvmStatic
    fun compact(messages: List<Message>, windowTokens: Long, overheadChars: Long): Compacted {
        val window = windowTokens * CHARS_PER_TOKEN
        val sizes = messages.map(::sizeOf).toMutableList()
        var total = overheadChars + sizes.sum()
        if (total <= window * TRIGGER_SHARE) return Compacted(messages, 0, 0)

        val target = (window * TARGET_SHARE).toLong()
        val result = messages.toMutableList()
        var shrunk = 0

        val lastResults = result.indexOfLast(::hasToolResult)
        for (i in result.indices) {
            if (total <= target) break
            if (i == lastResults || !hasToolResult(result[i])) continue
            val (smaller, cut) = shrink(result[i] as Message.User)
            if (cut == 0) continue
            result[i] = smaller
            shrunk += cut
            val now = sizeOf(smaller)
            total -= sizes[i] - now
            sizes[i] = now
        }

        var dropped = 0
        var from = 0
        while (total > target) {
            val starts = turnStarts(result, from)
            if (starts.size < 2) break
            val end = starts[1]
            for (i in from until end) total -= sizes[i]
            from = end
            dropped++
        }
        return Compacted(if (from == 0) result else result.subList(from, result.size).toList(), shrunk, dropped)
    }

    private fun turnStarts(messages: List<Message>, from: Int): List<Int> =
        (from until messages.size).filter { i -> messages[i] is Message.User && !hasToolResult(messages[i]) }

    private fun hasToolResult(message: Message) = message.parts.any { it is MessagePart.Tool.Result }

    /** Returns the message with its long results cut, and how many were cut. */
    private fun shrink(message: Message.User): Pair<Message.User, Int> {
        var cut = 0
        val parts = message.parts.map { part ->
            if (part is MessagePart.Tool.Result && part.output.length > KEPT_RESULT_CHARS + MARKER_SLACK) {
                cut++
                val omitted = part.output.length - KEPT_RESULT_CHARS
                MessagePart.Tool.Result(
                    id = part.id, tool = part.tool, isError = part.isError,
                    output = part.output.take(KEPT_RESULT_CHARS) +
                        "\n[… $omitted more characters were trimmed to fit the model's context window]",
                )
            } else {
                part
            }
        }
        return message.copy(parts = parts) to cut
    }

    /** Cutting a result that is barely longer than what is kept would save nothing, since the marker is added. */
    private const val MARKER_SLACK = 100

    @JvmStatic
    fun sizeOf(message: Message): Long = message.parts.sumOf { part ->
        when (part) {
            is MessagePart.Text -> part.text.length
            is MessagePart.Tool.Call -> part.tool.length + part.args.length
            is MessagePart.Tool.Result -> part.tool.length + part.output.length
            is MessagePart.Reasoning -> part.content.sumOf { it.length }
            is MessagePart.Attachment -> ATTACHMENT_CHARS
        }.toLong()
    }
}
