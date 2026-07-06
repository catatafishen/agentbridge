package com.github.catatafishen.agentbridge.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
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
}
