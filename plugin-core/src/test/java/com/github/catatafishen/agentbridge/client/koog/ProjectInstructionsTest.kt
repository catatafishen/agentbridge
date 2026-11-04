package com.github.catatafishen.agentbridge.client.koog

import com.github.catatafishen.agentbridge.client.claude.InstructionsManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ProjectInstructionsTest {

    @Nested
    inner class Loading {
        @Test
        fun `the file at the project root is returned`(@TempDir root: Path) {
            Files.writeString(root.resolve("AGENTS.md"), "# Rules\n\nDo the thing.\n")

            assertEquals("# Rules\n\nDo the thing.", ProjectInstructions.load(root.toString()))
        }

        @Test
        fun `a project without the file gets nothing`(@TempDir root: Path) {
            assertNull(ProjectInstructions.load(root.toString()))
        }

        @Test
        fun `a directory with that name is not read as a file`(@TempDir root: Path) {
            Files.createDirectory(root.resolve("AGENTS.md"))

            assertNull(ProjectInstructions.load(root.toString()))
        }

        @Test
        fun `a blank file gets nothing`(@TempDir root: Path) {
            Files.writeString(root.resolve("AGENTS.md"), "  \n\n \t\n")

            assertNull(ProjectInstructions.load(root.toString()))
        }

        @Test
        fun `a file that is not valid UTF-8 is skipped instead of failing the turn`(@TempDir root: Path) {
            Files.write(root.resolve("AGENTS.md"), byteArrayOf(0x41, 0xC3.toByte(), 0x28))

            assertNull(ProjectInstructions.load(root.toString()))
        }

        @Test
        fun `only the file at the root is used, not one in a subdirectory`(@TempDir root: Path) {
            Files.createDirectory(root.resolve("sub"))
            Files.writeString(root.resolve("sub").resolve("AGENTS.md"), "nested")

            assertNull(ProjectInstructions.load(root.toString()))
        }

        @Test
        fun `an edit is picked up by the next read`(@TempDir root: Path) {
            val file = root.resolve("AGENTS.md")
            Files.writeString(file, "first")
            assertEquals("first", ProjectInstructions.load(root.toString()))

            Files.writeString(file, "second")

            assertEquals("second", ProjectInstructions.load(root.toString()))
        }
    }

    @Nested
    inner class Preparing {
        private val generated = InstructionsManager.INSTRUCTIONS_SENTINEL +
            "\n\nGENERATED STARTUP TEXT\n\n> End of auto-generated instructions\n---"

        @Test
        fun `the plugin's own generated block is dropped and the rest kept`() {
            val prepared = ProjectInstructions.prepare("$generated\n\n# Repo rules\n\nBe kind.")

            assertEquals("# Repo rules\n\nBe kind.", prepared)
        }

        @Test
        fun `a file that is only the generated block gets nothing`() {
            assertNull(ProjectInstructions.prepare(generated))
        }

        @Test
        fun `a file without a generated block is kept whole`() {
            assertEquals("plain rules", ProjectInstructions.prepare("plain rules"))
        }

        @Test
        fun `text at the limit is not marked as truncated`() {
            val text = "x".repeat(ProjectInstructions.MAX_CHARS)

            assertEquals(text, ProjectInstructions.prepare(text))
        }

        @Test
        fun `text over the limit is cut and says so`() {
            val prepared = ProjectInstructions.prepare("x".repeat(ProjectInstructions.MAX_CHARS + 500))!!

            assertTrue(prepared.startsWith("x".repeat(ProjectInstructions.MAX_CHARS)))
            assertTrue(prepared.contains("AGENTS.md was truncated"), prepared.takeLast(120))
            assertFalse(prepared.contains("x".repeat(ProjectInstructions.MAX_CHARS + 1)))
        }
    }
}
