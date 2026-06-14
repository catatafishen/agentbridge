package com.github.catatafishen.agentbridge.integration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Matrix row: {@code get_highlights} × {IU, CL, RD}.
 *
 * Opens the fixture's source file in the real editor, then asserts {@code get_highlights} returns
 * a non-error response — the same contract the headless {@code GetHighlightsCompatTest} asserted,
 * but now against the real out-of-process backend. "No highlights" is a pass (the tool ran); only
 * an {@code Error} response (the backend could not be queried) is a red cell.
 */
class GetHighlightsIntegrationTest {

    @Test
    fun `get_highlights returns a non-error response`() = IdeBench.run("getHighlights") { ide, mcp ->
        // Highlights are only cached for open files, so open the fixture file first.
        mcp.callTool("open_in_editor", mapOf("file" to ide.highlightsFile))
        val result = mcp.callTool(
            "get_highlights",
            mapOf("path" to ide.highlightsFile),
            timeout = Duration.ofSeconds(120),
        )
        println("[integration] ${ide.key}: get_highlights(${ide.highlightsFile}) → $result")
        assertFalse(
            result.startsWith("Error"),
            "get_highlights on ${ide.key} returned an error:\n$result",
        )
    }
}
