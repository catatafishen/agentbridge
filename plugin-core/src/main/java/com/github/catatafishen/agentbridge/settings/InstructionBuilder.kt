package com.github.catatafishen.agentbridge.settings

import com.intellij.openapi.application.ApplicationInfo

/**
 * Builds the agents' startup instructions with the IDE name dynamically inserted.
 * This allows the instructions to better fit to **all** JetBrains IDEs.
 */
class InstructionBuilder {
    companion object {
        /**
         * The placeholder for the IDE name in the instructions.
         * The IDE name is dynamically inserted at runtime.
         */
        @JvmField
        var KEY_IDE_NAME: String = $$"${IDE_NAME}"

        private fun ideName(): String = ApplicationInfo.getInstance().fullApplicationName

        /**
         * Default instructions for the agent, with the IDE name dynamically inserted.
         */
        @JvmStatic
        fun buildDefault(): String {
            return "You are running inside a ${ideName()} plugin with IDE tools accessible via MCP."
        }

        /**
         * Builds a string with the IDE name dynamically inserted. The string must contain the placeholder
         * `${IDE_NAME}` / [KEY_IDE_NAME] for the IDE name to be inserted.
         */
        @JvmStatic
        fun build(string: String): String {
            return string.replace(
                KEY_IDE_NAME, ideName()
            )
        }
    }
}
