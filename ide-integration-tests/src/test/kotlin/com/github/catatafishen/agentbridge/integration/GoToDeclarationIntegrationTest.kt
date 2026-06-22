package com.github.catatafishen.agentbridge.integration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Matrix row: {@code go_to_declaration} × {IU, CL}.
 *
 * Points the tool at a *usage* of {@code navigationSymbol} ({@code navigationUsageFile}:{@code line})
 * and asserts the resolved declaration snippet mentions the symbol. This is the bench guard for
 * issue #794 bug #3: for C/C++ leaf identifiers {@code getReference()} is null, so
 * {@code GoToDeclarationTool} resolves via the platform's language-agnostic
 * {@code findReferenceAt}/{@code TargetElementUtil} path — a red cell means that path no longer
 * resolves against the real CLion Nova backend. RD has no {@code navigationUsageFile} yet, so the
 * cell skips ({@code assumeTrue}) and renders as not-implemented (❓).
 */
class GoToDeclarationIntegrationTest {

    @Test
    fun `go_to_declaration resolves a declaration from a usage`() {
        val ide = IdeUnderTest.current()
        val usageFile = ide.navigationUsageFile
        assumeTrue(usageFile != null, "go_to_declaration bench not implemented for ${ide.key}")
        requireNotNull(usageFile)

        IdeBench.run("goToDeclaration") { _, mcp ->
            val result = mcp.callTool(
                "go_to_declaration",
                mapOf(
                    "file" to usageFile,
                    "line" to ide.navigationUsageLine,
                    "symbol" to ide.navigationSymbol,
                ),
                timeout = Duration.ofSeconds(120),
            )
            println(
                "[integration] ${ide.key}: go_to_declaration($usageFile:${ide.navigationUsageLine}, " +
                    "${ide.navigationSymbol}) → $result"
            )
            assertFalse(
                result.startsWith("Error"),
                "go_to_declaration on ${ide.key} returned an error:\n$result",
            )
            assertTrue(
                result.contains(ide.navigationSymbol),
                "Expected the declaration of '${ide.navigationSymbol}' from go_to_declaration on " +
                    "${ide.key}, got:\n$result",
            )
        }
    }
}
