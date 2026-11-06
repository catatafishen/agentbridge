package com.github.catatafishen.agentbridge.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InstructionBuilderTest {

    @Test
    fun `placeholder is replaced with the IDE name`() {
        assertEquals(
            "Running inside a RustRover plugin.",
            InstructionBuilder.replacePlaceholder(
                "Running inside a ${InstructionBuilder.KEY_IDE_NAME} plugin.",
                "RustRover"
            )
        )
    }

    @Test
    fun `every occurrence of the placeholder is replaced`() {
        val input = "${InstructionBuilder.KEY_IDE_NAME} and ${InstructionBuilder.KEY_IDE_NAME}"
        assertEquals("PyCharm and PyCharm", InstructionBuilder.replacePlaceholder(input, "PyCharm"))
    }

    @Test
    fun `text without placeholder is unchanged`() {
        assertEquals("nothing to do", InstructionBuilder.replacePlaceholder("nothing to do", "GoLand"))
    }

    @Test
    fun `placeholder has the literal dollar-brace form`() {
        assertEquals("\${IDE_NAME}", InstructionBuilder.KEY_IDE_NAME)
    }
}
