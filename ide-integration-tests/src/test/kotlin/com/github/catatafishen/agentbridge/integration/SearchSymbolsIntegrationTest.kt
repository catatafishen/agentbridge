package com.github.catatafishen.agentbridge.integration

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Matrix row: {@code search_symbols} × {IU, CL, RD}.
 *
 * Launches the IDE selected by {@code agentbridge.ide}, opens its fixture, and asserts an exact
 * {@code search_symbols} query for the fixture's {@code Widget} type returns it. A red cell means
 * the real backend's symbol index/PSI does not expose the type to the tool (e.g., the CLion Nova /
 * Rider blind spots tracked in issue #794) — that is real compatibility data, not a harness bug.
 */
class SearchSymbolsIntegrationTest {

    @Test
    fun `search_symbols finds a class symbol`() = IdeBench.run("searchSymbols") { ide, mcp ->
        val result = mcp.callTool(
            "search_symbols",
            mapOf("query" to ide.symbolQuery, "type" to "class"),
            timeout = Duration.ofSeconds(120),
        )
        println("[integration] ${ide.key}: search_symbols(${ide.symbolQuery}) → $result")
        assertTrue(
            result.contains(ide.expectedSymbol),
            "Expected '${ide.expectedSymbol}' from search_symbols on ${ide.key}, got:\n$result",
        )
    }
}
