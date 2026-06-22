package com.github.catatafishen.agentbridge.integration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Matrix row: {@code get_compilation_errors} × {IU, CL, RD}.
 *
 * Opens the fixture's source file in the real editor, then asserts {@code get_compilation_errors}
 * returns a non-error response. The fixtures compile cleanly, so "No compilation errors in N files
 * checked." is the expected green result — the cell only goes red if the cached-daemon error scan
 * could not run ({@code Error} response). Faster, error-only companion of the {@code get_problems}
 * and {@code get_highlights} cells.
 */
class GetCompilationErrorsIntegrationTest {

    @Test
    fun `get_compilation_errors returns a non-error response`() =
        IdeBench.run("getCompilationErrors") { ide, mcp ->
            // Errors are read from cached daemon analysis, which only runs for open files.
            mcp.callTool("open_in_editor", mapOf("file" to ide.highlightsFile))
            val result = mcp.callTool(
                "get_compilation_errors",
                mapOf("path" to ide.highlightsFile),
                timeout = Duration.ofSeconds(120),
            )
            println("[integration] ${ide.key}: get_compilation_errors(${ide.highlightsFile}) → $result")
            assertFalse(
                result.startsWith("Error"),
                "get_compilation_errors on ${ide.key} returned an error:\n$result",
            )
        }
}
