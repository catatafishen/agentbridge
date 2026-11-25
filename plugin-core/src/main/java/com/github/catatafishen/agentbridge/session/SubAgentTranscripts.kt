package com.github.catatafishen.agentbridge.session

import com.github.catatafishen.agentbridge.bridge.EntryData
import com.github.catatafishen.agentbridge.bridge.MessageFormatter

/**
 * The conversations of the chat's sub-agents, recorded while they stream so they can be saved with each
 * sub-agent's entry ([EntryData.SubAgent.children]) and shown again after a restart.
 *
 * Each is kept in the order its section shows it live: text blocks split where [SubAgentTextBreaks] starts a new
 * one, with the tool calls and reasoning between them.
 *
 * Not thread-safe: [ConversationEntryStore] calls it under its lock.
 */
class SubAgentTranscripts {

    private class Transcript {
        val items = mutableListOf<EntryData>()
        val toolCalls = mutableMapOf<String, EntryData.ToolCall>()
        val breaks = SubAgentTextBreaks()
        /** The block text goes on into, unless [breaks] starts a new one. */
        var text: EntryData.Text? = null
        /** The reasoning since the current text block started; the section shows it as one thought chip. */
        var thinking: EntryData.Thinking? = null
    }

    private val transcripts = mutableMapOf<String, Transcript>()

    /** The sub-agent each recorded tool call belongs to, since tool call updates carry only the tool's id. */
    private val toolOwners = mutableMapOf<String, String>()

    fun appendText(subAgentId: String, chunk: String, messageId: String?, timestamp: String, agent: String) {
        if (chunk.isEmpty()) return
        val transcript = transcriptOf(subAgentId)
        val newBlock = transcript.breaks.startsNewBlock(messageId)
        val current = transcript.text
        if (!newBlock && current != null) {
            current.raw += chunk
            return
        }
        val text = EntryData.Text(SubAgentTextBreaks.blockStart(chunk), timestamp, agent)
        transcript.items += text
        transcript.text = text
        transcript.thinking = null
    }

    fun appendThinking(subAgentId: String, chunk: String, timestamp: String, agent: String) {
        if (chunk.isEmpty()) return
        val transcript = transcriptOf(subAgentId)
        // Like the section, which shows no thought chip for blank reasoning.
        if (transcript.thinking == null && chunk.isBlank()) return
        transcript.breaks.chipAdded()
        val thinking = transcript.thinking ?: EntryData.Thinking(timestamp = timestamp, agent = agent).also {
            transcript.items += it
            transcript.thinking = it
        }
        thinking.raw += chunk
    }

    /**
     * Records a tool call of [subAgentId]. It has no status until an update gives it one, so a call the agent
     * never finishes is restored as finished rather than spinning forever.
     */
    fun addToolCall(
        subAgentId: String, toolId: String, title: String, arguments: String?, kind: String?,
        timestamp: String, agent: String
    ) {
        val transcript = transcriptOf(subAgentId)
        transcript.breaks.chipAdded()
        val call = EntryData.ToolCall(
            title, arguments, kind ?: "other", timestamp = timestamp, agent = agent, entryId = toolId
        )
        transcript.items += call
        transcript.toolCalls[toolId] = call
        toolOwners[toolId] = subAgentId
    }

    /** @return false when [toolId] is not a recorded sub-agent tool call */
    fun updateToolCall(toolId: String, update: ToolCallChange): Boolean {
        val owner = toolOwners[toolId] ?: return false
        val call = transcripts[owner]?.toolCalls?.get(toolId) ?: return false
        call.status = update.status
        update.result?.let { call.result = it }
        update.description?.let { call.description = it }
        call.autoDenied = update.autoDenied
        update.denialReason?.let { call.denialReason = it }
        update.arguments?.let { call.arguments = it }
        return true
    }

    /**
     * The transcript of [subAgentId] as copies, so the list handed to the database writer does not change
     * while the sub-agent goes on streaming. Blank text and reasoning are left out. It is taken when the sub-agent
     * ends, so a tool call still running then is saved without a status and restored as finished.
     */
    fun snapshot(subAgentId: String): List<EntryData> {
        val transcript = transcripts[subAgentId] ?: return emptyList()
        return transcript.items.mapNotNull {
            when (it) {
                is EntryData.Text -> it.takeIf { it.raw.isNotBlank() }
                    ?.let { t -> EntryData.Text(t.raw, t.timestamp, t.agent, t.model, t.entryId) }

                is EntryData.Thinking -> it.takeIf { it.raw.isNotBlank() }
                    ?.let { t -> EntryData.Thinking(t.raw, t.timestamp, t.agent, t.model, t.entryId) }

                is EntryData.ToolCall -> copyOf(it)
                else -> null
            }
        }
    }

    /** Stops recording [subAgentId], e.g. when the agent supplied the whole transcript itself. */
    fun forget(subAgentId: String) {
        transcripts.remove(subAgentId)?.toolCalls?.keys?.forEach { toolOwners.remove(it) }
    }

    fun clear() {
        transcripts.clear()
        toolOwners.clear()
    }

    private fun transcriptOf(subAgentId: String): Transcript = transcripts.getOrPut(subAgentId) { Transcript() }

    private fun copyOf(call: EntryData.ToolCall) = EntryData.ToolCall(
        call.title, call.arguments, call.kind, call.result,
        call.status?.takeUnless { it.lowercase() in UNFINISHED }, call.description, call.filePath,
        call.autoDenied, call.denialReason, call.pluginTool, call.timestamp, call.agent, call.model, call.entryId
    )

    private companion object {
        val UNFINISHED = setOf(MessageFormatter.ChipStatus.PENDING, MessageFormatter.ChipStatus.RUNNING)
    }

    /** What a tool call update changes; null fields keep the recorded value. */
    data class ToolCallChange @JvmOverloads constructor(
        val status: String,
        val result: String? = null,
        val description: String? = null,
        val autoDenied: Boolean = false,
        val denialReason: String? = null,
        val arguments: String? = null,
    )
}
