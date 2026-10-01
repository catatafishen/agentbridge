package com.github.catatafishen.agentbridge.client.koog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class KoogSupportAndSettingsTest {

    @Nested
    inner class Support {
        @Test
        fun `Kotlin 2_3 and newer is supported`() {
            assertTrue(KoogSupport.isSupported(KotlinVersion(2, 3, 0)))
            assertTrue(KoogSupport.isSupported(KotlinVersion(2, 3, 21)))
            assertTrue(KoogSupport.isSupported(KotlinVersion(2, 4, 20)))
            assertTrue(KoogSupport.isSupported(KotlinVersion(3, 0, 0)))
        }

        @Test
        fun `older Kotlin runtimes such as IDE 2025_3 are not supported`() {
            assertFalse(KoogSupport.isSupported(KotlinVersion(2, 2, 21)))
            assertFalse(KoogSupport.isSupported(KotlinVersion(2, 0, 0)))
            assertFalse(KoogSupport.isSupported(KotlinVersion(1, 9, 25)))
        }

        @Test
        fun `the real runtime used by the tests is supported`() {
            // The plugin is built with Kotlin 2.4, so the test JVM's stdlib must pass the gate.
            assertTrue(KoogSupport.isSupported())
        }

        @Test
        fun `the unsupported message names the requirement`() {
            assertTrue(KoogSupport.unsupportedReason().contains("2.3"))
        }
    }

    @Nested
    inner class ConfigurationProblems {
        @Test
        fun `Copilot without token and without a client id explains the missing client id`() {
            val problem = KoogSettings.problem(KoogProviderKind.COPILOT, null, null, "", "")

            assertNotNull(problem)
            assertTrue(problem!!.contains("client id"))
            // Must be recognised by the shared authentication handling (see docs/AUTH-HANDLING.md).
            assertTrue(problem.contains("authenticated"))
        }

        @Test
        fun `Copilot with a client id but no token asks the user to sign in`() {
            val problem = KoogSettings.problem(KoogProviderKind.COPILOT, "", null, "", "abc123")

            assertNotNull(problem)
            assertTrue(problem!!.contains("Sign in"))
            assertTrue(problem.contains("authenticated"))
        }

        @Test
        fun `Copilot with a token is ready even without a client id`() {
            assertNull(KoogSettings.problem(KoogProviderKind.COPILOT, "gho_token", null, "", ""))
        }

        @Test
        fun `OpenAI-compatible needs an API key`() {
            val problem = KoogSettings.problem(KoogProviderKind.OPENAI_COMPATIBLE, null, " ", "gpt-4o", "")

            assertNotNull(problem)
            assertTrue(problem!!.contains("API key"))
            assertTrue(problem.contains("authenticated"))
        }

        @Test
        fun `OpenAI-compatible needs a model id`() {
            val problem = KoogSettings.problem(KoogProviderKind.OPENAI_COMPATIBLE, null, "sk-test", " ", "")

            assertNotNull(problem)
            assertTrue(problem!!.contains("model"))
        }

        @Test
        fun `OpenAI-compatible with key and model is ready`() {
            assertNull(KoogSettings.problem(KoogProviderKind.OPENAI_COMPATIBLE, null, "sk-test", "gpt-4o", ""))
        }
    }

    @Nested
    inner class ClientIdFile {
        @Test
        fun `comments and blank lines are skipped and the first value wins`() {
            val content = "# comment\n\n  # indented comment\n  Iv1.abc  \nsecond\n"

            assertEquals("Iv1.abc", KoogSettings.parseClientId(content))
        }

        @Test
        fun `a file with only comments yields no client id`() {
            assertEquals("", KoogSettings.parseClientId("# only\n# comments\n"))
        }

        @Test
        fun `a missing file yields no client id`() {
            assertEquals("", KoogSettings.parseClientId(null))
        }

        @Test
        fun `the bundled file ships without a client id so nothing foreign is reused`() {
            val bundled = KoogSettings::class.java.getResourceAsStream("/koog/copilot-oauth-client-id.txt")
                ?.use { it.readBytes().decodeToString() }

            assertNotNull(bundled, "bundled resource is missing")
            assertEquals("", KoogSettings.parseClientId(bundled))
        }
    }

    @Nested
    inner class Providers {
        @Test
        fun `provider ids round trip and unknown ids fall back to Copilot`() {
            assertEquals(KoogProviderKind.OPENAI_COMPATIBLE, KoogProviderKind.fromId("openai"))
            assertEquals(KoogProviderKind.COPILOT, KoogProviderKind.fromId("copilot"))
            assertEquals(KoogProviderKind.COPILOT, KoogProviderKind.fromId("nonsense"))
            assertEquals(KoogProviderKind.COPILOT, KoogProviderKind.fromId(null))
        }
    }
}
