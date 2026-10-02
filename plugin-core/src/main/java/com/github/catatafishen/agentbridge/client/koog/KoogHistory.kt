package com.github.catatafishen.agentbridge.client.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import com.github.catatafishen.agentbridge.bridge.EntryData
import com.github.catatafishen.agentbridge.session.exporters.ExportUtils

/**
 * Turns the plugin's stored transcript of a conversation (kept for every agent) into the message history of a
 * new Koog conversation, so a restart or an agent switch does not lose the thread.
 *
 * Rules that keep the result a history a provider accepts and the model can use:
 * - The history starts at a user prompt: entries before the first one belong to a turn whose start was not loaded.
 * - A tool call is kept only with its result, and only if this agent actually has a tool of that name. A call to
 *   some other agent's native tool would invite the model to repeat it.
 * - Arguments that are not a JSON object become `{}` (see [KoogConversation.withValidArguments]).
 * - A trailing prompt nobody answered is left out: the prompt about to be sent replaces it.
 * - When the history exceeds the budget, whole oldest turns are dropped, never part of one. Individual tool
 *   results are cut short with a visible marker so one big file read cannot crowd out everything else.
 */
object KoogHistory {
    /** Roughly 10k tokens: leaves room for the system prompt, the tool descriptions and the answer. */
    const val DEFAULT_BUDGET_CHARS = 40_000
    const val MAX_RESULT_CHARS = 4_000
    const val NO_RESULT = "(no result was recorded for this tool call)"

    /**
     * @param skippedToolCalls calls left out because this agent has no tool with that name
     * @param droppedTurns oldest turns left out to fit the budget
     */
    data class Restored(val messages: List<Message>, val skippedToolCalls: Int, val droppedTurns: Int)

    @JvmStatic
    @JvmOverloads
    fun restore(
        entries: List<EntryData>,
        knownTools: Set<String>,
        budgetChars: Int = DEFAULT_BUDGET_CHARS,
        clock: KoogClock = KoogClock.System,
    ): Restored {
        val ids = IdSource()
        val turns = mutableListOf<Turn>()
        for (entry in entries) {
            when (entry) {
                is EntryData.Prompt -> if (entry.text.isNotBlank()) turns += Turn(entry.text, knownTools, ids, clock)
                is EntryData.Text -> turns.lastOrNull()?.text(entry.raw)
                is EntryData.ToolCall -> turns.lastOrNull()?.toolCall(entry)
                // Reasoning, sub-agent runs, statistics, status lines, nudges and context chips are not part of
                // what the model said or was told.
                else -> Unit
            }
        }
        turns.forEach(Turn::finish)
        if (turns.lastOrNull()?.answered == false) turns.removeLast()

        var total = turns.sumOf { it.chars }
        var first = 0
        while (total > budgetChars && turns.size - first > 1) total -= turns[first++].chars
        val kept = turns.drop(first)
        return Restored(kept.flatMap { it.messages }, kept.sumOf { it.skipped }, first)
    }

    private class IdSource {
        private var last = 0
        fun next() = "restored_${++last}"
    }

    /** One user prompt and everything the agent did in answer to it. */
    private class Turn(
        userText: String,
        private val known: Set<String>,
        private val ids: IdSource,
        private val clock: KoogClock,
    ) {
        val messages = mutableListOf<Message>(Message.User(userText, RequestMetaInfo.create(clock)))
        var chars = userText.length
            private set
        var skipped = 0
            private set

        /** False while only the prompt is there, i.e. nothing came back for it. */
        val answered: Boolean get() = messages.size > 1

        private var pendingText: String? = null
        private val parts = mutableListOf<MessagePart.ResponsePart>()
        private val results = mutableListOf<MessagePart.Tool.Result>()

        fun text(raw: String) {
            if (raw.isBlank()) return
            // Text after a tool result is the model's next step, not a continuation of the one with the calls.
            if (results.isNotEmpty()) flushStep()
            pendingText = pendingText?.let { "$it\n\n$raw" } ?: raw
            chars += raw.length
        }

        fun toolCall(call: EntryData.ToolCall) {
            val name = ExportUtils.canonicalToolName(call)
            if (name !in known) {
                skipped++
                return
            }
            commitText()
            val id = ids.next()
            val rawArgs = call.arguments.orEmpty()
            val args = if (rawArgs.isNotBlank() && KoogConversation.parseArguments(rawArgs) != null) rawArgs else "{}"
            val output = outputOf(call)
            parts += MessagePart.Tool.Call(id = id, tool = name, args = args)
            results += MessagePart.Tool.Result(
                id = id, tool = name, output = output,
                isError = call.autoDenied || call.status.equals("failed", ignoreCase = true),
            )
            chars += name.length + args.length + output.length
        }

        fun finish() {
            commitText()
            flushStep()
        }

        private fun outputOf(call: EntryData.ToolCall): String {
            val recorded = call.result?.takeIf { it.isNotEmpty() }
                ?: if (call.autoDenied) "Denied: ${call.denialReason ?: "not permitted"}" else NO_RESULT
            return if (recorded.length <= MAX_RESULT_CHARS) {
                recorded
            } else {
                recorded.take(MAX_RESULT_CHARS) + "\n[… ${recorded.length - MAX_RESULT_CHARS} more characters were not restored]"
            }
        }

        private fun commitText() {
            pendingText?.let { parts += MessagePart.Text(it) }
            pendingText = null
        }

        private fun flushStep() {
            commitText()
            if (parts.isEmpty()) return
            messages += Message.Assistant(parts = parts.toList(), finishReason = null, metaInfo = ResponseMetaInfo.Empty)
            if (results.isNotEmpty()) messages += Message.User(results.toList(), RequestMetaInfo.create(clock))
            parts.clear()
            results.clear()
        }
    }
}
