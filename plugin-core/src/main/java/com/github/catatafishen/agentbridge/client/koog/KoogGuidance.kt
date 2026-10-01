package com.github.catatafishen.agentbridge.client.koog

/**
 * Chooses the tool guidance the Koog agent's system prompt carries.
 *
 * AgentBridge's default startup instructions are written for every agent, including CLIs that bring
 * their own built-in tools and may defer tool schemas. Parts of them ("never use the agent's native
 * Run Command", "look a capability up before guessing a name") are redundant or misleading here, where
 * there are no built-in tools and every schema is sent in full. So, unless the user has replaced the
 * shared instructions, the default text is swapped for a Koog-specific variant.
 *
 * The MCP `initialize` instructions are the shared text followed, when enabled, by a memory section.
 * That tail is kept.
 */
object KoogGuidance {

    /**
     * @param mcpInstructions what the in-process MCP handler returned from `initialize`
     * @param defaultTemplate the bundled default for the shared startup instructions
     * @param userCustomized whether the user replaced the shared instructions in settings
     * @param koogVariant the Koog-specific replacement for [defaultTemplate]
     */
    @JvmStatic
    fun compose(mcpInstructions: String, defaultTemplate: String, userCustomized: Boolean, koogVariant: String): String {
        // A user who edited the shared instructions chose that text for every agent; honour it as written.
        if (userCustomized) return mcpInstructions
        // The default is always the prefix when not customised. If it is not, something changed in how the
        // handler builds its instructions: use the handler's text unchanged rather than guess what to cut.
        if (!mcpInstructions.startsWith(defaultTemplate)) return mcpInstructions
        val tail = mcpInstructions.removePrefix(defaultTemplate).trim()
        return listOf(koogVariant.trim(), tail).filter { it.isNotEmpty() }.joinToString("\n\n")
    }
}
