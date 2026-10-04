package com.github.catatafishen.agentbridge.client.koog

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import com.github.catatafishen.agentbridge.bridge.EntryData
import com.github.catatafishen.agentbridge.model.ContentBlock
import com.github.catatafishen.agentbridge.model.SessionUpdate
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import java.util.UUID

/** Streams one model response. A seam over Koog's [PromptExecutor] so the loop is testable without a network. */
fun interface ModelStreamer {
    fun stream(prompt: Prompt, tools: List<ToolDescriptor>): Flow<StreamFrame>
}

/** [ModelStreamer] backed by a Koog executor and a fixed model. */
class ExecutorStreamer(private val executor: PromptExecutor, private val model: LLModel) : ModelStreamer {
    override fun stream(prompt: Prompt, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        executor.executeStreaming(prompt, model, tools)
}

/** How a turn ended and what it cost, in the vocabulary of the rest of the plugin (ACP stop reasons). */
data class TurnResult(val stopReason: String, val inputTokens: Long?, val outputTokens: Long?)

/**
 * One chat conversation: history plus the agent loop.
 *
 * Each turn sends the history to the model, streams the answer to the UI, runs any tool calls through
 * the [ToolBackend] and repeats until the model answers without calling a tool. The loop is ours, built
 * on Koog's provider clients and message model, because it must (a) keep history across turns,
 * (b) stream into the chat as it arrives, (c) be cancellable from the Stop button, and (d) send every
 * tool call through AgentBridge's existing permission path. Koog's agent graph offers none of that
 * more simply.
 *
 * Not thread-safe: one turn at a time per conversation.
 */
class KoogConversation(
    private val streamer: ModelStreamer,
    private val tools: ToolBackend,
    private val systemPrompt: () -> String,
    /** Most tool calls one turn may make, or 0 for no limit. Read at the start of each turn. */
    private val maxToolCalls: () -> Int = { 0 },
    private val clock: KoogClock = KoogClock.System,
    private val restoreBudgetChars: Int = KoogHistory.DEFAULT_BUDGET_CHARS,
    /** The model's context window in tokens, or null when unknown (then nothing is trimmed). Read every request. */
    private val contextWindow: () -> Long? = { null },
    /** Whether the selected model can be sent images. When false they become a text note. Read every turn. */
    private val acceptsImages: () -> Boolean = { true },
) {
    private val history = mutableListOf<Message>()

    private var pendingRestore: List<EntryData>? = null

    val messageCount: Int get() = history.size

    /**
     * Seeds this new conversation from a stored one. The conversion happens at the start of the first turn,
     * when the tool list is known: a stored call is only kept if this agent has that tool.
     */
    fun restore(entries: List<EntryData>) {
        check(history.isEmpty() && pendingRestore == null) { "Only a new conversation can be restored" }
        pendingRestore = entries
    }

    suspend fun runTurn(user: List<ContentBlock>, onUpdate: (SessionUpdate) -> Unit): TurnResult {
        val specs = tools.listTools()
        applyPendingRestore(specs)
        history += Message.User(PromptText.toParts(user, acceptsImages()), RequestMetaInfo.create(clock))
        val descriptors = specs.map(KoogToolSchemas::toDescriptor)
        val specsByName = specs.associateBy { it.name }
        val system = systemPrompt()
        val overheadChars = system.length.toLong() + specs.sumOf { it.name.length + it.description.length + it.inputSchema.toString().length }

        var inputTokens = 0L
        var outputTokens = 0L
        var sawUsage = false
        var toolCalls = 0
        val limit = maxToolCalls()

        // No fixed step cap: a turn ends when the model stops calling tools, the user stops it, or the configured
        // tool-call limit is reached. Context growth is bounded by compaction, not by counting steps.
        while (true) {
            currentCoroutineContext().ensureActive()
            compactHistory(overheadChars, onUpdate)
            val request = prompt("koog") {
                system(system)
                messages(history.toList())
            }
            val frames = mutableListOf<StreamFrame>()
            streamer.stream(request, descriptors).collect { frame ->
                frames += frame
                emitStreamingUpdate(frame, onUpdate)
            }
            val assistant = assemble(frames)
            assistant.metaInfo.inputTokensCount?.let { inputTokens += it; sawUsage = true }
            assistant.metaInfo.outputTokensCount?.let { outputTokens += it; sawUsage = true }

            val calls = assistant.parts.filterIsInstance<MessagePart.Tool.Call>()
            if (calls.isEmpty()) {
                history += assistant
                return result(stopReasonFor(assistant.finishReason), sawUsage, inputTokens, outputTokens, onUpdate)
            }
            val results = calls.map { call -> executeCall(call, specsByName[call.tool], onUpdate) }
            // Recorded only now, together with the results. If the user stops the turn while a tool runs,
            // history must not keep tool calls that have no results: providers reject such a history, which
            // would break every later turn of the session.
            history += withValidArguments(assistant)
            history += Message.User(results, RequestMetaInfo.create(clock))
            // Checked only after the results are recorded, so the history stays valid for the next turn.
            toolCalls += calls.size
            if (limit > 0 && toolCalls >= limit) {
                return result(STOP_MAX_STEPS, sawUsage, inputTokens, outputTokens, onUpdate)
            }
        }
    }

    /**
     * Trims the history in place when the next request would not comfortably fit the model's window, and tells the user.
     * Done before a request, so the history the model sees is the one that was checked. Trimmed text stays trimmed:
     * the same cut is not repeated on every step.
     */
    private fun compactHistory(overheadChars: Long, onUpdate: (SessionUpdate) -> Unit) {
        val window = contextWindow()?.takeIf { it > 0 } ?: return
        val compacted = KoogCompaction.compact(history.toList(), window, overheadChars)
        if (!compacted.changed) return
        history.clear()
        history += compacted.messages
        val what = listOfNotNull(
            compacted.shrunkResults.takeIf { it > 0 }?.let { "$it old tool result(s) shortened" },
            compacted.droppedTurns.takeIf { it > 0 }?.let { "$it oldest exchange(s) dropped" },
        ).joinToString(", ")
        log.info("Koog trimmed the conversation to fit a $window-token context window: $what")
        onUpdate(
            SessionUpdate.Banner(
                "The conversation was close to the model's context limit, so older parts were trimmed ($what).",
                SessionUpdate.BannerLevel.WARNING,
                SessionUpdate.ClearOn.NEXT_SUCCESS,
            )
        )
    }

    private fun applyPendingRestore(specs: List<McpToolSpec>) {
        val entries = pendingRestore ?: return
        pendingRestore = null
        val restored = KoogHistory.restore(entries, specs.mapTo(HashSet()) { it.name }, restoreBudgetChars, clock)
        history += restored.messages
        log.info(
            "Koog resumed the previous conversation: ${restored.messages.size} message(s) from ${entries.size} stored " +
                "entries; ${restored.droppedTurns} oldest turn(s) left out to fit the budget; " +
                "${restored.skippedToolCalls} tool call(s) skipped because this agent has no such tool"
        )
    }

    private fun emitStreamingUpdate(frame: StreamFrame, onUpdate: (SessionUpdate) -> Unit) {
        when (frame) {
            is StreamFrame.TextDelta ->
                if (frame.text.isNotEmpty()) onUpdate(SessionUpdate.AgentMessageChunk(listOf(ContentBlock.Text(frame.text))))

            is StreamFrame.ReasoningDelta ->
                frame.text?.takeIf { it.isNotEmpty() }
                    ?.let { onUpdate(SessionUpdate.AgentThoughtChunk(listOf(ContentBlock.Thinking(it)))) }

            else -> Unit
        }
    }

    private suspend fun executeCall(
        call: MessagePart.Tool.Call,
        spec: McpToolSpec?,
        onUpdate: (SessionUpdate) -> Unit,
    ): MessagePart.Tool.Result {
        val callId = call.id ?: "koog-" + UUID.randomUUID()
        val kind = SessionUpdate.ToolKind.fromString(spec?.kind)
        onUpdate(
            SessionUpdate.ToolCall(
                callId, spec?.displayName ?: call.tool, call.tool, kind,
                call.args.takeIf { it.isNotBlank() && it != "{}" }, null, null, null, null, null,
            )
        )
        val arguments = parseArguments(call.args)
        val outcome = if (arguments == null) {
            ToolOutcome("Error: the arguments for ${call.tool} are not a valid JSON object: ${call.args}", true)
        } else {
            runInterruptible(Dispatchers.IO) { tools.call(call.tool, arguments, callId) }
        }
        onUpdate(
            SessionUpdate.ToolCallUpdate(
                callId,
                if (outcome.isError) SessionUpdate.ToolCallStatus.FAILED else SessionUpdate.ToolCallStatus.COMPLETED,
                outcome.text.takeIf { !outcome.isError },
                outcome.text.takeIf { outcome.isError },
                null, false, null, call.args.takeIf { it.isNotBlank() }, kind,
            )
        )
        return MessagePart.Tool.Result(id = call.id, tool = call.tool, output = outcome.text, isError = outcome.isError)
    }

    private fun result(
        stopReason: String,
        sawUsage: Boolean,
        input: Long,
        output: Long,
        onUpdate: (SessionUpdate) -> Unit,
    ): TurnResult {
        if (sawUsage) onUpdate(SessionUpdate.TurnUsage(input.toInt(), output.toInt(), null))
        return TurnResult(stopReason, input.takeIf { sawUsage }, output.takeIf { sawUsage })
    }

    companion object {
        private val log = Logger.getInstance(KoogConversation::class.java)

        const val STOP_END_TURN = "end_turn"
        const val STOP_MAX_TOKENS = "max_tokens"
        const val STOP_MAX_STEPS = "max_turn_requests"

        /** Maps a provider finish reason to an ACP stop reason. A cut-off answer is reported, not hidden. */
        @JvmStatic
        fun stopReasonFor(finishReason: String?): String = when (finishReason?.lowercase()) {
            "length", "max_tokens" -> STOP_MAX_TOKENS
            else -> STOP_END_TURN
        }

        /**
         * Collects streamed frames into the assistant message. Equivalent to Koog's `toMessageResponse()`
         * except that tool-call arguments are kept as the raw text the model sent: Koog's version parses
         * them eagerly and throws on malformed JSON, which would abort the whole turn. Here a malformed
         * call is answered with an error the model can learn from.
         */
        @JvmStatic
        internal fun assemble(frames: List<StreamFrame>): Message.Assistant {
            var end: StreamFrame.End? = null
            val parts = frames.mapNotNull<StreamFrame, MessagePart.ResponsePart> { frame ->
                when (frame) {
                    is StreamFrame.ReasoningComplete -> MessagePart.Reasoning(
                        id = frame.id, content = frame.content, summary = frame.summary, encrypted = frame.encrypted,
                    )

                    is StreamFrame.TextComplete -> MessagePart.Text(frame.text)
                    is StreamFrame.ToolCallComplete -> MessagePart.Tool.Call(id = frame.id, tool = frame.name, args = frame.content)
                    is StreamFrame.End -> {
                        end = frame
                        null
                    }

                    else -> null
                }
            }
            return Message.Assistant(
                parts = parts,
                finishReason = end?.finishReason,
                metaInfo = end?.metaInfo ?: ResponseMetaInfo.Empty,
            )
        }

        /**
         * Replaces tool-call arguments that are not a JSON object with `{}` before the message goes into
         * the history. The history is sent to the provider on every request, and a provider rejects a
         * request whose earlier tool call carries unparsable arguments. The model has already been told
         * (in the tool result) what it got wrong.
         */
        @JvmStatic
        internal fun withValidArguments(message: Message.Assistant): Message.Assistant {
            if (message.parts.none { it is MessagePart.Tool.Call && parseArguments(it.args) == null }) return message
            val fixed = message.parts.map { part ->
                if (part is MessagePart.Tool.Call && parseArguments(part.args) == null) {
                    MessagePart.Tool.Call(id = part.id, tool = part.tool, args = "{}")
                } else {
                    part
                }
            }
            return message.copy(parts = fixed)
        }

        /**
         * Parses model-produced tool arguments. Blank means "no arguments"; anything that is not a JSON
         * object returns null so the model is told its call was malformed instead of the turn crashing.
         */
        @JvmStatic
        fun parseArguments(raw: String): JsonObject? {
            if (raw.isBlank()) return JsonObject()
            return try {
                JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject
            } catch (_: com.google.gson.JsonParseException) {
                null
            }
        }
    }
}

/** Turns the chat UI's content blocks into what a chat-completions model receives. */
object PromptText {
    /**
     * The request parts for one user message: text (consecutive text blocks joined) and, when the model can see
     * them, images as real attachments in their original position. An image the model cannot take, or one
     * with unusable data, becomes a note, so the model and the user both know it was not seen.
     */
    @JvmStatic
    fun toParts(blocks: List<ContentBlock>, acceptsImages: Boolean = true): List<MessagePart.RequestPart> {
        val parts = mutableListOf<MessagePart.RequestPart>()
        val text = mutableListOf<String>()
        fun flushText() {
            if (text.isNotEmpty()) parts += MessagePart.Text(text.joinToString("\n\n"))
            text.clear()
        }
        for (block in blocks) {
            if (block is ContentBlock.Image) {
                val image = if (acceptsImages) imagePart(block) else null
                if (image != null) {
                    flushText()
                    parts += image
                } else {
                    text += if (acceptsImages) IMAGE_UNUSABLE else IMAGE_NOT_SEEN
                }
            } else {
                describe(block)?.let { text += it }
            }
        }
        flushText()
        // A message with no content at all still has to be a valid request.
        return parts.ifEmpty { listOf(MessagePart.Text("")) }
    }

