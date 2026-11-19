package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.model.SessionUpdate

/**
 * The sub-agents in the chat that work in sessions of their own (OpenCode's child sessions): which session
 * belongs to which sub-agent entry, the tool calls each made, and which still work in the background after
 * the tool call that started them completed.
 *
 * A sub-agent's session updates carry the session's id and title, never the id of the tool call that
 * started it. OpenCode titles the session with the description the main agent gave the sub-agent, so a new
 * session is matched by that description; the launch result then names the sub-agent's session for certain.
 *
 * Thread-safe: updates arrive on the ACP reader thread, Stop and the end of the post-turn window on others.
 */
class SubAgentSessions {

    /** A tool call made by a sub-agent: [acpId] identifies it to the tool call tracker, [recordId] in the chat. */
    data class ChildTool(val acpId: String, val recordId: String)

    /** A background sub-agent that is no longer tracked, with the tool calls it made. */
    data class Finished(val recordId: String, val children: List<ChildTool>)

    /** How a sub-agent's session ended, as the session reported it. */
    data class SessionEnd(val state: SessionUpdate.ChildSessionState, val error: String?)

    private class Tracked(var description: String?) {
        var sessionId: String? = null
        var inBackground = false
        var endedBeforeLaunch: SessionEnd? = null
        val children = mutableListOf<ChildTool>()

        fun finished(recordId: String) = Finished(recordId, children.toList())
    }

    /** By chat record id, in the order the sub-agents started. */
    private val tracked = LinkedHashMap<String, Tracked>()

    /** Tracks the sub-agent shown by chat entry [recordId], which was asked to do [description]. */
    @Synchronized
    fun started(recordId: String, description: String?) {
        tracked.putIfAbsent(recordId, Tracked(normalize(description)))
    }

    /**
     * Updates the [description] of the tracked sub-agent [recordId]. A null or blank description, or a sub-agent
     * no longer tracked, changes nothing.
     */
    @Synchronized
    fun describe(recordId: String, description: String?) {
        val normalized = normalize(description) ?: return
        tracked[recordId]?.description = normalized
    }

    /**
     * Records that the sub-agent [recordId] works on in the background in session [sessionId]. A follow-up
     * sent to a sub-agent gets an entry of its own, which takes the session over.
     *
     * @return the entry that had the session until now and is still in the background, or null
     */
    @Synchronized
    fun launchedInBackground(recordId: String, sessionId: String): Finished? {
        val previous = tracked.entries.firstOrNull { it.key != recordId && it.value.sessionId == sessionId }
        previous?.let { tracked.remove(it.key) }
        val sub = tracked.getOrPut(recordId) { Tracked(null) }
        sub.sessionId = sessionId
        sub.inBackground = true
        return previous?.takeIf { it.value.inBackground }?.let { it.value.finished(it.key) }
    }

    /**
     * The record id of the sub-agent that owns the session [sessionId] titled [title], or null.
     *
     * A session seen before keeps its owner. A new one goes to a sub-agent that has no session yet: the one
     * whose description equals [title], or with no title, the only such sub-agent. When several match, the
     * most recently started wins, so a sub-agent left over from a stopped turn never takes the session of a
     * new one with the same description.
     */
    @Synchronized
    fun ownerOf(sessionId: String, title: String?): String? {
        tracked.entries.firstOrNull { it.value.sessionId == sessionId }?.let { return it.key }
        val unbound = tracked.entries.filter { it.value.sessionId == null }
        val wanted = normalize(title)
        val owner = if (wanted != null) {
            unbound.lastOrNull { it.value.description == wanted }
        } else {
            unbound.singleOrNull()
        } ?: return null
        owner.value.sessionId = sessionId
        return owner.key
    }

    /** Records that the sub-agent [ownerRecordId] made the tool call [child]. Ignored once it is no longer tracked. */
    @Synchronized
    fun addChildTool(ownerRecordId: String, child: ChildTool) {
        tracked[ownerRecordId]?.children?.add(child)
    }

    /**
     * Stops tracking and returns the background sub-agent working in session [sessionId], whose session ended
     * with [end]; null when none does. A sub-agent still inside the tool call that starts it stays tracked and
     * keeps [end]: a background launch result then ends it ([takeEndedBeforeLaunch]), any other result of that
     * call ends it anyway.
     */
    @Synchronized
    fun finish(sessionId: String, end: SessionEnd): Finished? {
        val entry = tracked.entries.firstOrNull { it.value.sessionId == sessionId } ?: return null
        if (!entry.value.inBackground) {
            entry.value.endedBeforeLaunch = end
            return null
        }
        tracked.remove(entry.key)
        return entry.value.finished(entry.key)
    }

    /**
     * Stops tracking and returns the background sub-agent [recordId] with its session's end, if the session
     * ended before the launch result arrived; otherwise null.
     */
    @Synchronized
    fun takeEndedBeforeLaunch(recordId: String): Pair<Finished, SessionEnd>? {
        val sub = tracked[recordId]?.takeIf { it.inBackground } ?: return null
        val end = sub.endedBeforeLaunch ?: return null
        tracked.remove(recordId)
        return sub.finished(recordId) to end
    }

    /** Stops tracking the sub-agent shown by chat entry [recordId], whose tool call ended it. */
    @Synchronized
    fun forget(recordId: String) {
        tracked.remove(recordId)
    }

    /** Stops tracking every sub-agent and returns those that were in the background, in start order. */
    @Synchronized
    fun takeAll(): List<Finished> {
        val background = tracked.entries.filter { it.value.inBackground }.map { it.value.finished(it.key) }
        tracked.clear()
        return background
    }

    companion object {
        private const val TITLE_SEPARATOR = ": "

        /**
         * The tool's own name in [toolTitle], which OpenCode prefixes with the sub-agent's session title as
         * `"<title>: <tool>"`. Unchanged when it has no such prefix.
         */
        @JvmStatic
        fun toolTitle(sessionTitle: String?, toolTitle: String): String {
            val prefix = normalize(sessionTitle)?.let { "$it$TITLE_SEPARATOR" } ?: return toolTitle
            return if (toolTitle.length > prefix.length && toolTitle.startsWith(prefix)) {
                toolTitle.substring(prefix.length)
            } else {
                toolTitle
            }
        }

        private fun normalize(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }
    }
}
