package com.github.catatafishen.agentbridge.integration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Matrix row: {@code get_problems} × {IU, CL, RD}.
 *
 * Opens the fixture's source file in the real editor, then asserts {@code get_problems} returns a
 * non-error response. The fixtures are clean, so "No problems found …" is the expected green
 * result — the cell only goes red if the backend could not be queried at all ({@code Error}
 * response). Companion of the {@code get_highlights} cell; both exercise the cached-daemon
 * diagnostics path that issue #794 reported broken for C/C++.
 */
class GetProblemsIntegrationTest {

    @Test
    fun `get_problems returns a non-error response`() = IdeBench.run("getProblems") { ide, mcp ->
        // Problems are only cached for open files, so open the fixture file first.
        mcp.callTool("open_in_editor", mapOf("file" to ide.highlightsFile))
        val result = mcp.callTool(
            "get_problems",
            mapOf("path" to ide.highlightsFile),
            timeout = Duration.ofSeconds(120),
        )
        println("[integration] ${ide.key}: get_problems(${ide.highlightsFile}) → $result")
        assertFalse(
            result.startsWith("Error"),
            "get_problems on ${ide.key} returned an error:\n$result",
        )
    }
}
