package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.BuildInfo
import com.github.catatafishen.agentbridge.client.koog.KoogSupport
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import java.awt.datatransfer.StringSelection

/**
 * Shown in place of the Koog settings page when the agent cannot run in this IDE (or its classes fail to load).
 * It says why, and doubles as a debug surface: the details are what is needed to see why the agent is missing on a
 * given machine. Deliberately free of any Koog import, so it can load wherever Koog cannot.
 */
class KoogUnavailableConfigurable(private val failure: Throwable? = null) :
    com.intellij.openapi.options.BoundConfigurable("Built-in Agent (Koog)"),
    SearchableConfigurable {

    override fun getId(): String = KOOG_CONFIGURABLE_ID

    override fun createPanel(): com.intellij.openapi.ui.DialogPanel {
        val details = KoogAvailability.details(failure)
        return panel {
            row {
                text(
                    "<b>The built-in agent is not available in this IDE.</b><br>" + KoogAvailability.summary(failure),
                    MAX_LINE_LENGTH_WORD_WRAP,
                )
            }
            row {
                text(
                    "It needs a Kotlin runtime of ${KoogSupport.MIN_VERSION} or newer, which comes with IntelliJ " +
                        "2026.1 and later. Nothing else about AgentBridge is affected.",
                    MAX_LINE_LENGTH_WORD_WRAP,
                ).applyToComponent { foreground = UIUtil.getContextHelpForeground() }
            }
            group("Details") {
                row {
                    cell(javax.swing.JTextArea(details).apply {
                        isEditable = false
                        lineWrap = false
                        rows = details.lines().size
                        font = com.intellij.util.ui.JBUI.Fonts.create(java.awt.Font.MONOSPACED, 12)
                    }).resizableColumn()
                }
                row {
                    button("Copy details") {
                        CopyPasteManager.getInstance().setContents(StringSelection(details))
                    }
                    comment("Include these when reporting that the agent is missing.")
                }
            }
        }
    }

    companion object {
        const val KOOG_CONFIGURABLE_ID = "com.github.catatafishen.agentbridge.client.koog"
    }
}

/** Why the Koog agent is or is not available here, in a form both the log and the settings page can use. */
object KoogAvailability {

    private const val PROBE_CLASS = "ai.koog.prompt.message.Message"

    /** The first reason Koog cannot run, or null when the gate passes and the Koog classes load. */
    @JvmStatic
    fun problem(): String? {
        if (!KoogSupport.isSupported()) return KoogSupport.unsupportedReason()
        return probeClasses()?.let { "The Koog classes could not be loaded: $it" }
    }

    fun summary(failure: Throwable?): String = when {
        failure != null -> "The settings page failed to load: ${describe(failure)}"
        !KoogSupport.isSupported() -> KoogSupport.unsupportedReason()
        else -> probeClasses()?.let { "The Koog classes could not be loaded: $it" }
            ?: "The runtime requirements are met, so the page should not be missing; see the details."
    }

    /** Loads (without initialising) a Koog class through the plugin's own class loader. */
    private fun probeClasses(): String? = try {
        Class.forName(PROBE_CLASS, false, KoogAvailability::class.java.classLoader)
        null
    } catch (t: Throwable) {
        describe(t)
    }

    fun details(failure: Throwable?): String = buildString {
        appendLine("Plugin version:   ${BuildInfo.getVersion()}")
        appendLine("IDE:              ${ideDescription()}")
        appendLine("OS / Java:        ${SystemInfo.OS_NAME} ${SystemInfo.OS_VERSION} / ${System.getProperty("java.version")}")
        appendLine("Kotlin runtime:   ${KotlinVersion.CURRENT} (needs ${KoogSupport.MIN_VERSION}+) - what this plugin resolves")
        appendLine("Kotlin origin:    ${origin(KotlinVersion::class.java)}")
        appendLine("Platform Kotlin:  ${platformKotlin()} - what the IDE itself loads")
        appendLine("Gate passes:      ${KoogSupport.isSupported()}")
        appendLine("Koog classes:     ${probeClasses() ?: "load fine"}")
        appendLine("Plugin origin:    ${origin(KoogSupport::class.java)}")
        appendLine("Classpath Kotlin: ${classpathKotlin()}")
        if (failure != null) {
            appendLine("Page failure:     ${describe(failure)}")
        }
    }.trimEnd()

    /**
     * Where a class really comes from. A plugin class loader reports no code source, so use the URL of the class file
     * (which names the jar) and the loader that defined it.
     */
    private fun origin(type: Class<*>): String = try {
        val file = type.name.replace('.', '/') + ".class"
        val url = (type.classLoader ?: ClassLoader.getSystemClassLoader()).getResource(file)
        "${url ?: "unknown"} [loader: ${type.classLoader ?: "bootstrap"}]"
    } catch (t: Throwable) {
        "unknown (${describe(t)})"
    }

    /**
     * The Kotlin stdlib the IDE itself uses, read through the application class loader instead of this plugin's.
     * If it differs from [KotlinVersion.CURRENT], something other than the platform is supplying the stdlib to the plugin.
     */
    private fun platformKotlin(): String = try {
        val loader = ApplicationInfo::class.java.classLoader
        val type = Class.forName("kotlin.KotlinVersion", true, loader)
        val current = try {
            type.getField("CURRENT").get(null)
        } catch (_: NoSuchFieldException) {
            val companion = type.getField("Companion").get(null)
            companion.javaClass.getMethod("getCURRENT").invoke(companion)
        }
        "$current [loader: $loader]"
    } catch (t: Throwable) {
        "unknown (${describe(t)})"
    }

    /** Kotlin stdlib jars named on the JVM's own class path, in case a launcher or agent puts an old one there. */
    private fun classpathKotlin(): String = try {
        System.getProperty("java.class.path").orEmpty()
            .split(java.io.File.pathSeparatorChar)
            .filter { it.contains("kotlin", ignoreCase = true) }
            .ifEmpty { listOf("none") }
            .joinToString("; ")
    } catch (t: Throwable) {
        "unknown (${describe(t)})"
    }

    /** This page is the debug surface, so a failing lookup must be reported, never thrown. */
    private fun ideDescription(): String = try {
        val app = ApplicationInfo.getInstance()
        "${app.fullApplicationName} (${app.build.asString()})"
    } catch (t: Throwable) {
        "unknown (${describe(t)})"
    }

    private fun describe(t: Throwable): String =
        generateSequence(t) { it.cause }.joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
}
