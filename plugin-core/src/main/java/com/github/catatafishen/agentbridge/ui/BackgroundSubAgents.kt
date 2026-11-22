package com.github.catatafishen.agentbridge.ui

/**
 * Sub-agents that keep working after the tool call that started them has completed (OpenCode 2.3's
 * `subagent` tool in background mode), and the tool calls they make meanwhile.
 *
 * The protocol says neither which sub-agent a tool call belongs to nor when a background sub-agent finishes:
 * - OpenCode titles the sub-agent's tool calls `"<description>: <tool>"`, so they are matched to the
 *   sub-agent by its description. They never get a completion update.
 * - The sub-agent's report reaches the chat only as a message the main agent sends after its turn ended.
 *   With one sub-agent running, that message is its report; with several, there is no telling whose it is.
 *
 * Thread-safe: launches and tool calls arrive on the ACP reader thread, the end of the post-turn window on
 * a scheduler thread.
 */
class BackgroundSubAgents {

    /** A tool call made by a background sub-agent: [acpId] identifies it to the tool call tracker, [recordId] in the chat. */
    data class ChildTool(val acpId: String, val recordId: String)

    /** A background sub-agent that is no longer tracked, with the tool calls it made. */
    data class Finished(val recordId: String, val children: List<ChildTool>)

    /** The background sub-agent a tool call belongs to, and the tool's name without the sub-agent's description. */
    data class Owner(val recordId: String, val toolTitle: String)

    /** [titlePrefix] is `"<description>: "`, or null when the sub-agent has no description to match by. */
    private class Running(val titlePrefix: String?) {
        val children = mutableListOf<ChildTool>()
    }

    /** By chat record id, in launch order. */
    private val running = LinkedHashMap<String, Running>()

    /** Starts tracking the sub-agent shown by chat entry [recordId]; [description] is what it was asked to do. */
    @Synchronized
    fun launched(recordId: String, description: String?) {
        running[recordId] = Running(description?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it$TITLE_SEPARATOR" })
    }

    /**
     * The background sub-agent whose description prefixes [toolTitle] as `"<description>: "`, or null. When
     * several match, the longest description wins, so "Fix tests" never claims a tool call of "Fix tests: unit".
     */
    @Synchronized
    fun ownerOf(toolTitle: String): Owner? {
        var best: Owner? = null
        var bestPrefixLength = -1
        for ((recordId, sub) in running) {
            val prefix = sub.titlePrefix ?: continue
            if (prefix.length > bestPrefixLength && toolTitle.length > prefix.length && toolTitle.startsWith(prefix)) {
                best = Owner(recordId, toolTitle.substring(prefix.length))
                bestPrefixLength = prefix.length
            }
        }
        return best
    }

    /** Records that the sub-agent [ownerRecordId] made the tool call [child]. Ignored once the sub-agent is finished. */
    @Synchronized
    fun addChildTool(ownerRecordId: String, child: ChildTool) {
        running[ownerRecordId]?.children?.add(child)
    }

    /** Stops tracking and returns the one running sub-agent, or null when none or several are running. */
    @Synchronized
    fun takeIfOnlyOne(): Finished? {
        val only = running.entries.singleOrNull() ?: return null
        running.clear()
        return Finished(only.key, only.value.children.toList())
    }

    /** Stops tracking and returns every running sub-agent, in launch order. */
    @Synchronized
    fun takeAll(): List<Finished> {
        val all = running.map { (recordId, sub) -> Finished(recordId, sub.children.toList()) }
        running.clear()
        return all
    }

    private companion object {
        const val TITLE_SEPARATOR = ": "
    }
}
