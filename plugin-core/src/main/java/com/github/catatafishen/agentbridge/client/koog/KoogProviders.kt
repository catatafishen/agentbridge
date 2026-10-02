package com.github.catatafishen.agentbridge.client.koog

import ai.koog.http.client.java.JavaKoogHttpClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

/** Where the model comes from. */
enum class KoogProviderKind(val id: String, val label: String) {
    COPILOT("copilot", "GitHub Copilot subscription"),
    OPENAI_COMPATIBLE("openai", "OpenAI-compatible API (API key)");

    companion object {
        @JvmStatic
        fun fromId(id: String?): KoogProviderKind = entries.firstOrNull { it.id == id } ?: COPILOT
    }
}

/** A model the user can choose, independent of Koog types. */
data class KoogModelChoice(
    val id: String,
    val name: String,
    val contextLength: Long? = null,
    val maxOutputTokens: Long? = null,
)

/** Builds Koog executors and models for the supported providers. */
object KoogProviders {
    private const val DEFAULT_OPENAI_BASE = "https://api.openai.com"
    private const val DEFAULT_CHAT_PATH = "v1/chat/completions"

    /** An OpenAI-style base URL split into what Koog wants: a host base and the chat path. */
    data class Endpoint(val baseUrl: String, val chatPath: String)

    /**
     * Accepts what people paste from provider docs. Many give a base that already ends in `/v1`
     * (OpenRouter, Ollama, most gateways), while Koog appends `v1/chat/completions` itself.
     */
    @JvmStatic
    fun openAiEndpoint(userInput: String?): Endpoint {
        val trimmed = userInput?.trim()?.trimEnd('/').orEmpty()
        if (trimmed.isEmpty()) return Endpoint(DEFAULT_OPENAI_BASE, DEFAULT_CHAT_PATH)
        val versioned = Regex("^(.*)/(v\\d+)$").matchEntire(trimmed)
        return if (versioned != null) {
            Endpoint(versioned.groupValues[1], "${versioned.groupValues[2]}/chat/completions")
        } else {
            Endpoint(trimmed, DEFAULT_CHAT_PATH)
        }
    }

    /**
     * @param baseUrl the OpenAI-compatible endpoint (ignored for Copilot)
     * @param copilotBaseUrl the Copilot API host; only differs from the default for GitHub Enterprise or tests
     * @param sessions when given (Copilot), the session token and API host come from here and the token is
     *   renewed per request, so a long conversation outlives one session; [apiKey] and [copilotBaseUrl] are then unused
     */
    @JvmStatic
    @JvmOverloads
    fun createExecutor(
        kind: KoogProviderKind,
        baseUrl: String?,
        apiKey: String,
        userAgent: String,
        copilotBaseUrl: String = CopilotHeaders.API_BASE,
        sessions: CopilotSessions? = null,
        editorVersion: String = CopilotHeaders.DEFAULT_EDITOR_VERSION,
    ): PromptExecutor {
        val jdk = JavaKoogHttpClient.Factory()
        return when (kind) {
            KoogProviderKind.COPILOT -> {
                val perRequest: (Any?) -> Map<String, String> = if (sessions == null) {
                    CopilotHeaders::perRequest
                } else {
                    { body -> CopilotHeaders.perRequest(body) + ("Authorization" to "Bearer ${sessions.current().token}") }
                }
                val factory = HeaderInjectingHttpClientFactory(
                    jdk, CopilotHeaders.fixed(userAgent, editorVersion), perRequest, CopilotStreamChunks::normalize,
                )
                val session = sessions?.current()
                val settings = OpenAIClientSettings(
                    baseUrl = session?.apiBase ?: copilotBaseUrl,
                    chatCompletionsPath = CopilotHeaders.CHAT_COMPLETIONS_PATH,
                )
                MultiLLMPromptExecutor(OpenAILLMClient(session?.token ?: apiKey, settings, factory))
            }

            KoogProviderKind.OPENAI_COMPATIBLE -> {
                val endpoint = openAiEndpoint(baseUrl)
                val factory = HeaderInjectingHttpClientFactory(
                    jdk, mapOf("User-Agent" to userAgent), JsonContentType::perRequest,
                )
                val settings = OpenAIClientSettings(baseUrl = endpoint.baseUrl, chatCompletionsPath = endpoint.chatPath)
                MultiLLMPromptExecutor(OpenAILLMClient(apiKey, settings, factory))
            }
        }
    }

    /**
     * Koog needs a model descriptor to route and shape requests. Both providers speak OpenAI chat
     * completions, so the model is described as an OpenAI one with tool support.
     */
    @JvmStatic
    fun toLLModel(choice: KoogModelChoice): LLModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = choice.id,
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Tools,
            LLMCapability.Temperature,
            LLMCapability.OpenAIEndpoint.Completions,
        ),
        contextLength = choice.contextLength,
        maxOutputTokens = choice.maxOutputTokens,
    )
}
