package com.github.catatafishen.agentbridge.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ToolCallParamsPanelTest {

    @Nested
    inner class ClassifyValue {

        // ── URL ──────────────────────────────────────────────────────────

        @Test
        fun `https URL returns Url`() {
            val result = ToolCallParamsPanel.classifyValue("https://example.com/page")
            assertInstanceOf(ToolCallParamsPanel.ValueType.Url::class.java, result)
        }

        @Test
        fun `http URL returns Url`() {
            val result = ToolCallParamsPanel.classifyValue("http://localhost:8080/api")
            assertInstanceOf(ToolCallParamsPanel.ValueType.Url::class.java, result)
        }

        @Test
        fun `URL preserves the original value`() {
            val url = "https://github.com/owner/repo/pull/42"
            val result = ToolCallParamsPanel.classifyValue(url) as ToolCallParamsPanel.ValueType.Url
            assertEquals(url, result.url)
        }

        // ── File path ────────────────────────────────────────────────────

        @Test
        fun `absolute unix path returns FilePath`() {
            val result = ToolCallParamsPanel.classifyValue("/home/user/project/Foo.kt")
            assertInstanceOf(ToolCallParamsPanel.ValueType.FilePath::class.java, result)
        }

        @Test
        fun `home-relative path returns FilePath`() {
            val result = ToolCallParamsPanel.classifyValue("~/Documents/notes.md")
            assertInstanceOf(ToolCallParamsPanel.ValueType.FilePath::class.java, result)
        }

        @Test
        fun `dot-relative path returns FilePath`() {
            val result = ToolCallParamsPanel.classifyValue("./src/main/Foo.kt")
            assertInstanceOf(ToolCallParamsPanel.ValueType.FilePath::class.java, result)
        }

        @Test
        fun `parent-relative path returns FilePath`() {
            val result = ToolCallParamsPanel.classifyValue("../other-module/Bar.java")
            assertInstanceOf(ToolCallParamsPanel.ValueType.FilePath::class.java, result)
        }

        @Test
        fun `windows absolute path returns FilePath`() {
            val result = ToolCallParamsPanel.classifyValue("C:\\Users\\user\\project\\Foo.kt")
            assertInstanceOf(ToolCallParamsPanel.ValueType.FilePath::class.java, result)
        }

        @Test
        fun `path with spaces returns Plain`() {
            val result = ToolCallParamsPanel.classifyValue("/path with spaces/file.txt")
            assertInstanceOf(ToolCallParamsPanel.ValueType.Plain::class.java, result)
        }

        // ── Commit hash ──────────────────────────────────────────────────

        @Test
        fun `40-char full hash returns CommitHash`() {
            val result = ToolCallParamsPanel.classifyValue("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2")
            assertInstanceOf(ToolCallParamsPanel.ValueType.CommitHash::class.java, result)
        }

        @Test
        fun `7-char short hash with letter returns CommitHash`() {
            val result = ToolCallParamsPanel.classifyValue("abc1234")
            assertInstanceOf(ToolCallParamsPanel.ValueType.CommitHash::class.java, result)
        }

        @Test
        fun `pure 7-digit integer is not a commit hash`() {
            val result = ToolCallParamsPanel.classifyValue("1234567")
            assertInstanceOf(ToolCallParamsPanel.ValueType.Plain::class.java, result)
        }

        @Test
        fun `hash value preserves original`() {
            val hash = "a1b2c3d"
            val result = ToolCallParamsPanel.classifyValue(hash) as ToolCallParamsPanel.ValueType.CommitHash
            assertEquals(hash, result.hash)
        }

        @Test
        fun `6-char hex is too short to be a commit hash`() {
            val result = ToolCallParamsPanel.classifyValue("abc123")
            assertInstanceOf(ToolCallParamsPanel.ValueType.Plain::class.java, result)
        }

        @Test
        fun `string with non-hex chars is not a commit hash`() {
            val result = ToolCallParamsPanel.classifyValue("abc123xyz")
            assertInstanceOf(ToolCallParamsPanel.ValueType.Plain::class.java, result)
        }

        // ── Plain ────────────────────────────────────────────────────────

        @Test
        fun `plain word returns Plain`() {
            assertInstanceOf(
                ToolCallParamsPanel.ValueType.Plain::class.java,
                ToolCallParamsPanel.classifyValue("hello")
            )
        }

        @Test
        fun `boolean string returns Plain`() {
            assertInstanceOf(
                ToolCallParamsPanel.ValueType.Plain::class.java,
                ToolCallParamsPanel.classifyValue("true")
            )
        }

        @Test
        fun `number string returns Plain`() {
            assertInstanceOf(
                ToolCallParamsPanel.ValueType.Plain::class.java,
                ToolCallParamsPanel.classifyValue("42")
            )
        }

        @Test
        fun `URL takes precedence over file-path-like values`() {
            val result = ToolCallParamsPanel.classifyValue("https://example.com/path/to/file")
            assertInstanceOf(ToolCallParamsPanel.ValueType.Url::class.java, result)
        }

        @Test
        fun `whitespace is trimmed before classification`() {
            val result = ToolCallParamsPanel.classifyValue("  https://example.com  ")
            assertInstanceOf(ToolCallParamsPanel.ValueType.Url::class.java, result)
        }
    }

    @Nested
    inner class DisplayText {

        @Test
        fun `short text is shown whole`() {
            assertEquals("Run the unit tests", ToolCallParamsPanel.displayText("Run the unit tests"))
        }

        @Test
        fun `text of a few lines is shown whole, with its own line endings`() {
            assertEquals("one\r\ntwo", ToolCallParamsPanel.displayText("one\r\ntwo"))
        }

        @Test
        fun `long single line is cut at 1000 characters`() {
            val shown = ToolCallParamsPanel.displayText("a".repeat(1500))

            assertEquals("a".repeat(1000) + "…", shown)
        }

        @Test
        fun `an emoji at the cut is left out whole, not split`() {
            val shown = ToolCallParamsPanel.displayText("a".repeat(999) + "😀" + "a".repeat(10))

            assertEquals("a".repeat(999) + "…", shown)
        }

        @Test
        fun `text of exactly 1000 characters is not cut`() {
            assertEquals("a".repeat(1000), ToolCallParamsPanel.displayText("a".repeat(1000)))
        }

        @Test
        fun `many lines are cut after 8 and the line count is given`() {
            val raw = (1..30).joinToString("\n") { "line $it" }

            val shown = ToolCallParamsPanel.displayText(raw)

            assertEquals((1..8).joinToString("\n") { "line $it" } + "… (30 lines)", shown)
        }

        @Test
        fun `long lines are cut by length before the line limit`() {
            val raw = List(5) { "b".repeat(300) }.joinToString("\n")

            val shown = ToolCallParamsPanel.displayText(raw)

            assertTrue(shown.startsWith(raw.take(1000)), shown)
            assertTrue(shown.endsWith("… (5 lines)"), shown)
        }
    }

    @Nested
    inner class WrappedText {

        @Test
        fun `long text wraps to the width it is given`() {
            val text = ToolCallParamsPanel.wrappedText("word ".repeat(100).trim())
            val oneLine = text.getFontMetrics(text.font).height

            text.setSize(200, oneLine)

            assertTrue(text.preferredSize.height > 3 * oneLine, "height ${text.preferredSize.height}")
        }
    }
}
