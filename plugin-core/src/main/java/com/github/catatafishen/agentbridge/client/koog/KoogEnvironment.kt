package com.github.catatafishen.agentbridge.client.koog

import com.github.catatafishen.agentbridge.BuildInfo
import com.github.catatafishen.agentbridge.client.ClientSessionException
import com.github.catatafishen.agentbridge.settings.StartupInstructionsSettings
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
}

class IdeKoogEnvironment(project: Project) : KoogEnvironment {

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
        KoogProviderKind.COPILOT -> CopilotModels.usable(KoogNetwork.fetchCopilotModels(credential, userAgent()))
            .map { KoogModelChoice(it.id, it.name, it.contextWindow, it.maxOutputTokens) }

        // A generic OpenAI-style endpoint has no reliable model list; the user names the model.
        KoogProviderKind.OPENAI_COMPATIBLE -> listOf(KoogModelChoice(KoogSettings.modelId, KoogSettings.modelId))
    }

    override fun connect(kind: KoogProviderKind, credential: String): ProviderConnection {
        val executor = KoogProviders.createExecutor(kind, KoogSettings.openAiBaseUrl, credential, userAgent())
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

    private fun userAgent(): String = "AgentBridge/" + BuildInfo.getVersion()

    private fun bundled(resource: String): String =
        IdeKoogEnvironment::class.java.getResourceAsStream(resource)?.use { it.readBytes().decodeToString().trim() }
            ?: throw ClientSessionException("Bundled resource missing: $resource")

    private companion object {
        const val SYSTEM_PROMPT_RESOURCE = "/koog/system-prompt.md"
        const val TOOL_GUIDANCE_RESOURCE = "/koog/tool-guidance.md"
    }
}
