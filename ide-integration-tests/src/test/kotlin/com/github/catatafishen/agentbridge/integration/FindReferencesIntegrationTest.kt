package com.github.catatafishen.agentbridge.integration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Matrix row: {@code find_references} × {IU, CL}.
 *
 * Searches for usages of the fixture's {@code navigationSymbol} (a field for Java, the {@code Widget}
 * type for C++) and asserts at least one reference comes back. This is the bench guard for issue
 * #794 bug #1b: CLion Nova's lazy C++ parser exposes no {@code PsiNamedElement} for declarations,
 * so {@code FindReferencesTool} falls back to a word-search path — a red cell here means that
 * fallback stopped finding usages against the real backend. RD has no {@code navigationUsageFile}
 * yet, so the cell skips ({@code assumeTrue}) and renders as not-implemented (❓).
 */
class FindReferencesIntegrationTest {

    @Test
    fun `find_references finds usages of a symbol`() {
        val ide = IdeUnderTest.current()
        assumeTrue(ide.navigationUsageFile != null, "find_references bench not implemented for ${ide.key}")

        IdeBench.run("findReferences") { _, mcp ->
            val result = mcp.callTool(
                "find_references",
                mapOf("symbol" to ide.navigationSymbol),
                timeout = Duration.ofSeconds(120),
            )
            println("[integration] ${ide.key}: find_references(${ide.navigationSymbol}) → $result")
            assertFalse(
                result.startsWith("Error"),
                "find_references on ${ide.key} returned an error:\n$result",
            )
            assertTrue(
                result.contains(ide.navigationSymbol),
                "Expected a reference mentioning '${ide.navigationSymbol}' from find_references on " +
                    "${ide.key}, got:\n$result",
            )
        }
    }
}
