package com.github.catatafishen.agentbridge.integration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.system.measureTimeMillis

/**
 * Matrix row: {@code find_references} on a large class graph × {IU, CL}.
 *
 * Generates [NODE_COUNT] interlinked classes into the fixture ([LargeGraphFixture]); each one owns a
 * {@code Widget}. The bench then searches all usages of {@code Widget} across the whole graph, which
 * forces the backend to walk thousands of files, and asserts that every node's usage is found
 * (the result header reports the number of returned references, not a truncated line count).
 */
class FindReferencesLargeGraphIntegrationTest {

    @Test
    fun `find_references finds a usage in every node of a large class graph`() {
        val ide = IdeUnderTest.current()
        assumeTrue(
            ide.navigationUsageFile != null && LargeGraphFixture.supports(ide),
            "large-graph find_references bench not implemented for ${ide.key}",
        )

        IdeBench.run("findReferencesLargeGraph", largeGraphNodes = NODE_COUNT) { _, mcp ->
            lateinit var result: String
            val elapsedMs = measureTimeMillis {
                result = mcp.callTool(
                    "find_references",
                    mapOf("symbol" to ide.expectedSymbol, "max_results" to MAX_RESULTS),
                    timeout = SEARCH_TIMEOUT,
                )
            }
            println(
                "[integration] ${ide.key}: find_references(${ide.expectedSymbol}) over $NODE_COUNT nodes " +
                    "took ${elapsedMs}ms",
            )

            assertFalse(
                result.startsWith("Error"),
                "find_references on ${ide.key} returned an error:\n${result.take(2_000)}",
            )
            val count = REFERENCE_COUNT.find(result)?.groupValues?.get(1)?.toInt() ?: 0
            assertTrue(
                count >= NODE_COUNT,
                "Expected at least $NODE_COUNT references (one per node) on ${ide.key}, got $count:\n" +
                    result.take(2_000),
            )
            println(
                "[integration] ${ide.key}: PASSED large-graph find_references returned $count references " +
                    "for $NODE_COUNT nodes (>= $NODE_COUNT) in ${elapsedMs}ms",
            )
        }
    }

    private companion object {
        const val NODE_COUNT = LargeGraphFixture.DEFAULT_NODE_COUNT
        const val MAX_RESULTS = 50_000
        val SEARCH_TIMEOUT: Duration = Duration.ofMinutes(10)
        val REFERENCE_COUNT = Regex("""^(\d+) references found""")
    }
}