    @JvmStatic
    fun flatten(blocks: List<ContentBlock>): String = blocks.mapNotNull(::describe).joinToString("\n\n")

    private fun imagePart(block: ContentBlock.Image): MessagePart.Attachment? {
        val mime = block.mimeType().trim().lowercase()
        val data = block.data().trim().removePrefix("data:$mime;base64,")
        if (!mime.startsWith("image/") || data.isEmpty()) return null
        val source = AttachmentSource.Image(
            content = AttachmentContent.Binary.Base64(data),
            format = mime.substringAfter('/').substringBefore('+'),
            mimeType = mime,
        )
        return MessagePart.Attachment(source)
    }

    private fun describe(block: ContentBlock): String? = when (block) {
        is ContentBlock.Text -> block.text
        is ContentBlock.Resource -> block.resource().let { r ->
            r.text()?.let { text -> "<file path=\"${r.uri()}\">\n$text\n</file>" }
                ?: "[attached resource ${r.uri()} (binary content is not sent)]"
        }

        // A pointer, not content: the model can open it with its own read tools.
        is ContentBlock.ResourceLink -> "[referenced file: ${block.uri()}]"
        is ContentBlock.Image -> IMAGE_NOT_SEEN
        is ContentBlock.Audio -> "[audio attachment is not supported by this agent]"
        is ContentBlock.Thinking -> null
    }

    private const val IMAGE_NOT_SEEN = "[image attachment not sent: the selected model does not accept images]"
    private const val IMAGE_UNUSABLE = "[image attachment not sent: it has no usable image data]"
}
