package com.github.catatafishen.agentbridge.integration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Matrix row: {@code find_implementations} × {IU}.
 *
 * Points the tool at the class/interface declaration ({@code typeHierarchyFile}:{@code
 * typeHierarchyLine}) and asserts the result is not an error and still mentions the symbol. This is
 * the bench guard for issue #794 bug #5: with {@code file} and {@code line} the tool resolves via
 * the platform-level {@code DefinitionsScopedSearch} path (shared {@code
 * ToolUtils.resolveNamedElement}). The fixture {@code Widget} has no subtypes, so the green result
 * is the tool's "no subtypes or implementations found for: Widget" message — a non-error response
 * that still names the symbol. A red cell means that resolution no longer works against the real
 * backend.
 *
 * <p>CLion Nova and Rider have no {@code typeHierarchyFile}, so the cell skips ({@code assumeTrue})
 * and renders as not-implemented (❓). On Nova this is a confirmed limitation: {@code
 * DefinitionsScopedSearch} has no C++ query executor on the lazy frontend and {@code
 * resolveNamedElement} returns {@code null} for C++ declarations — see
 * {@code docs/bugs/issue-794-bug-inventory.md} (#5).
 */
class FindImplementationsIntegrationTest {

    @Test
    fun `find_implementations resolves a type by position`() {
        val ide = IdeUnderTest.current()
        val file = ide.typeHierarchyFile
        assumeTrue(file != null, "find_implementations not benched for ${ide.key} (see issue-794 inventory #5)")
        requireNotNull(file)

        IdeBench.run("findImplementations") { _, mcp ->
            val result = mcp.callTool(
                "find_implementations",
                mapOf(
                    "symbol" to ide.expectedSymbol,
                    "file" to file,
                    "line" to ide.typeHierarchyLine,
                ),
                timeout = Duration.ofSeconds(120),
            )
            println(
                "[integration] ${ide.key}: find_implementations(${ide.expectedSymbol}, " +
                    "$file:${ide.typeHierarchyLine}) → $result"
            )
            assertFalse(
                result.startsWith("Error"),
                "find_implementations on ${ide.key} returned an error:\n$result",
            )
            assertTrue(
                result.contains(ide.expectedSymbol),
                "Expected find_implementations on ${ide.key} to name '${ide.expectedSymbol}', got:\n$result",
            )
        }
    }
}
