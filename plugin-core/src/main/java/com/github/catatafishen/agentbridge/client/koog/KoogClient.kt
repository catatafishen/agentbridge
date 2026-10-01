package com.github.catatafishen.agentbridge.client.koog

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
 *
 * Everything that touches the IDE, the settings or the network goes through [KoogEnvironment], so the
 * logic here is testable without an IDE.
 */
class KoogClient(private val env: KoogEnvironment) : AbstractClient() {

    /** The constructor the registry uses. */
    constructor(project: Project) : this(IdeKoogEnvironment(project))

    private class Runtime(
        val connection: ProviderConnection,
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

    override fun agentId(): String = KoogSupport.AGENT_ID

    override fun displayName(): String = "Built-in Agent (Koog)"

    override fun start() {
        env.configurationProblem()?.let { throw ClientStartException(it) }
        val kind = env.provider()
        val credential = env.credential(kind)
            ?: throw ClientStartException("Koog is not authenticated: no credentials are stored.")
        val choices = try {
            env.loadModels(kind, credential)
        } catch (e: Exception) {
            throw ClientStartException(KoogErrors.classify(e, kind.label).message, e)
        }
        if (choices.isEmpty()) {
            throw ClientStartException(
                "No usable models were returned by ${kind.label}. The agent needs a model with tool support " +
                    "on the chat-completions endpoint."
            )
        }
        val previous = runtime
        runtime = Runtime(env.connect(kind, credential), kind, choices.associateBy { it.id })
        selectedModel = env.preferredModelId().takeIf { id -> choices.any { it.id == id } } ?: choices.first().id
        previous?.let { closeQuietly(it.connection) }
        log.info("Koog started: provider=${kind.id}, ${choices.size} model(s), selected=$selectedModel")
    }

    override fun stop() {
        activeTurns.values.forEach { it.cancel() }
        activeTurns.clear()
        conversations.clear()
        runtime?.let { closeQuietly(it.connection) }
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
        val system = env.systemPrompt(cwd)
        val streamer = ModelStreamer { prompt, toolDescriptors ->
            val model = selectedModel ?: error("No model selected")
            active.connection.streamer(active.choices.getValue(model)).stream(prompt, toolDescriptors)
        }
        val id = "koog-" + UUID.randomUUID()
        conversations[id] = KoogConversation(streamer, env.tools, systemPrompt = { system })
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
            // The user pressed Stop: a normal end, reported with the ACP "cancelled" stop reason.
            if (turn.isCancelled) return PromptResponse("cancelled", null)
            throw InterruptedException("Prompt interrupted")
        } catch (e: Exception) {
            throw ClientPromptException(KoogErrors.classify(e, active.provider.label).message, e)
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
        env.rememberModel(modelId)
    }

    override fun modelDisplayMode(): ModelDisplayMode = ModelDisplayMode.NAME

    private fun closeQuietly(connection: AutoCloseable) {
        try {
            connection.close()
        } catch (e: Exception) {
            log.debug("Koog: closing the provider connection failed", e)
        }
    }

    companion object {
        /** Our preamble, the project root, then tool guidance (see [KoogGuidance] for which text that is). */
        @JvmStatic
        fun systemPrompt(preamble: String, projectRoot: String, mcpInstructions: String): String =
            listOf(preamble, "Project root: $projectRoot", mcpInstructions.trim())
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
    }
}
