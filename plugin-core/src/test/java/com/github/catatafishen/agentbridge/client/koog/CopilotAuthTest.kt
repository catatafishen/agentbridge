package com.github.catatafishen.agentbridge.client.koog

import com.github.catatafishen.agentbridge.client.koog.CopilotAuth.AuthException
import com.github.catatafishen.agentbridge.client.koog.CopilotAuth.PollResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CopilotAuthTest {

    private val code = CopilotAuth.DeviceCode("dev123", "ABCD-1234", "https://github.com/login/device", 5, 900)

    /** Replays canned responses and records every request. */
    private class ScriptedPoster(private vararg val responses: String) : JsonPoster {
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        private var next = 0

        override fun post(url: String, body: com.google.gson.JsonObject): String {
            urls += url
            bodies += body.toString()
            return responses[minOf(next++, responses.size - 1)]
        }
    }

    @Nested
    inner class Requests {
        @Test
        fun `the device code request asks for the read-only user scope`() {
            val body = CopilotAuth.deviceCodeRequest("client-1")

            assertEquals("client-1", body.get("client_id").asString)
            assertEquals("read:user", body.get("scope").asString)
        }

        @Test
        fun `the poll request uses the device grant type`() {
            val body = CopilotAuth.pollRequest("client-1", "dev123")

            assertEquals("urn:ietf:params:oauth:grant-type:device_code", body.get("grant_type").asString)
            assertEquals("dev123", body.get("device_code").asString)
            assertEquals("client-1", body.get("client_id").asString)
        }
    }

    @Nested
    inner class DeviceCodeParsing {
        @Test
        fun `a complete response parses`() {
            val parsed = CopilotAuth.parseDeviceCode(
                """{"device_code":"d","user_code":"U-1","verification_uri":"https://github.com/login/device","interval":7,"expires_in":600}"""
            )

            assertEquals("d", parsed.deviceCode)
            assertEquals("U-1", parsed.userCode)
            assertEquals(7, parsed.intervalSeconds)
            assertEquals(600, parsed.expiresInSeconds)
        }

        @Test
        fun `missing interval and expiry get RFC defaults`() {
            val parsed = CopilotAuth.parseDeviceCode(
                """{"device_code":"d","user_code":"U","verification_uri":"https://x"}"""
            )

            assertEquals(5, parsed.intervalSeconds)
            assertEquals(900, parsed.expiresInSeconds)
        }

        @Test
        fun `an error response is raised with GitHub's description`() {
            val e = assertThrows(AuthException::class.java) {
                CopilotAuth.parseDeviceCode("""{"error":"unauthorized_client","error_description":"Device flow is disabled"}""")
            }

            assertTrue(e.message!!.contains("Device flow is disabled"))
        }

        @Test
        fun `a response without a user code is rejected`() {
            val e = assertThrows(AuthException::class.java) {
                CopilotAuth.parseDeviceCode("""{"device_code":"d","verification_uri":"https://x"}""")
            }

            assertTrue(e.message!!.contains("user_code"))
        }

        @Test
        fun `garbage is rejected with a clear message`() {
            assertThrows(AuthException::class.java) { CopilotAuth.parseDeviceCode("<html>nope</html>") }
        }
    }

    @Nested
    inner class PollParsing {
        @Test
        fun `a token wins`() {
            assertEquals(PollResult.Token("gho_abc"), CopilotAuth.parsePoll("""{"access_token":"gho_abc","token_type":"bearer"}"""))
        }

        @Test
        fun `authorization_pending keeps waiting`() {
            assertEquals(PollResult.Pending, CopilotAuth.parsePoll("""{"error":"authorization_pending"}"""))
        }

        @Test
        fun `slow_down reports the server's new interval when given`() {
            assertEquals(PollResult.SlowDown(15), CopilotAuth.parsePoll("""{"error":"slow_down","interval":15}"""))
            assertEquals(PollResult.SlowDown(null), CopilotAuth.parsePoll("""{"error":"slow_down"}"""))
        }

        @Test
        fun `expired and denied are terminal with a readable message`() {
            val expired = CopilotAuth.parsePoll("""{"error":"expired_token"}""") as PollResult.Failed
            val denied = CopilotAuth.parsePoll("""{"error":"access_denied"}""") as PollResult.Failed

            assertTrue(expired.message.contains("expired"))
            assertTrue(denied.message.contains("cancelled"))
        }

        @Test
        fun `an unknown error is terminal and carries GitHub's description`() {
            val failed = CopilotAuth.parsePoll("""{"error":"weird","error_description":"Something odd"}""") as PollResult.Failed

            assertTrue(failed.message.contains("Something odd"))
        }

        @Test
        fun `a body with neither token nor error is a failure, not a hang`() {
            assertTrue(CopilotAuth.parsePoll("{}") is PollResult.Failed)
            assertTrue(CopilotAuth.parsePoll("not json") is PollResult.Failed)
        }
    }

    @Nested
    inner class AwaitingToken {
        @Test
        fun `start posts to the device code endpoint`() {
            val poster = ScriptedPoster("""{"device_code":"d","user_code":"U","verification_uri":"https://x"}""")

            CopilotAuth.start("client-1", poster)

            assertEquals(listOf(CopilotAuth.DEVICE_CODE_URL), poster.urls)
        }

        @Test
        fun `pending then token returns the token after waiting the interval plus the safety margin`() {
            val poster = ScriptedPoster("""{"error":"authorization_pending"}""", """{"access_token":"gho_ok"}""")
            val sleeps = mutableListOf<Long>()

            val token = CopilotAuth.awaitToken("client-1", code, poster, { sleeps += it })

            assertEquals("gho_ok", token)
            assertEquals(listOf(8_000L, 8_000L), sleeps)
            assertEquals(listOf(CopilotAuth.ACCESS_TOKEN_URL, CopilotAuth.ACCESS_TOKEN_URL), poster.urls)
        }

        @Test
        fun `slow_down without an interval adds five seconds`() {
            val poster = ScriptedPoster("""{"error":"slow_down"}""", """{"access_token":"t"}""")
            val sleeps = mutableListOf<Long>()

            CopilotAuth.awaitToken("c", code, poster, { sleeps += it })

            assertEquals(listOf(8_000L, 13_000L), sleeps)
        }

        @Test
        fun `slow_down with an interval uses the server's value`() {
            val poster = ScriptedPoster("""{"error":"slow_down","interval":20}""", """{"access_token":"t"}""")
            val sleeps = mutableListOf<Long>()

            CopilotAuth.awaitToken("c", code, poster, { sleeps += it })

            assertEquals(listOf(8_000L, 23_000L), sleeps)
        }

        @Test
        fun `a terminal error stops polling`() {
            val poster = ScriptedPoster("""{"error":"access_denied"}""")

            val e = assertThrows(AuthException::class.java) { CopilotAuth.awaitToken("c", code, poster, {}) }

            assertTrue(e.message!!.contains("cancelled"))
            assertEquals(1, poster.urls.size)
        }

        @Test
        fun `cancelling aborts before the next poll`() {
            val poster = ScriptedPoster("""{"error":"authorization_pending"}""")
            var polls = 0

            val e = assertThrows(AuthException::class.java) {
                CopilotAuth.awaitToken("c", code, poster, {}, isCancelled = { polls++ >= 1 })
            }

            assertTrue(e.message!!.contains("cancelled"))
            assertEquals(1, poster.urls.size)
        }

        @Test
        fun `the flow gives up once the code has expired`() {
            val poster = ScriptedPoster("""{"error":"authorization_pending"}""")
            var now = 0L

            val e = assertThrows(AuthException::class.java) {
                CopilotAuth.awaitToken("c", code, poster, { now += 400_000L }, nowMillis = { now })
            }

            assertTrue(e.message!!.contains("expired"))
        }
    }
}
