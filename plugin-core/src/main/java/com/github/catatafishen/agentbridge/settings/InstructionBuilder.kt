package com.github.catatafishen.agentbridge.settings

import com.intellij.openapi.application.ApplicationInfo

/**
 * Builds the agents' startup instructions with the IDE name dynamically inserted.
 * This allows the instructions to be more generic and adaptable to different IDEs.
 */
class InstructionBuilder {
    companion object {
        @JvmField
        var KEY_IDE_NAME: String = $$"${IDE_NAME}"

        private fun ideName(): String = ApplicationInfo.getInstance().fullApplicationName

        @JvmStatic
        fun buildDefault(): String {
            return "You are running inside a ${ideName()} plugin with IDE tools accessible via MCP."
        }

        @JvmStatic
        fun build(string: String): String {
            return string.replace(
                KEY_IDE_NAME, ideName()
            )
        }
    }
}
