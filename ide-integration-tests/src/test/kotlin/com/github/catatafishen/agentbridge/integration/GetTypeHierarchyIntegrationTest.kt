package com.github.catatafishen.agentbridge.integration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Matrix row: {@code get_type_hierarchy} × {IU, CL}.
 *
 * Asks for {@code direction=subtypes} at a place where {@code expectedSymbol} resolves to a
 * class/interface ({@code typeHierarchyFile}:{@code typeHierarchyLine}). The subtypes direction is
 * the language-agnostic path ({@code DefinitionsScopedSearch} via the shared
 * {@code ToolUtils.resolveNamedElement}), so a single coordinate covers both Java (IU) and C++
 * (CL). This is the bench guard for issue #794 bug #6. For CLion Nova — whose lazy C++ parser
 * exposes no {@code PsiNameIdentifierOwner} on a declaration token — the coordinate points at a
 * *usage* ({@code main.cpp:12}) so resolution succeeds through the reference fallback. The fixture
 * {@code Widget} has no subtypes, so the green result is the tool's "no subtypes" message — a
 * non-error response that still names the symbol. A red cell means subtype resolution no longer
 * works against the real backend. RD has no {@code typeHierarchyFile} yet, so the cell skips
 * ({@code assumeTrue}) and renders as not-implemented (❓).
 */
class GetTypeHierarchyIntegrationTest {

    @Test
    fun `get_type_hierarchy resolves subtypes by position`() {
        val ide = IdeUnderTest.current()
        val file = ide.typeHierarchyFile
        assumeTrue(file != null, "get_type_hierarchy bench not implemented for ${ide.key}")
        requireNotNull(file)

        IdeBench.run("getTypeHierarchy") { _, mcp ->
            val result = mcp.callTool(
                "get_type_hierarchy",
                mapOf(
                    "symbol" to ide.expectedSymbol,
                    "direction" to "subtypes",
                    "file" to file,
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
