package com.github.catatafishen.agentbridge.client.koog

import ai.koog.prompt.executor.model.PromptExecutor
import com.github.catatafishen.agentbridge.BuildInfo
import com.github.catatafishen.agentbridge.acp.protocol.PromptRequest
import com.github.catatafishen.agentbridge.client.AbstractClient
import com.github.catatafishen.agentbridge.client.ClientPromptException
import com.github.catatafishen.agentbridge.client.ClientSessionException
import com.github.catatafishen.agentbridge.client.ClientStartException
import com.github.catatafishen.agentbridge.model.Model
import com.github.catatafishen.agentbridge.model.PromptResponse
import com.github.catatafishen.agentbridge.model.SessionUpdate
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

/**
 * AgentBridge's built-in agent: no external CLI, no second harness. It calls the model provider directly
 * through Koog's clients and runs every tool call through AgentBridge's own tool layer in-process.
 *
 * Because nothing else supplies a system prompt or built-in tools, the model sees exactly one set of
 * instructions (ours) and exactly the tools AgentBridge exposes: there are no competing instructions
 * to disable and no native tools to filter.
 */
class KoogClient(private val project: Project) : AbstractClient() {

    private class Runtime(
        val executor: PromptExecutor,
        val provider: KoogProviderKind,
        val choices: Map<String, KoogModelChoice>,
    )

    private val log = Logger.getInstance(KoogClient::class.java)

    @Volatile
    private var runtime: Runtime? = null

    @Volatile
    private var selectedModel: String? = null

    private val conversations = ConcurrentHashMap<String, KoogConversation>()
    private val activeTurns = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tools: ToolBackend by lazy { McpToolBackend(project) }

    override fun agentId(): String = KoogSupport.AGENT_ID

    override fun displayName(): String = "Built-in Agent (Koog)"

    override fun start() {
        KoogSettings.configurationProblem()?.let { throw ClientStartException(it) }
        val kind = KoogSettings.provider
        val key = (if (kind == KoogProviderKind.COPILOT) KoogSettings.copilotToken else KoogSettings.openAiApiKey)
            ?: throw ClientStartException("Koog is not authenticated: no credentials are stored.")
        val agent = userAgent()
        val choices = try {
            loadModels(kind, key, agent)
        } catch (e: Exception) {
            val classified = KoogErrors.classify(e, kind.label)
            throw ClientStartException(classified.message, e)
        }
        if (choices.isEmpty()) {
            throw ClientStartException(
                "No usable models were returned by ${kind.label}. The agent needs a model with tool support " +
                    "on the chat-completions endpoint."
            )
        }
        val previous = runtime
        runtime = Runtime(
            KoogProviders.createExecutor(kind, KoogSettings.openAiBaseUrl, key, agent), kind,
            choices.associateBy { it.id },
        )
        selectedModel = KoogSettings.modelId.takeIf { choices.any { c -> c.id == it } } ?: choices.first().id
        previous?.let { closeQuietly(it.executor) }
        log.info("Koog started: provider=${kind.id}, ${choices.size} model(s), selected=$selectedModel")
    }

    override fun stop() {
        activeTurns.values.forEach { it.cancel() }
        activeTurns.clear()
        conversations.clear()
        runtime?.let { closeQuietly(it.executor) }
        runtime = null
        setCurrentSession(null)
    }

    override fun close() {
        stop()
        scope.cancel()
    }

    override fun isConnected(): Boolean = runtime != null

    override fun clearPersistedSession() {
        conversations.clear()
        setCurrentSession(null)
    }

    override fun dropCurrentSession() {
        currentSessionId?.let { conversations.remove(it) }
        super.dropCurrentSession()
    }

    override fun createSession(cwd: String): String {
        val active = runtime ?: throw ClientSessionException("Koog is not started")
        val system = buildSystemPrompt(cwd)
        val streamer = ModelStreamer { prompt, toolDescriptors ->
            val model = selectedModel ?: error("No model selected")
            ExecutorStreamer(active.executor, KoogProviders.toLLModel(active.choices.getValue(model)))
                .stream(prompt, toolDescriptors)
        }
        val id = "koog-" + UUID.randomUUID()
        conversations[id] = KoogConversation(streamer, tools, systemPrompt = { system })
        setCurrentSession(id)
        return id
    }

