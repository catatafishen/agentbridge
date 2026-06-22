package com.github.catatafishen.agentbridge.integration

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Matrix row: {@code search_symbols} × {IU, CL}.
 *
 * Rider is excluded: its C#/F#/VB PSI lives in the ReSharper backend, not the IntelliJ
 * frontend, so {@code classifyElement()} fails on Rider's coarse PSI stubs.
 * {@code search_symbols} is listed in the Rider-disabled tools table in README.md and
 * guarded by {@code PsiBridgeService.RIDER_DISABLED_TOOLS}. The cell skips via
 * {@code assumeTrue} and renders as not-implemented in the matrix.
 */
class SearchSymbolsIntegrationTest {

    @Test
    fun `search_symbols finds a class symbol`() {
        val ide = IdeUnderTest.current()
        assumeTrue(
            ide.searchSymbolsSupported, "search_symbols disabled for ${ide.key}: " +
                "C# PSI lives in the ReSharper backend (RIDER_DISABLED_TOOLS in PsiBridgeService)"
        )

        IdeBench.run("searchSymbols") { ide, mcp ->
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
}
