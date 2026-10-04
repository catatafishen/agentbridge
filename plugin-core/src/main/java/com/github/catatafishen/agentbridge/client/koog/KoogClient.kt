package com.github.catatafishen.agentbridge.client.koog

import com.github.catatafishen.agentbridge.acp.protocol.PromptRequest
import com.github.catatafishen.agentbridge.bridge.SessionOption
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

    /** The chosen reasoning-effort level, or null for the provider's default. Applies to whichever model is selected. */
    @Volatile
    private var effort: String? = null

    /** False only between a new conversation (or a dropped session) and the session created next. */
    @Volatile
    private var restoreNext = true

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
        // A new conversation starts empty even if the stored one has not been reset yet by the time the next
        // session is created.
        restoreNext = false
    }

    override fun dropCurrentSession() {
        currentSessionId?.let { conversations.remove(it) }
        super.dropCurrentSession()
        // The session was dropped because it went wrong; the stored transcript still holds what went wrong.
        restoreNext = false
    }

    @Synchronized
    override fun createSession(cwd: String): String {
        val active = runtime ?: throw ClientSessionException("Koog is not started")
        // The chat calls this before every prompt and relies on a live session being reused. A new one here would
        // start the next prompt with an empty history, so the model would forget everything said before.
        currentSessionId?.takeIf { conversations.containsKey(it) }?.let { return it }

        val streamer = ModelStreamer { prompt, toolDescriptors ->
            val model = selectedModel ?: error("No model selected")
            val choice = active.choices.getValue(model)
            // The effort is read per request, so a menu change applies to the very next one.
            val params = KoogProviders.paramsFor(effort, choice.reasoningEfforts)
            val request = if (params != null) prompt.withParams(params) else prompt
            active.connection.streamer(choice).stream(request, toolDescriptors)
        }
        // Read at the start of every turn, not once per session: edits to the startup instructions, the tool
        // guidance or the memory then apply to the next message instead of the next new conversation.
        val conversation = KoogConversation(
            streamer, env.tools,
            systemPrompt = { env.systemPrompt(cwd) },
            contextWindow = { selectedModel?.let { active.choices[it]?.contextLength } },
        )
        restorePrevious(conversation)
        val id = "koog-" + UUID.randomUUID()
        conversations[id] = conversation
        setCurrentSession(id)
        return id
    }

    /**
     * Gives a new conversation the stored one's history, so a restart or an agent switch keeps the thread. Skipped
     * once after a new conversation was started or a session was dropped.
     */
    private fun restorePrevious(conversation: KoogConversation) {
        val skip = !restoreNext
        restoreNext = true
        if (skip) return
        val previous = env.previousConversation()
        if (previous.isEmpty()) return
        conversation.restore(previous)
        env.onHistoryRestored()
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
        runtime?.choices?.values?.map { Model(it.id, it.name, describe(it), null) }.orEmpty()

    /** What the model picker can show about a model beyond its name; null when the provider told us nothing. */
    private fun describe(choice: KoogModelChoice): String? {
        val parts = listOfNotNull(
            choice.contextLength?.let { "${formatTokens(it)} context" },
            choice.maxOutputTokens?.let { "${formatTokens(it)} max output" },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

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

    /**
     * The reasoning-effort choice, offered only while the selected model says it takes one. The levels come from the
     * provider's own catalog, so a model never gets a level it would reject. The empty value means "the provider's
     * default": nothing is sent.
     */
    override fun listSessionOptions(): List<SessionOption> {
        val levels = KoogProviders.sendableEfforts(runtime?.choices?.get(selectedModel)?.reasoningEfforts.orEmpty())
        if (levels.isEmpty()) return emptyList()
        val labels = (listOf("" to "Default") + levels.map { it to it.replaceFirstChar(Char::uppercase) }).toMap()
        return listOf(SessionOption(KoogProviders.EFFORT_OPTION_KEY, "Reasoning effort", listOf("") + levels, labels))
    }

    override fun setSessionOption(sessionId: String, key: String, value: String) {
        if (key != KoogProviders.EFFORT_OPTION_KEY) return
        effort = value.trim().lowercase().takeIf { it.isNotEmpty() }
    }

    private fun closeQuietly(connection: AutoCloseable) {
        try {
            connection.close()
        } catch (e: Exception) {
            log.debug("Koog: closing the provider connection failed", e)
        }
    }

    companion object {
        /** `128000` as `128k`, `1048576` as `1M`, so a model's limits read at a glance. */
        @JvmStatic
        fun formatTokens(tokens: Long): String = when {
            tokens >= 1_000_000 && tokens % 1_000_000 < 50_000 -> "${tokens / 1_000_000}M tokens"
            tokens >= 1_000_000 -> "%.1fM tokens".format(tokens / 1_000_000.0)
            tokens >= 1_000 -> "${(tokens + 500) / 1_000}k tokens"
            else -> "$tokens tokens"
        }

        /** Our preamble, the project root, then tool guidance (see [KoogGuidance] for which text that is). */
        @JvmStatic
        fun systemPrompt(preamble: String, projectRoot: String, mcpInstructions: String): String =
            listOf(preamble, "Project root: $projectRoot", mcpInstructions.trim())
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
    }
}