    override fun cancelSession(sessionId: String) {
        activeTurns[sessionId]?.cancel()
    }

    override fun sendPrompt(request: PromptRequest, onUpdate: Consumer<SessionUpdate>): PromptResponse {
        val sessionId = request.sessionId()
        val conversation = conversations[sessionId]
            ?: throw ClientPromptException("Koog has no session $sessionId. Start a new conversation.")
        val active = runtime ?: throw ClientPromptException("Koog is not started")
        request.modelId()?.takeIf { it.isNotBlank() && active.choices.containsKey(it) }?.let { selectedModel = it }

        val turn = scope.async { conversation.runTurn(request.prompt(), onUpdate::accept) }
        activeTurns[sessionId] = turn
        try {
            val result = runBlocking { turn.await() }
            val usage = if (result.inputTokens != null || result.outputTokens != null) {
                PromptResponse.TurnUsage(result.inputTokens, result.outputTokens, null)
            } else {
                null
            }
            return PromptResponse(result.stopReason, usage)
        } catch (_: CancellationException) {
            if (turn.isCancelled) return PromptResponse("cancelled", null)
            throw InterruptedException("Prompt interrupted")
        } catch (e: Exception) {
            val classified = KoogErrors.classify(e, active.provider.label)
            throw ClientPromptException(classified.message, e)
        } finally {
            activeTurns.remove(sessionId, turn)
        }
    }

    override fun getAvailableModels(): List<Model> =
        runtime?.choices?.values?.map { Model(it.id, it.name, null, null) }.orEmpty()

    override fun getCurrentModelId(): String? = selectedModel

    override fun setModel(sessionId: String, modelId: String) {
        val active = runtime ?: return
        if (!active.choices.containsKey(modelId)) {
            log.warn("Koog: ignoring unknown model '$modelId'")
            return
        }
        selectedModel = modelId
        KoogSettings.modelId = modelId
    }

    override fun modelDisplayMode(): ModelDisplayMode = ModelDisplayMode.NAME

    private fun loadModels(kind: KoogProviderKind, key: String, agent: String): List<KoogModelChoice> = when (kind) {
        KoogProviderKind.COPILOT -> CopilotModels.usable(KoogNetwork.fetchCopilotModels(key, agent))
            .map { KoogModelChoice(it.id, it.name, it.contextWindow, it.maxOutputTokens) }

        // A generic OpenAI-style endpoint has no reliable model list; the user names the model.
        KoogProviderKind.OPENAI_COMPATIBLE -> listOf(KoogModelChoice(KoogSettings.modelId, KoogSettings.modelId))
    }

    private fun buildSystemPrompt(cwd: String): String {
        val preamble = KoogClient::class.java.getResourceAsStream(SYSTEM_PROMPT_RESOURCE)?.use {
            it.readBytes().decodeToString().trim()
        } ?: throw ClientSessionException("Bundled resource missing: $SYSTEM_PROMPT_RESOURCE")
        return systemPrompt(preamble, cwd, tools.instructions())
    }

    private fun userAgent(): String = "AgentBridge/" + BuildInfo.getVersion()

    private fun closeQuietly(executor: PromptExecutor) {
        try {
            (executor as? AutoCloseable)?.close()
        } catch (e: Exception) {
            log.debug("Koog: closing executor failed", e)
        }
    }

    companion object {
        private const val SYSTEM_PROMPT_RESOURCE = "/koog/system-prompt.md"

        /** Our preamble, the project root, then the guidance every AgentBridge agent gets over MCP. */
        @JvmStatic
        fun systemPrompt(preamble: String, projectRoot: String, mcpInstructions: String): String =
            listOf(preamble, "Project root: $projectRoot", mcpInstructions.trim())
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
    }
}
