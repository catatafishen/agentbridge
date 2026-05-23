package com.github.catatafishen.agentbridge.ui

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for [parseQuickReplyOption] — the Kotlin-internal parser
 * that converts raw quick-reply option strings into [QuickReplyOption].
 */
class ParseQuickReplyOptionTest {

    @Nested
    inner class PlainLabels {
        @Test
        fun `simple label with no colon`() {
            val opt = parseQuickReplyOption("Yes")
            assertEquals("Yes", opt.label)
            assertEquals(QuickReplyColor.NONE, opt.color)
            assertFalse(opt.dismiss)
        }

        @Test
        fun `empty string`() {
            val opt = parseQuickReplyOption("")
            assertEquals("", opt.label)
            assertEquals(QuickReplyColor.NONE, opt.color)
            assertFalse(opt.dismiss)
        }

        @Test
        fun `whitespace-only string`() {
            val opt = parseQuickReplyOption("   ")
            assertEquals("   ", opt.label)
            assertEquals(QuickReplyColor.NONE, opt.color)
            assertFalse(opt.dismiss)
        }
    }

    @Nested
    inner class DismissSuffix {
        @Test
        fun `dismiss suffix strips and sets dismiss flag`() {
            val opt = parseQuickReplyOption("No:dismiss")
            assertEquals("No", opt.label)
            assertEquals(QuickReplyColor.NONE, opt.color)
            assertTrue(opt.dismiss)
        }

        @Test
        fun `dismiss is case-insensitive`() {
            val opt = parseQuickReplyOption("Cancel:DISMISS")
            assertEquals("Cancel", opt.label)
            assertTrue(opt.dismiss)
        }

        @Test
        fun `dismiss with surrounding spaces`() {
            val opt = parseQuickReplyOption("Skip : dismiss ")
            assertEquals("Skip", opt.label)
            assertTrue(opt.dismiss)
        }
    }

    @Nested
    inner class ColorSuffixes {
        @Test
        fun `primary color`() {
            val opt = parseQuickReplyOption("Apply:primary")
            assertEquals("Apply", opt.label)
            assertEquals(QuickReplyColor.PRIMARY, opt.color)
            assertFalse(opt.dismiss)
        }

        @Test
        fun `danger color`() {
            val opt = parseQuickReplyOption("Delete all:danger")
            assertEquals("Delete all", opt.label)
            assertEquals(QuickReplyColor.DANGER, opt.color)
        }

        @Test
        fun `success color`() {
            val opt = parseQuickReplyOption("Save:success")
            assertEquals("Save", opt.label)
            assertEquals(QuickReplyColor.SUCCESS, opt.color)
        }

        @Test
        fun `warning color`() {
            val opt = parseQuickReplyOption("Caution:warning")
            assertEquals("Caution", opt.label)
            assertEquals(QuickReplyColor.WARNING, opt.color)
        }

        @Test
        fun `color suffix is case-insensitive`() {
            val opt = parseQuickReplyOption("Apply:PRIMARY")
            assertEquals("Apply", opt.label)
            assertEquals(QuickReplyColor.PRIMARY, opt.color)
        }
    }

    @Nested
    inner class UnrecognizedSuffix {
        @Test
        fun `unknown suffix preserves entire string as label`() {
            val opt = parseQuickReplyOption("Unknown:foobar")
            assertEquals("Unknown:foobar", opt.label)
            assertEquals(QuickReplyColor.NONE, opt.color)
            assertFalse(opt.dismiss)
        }
    }

    @Nested
    inner class ColonsInLabel {
        @Test
        fun `only last colon is checked for suffix`() {
            val opt = parseQuickReplyOption("Label with: colon:primary")
            assertEquals("Label with: colon", opt.label)
            assertEquals(QuickReplyColor.PRIMARY, opt.color)
        }

        @Test
        fun `colon at start does not strip`() {
            // idx = 0, which is NOT > 0, so entire string is returned
            val opt = parseQuickReplyOption(":dismiss")
            assertEquals(":dismiss", opt.label)
            assertEquals(QuickReplyColor.NONE, opt.color)
            assertFalse(opt.dismiss)
        }

        @Test
        fun `multiple colons with recognized last suffix`() {
            val opt = parseQuickReplyOption("a:b:c:danger")
            assertEquals("a:b:c", opt.label)
            assertEquals(QuickReplyColor.DANGER, opt.color)
        }
    }

    @Nested
    inner class QuickReplyTagRegex {
        @Test
        fun `matches standard tag`() {
            val match = QUICK_REPLY_TAG_REGEX.find("[quick-reply: Yes | No]")
            assertNotNull(match)
            assertEquals("Yes | No", match!!.groupValues[1].trim())
        }

        @Test
        fun `matches tag with extra whitespace`() {
            val match = QUICK_REPLY_TAG_REGEX.find("[  quick-reply:  A | B  ]")
            assertNotNull(match)
        }

        @Test
        fun `no match on plain text`() {
            val match = QUICK_REPLY_TAG_REGEX.find("just some text")
            assertNull(match)
        }

        @Test
        fun `matches tag embedded in longer text`() {
            val text = "Some response text.\n[quick-reply: Option A | Option B]"
            val match = QUICK_REPLY_TAG_REGEX.find(text)
            assertNotNull(match)
        }
    }
}
