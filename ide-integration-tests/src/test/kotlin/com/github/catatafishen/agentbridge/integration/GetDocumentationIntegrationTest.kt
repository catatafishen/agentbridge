package com.github.catatafishen.agentbridge.integration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Matrix row: {@code get_documentation} × {IU, CL}.
 *
 * Resolves documentation by position ({@code symbolInfoFile}:{@code symbolInfoLine}, the same
 * declaration the {@code get_symbol_info} cell points at) and asserts the resolved name comes back.
 * This is the bench guard for issue #794 bug #8: the FQN-only path was Java/Kotlin-only, so
 * {@code GetDocumentationTool} gained a language-agnostic file+line path
 * ({@code ToolUtils.resolveNamedElement}). "No documentation available for X. Element found: …" is
 * still a green cell — it proves the symbol resolved; only an {@code Error}/"not found" response is
 * red. RD has no {@code symbolInfoFile} yet, so the cell skips ({@code assumeTrue}) and renders as
 * not-implemented (❓).
 */
class GetDocumentationIntegrationTest {

    @Test
    fun `get_documentation resolves a symbol by position`() {
        val ide = IdeUnderTest.current()
        val docFile = ide.symbolInfoFile
        assumeTrue(docFile != null, "get_documentation bench not implemented for ${ide.key}")
        requireNotNull(docFile)

        IdeBench.run("getDocumentation") { _, mcp ->
            val result = mcp.callTool(
                "get_documentation",
                mapOf(
                    "symbol" to ide.expectedSymbolInfoName,
                    "path" to docFile,
                    "line" to ide.symbolInfoLine,
                ),
                timeout = Duration.ofSeconds(120),
            )
            println(
                "[integration] ${ide.key}: get_documentation(${ide.expectedSymbolInfoName}, " +
                    "$docFile:${ide.symbolInfoLine}) → $result"
            )
            assertFalse(
                result.startsWith("Error"),
                "get_documentation on ${ide.key} returned an error:\n$result",
            )
            assertTrue(
                result.contains(ide.expectedSymbolInfoName),
                "Expected documentation for '${ide.expectedSymbolInfoName}' from get_documentation on " +
                    "${ide.key}, got:\n$result",
            )
        }
    }
}
