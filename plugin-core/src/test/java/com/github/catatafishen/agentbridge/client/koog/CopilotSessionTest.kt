package com.github.catatafishen.agentbridge.client.koog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CopilotSessionTest {

    private fun body(token: String = "tid=abc", expires: Long = 10_000, api: String = "https://api.individual.githubcopilot.com") =
        """{"token":"$token","expires_at":$expires,"refresh_in":1500,"endpoints":{"api":"$api"}}"""

    @Test
    fun `a Copilot content-filter chunk without object or model gets the envelope Koog requires`() {
        val first = """{"choices":[],"created":0,"id":"","prompt_filter_results":[{"index":0}]}"""

        val normalized = com.google.gson.JsonParser.parseString(CopilotStreamChunks.normalize(first)).asJsonObject

        assertEquals("chat.completion.chunk", normalized.get("object").asString)
        assertEquals("", normalized.get("model").asString)
        assertTrue(normalized.has("prompt_filter_results"))
    }

    @Test
    fun `normalizing never changes the content a chunk carries`() {
        val chunk = """{"id":"a","created":5,"model":"m","choices":[{"index":0,"delta":{"content":"hi"}}],"usage":{"prompt_tokens":3}}"""

        val normalized = com.google.gson.JsonParser.parseString(CopilotStreamChunks.normalize(chunk)).asJsonObject

        assertEquals("hi", normalized.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("delta").get("content").asString)
        assertEquals(3, normalized.getAsJsonObject("usage").get("prompt_tokens").asInt)
        assertEquals("a", normalized.get("id").asString)
    }

    @Test
    fun `a complete chunk is returned untouched and unparseable data is left for Koog to report`() {
        val complete = """{"id":"a","object":"chat.completion.chunk","created":5,"model":"m","choices":[]}"""

        assertEquals(complete, CopilotStreamChunks.normalize(complete))
        assertEquals("not json", CopilotStreamChunks.normalize("not json"))
    }

    private fun deltaOf(chunk: String): com.google.gson.JsonObject =
        com.google.gson.JsonParser.parseString(chunk).asJsonObject
            .getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("delta")

    @Test
    fun `reasoning sent under another field name is copied to the one Koog reads`() {
        for (alias in listOf("reasoning_text", "reasoning")) {
            val chunk = """{"choices":[{"index":0,"delta":{"$alias":"hmm"}}]}"""

            val delta = deltaOf(ReasoningFields.normalize(chunk))

            assertEquals("hmm", delta.get("reasoning_content").asString, alias)
            // Nothing is removed: the original field stays.
            assertEquals("hmm", delta.get(alias).asString, alias)
        }
    }

    @Test
    fun `a delta that already has reasoning_content, or none, is returned untouched`() {
        val canonical = """{"choices":[{"index":0,"delta":{"reasoning_content":"a","reasoning":"b"}}]}"""
        val plain = """{"choices":[{"index":0,"delta":{"content":"hi"}}]}"""
        val empty = """{"choices":[{"index":0,"delta":{"reasoning":""}}]}"""

        assertEquals(canonical, ReasoningFields.normalize(canonical))
        assertEquals(plain, ReasoningFields.normalize(plain))
        assertEquals(empty, ReasoningFields.normalize(empty))
        assertEquals("not json with reasoning", ReasoningFields.normalize("not json with reasoning"))
    }

    @Test
    fun `reasoning_text wins over reasoning when both are present`() {
        val chunk = """{"choices":[{"index":0,"delta":{"reasoning_text":"first","reasoning":"second"}}]}"""

        assertEquals("first", deltaOf(ReasoningFields.normalize(chunk)).get("reasoning_content").asString)
    }

    @Test
    fun `the Copilot normalizer applies the reasoning alias together with the envelope repair`() {
        val chunk = """{"choices":[{"index":0,"delta":{"reasoning_text":"hmm"}}]}"""

        val normalized = CopilotStreamChunks.normalize(chunk)

        assertEquals("hmm", deltaOf(normalized).get("reasoning_content").asString)
        assertEquals("chat.completion.chunk", com.google.gson.JsonParser.parseString(normalized).asJsonObject.get("object").asString)
    }

    @Test
    fun `a session response is parsed`() {
        val session = CopilotSessions.parse(body())

        assertEquals(CopilotSession("tid=abc", 10_000, "https://api.individual.githubcopilot.com"), session)
    }

    @Test
    fun `a trailing slash on the api host is removed`() {
        assertEquals(
            "https://api.business.githubcopilot.com",
            CopilotSessions.parse(body(api = "https://api.business.githubcopilot.com/")).apiBase,
        )
    }

    @Test
    fun `a response missing the token, expiry or endpoint is rejected rather than defaulted`() {
        listOf(
            """{"expires_at":1,"endpoints":{"api":"https://x"}}""",
            """{"token":"t","endpoints":{"api":"https://x"}}""",
            """{"token":"t","expires_at":1}""",
            """{"token":"t","expires_at":1,"endpoints":{}}""",
            """not json""",
        ).forEach { json ->
            assertThrows(CopilotAuth.AuthException::class.java) { CopilotSessions.parse(json) }
        }
    }

    @Test
    fun `a fresh session is reused without another exchange`() {
        var exchanges = 0
        val sessions = CopilotSessions({ exchanges++; body(expires = 10_000) }, { 1_000 })

        sessions.current()
        sessions.current()

        assertEquals(1, exchanges)
    }

    @Test
    fun `a session close to expiry is exchanged again`() {
        var now = 1_000L
        var exchanges = 0
        val sessions = CopilotSessions({ exchanges++; body(token = "t$exchanges", expires = now + 600) }, { now })

        assertEquals("t1", sessions.current().token)
        now += 600 - CopilotSessions.REFRESH_MARGIN_SECONDS + 1

        assertEquals("t2", sessions.current().token)
        assertEquals(2, exchanges)
    }

    @Test
    fun `a refused exchange propagates and nothing is cached`() {
        var attempts = 0
        val sessions = CopilotSessions({
            attempts++
            if (attempts == 1) throw CopilotAuth.AuthException("refused") else body()
        }, { 1_000 })

        assertThrows(CopilotAuth.AuthException::class.java) { sessions.current() }
        assertTrue(sessions.current().token.isNotEmpty())
        assertEquals(2, attempts)
    }
}
