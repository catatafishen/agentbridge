package com.github.catatafishen.agentbridge.client.koog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CopilotSetupGuideTest {

    @Nested
    inner class Steps {
        @Test
        fun `the first step points at GitHub's new OAuth app page`() {
            assertEquals("https://github.com/settings/applications/new", CopilotSetupGuide.NEW_APP_URL)
            assertTrue(CopilotSetupGuide.steps().first().contains(CopilotSetupGuide.NEW_APP_URL))
        }

        @Test
        fun `the steps cover enabling device flow and copying the client id, not the secret`() {
            val all = CopilotSetupGuide.steps().joinToString("\n")

            assertTrue(all.contains("Enable Device Flow"))
            assertTrue(all.contains("Client ID"))
            assertTrue(all.contains("secret"))
            assertTrue(all.contains(CopilotSetupGuide.SETTINGS_PATH))
        }

        @Test
        fun `text is numbered from one, one step per line`() {
            val lines = CopilotSetupGuide.stepsAsText().lines()

            assertEquals(CopilotSetupGuide.steps().size, lines.size)
            lines.forEachIndexed { i, line -> assertTrue(line.startsWith("${i + 1}. "), line) }
        }

        @Test
        fun `html is wrapped, numbered and escaped`() {
            val html = CopilotSetupGuide.stepsAsHtml()

            assertTrue(html.startsWith("<html>") && html.endsWith("</html>"))
            assertTrue(html.contains("<br>"))
            assertTrue(html.contains("1. "))
            // Quotes in the steps are text, but a stray < or & would break the markup.
            assertFalse(Regex("<(?!/?html|br)").containsMatchIn(html))
        }

        @Test
        fun `the short hint names both ways forward`() {
            val hint = CopilotSetupGuide.shortHint()

            assertTrue(hint.contains("OAuth app"))
            assertTrue(hint.contains("API key"))
            assertTrue(hint.contains(CopilotSetupGuide.SETTINGS_PATH))
        }
    }

    @Nested
    inner class ClientIdValidation {
        @Test
        fun `blank means use the bundled one and is fine`() {
            assertNull(CopilotSetupGuide.clientIdProblem(null))
            assertNull(CopilotSetupGuide.clientIdProblem(""))
            assertNull(CopilotSetupGuide.clientIdProblem("   "))
        }

        @Test
        fun `real looking client ids are accepted`() {
            assertNull(CopilotSetupGuide.clientIdProblem("Ov23liAbCdEfGhIjKlMn"))
            assertNull(CopilotSetupGuide.clientIdProblem("  Iv1.0123456789abcdef  "))
            // 20 hex characters is the legacy shape of a client id; only 40 is a secret.
            assertNull(CopilotSetupGuide.clientIdProblem("0123456789abcdef0123"))
        }

        @Test
        fun `a pasted client secret is recognised and explained`() {
            val problem = CopilotSetupGuide.clientIdProblem("0123456789abcdef0123456789abcdef01234567")

            assertNotNull(problem)
            assertTrue(problem!!.contains("client secret"))
            assertTrue(problem.contains("Client ID"))
        }

        @Test
        fun `a value with spaces is rejected, as when the label was copied too`() {
            val problem = CopilotSetupGuide.clientIdProblem("Client ID Ov23liAbCdEfGhIjKlMn")

            assertNotNull(problem)
            assertTrue(problem!!.contains("spaces"))
        }

        @Test
        fun `a value too short to be an id is flagged`() {
            assertNotNull(CopilotSetupGuide.clientIdProblem("abc123"))
        }
    }

    @Nested
    inner class FriendlyGitHubErrors {
        @Test
        fun `device flow not enabled tells the user where to enable it`() {
            val e = assertThrows(CopilotAuth.AuthException::class.java) {
                CopilotAuth.parseDeviceCode("""{"error":"device_flow_disabled","error_description":"Device flow must be explicitly enabled for this App"}""")
            }

            assertTrue(e.message!!.contains("Enable Device Flow"), e.message)
            assertTrue(e.message!!.contains("Developer settings"))
        }

        @Test
        fun `an unknown client id says to check the id and not the secret`() {
            val e = assertThrows(CopilotAuth.AuthException::class.java) {
                CopilotAuth.parseDeviceCode("""{"error":"incorrect_client_credentials"}""")
            }

            assertTrue(e.message!!.contains("Client ID"), e.message)
            assertTrue(e.message!!.contains("secret"))
        }

        @Test
        fun `the same mistakes are explained when they show up while polling`() {
            val disabled = CopilotAuth.parsePoll("""{"error":"device_flow_disabled"}""")
            val unknown = CopilotAuth.parsePoll("""{"error":"incorrect_client_credentials"}""")

            assertTrue((disabled as CopilotAuth.PollResult.Failed).message.contains("Enable Device Flow"))
            assertTrue((unknown as CopilotAuth.PollResult.Failed).message.contains("Client ID"))
        }

        @Test
        fun `any other GitHub error keeps GitHub's own description`() {
            val e = assertThrows(CopilotAuth.AuthException::class.java) {
                CopilotAuth.parseDeviceCode("""{"error":"something_new","error_description":"Try again later"}""")
            }

            assertTrue(e.message!!.contains("Try again later"))
        }
    }

    @Nested
    inner class ConfigurationMessages {
        @Test
        fun `the missing client id message says what to do, not only what is missing`() {
            val message = KoogSettings.problem(KoogProviderKind.COPILOT, null, null, "", copilotClientId = "")!!

            assertTrue(message.contains("client id"))
            assertTrue(message.contains("OAuth app"))
            assertTrue(message.contains("API key"))
            assertTrue(message.contains(CopilotSetupGuide.SETTINGS_PATH))
        }

        @Test
        fun `messages name the settings page by its real title`() {
            val message = KoogSettings.problem(KoogProviderKind.COPILOT, null, null, "", copilotClientId = "Ov23liAbCdEfGhIjKlMn")!!

            assertTrue(message.contains("Built-in Agent (Koog)"), message)
        }
    }
}
