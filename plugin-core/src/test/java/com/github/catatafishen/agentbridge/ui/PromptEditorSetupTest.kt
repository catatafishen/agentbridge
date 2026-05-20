package com.github.catatafishen.agentbridge.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Pure-logic tests for [PromptEditorLogic]'s companion methods.
 * No IDE / Project fixture is required — these test the decision logic
 * extracted from the UI event handlers.
 */
class PromptEditorSetupTest {

    @Nested
    inner class ResolveEnterActionTest {

        @Test
        fun `blank prompt returns noop`() {
            assertEquals("noop", PromptEditorLogic.resolveEnterAction(
                promptText = "", hasAuthPendingError = false,
                hasPendingAskUserRequest = false, isSending = false
            ))
            assertEquals("noop", PromptEditorLogic.resolveEnterAction(
                promptText = "   ", hasAuthPendingError = false,
                hasPendingAskUserRequest = false, isSending = false
            ))
        }

        @Test
        fun `auth pending error returns noop`() {
            assertEquals("noop", PromptEditorLogic.resolveEnterAction(
                promptText = "hello", hasAuthPendingError = true,
                hasPendingAskUserRequest = false, isSending = false
            ))
        }

        @Test
        fun `pending ask-user request returns send`() {
            assertEquals("send", PromptEditorLogic.resolveEnterAction(
                promptText = "yes", hasAuthPendingError = false,
                hasPendingAskUserRequest = true, isSending = false
            ))
        }

        @Test
        fun `is sending returns nudge`() {
            assertEquals("nudge", PromptEditorLogic.resolveEnterAction(
                promptText = "follow up", hasAuthPendingError = false,
                hasPendingAskUserRequest = false, isSending = true
            ))
        }

        @Test
        fun `pending ask-user takes precedence over is sending`() {
            assertEquals("send", PromptEditorLogic.resolveEnterAction(
                promptText = "y", hasAuthPendingError = false,
                hasPendingAskUserRequest = true, isSending = true
            ))
        }

        @Test
        fun `normal send when idle with text`() {
            assertEquals("send", PromptEditorLogic.resolveEnterAction(
                promptText = "hello world", hasAuthPendingError = false,
                hasPendingAskUserRequest = false, isSending = false
            ))
        }
    }

    @Nested
    inner class FilterSlashCommandsTest {

        private val commands = listOf("/help", "/new", "/history", "/clear")

        @Test
        fun `matches prefix case-insensitive`() {
            assertEquals(listOf("/help", "/history"), PromptEditorLogic.filterSlashCommands("/h", commands))
        }

        @Test
        fun `exact match returns command`() {
            assertEquals(listOf("/help"), PromptEditorLogic.filterSlashCommands("/help", commands))
        }

        @Test
        fun `no slash prefix returns empty`() {
            assertEquals(emptyList<String>(), PromptEditorLogic.filterSlashCommands("help", commands))
        }

        @Test
        fun `contains newline returns empty`() {
            assertEquals(emptyList<String>(), PromptEditorLogic.filterSlashCommands("/h\nsomething", commands))
        }

        @Test
        fun `no matches returns empty`() {
            assertEquals(emptyList<String>(), PromptEditorLogic.filterSlashCommands("/xyz", commands))
        }

        @Test
        fun `slash only matches all`() {
            assertEquals(commands, PromptEditorLogic.filterSlashCommands("/", commands))
        }

        @Test
        fun `empty command list returns empty`() {
            assertEquals(emptyList<String>(), PromptEditorLogic.filterSlashCommands("/h", emptyList()))
        }
    }

    @Nested
    inner class ShouldSmartPasteTest {

        @Test
        fun `short text below both thresholds returns false`() {
            assertFalse(PromptEditorLogic.shouldSmartPaste("hello", 5, 100))
        }

        @Test
        fun `multiline text exceeds line threshold returns true`() {
            val text = "line1\nline2\nline3\nline4\nline5\nline6"
            assertTrue(PromptEditorLogic.shouldSmartPaste(text, 5, 1000))
        }

        @Test
        fun `long single line exceeds char threshold returns true`() {
            val text = "a".repeat(101)
            assertTrue(PromptEditorLogic.shouldSmartPaste(text, 5, 100))
        }

        @Test
        fun `exactly at line threshold returns false`() {
            val text = "1\n2\n3\n4\n5" // 5 lines, threshold 5, not > 5
            assertFalse(PromptEditorLogic.shouldSmartPaste(text, 5, 1000))
        }

        @Test
        fun `one above line threshold returns true`() {
            val text = "1\n2\n3\n4\n5\n6"
            assertTrue(PromptEditorLogic.shouldSmartPaste(text, 5, 1000))
        }

        @Test
        fun `exactly at char threshold returns false`() {
            val text = "a".repeat(100)
            assertFalse(PromptEditorLogic.shouldSmartPaste(text, 100, 100))
        }

        @Test
        fun `one above char threshold returns true`() {
            val text = "a".repeat(101)
            assertTrue(PromptEditorLogic.shouldSmartPaste(text, 100, 100))
        }
    }
}
