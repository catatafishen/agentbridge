package com.github.catatafishen.agentbridge.ui

/**
 * Finds which AgentBridge tool the title of an agent's tool call names.
 *
 * Every agent writes the title in its own way: the bare id, an id behind the name of the MCP server
 * (`agentbridge-run_nested_session`, `agentbridge_…`, `mcp_agentbridge_…`, `@agentbridge/…`,
 * `Running: @agentbridge/…`), or the display name (`Run Nested Session`). The client of an agent strips the prefix it
 * knows, but whatever it does not know would leave the tool unrecognised, and a tool that is not recognised loses
 * what depends on it, such as the colour of its chip.
 *
 * Pure logic, no IDE and no UI, so it is tested alone.
 */
internal object ToolTitleResolver {

    /**
     * What may stand between a server prefix and the tool id. A space is left out on purpose: an id after a space
     * would match the end of any sentence, such as "Find usages in sleep".
     */
    private const val SEPARATORS = "-_/:"

    /**
     * The id of the tool [title] names, or `null` when it names none.
     *
     * In this order: the title is an id; the title is a display name; the title ends with an id behind a separator,
     * where the longest id wins, so `mcp_agentbridge_git_status` is `git_status` and not `status`.
     *
     * @param ids the ids of all tools
     * @param idOfDisplayName the id of the tool with this display name, ignoring case, or `null`
     */
    fun resolve(title: String, ids: Collection<String>, idOfDisplayName: (String) -> String?): String? {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed in ids) return trimmed
        idOfDisplayName(trimmed)?.let { return it }
        return ids.asSequence().filter { endsWithIdBehindSeparator(trimmed, it) }.maxByOrNull { it.length }
    }

    private fun endsWithIdBehindSeparator(title: String, id: String): Boolean =
        id.isNotEmpty() && title.length > id.length && title.endsWith(id) &&
            title[title.length - id.length - 1] in SEPARATORS
}
