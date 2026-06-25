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
 * <p>CLion Nova is a confirmed limitation: {@code refactoringNavSupported=false} makes the cell
 * skip with a {@code disabled}-flavoured assumption, so the matrix renders it 🚫 (unavailable) —
 * {@code DefinitionsScopedSearch} has no C++ query executor on the lazy frontend and {@code
 * resolveNamedElement} returns {@code null} for C++ declarations (see
 * {@code docs/bugs/issue-794-bug-inventory.md} #5). Rider has no {@code typeHierarchyFile}, so its
 * cell skips → ❓ (not benched yet).
 */
class FindImplementationsIntegrationTest {

    @Test
    fun `find_implementations resolves a type by position`() {
        val ide = IdeUnderTest.current()
        assumeTrue(
            ide.refactoringNavSupported,
            "find_implementations is disabled on ${ide.key}: resolveNamedElement finds no " +
                "PsiNameIdentifierOwner and DefinitionsScopedSearch has no frontend query executor " +
                "on CLion Nova — see issue-794 inventory #5",
        )
        val file = ide.typeHierarchyFile
        assumeTrue(file != null, "find_implementations not benched for ${ide.key} (see issue-794 inventory #5)")
        requireNotNull(file)

        IdeBench.run("findImplementations") { _, mcp ->
            val result = mcp.callTool(
                "find_implementations",
                mapOf(
                    "symbol" to ide.expectedSymbol,
                    "path" to file,
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
