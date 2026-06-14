package com.github.catatafishen.agentbridge.integration

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Matrix row: {@code get_file_outline} × {CL}.
 *
 * CLion Nova/Radler is a separate out-of-process C++ backend; this asserts the namespaced
 * fixture symbols come back over MCP (the regression guard for the namespace-recursion fix in
 * {@code NavigationTool}). The other IDE columns are not implemented yet, so the test skips on
 * them ({@code assumeTrue}) — a skipped cell renders as "not implemented" (❓) in the matrix,
 * not as a pass.
 */
class GetFileOutlineIntegrationTest {

    @Test
    fun `get_file_outline returns C++ symbols from CLion Nova`() {
        val ide = IdeUnderTest.current()
        assumeTrue(ide.key == "CL", "get_file_outline bench currently covers CLion only")

        IdeBench.run("getFileOutline") { _, mcp ->
            // wait=true makes the tool retry internally until PSI is populated (up to 90s),
            // so no polling here.
            val outline = mcp.callTool(
                "get_file_outline",
                mapOf("path" to "classdef.h", "wait" to true, "timeout" to 90),
                timeout = Duration.ofSeconds(120),
            )
            println("[integration] get_file_outline(classdef.h) → $outline")

            assertTrue(
                outline.contains("Widget"),
                "Expected class 'Widget' in C++ outline after 90s wait, got:\n$outline",
            )
            assertTrue(
                outline.contains("Point"),
                "Expected struct 'Point' in C++ outline after 90s wait, got:\n$outline",
            )
        }
    }
}
