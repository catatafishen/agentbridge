package com.github.catatafishen.agentbridge.session

import com.github.catatafishen.agentbridge.bridge.ContextFileRef
import com.github.catatafishen.agentbridge.bridge.EntryData
import com.github.catatafishen.agentbridge.bridge.MessageFormatter
import com.github.catatafishen.agentbridge.bridge.NudgeSource
import com.github.catatafishen.agentbridge.ui.ChatPanelApi
import com.github.catatafishen.agentbridge.ui.TurnStatsData
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns the conversation entries list and streaming state.
 *
 * This is the canonical data layer for conversation state — all mutations
 * (prompt addition, text streaming, tool call tracking, turn stats) go through
 * here. UI panels observe via [addChangeListener] and render accordingly.
 *
 * Thread safety: all access is synchronized via [lock]. Callers may invoke from
 * any thread (EDT, streaming threads, background pool). The change listeners
 * list uses CopyOnWriteArrayList for safe iteration during notification.
 */
class ConversationEntryStore {

    private val lock = Any()
    private val _entries = mutableListOf<EntryData>()
    private var _currentText: EntryData.Text? = null
    private var _currentThinking: EntryData.Thinking? = null
    private val _toolCallEntries = mutableMapOf<String, EntryData.ToolCall>()
    private val _subAgentEntries = mutableMapOf<String, EntryData.SubAgent>()
    private val _changeListeners = CopyOnWriteArrayList<Runnable>()

    private var _currentAgent = ""

    // ── Public read access ────────────────────────────────────────────────────

    /** Returns a defensive copy of the entries list. */
    fun getEntries(): List<EntryData> = synchronized(lock) { _entries.toList() }

    /** Returns a snapshot (ArrayList copy) for iteration outside EDT. */
    fun entriesSnapshot(): List<EntryData> = synchronized(lock) { ArrayList(_entries) }

    /** Checks if an entry with the given ID is already tracked. */
    fun isEntryTracked(entryId: String): Boolean = synchronized(lock) { _entries.any { it.entryId == entryId } }

    val currentAgent: String get() = synchronized(lock) { _currentAgent }

    // ── Change listeners ──────────────────────────────────────────────────────

    fun addChangeListener(listener: Runnable) {
        _changeListeners.add(listener)
    }

    fun removeChangeListener(listener: Runnable) {
        _changeListeners.remove(listener)
    }

    private fun fireChanged() = _changeListeners.forEach { it.run() }

    // ── Mutations ─────────────────────────────────────────────────────────────

    fun setCurrentAgent(agentName: String) = synchronized(lock) {
        _currentAgent = agentName
    }

    fun addPromptEntry(text: String, contextFiles: List<ContextFileRef>?, entryId: String) {
        synchronized(lock) {
            val entry = EntryData.Prompt(text, timestamp(), contextFiles, id = entryId)
            _entries.add(entry)
        }
        fireChanged()
    }

    fun removePromptEntry(entryId: String) = synchronized(lock) {
        _entries.removeIf { it.entryId == entryId }
    }

    fun startStreaming() = synchronized(lock) {
        _currentText = null
        _currentThinking = null
    }

    /**
     * Appends text to the current streaming response. If no Text entry exists yet,
     * creates one and adds it to the entries list.
     */
    fun appendText(text: String) = synchronized(lock) {
        val current = _currentText
        if (current == null) {
            _currentText = EntryData.Text(text, timestamp(), _currentAgent).also { _entries.add(it) }
        } else {
            current.raw += text
        }
    }

    /**
     * Appends thinking text to the current thinking block. If no Thinking entry
     * exists yet, creates one and adds it to the entries list.
     */
    fun appendThinkingText(text: String) = synchronized(lock) {
        val current = _currentThinking
        if (current == null) {
            _currentThinking = EntryData.Thinking(text, timestamp(), _currentAgent).also { _entries.add(it) }
        } else {
            current.raw += text
        }
    }

    fun addToolCallEntry(
        id: String,
        title: String,
        arguments: String?,
        kind: String?
    ) {
        synchronized(lock) {
            val entry = EntryData.ToolCall(
                title, arguments, kind ?: "other",
                timestamp = timestamp(), agent = _currentAgent, entryId = id
            )
            _entries.add(entry)
            _toolCallEntries[id] = entry
        }
    }

    fun updateToolCall(id: String, status: String, update: ChatPanelApi.ToolCallUpdate) = synchronized(lock) {
        _toolCallEntries[id]?.let { entry ->
            entry.status = status
            update.details?.let { entry.result = it }
            update.description?.let { entry.description = it }
            update.kind?.let { entry.kind = it }
            entry.autoDenied = update.autoDenied
            update.denialReason?.let { entry.denialReason = it }
        }
    }

    fun addSubAgentEntry(
        id: String,
        agentType: String,
        description: String,
        prompt: String?,
        initialState: ChatPanelApi.SubAgentInitialState
    ) {
        synchronized(lock) {
            val entry = EntryData.SubAgent(
                agentType, description, prompt,
                result = initialState.result,
                status = initialState.status,
                autoDenied = initialState.autoDenied,
                denialReason = initialState.denialReason,
                timestamp = timestamp(), agent = _currentAgent, entryId = id
            )
            _entries.add(entry)
            _subAgentEntries[id] = entry
        }
    }

    fun updateSubAgentResult(
        id: String,
        status: String,
        result: String?,
        description: String?,
        autoDenied: Boolean,
        denialReason: String?
    ) = synchronized(lock) {
        _subAgentEntries[id]?.let { entry ->
            entry.status = status
            result?.let { entry.result = it }
            entry.autoDenied = autoDenied
            denialReason?.let { entry.denialReason = it }
        }
    }

    fun addNudgeEntry(id: String, text: String, source: NudgeSource) {
        synchronized(lock) {
            _entries.add(EntryData.Nudge(text, id = id, sent = true, timestamp = timestamp(), source = source))
        }
    }

    fun finishResponse() {
        synchronized(lock) {
            _currentText = null
            _currentThinking = null
        }
        fireChanged()
    }

    fun emitTurnStats(stats: TurnStatsData): EntryData.TurnStats {
        val entry: EntryData.TurnStats
        synchronized(lock) {
            entry = EntryData.TurnStats(
                turnId = UUID.randomUUID().toString(),
                durationMs = stats.durationMs,
                inputTokens = stats.inputTokens.toLong(),
                outputTokens = stats.outputTokens.toLong(),
                costUsd = stats.costUsd,
                toolCallCount = stats.toolCallCount,
                linesAdded = stats.linesAdded,
                linesRemoved = stats.linesRemoved,
                model = stats.model,
                multiplier = stats.multiplier,
                timestamp = timestamp(),
            )
            _entries.add(entry)
        }
        fireChanged()
        return entry
    }

    fun addSessionSeparator(timestamp: String, agent: String) = synchronized(lock) {
        _entries.add(EntryData.SessionSeparator(timestamp, agent))
    }

    fun clear() {
        synchronized(lock) {
            _entries.clear()
            _currentText = null
            _currentThinking = null
            _toolCallEntries.clear()
            _subAgentEntries.clear()
        }
        fireChanged()
    }

    private fun timestamp() = MessageFormatter.timestamp()
}
