package com.github.catatafishen.agentbridge.settings.defaults

import com.github.catatafishen.agentbridge.custommcp.CustomMcpSettings
import com.github.catatafishen.agentbridge.memory.MemorySettings
import com.github.catatafishen.agentbridge.psi.graph.CodeGraphSettings
import com.github.catatafishen.agentbridge.services.ActiveAgentManager
import com.github.catatafishen.agentbridge.services.AgentProfileManager
import com.github.catatafishen.agentbridge.services.CleanupSettings
import com.github.catatafishen.agentbridge.services.GenericSettings
import com.github.catatafishen.agentbridge.services.ToolRegistry
import com.github.catatafishen.agentbridge.settings.ChatHistorySettings
import com.github.catatafishen.agentbridge.settings.DiagnosticFilterSettings
import com.github.catatafishen.agentbridge.settings.McpServerSettings

/**
 * Every group of project settings that can be a global default.
 *
 * What is left out on purpose:
 * - The web server ([com.github.catatafishen.agentbridge.settings.ChatWebServerSettings]) and the MCP server's
 *   port and "static port": they are specific to a project (two projects must not claim the same port).
 * - Everything that is runtime state rather than a choice, which lives in the same stores: resume and thread ids,
 *   billing counters, the transient conversation-injection flag, UI layout and dismissed banners.
 * - Settings that are already application-wide (agent profiles, chat input, storage location).
 */
object DefaultsSections {

    val all: List<DefaultsSection> = listOf(
        StateDefaultsSection(
            id = "mcp-server",
            title = "MCP server and tools",
            description = "Auto-start, transport, session limits, which tools are enabled, appearance and review " +
                "options. The port and \"static port\" stay as they are in each project.",
            storageFile = "mcpServer.xml",
            component = { McpServerSettings.getInstance(it) },
            stateClass = McpServerSettings.State::class.java,
            keep = ::keepProjectPort,
        ),
        StateDefaultsSection(
            id = "custom-mcp",
            title = "Custom MCP servers",
            description = "The external MCP servers you added. Includes their environment variables and headers, " +
                "which may hold tokens: they are stored unencrypted, like they are in the project.",
            storageFile = "customMcp.xml",
            component = { CustomMcpSettings.getInstance(it) },
            stateClass = CustomMcpSettings.State::class.java,
        ),
        PropertyDefaultsSection(
            id = "tool-permissions",
            title = "Tool permissions",
            description = "Allow / ask / deny per tool, and the policy for paths outside the project.",
            keys = { project ->
                ToolRegistry.getInstance(project).allTools.map { GenericSettings.toolPermissionKey(it.id()) } +
                    GenericSettings.outsideProjectAccessKey()
            },
        ),
        PropertyDefaultsSection(
            id = "agent",
            title = "Agent behaviour",
            description = "Which agent is active, auto-connect, follow-agent options and the turn timeouts.",
            keys = { ActiveAgentManager.USER_CHOICE_KEYS },
        ),
        PropertyDefaultsSection(
            id = "client-options",
            title = "Agent client options",
            description = "Per agent: model, agent, mode, effort and limits. Options an agent reports itself " +
                "(other than effort) are not copied.",
            keys = { AgentProfileManager.getInstance().allProfiles.flatMap { GenericSettings.userChoiceKeys(it.id) } },
        ),
        StateDefaultsSection(
            id = "chat-history",
            title = "Chat history limits",
            description = "How many turns are kept, restored and loaded. Not the conversations themselves.",
            storageFile = "chatHistory.xml",
            component = { ChatHistorySettings.getInstance(it) },
            stateClass = ChatHistorySettings.State::class.java,
        ),
        StateDefaultsSection(
            id = "memory",
            title = "Memory",
            description = "Semantic memory options.",
            storageFile = "agentbridgeMemory.xml",
            component = { MemorySettings.getInstance(it) },
            stateClass = MemorySettings.State::class.java,
        ),
        StateDefaultsSection(
            id = "code-graph",
            title = "Code graph",
            description = "Knowledge graph and git-history indexing options.",
            storageFile = "agentbridgeCodeGraph.xml",
            component = { CodeGraphSettings.getInstance(it) },
            stateClass = CodeGraphSettings.State::class.java,
        ),
        StateDefaultsSection(
            id = "diagnostic-filters",
            title = "Diagnostic filters",
            description = "Which severities and inspections are shown to agents.",
            storageFile = "agentbridge-diagnostic-filter.xml",
            component = { DiagnosticFilterSettings.getInstance(it) },
            stateClass = DiagnosticFilterSettings.State::class.java,
        ),
        StateDefaultsSection(
            id = "cleanup",
            title = "Resource cleanup",
            description = "When agent terminals, tabs and scratch files are cleaned up.",
            storageFile = "ideAgentCleanup.xml",
            component = { CleanupSettings.getInstance(it) },
            stateClass = CleanupSettings.State::class.java,
        ),
    )

    /**
     * Sections whose text may hold credentials. They start unchecked on the settings page, so copying them is
     * something the user does on purpose.
     */
    val sensitiveIds: Set<String> = setOf("custom-mcp")

    /**
     * The files under `.idea` that exist once a project has been configured, including the web server's, which
     * is not a section but tells the same thing.
     */
    val projectFiles: List<String> =
        all.filterIsInstance<StateDefaultsSection<*>>().map { it.storageFile } + "chatWebServer.xml"

    fun engine(): DefaultsEngine = DefaultsEngine(all, GlobalDefaults.getInstance())

    /**
     * The MCP server's port belongs to one project: two projects that both got the default's port would fight
     * over it. Whatever the project has stays.
     */
    internal fun keepProjectPort(current: McpServerSettings.State, incoming: McpServerSettings.State) {
        incoming.port = current.port
        incoming.isStaticPort = current.isStaticPort
    }
}
