package com.github.catatafishen.agentbridge.integration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Matrix row: {@code get_call_hierarchy} × {IU, CL}.
 *
 * Points the tool at {@code callHierarchySymbol} — a method/function — and asserts the result is
 * not an error and names the symbol. This is the bench guard for issue #794 bug #4. Resolution uses
 * the shared {@code ToolUtils.resolveNamedElement} (declaration-first, then reference fallback). On
 * IU the coordinate is the method *declaration* ({@code Widget.java:11}); on CLion Nova — whose lazy
 * C++ parser exposes no {@code PsiNameIdentifierOwner} on a declaration token — it is a *call site*
 * ({@code main.cpp:15}, {@code w.area()}) so resolution succeeds through the reference fallback and
 * {@code ReferencesSearch} reports that caller. A red cell means caller resolution no longer works
 * against the real backend. RD has no {@code callHierarchyFile} yet, so the cell skips
 * ({@code assumeTrue}) and renders as not-implemented (❓).
 */
class GetCallHierarchyIntegrationTest {

    @Test
    fun `get_call_hierarchy resolves callers by position`() {
        val ide = IdeUnderTest.current()
        val file = ide.callHierarchyFile
        assumeTrue(file != null, "get_call_hierarchy bench not implemented for ${ide.key}")
        requireNotNull(file)

        IdeBench.run("getCallHierarchy") { _, mcp ->
            val result = mcp.callTool(
                "get_call_hierarchy",
                mapOf(
                    "symbol" to ide.callHierarchySymbol,
                    "file" to file,
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
