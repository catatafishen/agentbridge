package com.github.catatafishen.agentbridge.integration

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Matrix row: {@code get_file_outline} × {IU, CL}.
 *
 * Asserts the language backend's PSI symbols come back over MCP for each covered IDE:
 * IntelliJ IDEA (in-process Java PSI) and CLion Nova/Radler (separate out-of-process C++
 * backend — the regression guard for the namespace-recursion fix in {@code NavigationTool}).
 * The file and the expected symbols are per-IDE config on {@link IdeUnderTest}; an IDE with no
 * {@code outlineFile} (such as RD) is not implemented yet, so the cell skips ({@code assumeTrue})
 * and renders as "not implemented" (❓) in the matrix rather than as a pass.
 */
class GetFileOutlineIntegrationTest {

    @Test
    fun `get_file_outline returns the backend's symbols`() {
        val ide = IdeUnderTest.current()
        val outlineFile = ide.outlineFile
        assumeTrue(outlineFile != null, "get_file_outline bench not implemented for ${ide.key}")
        requireNotNull(outlineFile)

        IdeBench.run("getFileOutline") { _, mcp ->
            // wait=true makes the tool retry internally until PSI is populated (up to 90s),
            // so no polling here.
            val outline = mcp.callTool(
                "get_file_outline",
                mapOf("path" to outlineFile, "wait" to true, "timeout" to 90),
                timeout = Duration.ofSeconds(120),
            )
            println("[integration] ${ide.key}: get_file_outline($outlineFile) → $outline")

            for (symbol in ide.expectedOutlineSymbols) {
                assertTrue(
                    outline.contains(symbol),
                    "Expected '$symbol' in ${ide.key} outline of $outlineFile after 90s wait, got:\n$outline",
                )
            }
        }
    }
}
