package com.github.catatafishen.agentbridge.session

import com.github.catatafishen.agentbridge.bridge.EntryData

/**
 * The conversations of the chat's sub-agents, recorded while they stream so they can be saved with each
 * sub-agent's entry ([EntryData.SubAgent.children]) and shown again after a restart.
 *
 * Each is kept in the shape its section shows it live: the reasoning, the tool calls in the order they
 * started, then the text. Tool calls do not split the text, because agents send them in mid-sentence
 * (OpenCode). Nested sessions record their own transcript and never come through here.
 *
 * Not thread-safe: [ConversationEntryStore] calls it under its lock.
 */
class SubAgentTranscripts {

    private class Transcript {
        var thinking: EntryData.Thinking? = null
        val toolCalls = LinkedHashMap<String, EntryData.ToolCall>()
        var text: EntryData.Text? = null
    }

    private val transcripts = mutableMapOf<String, Transcript>()

    /** The sub-agent each recorded tool call belongs to, since tool call updates carry only the tool's id. */
    private val toolOwners = mutableMapOf<String, String>()

    fun appendText(subAgentId: String, chunk: String, timestamp: String, agent: String) {
        if (chunk.isEmpty()) return
        val transcript = transcriptOf(subAgentId)
        val text = transcript.text ?: EntryData.Text(timestamp = timestamp, agent = agent).also { transcript.text = it }
        text.raw += chunk
    }

    fun appendThinking(subAgentId: String, chunk: String, timestamp: String, agent: String) {
        if (chunk.isEmpty()) return
        val transcript = transcriptOf(subAgentId)
        val thinking = transcript.thinking
            ?: EntryData.Thinking(timestamp = timestamp, agent = agent).also { transcript.thinking = it }
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
        transcriptOf(subAgentId).toolCalls[toolId] = EntryData.ToolCall(
            title, arguments, kind ?: "other", timestamp = timestamp, agent = agent, entryId = toolId
        )
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
     * while the sub-agent goes on streaming. Blank text and reasoning are left out.
     */
    fun snapshot(subAgentId: String): List<EntryData> {
        val transcript = transcripts[subAgentId] ?: return emptyList()
        return buildList {
            transcript.thinking?.takeIf { it.raw.isNotBlank() }?.let {
                add(EntryData.Thinking(it.raw, it.timestamp, it.agent, it.model, it.entryId))
            }
            transcript.toolCalls.values.forEach { add(copyOf(it)) }
            transcript.text?.takeIf { it.raw.isNotBlank() }?.let {
                add(EntryData.Text(it.raw, it.timestamp, it.agent, it.model, it.entryId))
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
        call.title, call.arguments, call.kind, call.result, call.status, call.description, call.filePath,
        call.autoDenied, call.denialReason, call.pluginTool, call.timestamp, call.agent, call.model, call.entryId
    )

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
