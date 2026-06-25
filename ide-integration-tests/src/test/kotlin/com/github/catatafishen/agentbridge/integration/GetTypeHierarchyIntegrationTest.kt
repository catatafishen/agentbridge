package com.github.catatafishen.agentbridge.integration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Matrix row: {@code get_type_hierarchy} × {IU}.
 *
 * Asks for {@code direction=subtypes} at the class/interface declaration
 * ({@code typeHierarchyFile}:{@code typeHierarchyLine}). The subtypes direction goes through the
 * platform-level {@code DefinitionsScopedSearch} path (shared {@code ToolUtils.resolveNamedElement}).
 * This is the bench guard for issue #794 bug #6. The fixture {@code Widget} has no subtypes, so the
 * green result is the tool's "no subtypes" message — a non-error response that still names the
 * symbol. A red cell means subtype resolution no longer works against the real backend.
 *
 * <p>CLion Nova is a confirmed limitation: {@code refactoringNavSupported=false} makes the cell
 * skip with a {@code disabled}-flavoured assumption, so the matrix renders it 🚫 (unavailable) —
 * {@code DefinitionsScopedSearch} has no C++ query executor on the lazy frontend and {@code
 * resolveNamedElement} returns {@code null} for C++ declarations (see
 * {@code docs/bugs/issue-794-bug-inventory.md} #6). Rider has no {@code typeHierarchyFile}, so its
 * cell skips → ❓ (not benched yet).
 */
class GetTypeHierarchyIntegrationTest {

    @Test
    fun `get_type_hierarchy resolves subtypes by position`() {
        val ide = IdeUnderTest.current()
        assumeTrue(
            ide.refactoringNavSupported,
            "get_type_hierarchy is disabled on ${ide.key}: resolveNamedElement finds no " +
                "PsiNameIdentifierOwner and DefinitionsScopedSearch has no frontend query executor " +
                "on CLion Nova — see issue-794 inventory #6",
        )
        val file = ide.typeHierarchyFile
        assumeTrue(file != null, "get_type_hierarchy not benched for ${ide.key} (see issue-794 inventory #6)")
        requireNotNull(file)

        IdeBench.run("getTypeHierarchy") { _, mcp ->
            val result = mcp.callTool(
                "get_type_hierarchy",
                mapOf(
                    "symbol" to ide.expectedSymbol,
                    "direction" to "subtypes",
                    "path" to file,
                    "line" to ide.typeHierarchyLine,
                ),
                timeout = Duration.ofSeconds(120),
            )
            println(
                "[integration] ${ide.key}: get_type_hierarchy(${ide.expectedSymbol}, subtypes, " +
                    "$file:${ide.typeHierarchyLine}) → $result"
            )
            assertFalse(
                result.startsWith("Error"),
                "get_type_hierarchy on ${ide.key} returned an error:\n$result",
            )
            assertTrue(
                result.contains(ide.expectedSymbol),
                "Expected get_type_hierarchy on ${ide.key} to name '${ide.expectedSymbol}', got:\n$result",
            )
        }
    }
}
