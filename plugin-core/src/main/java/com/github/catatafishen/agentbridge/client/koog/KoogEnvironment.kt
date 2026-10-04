package com.github.catatafishen.agentbridge.client.koog

import com.github.catatafishen.agentbridge.BuildInfo
import com.github.catatafishen.agentbridge.bridge.EntryData
import com.github.catatafishen.agentbridge.client.ClientSessionException
import com.github.catatafishen.agentbridge.services.ActiveAgentManager
import com.github.catatafishen.agentbridge.session.db.ConversationService
import com.github.catatafishen.agentbridge.settings.StartupInstructionsSettings
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.project.Project

/** A live connection to a model provider; closing it releases the HTTP client. */
interface ProviderConnection : AutoCloseable {
    /** A streamer bound to [model]. Called per request, so a model switch takes effect on the next one. */
    fun streamer(model: KoogModelChoice): ModelStreamer
}

/**
 * Everything [KoogClient] needs from the IDE, the settings and the network. A seam so the client's own logic
 * (lifecycle, cancellation, error mapping, model choice) can be tested with fakes; [IdeKoogEnvironment] is the
 * thin real implementation.
 */
interface KoogEnvironment {
    val tools: ToolBackend

    /** What stops the agent from starting, phrased as the next step to take, or null when it is ready. */
    fun configurationProblem(): String?

    fun provider(): KoogProviderKind

    /** The stored credential for [kind], or null when none is stored. */
    fun credential(kind: KoogProviderKind): String?

    fun preferredModelId(): String

    fun rememberModel(modelId: String)

    /** The models the provider offers. Throws on provider errors; the client turns them into start errors. */
    fun loadModels(kind: KoogProviderKind, credential: String): List<KoogModelChoice>

    fun connect(kind: KoogProviderKind, credential: String): ProviderConnection

    /** The full system prompt for a new session rooted at [cwd]. */
    fun systemPrompt(cwd: String): String

    /**
     * The stored transcript of this project's current conversation, oldest first, or empty when starting
     * fresh. The plugin keeps it for every agent, so it also covers turns another agent took.
     */
    fun previousConversation(): List<EntryData> = emptyList()

    /**
     * Called when the client restored that transcript itself. The shared fallback that prepends a summary to the
     * first prompt must then stay off, or the model would be told the same history twice.
     */
    fun onHistoryRestored() {}

    /** The plugin-wide limit on tool calls per turn; 0 means unlimited. */
    fun maxToolCallsPerTurn(): Int = 0
}

class IdeKoogEnvironment(private val project: Project) : KoogEnvironment {

    override val tools: ToolBackend by lazy { McpToolBackend(project) }

    override fun configurationProblem(): String? = KoogSettings.configurationProblem()

    override fun provider(): KoogProviderKind = KoogSettings.provider

    override fun credential(kind: KoogProviderKind): String? =
        if (kind == KoogProviderKind.COPILOT) KoogSettings.copilotToken else KoogSettings.openAiApiKey

    override fun preferredModelId(): String = KoogSettings.modelId

    override fun rememberModel(modelId: String) {
        KoogSettings.modelId = modelId
    }

    override fun loadModels(kind: KoogProviderKind, credential: String): List<KoogModelChoice> = when (kind) {
        KoogProviderKind.COPILOT -> {
            val session = copilotSessions(credential).current()
            CopilotModels.usable(KoogNetwork.fetchCopilotModels(session.token, session.apiBase, copilotIdentity()))
                .map { KoogModelChoice(it.id, it.name, it.contextWindow, it.maxOutputTokens, it.reasoningEfforts, it.supportsVision) }
        }

        // A generic OpenAI-style endpoint has no reliable model list; the user names the model.
        KoogProviderKind.OPENAI_COMPATIBLE -> listOf(KoogModelChoice(KoogSettings.modelId, KoogSettings.modelId))
    }

    override fun connect(kind: KoogProviderKind, credential: String): ProviderConnection {
        val sessions = if (kind == KoogProviderKind.COPILOT) copilotSessions(credential) else null
        val executor = KoogProviders.createExecutor(
            kind, KoogSettings.openAiBaseUrl, credential, userAgent(),
            sessions = sessions, editorVersion = editorVersion(),
        )
        return object : ProviderConnection {
            override fun streamer(model: KoogModelChoice): ModelStreamer =
                ExecutorStreamer(executor, KoogProviders.toLLModel(model))

            override fun close() {
                (executor as? AutoCloseable)?.close()
            }
        }
    }

    override fun systemPrompt(cwd: String): String {
        val settings = StartupInstructionsSettings.getInstance()
        val guidance = KoogGuidance.compose(
            mcpInstructions = tools.instructions(),
            defaultTemplate = settings.defaultTemplate,
            userCustomized = settings.isUsingCustomInstructions,
            koogVariant = bundled(TOOL_GUIDANCE_RESOURCE),
        )
        return KoogClient.systemPrompt(bundled(SYSTEM_PROMPT_RESOURCE), cwd, guidance)
    }

    override fun previousConversation(): List<EntryData> {
        val service = ConversationService.getInstance(project)
        // The prompt that triggered this call is saved asynchronously; wait so the read sees a settled store.
        service.awaitPendingSave(PENDING_SAVE_WAIT_MS)
        return service.loadRecentEntries(project.basePath)?.entries().orEmpty()
    }

    override fun onHistoryRestored() = ActiveAgentManager.setInjectConversationHistory(project, false)

    override fun maxToolCallsPerTurn(): Int = ActiveAgentManager.getInstance(project).sharedMaxToolCallsPerTurn

    private fun userAgent(): String = "AgentBridge/" + BuildInfo.getVersion()

    private fun editorVersion(): String {
        val info = ApplicationInfo.getInstance()
        return "${info.versionName}/${info.fullVersion}".replace(' ', '-')
    }

    private fun copilotIdentity(): Map<String, String> = CopilotHeaders.identity(userAgent(), editorVersion())

    private var cachedSessions: Pair<String, CopilotSessions>? = null

    /** One session cache per sign-in token, shared by the model list and the chat executor. */
    @Synchronized
    private fun copilotSessions(githubToken: String): CopilotSessions =
        cachedSessions?.takeIf { it.first == githubToken }?.second
            ?: CopilotSessions({ KoogNetwork.fetchCopilotSession(githubToken, copilotIdentity()) })
                .also { cachedSessions = githubToken to it }

    private fun bundled(resource: String): String =
        IdeKoogEnvironment::class.java.getResourceAsStream(resource)?.use { it.readBytes().decodeToString().trim() }
            ?: throw ClientSessionException("Bundled resource missing: $resource")

    private companion object {
        const val SYSTEM_PROMPT_RESOURCE = "/koog/system-prompt.md"
        const val TOOL_GUIDANCE_RESOURCE = "/koog/tool-guidance.md"
        const val PENDING_SAVE_WAIT_MS = 5_000L
    }
}
