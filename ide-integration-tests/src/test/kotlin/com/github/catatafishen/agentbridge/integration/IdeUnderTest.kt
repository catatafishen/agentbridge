package com.github.catatafishen.agentbridge.integration

import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.IdeInfo

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
                    symbolInfoFile = "classdef.h",
                    symbolInfoLine = 12,
                    expectedSymbolInfoName = "Widget",
                )

                "RD" -> IdeUnderTest(
                    key = "RD",
                    product = IdeProductProvider.RD,
                    version = System.getProperty("agentbridge.rd.version", "2026.1"),
                    fixture = "dotnet",
                    highlightsFile = "Widget.cs",
                    symbolQuery = "Widget",
                    expectedSymbol = "Widget",
                    outlineFile = null,
                    expectedOutlineSymbols = emptyList(),
                    symbolInfoFile = null,
                    symbolInfoLine = 0,
                    expectedSymbolInfoName = "",
                )

                else -> error("Unknown $IDE_PROPERTY='$key' (expected IU | CL | RD)")
            }
        }
    }
}
