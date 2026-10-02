package com.github.catatafishen.agentbridge.client.koog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class KoogGuidanceTest {

    private val default = "DEFAULT TEMPLATE\n"
    private val variant = "KOOG VARIANT"

    @Nested
    inner class Composition {
        @Test
        fun `the default text is replaced by the Koog variant`() {
            val composed = KoogGuidance.compose(default, default, userCustomized = false, koogVariant = variant)

            assertEquals(variant, composed)
        }

        @Test
        fun `the memory section that follows the default text is kept`() {
            val memory = "---\n\nSEMANTIC MEMORY: remember this"

            val composed = KoogGuidance.compose("$default\n$memory", default, userCustomized = false, koogVariant = variant)

            assertEquals("$variant\n\n${memory.trim()}", composed)
        }

        @Test
        fun `instructions the user replaced are honoured as written, not swapped`() {
            val custom = "My own instructions for every agent."

            val composed = KoogGuidance.compose(custom, default, userCustomized = true, koogVariant = variant)

            assertEquals(custom, composed)
        }

        @Test
        fun `unexpected handler text is passed through rather than cut blindly`() {
            val surprising = "Something that does not start with the default"

            val composed = KoogGuidance.compose(surprising, default, userCustomized = false, koogVariant = variant)

            assertEquals(surprising, composed)
        }
    }

    @Nested
    inner class BundledText {
        private fun resource(name: String) =
            KoogGuidance::class.java.getResourceAsStream(name)?.use { it.readBytes().decodeToString() }

        @Test
        fun `the Koog variant does not talk about native tools or other harnesses`() {
            val text = resource("/koog/tool-guidance.md")

            assertNotNull(text)
            // The point of the variant: no built-in tools exist here and schemas are never deferred.
            for (word in listOf("native", "built-in", "harness", "defer", "deferred", "MCP")) {
                val whole = Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE)
                assertFalse(whole.containsMatchIn(text!!), "must not mention '$word'")
            }
        }

        @Test
        fun `the Koog variant keeps the guidance that does apply`() {
            val text = resource("/koog/tool-guidance.md")!!

            for (needle in listOf("run_command", "git_*", "create_scratch_file", "auto_format_and_optimize_imports",
                "get_compilation_errors", "[User nudge]", "[quick-reply:")) {
                assertTrue(text.contains(needle), "missing '$needle'")
            }
        }

        @Test
        fun `every tool named in the Koog variant is also named in the shared default`() {
            // Guards against the variant inventing tools or drifting from the shared text.
            val variantText = resource("/koog/tool-guidance.md")!!
            val shared = resource("/default-startup-instructions.md")!!
            val toolNames = Regex("`([a-z]+(?:_[a-z]+)+)`").findAll(variantText).map { it.groupValues[1] }.toSet()

            val unknown = toolNames.filterNot { shared.contains(it) }

            assertTrue(unknown.isEmpty(), "named only in the Koog variant: $unknown")
        }
    }
}
