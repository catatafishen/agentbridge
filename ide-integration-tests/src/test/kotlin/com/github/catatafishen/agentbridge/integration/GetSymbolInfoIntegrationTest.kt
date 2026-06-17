package com.github.catatafishen.agentbridge.integration

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Matrix row: {@code get_symbol_info} × {IU, CL}.
 *
 * Points the tool at a class declaration line in the fixture (the `Widget` class — namespaced
 * under `vsc::` for CLion) and asserts the resolved name comes back. This is the regression guard
 * for the CLion Nova fallback in {@code GetSymbolInfoTool}: its original {@code PsiNamedElement}
 * ancestor walk finds nothing for CLion Nova's lazy C/C++ parser (issue #794 bug #2), so it now
 * falls back to {@code NavigationTool.findEnclosingCppDeclaration}. The file/line are per-IDE
 * config on {@link IdeUnderTest}; an IDE with no {@code symbolInfoFile} (such as RD) is not
 * implemented yet, so the cell skips ({@code assumeTrue}) and renders as "not implemented" (❓).
 */
class GetSymbolInfoIntegrationTest {

    @Test
    fun `get_symbol_info resolves the declaration at a file position`() {
        val ide = IdeUnderTest.current()
        val symbolInfoFile = ide.symbolInfoFile
        assumeTrue(symbolInfoFile != null, "get_symbol_info bench not implemented for ${ide.key}")
        requireNotNull(symbolInfoFile)

        IdeBench.run("getSymbolInfo") { _, mcp ->
            val result = mcp.callTool(
                "get_symbol_info",
                mapOf("file" to symbolInfoFile, "line" to ide.symbolInfoLine),
                timeout = Duration.ofSeconds(120),
            )
            println("[integration] ${ide.key}: get_symbol_info($symbolInfoFile:${ide.symbolInfoLine}) → $result")
            assertTrue(
                result.contains(ide.expectedSymbolInfoName),
                "Expected '${ide.expectedSymbolInfoName}' from get_symbol_info on ${ide.key}, got:\n$result",
            )
        }
    }
}
