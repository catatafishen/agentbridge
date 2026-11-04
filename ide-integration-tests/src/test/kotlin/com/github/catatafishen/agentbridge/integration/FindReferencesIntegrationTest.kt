package com.github.catatafishen.agentbridge.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Matrix row: {@code find_references} × {IU, CL}.
 *
 * Searches for usages of the fixture's {@code expectedSymbol} (the {@code Widget} class, which both
 * fixtures use 100+ times in {@code Shelf}) and asserts at least 100 references come back (the tool's
 * default cap) without truncation, and that repeated calls are stable. This is the bench guard for
 * issue #794 bug #1b: CLion Nova's lazy C++ parser exposes no {@code PsiNamedElement} for declarations,
 * so {@code FindReferencesTool} falls back to a word-search path — a red cell here means that
 * fallback stopped finding usages against the real backend. RD has no {@code navigationUsageFile}
 * yet, so the cell skips ({@code assumeTrue}) and renders as not-implemented (❓).
 */
class FindReferencesIntegrationTest {

    @Test
    fun `find_references finds usages of a symbol`() {
        val ide = IdeUnderTest.current()
        assumeTrue(ide.navigationUsageFile != null, "find_references bench not implemented for ${ide.key}")

        IdeBench.run("findReferences") { _, mcp ->
            val result = mcp.callTool(
                "find_references",
                mapOf("symbol" to ide.expectedSymbol, "max_results" to MAX_RESULTS),
                timeout = Duration.ofSeconds(120),
            )
            println("[integration] ${ide.key}: find_references(${ide.expectedSymbol}) → $result")
            assertFalse(
                result.startsWith("Error"),
                "find_references on ${ide.key} returned an error:\n$result",
            )
            assertTrue(
                result.contains(ide.expectedSymbol),
                "Expected a reference mentioning '${ide.expectedSymbol}' from find_references on " +
                    "${ide.key}, got:\n$result",
            )
            val count = referenceCount(result)
            assertTrue(
                count >= MIN_REFERENCES,
                "Expected at least $MIN_REFERENCES references from find_references on ${ide.key}, got $count:\n$result",
            )
            assertFalse(
                result.contains("(Showing "),
                "find_references on ${ide.key} was truncated although max_results=$MAX_RESULTS:\n$result",
            )
            println("[integration] ${ide.key}: PASSED find_references(${ide.expectedSymbol}) returned $count references (>= $MIN_REFERENCES, not truncated)")
        }
    }

    @Test
    fun `find_references returns identical results across repeated calls`() {
        val ide = IdeUnderTest.current()
        assumeTrue(ide.navigationUsageFile != null, "find_references bench not implemented for ${ide.key}")

        IdeBench.run("findReferencesRepeated") { _, mcp ->
            // Paged so no page hits the MCP output truncation (which would hide differences in the tail).
            fun fetchAll(): List<String> {
                val all = mutableListOf<String>()
                var offset = 0
                while (true) {
                    val page = mcp.callTool(
                        "find_references",
                        mapOf("symbol" to ide.expectedSymbol, "max_results" to PAGE_SIZE, "offset" to offset),
                        timeout = Duration.ofSeconds(120),
                    )
                    assertFalse(page.startsWith("Error"), "find_references on ${ide.key} returned an error:\n$page")
                    if (page.startsWith("No references found")) return all
                    all += normalizedReferences(page)
                    offset += referenceCount(page)
                    if (!page.contains("(Showing ")) return all
                }
            }

            val expected = fetchAll()
            assertTrue(expected.size >= MIN_REFERENCES, "Expected at least $MIN_REFERENCES references on ${ide.key}, got ${expected.size}")

            repeat(REPEATS - 1) { i ->
                val actual = fetchAll()
                assertEquals(
                    expected,
                    actual,
                    "find_references call #${i + 2} on ${ide.key} differed from call #1; " +
                        "missing=${expected - actual.toSet()} unexpected=${actual - expected.toSet()}",
                )
                if ((i + 2) % PROGRESS_INTERVAL == 0) {
                    println("[integration] ${ide.key}: find_references call #${i + 2}/$REPEATS identical to call #1")
                }
            }
            println(
                "[integration] ${ide.key}: PASSED all $REPEATS find_references calls returned the same " +
                    "${expected.size} references in the same order",
            )
        }
    }

    private fun referenceCount(result: String): Int =
        REFERENCE_COUNT.find(result)?.groupValues?.get(1)?.toInt() ?: 0

    /** Reference lines in the order returned; order must be stable too. */
    private fun normalizedReferences(result: String): List<String> =
        result.lines().drop(1).takeWhile { !it.startsWith("(Showing ") }.filter { it.isNotBlank() }

    private companion object {
        const val REPEATS = 200
        const val PROGRESS_INTERVAL = 50
        const val MIN_REFERENCES = 100
        const val MAX_RESULTS = 1000
        const val PAGE_SIZE = 50
        val REFERENCE_COUNT = Regex("""^(\d+) references found""")
    }
}
