package com.github.catatafishen.agentbridge.client.koog

import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.llm.LLMCapability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CopilotModelsHeadersAndProvidersTest {

    @Nested
    inner class Models {
        private val catalog = """{"data":[
            {"id":"gpt-4.1","name":"GPT-4.1","model_picker_enabled":true,
             "capabilities":{"type":"chat","supports":{"tool_calls":true},"limits":{"max_context_window_tokens":128000,"max_output_tokens":16384}},
             "supported_endpoints":["/chat/completions"]},
            {"id":"claude-sonnet","name":"Claude Sonnet","model_picker_enabled":true,
             "capabilities":{"type":"chat","supports":{"tool_calls":true},"limits":{"max_prompt_tokens":90000}},
             "supported_endpoints":["/v1/messages"]},
            {"id":"gpt-5-codex","name":"Codex","model_picker_enabled":true,
             "capabilities":{"type":"chat","supports":{"tool_calls":true}},"supported_endpoints":["/responses"]},
            {"id":"hidden-chat","name":"Hidden Chat","model_picker_enabled":false,
             "capabilities":{"type":"chat","supports":{"tool_calls":true}},"supported_endpoints":["/chat/completions"]},
            {"id":"unknown-tools","name":"Unknown Tools","model_picker_enabled":false,
             "capabilities":{"type":"chat","supports":{}},"supported_endpoints":["/chat/completions"]},
            {"id":"embedding","name":"Embed","model_picker_enabled":false,"capabilities":{"type":"embeddings","supports":{}}},
            {"id":"no-tools","name":"No tools","model_picker_enabled":true,
             "capabilities":{"type":"chat","supports":{"tool_calls":false}}},
            {"id":"completion","name":"Completion","model_picker_enabled":false,
             "capabilities":{"type":"completion","supports":{"tool_calls":true}}},
            {"id":"blocked","name":"Blocked","model_picker_enabled":true,"policy":{"state":"disabled"},
             "capabilities":{"type":"chat","supports":{"tool_calls":true}}},
            {"name":"nameless id is skipped"}]}"""

        @Test
        fun `the catalog retains picker-hidden models but drops disabled policy entries`() {
            val ids = CopilotModels.parse(catalog).map { it.id }

            assertEquals(
                listOf(
                    "gpt-4.1",
                    "claude-sonnet",
                    "gpt-5-codex",
                    "hidden-chat",
                    "unknown-tools",
                    "embedding",
                    "no-tools",
                    "completion",
                ),
                ids,
            )
        }

        @Test
        fun `limits and endpoints are read`() {
            val model = CopilotModels.parse(catalog).first { it.id == "gpt-4.1" }

            assertEquals(128000L, model.contextWindow)
            assertEquals(16384L, model.maxOutputTokens)
            assertEquals(listOf("/chat/completions"), model.endpoints)
        }

        @Test
        fun `the context window falls back to the prompt limit`() {
            assertEquals(90000L, CopilotModels.parse(catalog).first { it.id == "claude-sonnet" }.contextWindow)
        }

        @Test
        fun `usable keeps compatible chat completions models even when picker or tool metadata is absent`() {
            val ids = CopilotModels.usable(CopilotModels.parse(catalog)).map { it.id }

            // Other endpoints, non-chat types, and explicitly non-tool models are excluded.
            assertEquals(listOf("gpt-4.1", "hidden-chat", "unknown-tools"), ids)
        }

        @Test
        fun `a model without an endpoint list is assumed to use chat completions`() {
            val model = CopilotModel("m", "M", true, "chat", null, null, emptyList())

            assertTrue(model.usesChatCompletions)
        }

        @Test
        fun `describe lists every raw entry including ones the filters later drop`() {
            val lines = CopilotModels.describe(catalog)

            assertEquals(10, lines.size)
            assertTrue(lines[0].contains("id=gpt-4.1") && lines[0].contains("endpoints=/chat/completions"))
            assertTrue(lines.any { it.contains("id=blocked") && it.contains("policy=disabled") })
            assertTrue(lines.any { it.contains("id=hidden-chat") && it.contains("picker=false") })
        }

        @Test
        fun `describe reports a catalog without a data array`() {
            assertEquals(listOf("catalog has no data array"), CopilotModels.describe("not json"))
        }

        @Test
        fun `a model that lists reasoning efforts exposes them, in the order the catalog gives them`() {
            val json = """{"data":[
                {"id":"o","name":"O","capabilities":{"type":"chat","supports":{"tool_calls":true,"reasoning_effort":["Low","medium","HIGH","low"," "]}}},
                {"id":"plain","name":"Plain","capabilities":{"type":"chat","supports":{"tool_calls":true}}},
                {"id":"odd","name":"Odd","capabilities":{"type":"chat","supports":{"reasoning_effort":"high"}}}]}"""

            val byId = CopilotModels.parse(json).associateBy { it.id }

            assertEquals(listOf("low", "medium", "high"), byId.getValue("o").reasoningEfforts)
            assertTrue(byId.getValue("plain").reasoningEfforts.isEmpty())
            // A value that is not a list is ignored rather than guessed at.
            assertTrue(byId.getValue("odd").reasoningEfforts.isEmpty())
        }

        @Test
        fun `describe shows the efforts so a missing option can be traced to the catalog`() {
            val line = CopilotModels.describe(
                """{"data":[{"id":"o","capabilities":{"type":"chat","supports":{"reasoning_effort":["low","high"]}}}]}"""
            ).single()

            assertTrue(line.contains("effort=low,high"), line)
        }

        @Test
        fun `bad json yields an empty catalog`() {
            assertTrue(CopilotModels.parse("not json").isEmpty())
            assertTrue(CopilotModels.parse("""{"data":"nope"}""").isEmpty())
            assertTrue(CopilotModels.parse("{}").isEmpty())
        }
    }

    @Nested
    inner class Headers {
        @Test
        fun `a request ending in a user message is a user turn`() {
            assertEquals("user", CopilotHeaders.initiator("""{"messages":[{"role":"system"},{"role":"user","content":"hi"}]}"""))
        }

        @Test
        fun `a request continuing after a tool result is an agent turn`() {
            val body = """{"messages":[{"role":"user"},{"role":"assistant","tool_calls":[]},{"role":"tool","content":"x"}]}"""

            assertEquals("agent", CopilotHeaders.initiator(body))
        }

        @Test
        fun `an unreadable or empty body counts as a user turn`() {
            assertEquals("user", CopilotHeaders.initiator("not json"))
            assertEquals("user", CopilotHeaders.initiator("""{"messages":[]}"""))
            assertEquals("user", CopilotHeaders.initiator(null))
            assertEquals("user", CopilotHeaders.initiator(42))
        }

        @Test
        fun `per request headers carry the initiator and force a JSON content type`() {
            assertEquals(
                mapOf("x-initiator" to "agent", "Content-Type" to "application/json"),
                CopilotHeaders.perRequest("""{"messages":[{"role":"tool"}]}"""),
            )
        }

        @Test
        fun `a plain OpenAI-compatible request also gets a JSON content type per request`() {
            // Koog infers text/plain for a String body and the inferred value beats a default header,
            // so the override has to ride on every request.
            assertEquals(mapOf("Content-Type" to "application/json"), JsonContentType.perRequest("{}"))
        }

        @Test
        fun `fixed headers declare JSON and identify the client`() {
            val headers = CopilotHeaders.fixed("AgentBridge/1.0", "IntelliJ-IDEA/2026.1")

            assertEquals("application/json", headers["Content-Type"])
            assertEquals("AgentBridge/1.0", headers["User-Agent"])
            assertEquals("conversation-edits", headers["Openai-Intent"])
        }

        @Test
        fun `the identity headers Copilot needs for the full catalog are on every request`() {
            val headers = CopilotHeaders.fixed("AgentBridge/1.0", "IntelliJ-IDEA/2026.1")

            assertEquals("vscode-chat", headers["Copilot-Integration-Id"])
            assertEquals("IntelliJ-IDEA/2026.1", headers["Editor-Version"])
            assertEquals("AgentBridge/1.0", headers["Editor-Plugin-Version"])
        }

        @Test
        fun `the session exchange and the model list use the same identity as chat`() {
            val identity = CopilotHeaders.identity("AgentBridge/1.0", "IntelliJ-IDEA/2026.1")

            assertTrue(CopilotHeaders.fixed("AgentBridge/1.0", "IntelliJ-IDEA/2026.1").entries.containsAll(identity.entries))
        }

        @Test
        fun `a refused session exchange tells the user to sign in again and is recognised as an auth failure`() {
            listOf(401, 403, 404).forEach { status ->
                val message = KoogNetwork.sessionRefusal(status)

                assertTrue(message.contains("authenticated"), message)
                assertTrue(message.contains("sign in again", ignoreCase = true), message)
            }
            assertFalse(KoogNetwork.sessionRefusal(500).contains("authenticated"))
        }
    }

    @Nested
    inner class OpenAiEndpoints {
        @Test
        fun `blank input means the OpenAI default`() {
            assertEquals(KoogProviders.Endpoint("https://api.openai.com", "v1/chat/completions"), KoogProviders.openAiEndpoint(""))
            assertEquals(KoogProviders.Endpoint("https://api.openai.com", "v1/chat/completions"), KoogProviders.openAiEndpoint(null))
        }

        @Test
        fun `a base ending in a version is split so the path is not doubled`() {
            assertEquals(
                KoogProviders.Endpoint("https://openrouter.ai/api", "v1/chat/completions"),
                KoogProviders.openAiEndpoint("https://openrouter.ai/api/v1/"),
            )
            assertEquals(
                KoogProviders.Endpoint("http://localhost:11434", "v1/chat/completions"),
                KoogProviders.openAiEndpoint("http://localhost:11434/v1"),
            )
        }

        @Test
        fun `a different api version is preserved`() {
            assertEquals(
                KoogProviders.Endpoint("https://host", "v4/chat/completions"),
                KoogProviders.openAiEndpoint("https://host/v4"),
            )
        }

        @Test
        fun `a bare host keeps the standard path`() {
            assertEquals(
                KoogProviders.Endpoint("https://gateway.example.com", "v1/chat/completions"),
                KoogProviders.openAiEndpoint(" https://gateway.example.com/ "),
            )
        }

        @Test
        fun `models are described as tool capable chat completions models`() {
            val model = KoogProviders.toLLModel(KoogModelChoice("gpt-4.1", "GPT-4.1", 128000, 16384))

            assertEquals("gpt-4.1", model.id)
            assertTrue(model.supports(LLMCapability.Tools))
            assertTrue(model.supports(LLMCapability.OpenAIEndpoint.Completions))
            assertEquals(128000L, model.contextLength)
            assertEquals(16384L, model.maxOutputTokens)
        }
    }

    @Nested
    inner class Errors {
        @Test
        fun `401 and 403 are authentication failures the shared handling recognises`() {
            for (status in listOf(401, 403)) {
                val c = KoogErrors.classifyHttp(status, "bad credentials", "GitHub Copilot")

                assertTrue(c.isAuthentication)
                assertTrue(c.message.contains("authenticated"), c.message)
                assertTrue(c.message.contains("bad credentials"))
            }
        }

        @Test
        fun `429 is reported as rate limiting, not as an auth problem`() {
            val c = KoogErrors.classifyHttp(429, "", "OpenAI")

            assertFalse(c.isAuthentication)
            assertTrue(c.message.contains("rate limiting"))
        }

        @Test
        fun `other statuses keep a body snippet but are not auth failures`() {
            val c = KoogErrors.classifyHttp(500, "internal error", "OpenAI")

            assertFalse(c.isAuthentication)
            assertTrue(c.message.contains("500"))
            assertTrue(c.message.contains("internal error"))
        }

        @Test
        fun `a long body is cut`() {
            val c = KoogErrors.classifyHttp(500, "x".repeat(5000), "OpenAI")

            assertTrue(c.message.length < 600)
        }

        @Test
        fun `an http exception buried in a cause chain is found`() {
            val error = RuntimeException("wrapper", IllegalStateException(KoogHttpClientException("c", 401, "nope")))

            assertTrue(KoogErrors.classify(error, "GitHub Copilot").isAuthentication)
        }

        @Test
        fun `a non http failure surfaces its message`() {
            val c = KoogErrors.classify(java.net.ConnectException("Connection refused"), "OpenAI")

            assertFalse(c.isAuthentication)
            assertTrue(c.message.contains("Connection refused"))
        }
    }
}
