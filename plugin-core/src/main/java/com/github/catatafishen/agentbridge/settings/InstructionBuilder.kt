package com.github.catatafishen.agentbridge.settings

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager

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

    /**
     * The running IDE's full name. Without an IDE Application (plain unit tests that load
     * McpProtocolHandler, whose static initializer builds the instructions) there is no name to insert,
     * so the placeholder is kept visibly instead of failing class initialization.
     */
    private fun ideName(): String =
        if (ApplicationManager.getApplication() == null) KEY_IDE_NAME
        else ApplicationInfo.getInstance().fullApplicationName

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
