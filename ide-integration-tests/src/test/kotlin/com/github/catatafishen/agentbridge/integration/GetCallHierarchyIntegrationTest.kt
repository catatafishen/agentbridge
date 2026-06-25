package com.github.catatafishen.agentbridge.integration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Matrix row: {@code get_call_hierarchy} × {IU}.
 *
 * Points the tool at {@code callHierarchySymbol} — a method/function — and asserts the result is
 * not an error and names the symbol. This is the bench guard for issue #794 bug #4. Resolution uses
 * the shared {@code ToolUtils.resolveNamedElement} (declaration-first, then reference fallback); on
 * IU the coordinate is the method *declaration* ({@code Widget.java:11}). A red cell means caller
 * resolution no longer works against the real backend.
 *
 * <p>CLion Nova is a confirmed limitation: {@code refactoringNavSupported=false} makes the cell
 * skip with a {@code disabled}-flavoured assumption, so the matrix renders it 🚫 (unavailable) —
 * {@code ReferencesSearch} has no C++ query executor on the lazy frontend and {@code
 * resolveNamedElement} returns {@code null} for C++ declarations (see
 * {@code docs/bugs/issue-794-bug-inventory.md} #4). Rider has no {@code callHierarchyFile}, so its
 * cell skips → ❓ (not benched yet).
 */
class GetCallHierarchyIntegrationTest {

    @Test
    fun `get_call_hierarchy resolves callers by position`() {
        val ide = IdeUnderTest.current()
        assumeTrue(
            ide.refactoringNavSupported,
            "get_call_hierarchy is disabled on ${ide.key}: resolveNamedElement finds no " +
                "PsiNameIdentifierOwner and ReferencesSearch has no frontend query executor " +
                "on CLion Nova — see issue-794 inventory #4",
        )
        val file = ide.callHierarchyFile
        assumeTrue(file != null, "get_call_hierarchy not benched for ${ide.key} (see issue-794 inventory #4)")
        requireNotNull(file)

        IdeBench.run("getCallHierarchy") { _, mcp ->
            val result = mcp.callTool(
                "get_call_hierarchy",
                mapOf(
                    "symbol" to ide.callHierarchySymbol,
                    "path" to file,
                    "line" to ide.callHierarchyLine,
                ),
                timeout = Duration.ofSeconds(120),
            )
            println(
                "[integration] ${ide.key}: get_call_hierarchy(${ide.callHierarchySymbol}, " +
                    "$file:${ide.callHierarchyLine}) → $result"
            )
            assertFalse(
                result.startsWith("Error"),
                "get_call_hierarchy on ${ide.key} returned an error:\n$result",
            )
            assertTrue(
                result.contains(ide.callHierarchySymbol),
                "Expected get_call_hierarchy on ${ide.key} to name '${ide.callHierarchySymbol}', " +
                    "got:\n$result",
            )
        }
    }
}
