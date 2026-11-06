package com.github.catatafishen.agentbridge.settings

import com.intellij.openapi.application.ApplicationInfo

/**
 * Builds the agents' startup instructions with the IDE name dynamically inserted.
 * This allows the instructions to better fit to **all** JetBrains IDEs.
 */
object InstructionBuilder {

    /**
     * The placeholder for the IDE name in the instructions (literally `${IDE_NAME}`).
     * The IDE name is dynamically inserted at runtime.
     */
    const val KEY_IDE_NAME: String = "\${IDE_NAME}"

    private fun ideName(): String = ApplicationInfo.getInstance().fullApplicationName

    /**
     * Default instructions for the agent, with the IDE name dynamically inserted.
     */
    @JvmStatic
    fun buildDefault(): String =
        build("You are running inside a $KEY_IDE_NAME plugin with IDE tools accessible via MCP.")

    /**
     * Replaces every [KEY_IDE_NAME] placeholder in [string] with the running IDE's name.
     */
    @JvmStatic
    fun build(string: String): String = replacePlaceholder(string, ideName())

    /**
     * Pure substitution, separated from [ApplicationInfo] so it can be unit-tested.
     */
    @JvmStatic
    fun replacePlaceholder(string: String, ideName: String): String = string.replace(KEY_IDE_NAME, ideName)
}
