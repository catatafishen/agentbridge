package com.github.catatafishen.agentbridge.integration

import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.IdeInfo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * One column of the IDE × MCP-tool compatibility matrix: which product to launch and the
 * fixture/symbol the tool assertions reference.
 *
 * The active IDE is selected by the `agentbridge.ide` system property (IU | CL | RD), set per
 * CI matrix entry in `ide-integration-tests/build.gradle.kts`. Each product opens its own
 * committed fixture under `fixtures/` so the real language backend (IntelliJ Java, CLion
 * Nova/Radler, Rider/ReSharper) engages on a real project model.
 */
data class IdeUnderTest(
    /** Two-letter product code used as the matrix column and CI artifact suffix. */
    val key: String,
    /** Starter product descriptor; the framework downloads and launches this product. */
    val product: IdeInfo,
    /** Product version the Starter framework downloads. */
    val version: String,
    /** Fixture directory under `fixtures/` that this IDE opens. */
    val fixture: String,
    /** Project-relative file opened before the `get_highlights` assertion. */
    val highlightsFile: String,
    /** Exact name passed to `search_symbols`; every fixture declares a `Widget` type. */
    val symbolQuery: String,
    /** Substring the tool result must contain for the cell to be green. */
    val expectedSymbol: String,
    /** Project-relative file passed to `get_file_outline`; `null` = column not covered (cell skips → ❓). */
    val outlineFile: String?,
    /** Symbols the `get_file_outline` result must contain for the cell to be green. */
    val expectedOutlineSymbols: List<String>,
    /**
     * Whether {@code search_symbols} is implemented for this IDE; false = cell skips.
     * <p>
     * Rider's C#/F#/VB PSI lives in the ReSharper backend, not the IntelliJ frontend.
     * {@code classifyElement()} fails on Rider's coarse PSI stubs, so {@code search_symbols}
     * is listed in the Rider-disabled tools table in README.md and guarded by
     * {@code PsiBridgeService.RIDER_DISABLED_TOOLS}. The matrix cell skips with
     * {@code assumeTrue} — same pattern as {@code outlineFile} / {@code symbolInfoFile} —
     * and renders as not-implemented in the report.
     */
    val searchSymbolsSupported: Boolean = true,
    /**
     * How long to wait for the plugin's MCP server to report healthy after the IDE launches.
     * Rider launches a frontend plus a separate ReSharperHost (.NET) backend, and the project's
     * `postStartupActivity` — which starts the MCP server — only fires once that backend has
     * booted and loaded the solution. In CI that cold-start runs well past the 2-minute budget
     * that suffices for IntelliJ/CLion, so Rider gets a longer window.
     */
    val bootTimeout: Duration,
    /** Project-relative file passed to `get_symbol_info`; `null` = column not covered (cell skips → ❓). */
    val symbolInfoFile: String?,
    /** 1-based line passed to `get_symbol_info`, pointing at a declaration (e.g. a class). */
    val symbolInfoLine: Int,
    /** Substring the `get_symbol_info` result must contain for the cell to be green. */
    val expectedSymbolInfoName: String,
) {
    companion object {
        /** System property (set by the build per CI matrix entry) selecting the product to launch. */
        const val IDE_PROPERTY: String = "agentbridge.ide"

        /** Resolves the IDE-under-test from the `agentbridge.ide` system property. */
        fun current(): IdeUnderTest {
            return when (val key = System.getProperty(IDE_PROPERTY, "CL")) {
                "IU" -> IdeUnderTest(
                    key = "IU",
                    product = IdeProductProvider.IU,
                    version = System.getProperty("agentbridge.iu.version", "2026.1.3"),
                    fixture = "java-app",
                    highlightsFile = "src/fixture/Widget.java",
                    symbolQuery = "Widget",
                    expectedSymbol = "Widget",
                    outlineFile = "src/fixture/Widget.java",
                    expectedOutlineSymbols = listOf("Widget", "area"),
                    bootTimeout = 2.minutes,
                    symbolInfoFile = "src/fixture/Widget.java",
                    symbolInfoLine = 7,
                    expectedSymbolInfoName = "Widget",
                )

                "CL" -> IdeUnderTest(
                    key = "CL",
                    product = IdeProductProvider.CL,
                    version = System.getProperty("agentbridge.cl.version", "2026.1"),
                    fixture = "cpp-cmake",
                    highlightsFile = "classdef.h",
                    symbolQuery = "Widget",
                    expectedSymbol = "Widget",
                    outlineFile = "classdef.h",
                    expectedOutlineSymbols = listOf("Widget", "Point"),
                    bootTimeout = 2.minutes,
                    symbolInfoFile = "classdef.h",
                    symbolInfoLine = 12,
                    expectedSymbolInfoName = "Widget",
                )

                "RD" -> IdeUnderTest(
                    key = "RD",
                    product = IdeProductProvider.RD,
                    version = System.getProperty("agentbridge.rd.version", "2026.1"),
                    fixture = "dotnet/dotnet.sln",
                    highlightsFile = "Widget.cs",
                    symbolQuery = "Widget",
                    expectedSymbol = "Widget",
                    outlineFile = null,
                    expectedOutlineSymbols = emptyList(),
                    searchSymbolsSupported = false,
                    bootTimeout = 10.minutes,
                    symbolInfoFile = null,
                    symbolInfoLine = 0,
                    expectedSymbolInfoName = "",
                )

                else -> error("Unknown $IDE_PROPERTY='$key' (expected IU | CL | RD)")
            }
        }
    }
}
