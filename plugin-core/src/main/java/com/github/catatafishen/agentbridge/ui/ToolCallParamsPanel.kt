package com.github.catatafishen.agentbridge.ui

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Paths
import java.util.*
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.SwingConstants

/**
 * Renders tool-call parameters (raw JSON arguments) as a structured key-value panel.
 *
 * Each JSON field is displayed as a muted key label + value component. Values are classified
 * and rendered as:
 * - **URL** (`http://`, `https://`): wrapping link, opens in browser
 * - **File path** (starts with `/`, `~/`, `./`, `../`, or Windows drive): wrapping link, opens in IDE editor
 * - **Commit hash** (7–40 hex chars with at least one letter a–f): styled as code, click-to-copy
 * - **Text, arrays and objects**: wrapped to the popup's width, shortened by [displayText]; multi-line text and
 *   JSON in a monospaced font
 */
internal object ToolCallParamsPanel {

    private const val MAX_VALUE_DISPLAY_LENGTH = 1000
    private const val MAX_VALUE_DISPLAY_LINES = 8
    private const val KEY_COLUMN_WIDTH_PX = 100

    private val KNOWN_SOURCE_EXTENSIONS = setOf(
        "kt", "kts", "java", "py", "ts", "tsx", "js", "jsx", "go", "rs",
        "cpp", "c", "h", "rb", "swift", "scala", "groovy", "xml", "json",
        "yaml", "yml", "toml", "gradle", "properties", "sh", "bash", "zsh",
        "md", "txt", "sql", "html", "css", "scss",
    )

    /** Classification of a parameter value for rendering decisions. */
    sealed class ValueType {
        object Plain : ValueType()
        data class Url(val url: String) : ValueType()
        data class FilePath(val path: String) : ValueType()
        data class CommitHash(val hash: String) : ValueType()
    }

    /**
     * Classifies a single-line string value. Pure function — no side effects.
     *
     * Precedence: URL > file path > commit hash > plain.
     */
    fun classifyValue(value: String): ValueType {
        val trimmed = value.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") ->
                ValueType.Url(trimmed)

            looksLikeFilePath(trimmed) -> ValueType.FilePath(trimmed)
            looksLikeCommitHash(trimmed) -> ValueType.CommitHash(trimmed)
            else -> ValueType.Plain
        }
    }

    private fun looksLikeFilePath(value: String): Boolean {
        if (value.isBlank() || value.contains('\n') || value.contains(' ')) return false
        if (value.startsWith("/") ||
            value.startsWith("~/") ||
            value.startsWith("./") ||
            value.startsWith("../") ||
            Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(value)
        ) return true
        // Relative path with directory separator (e.g. src/main/Foo.kt)
        if (value.contains('/') || value.contains('\\')) return true
        // Bare filename with a known source or config extension (e.g. ToolCallPopup.kt)
        val ext = value.substringAfterLast('.', "")
        return ext.isNotEmpty() && ext.lowercase(Locale.ROOT) in KNOWN_SOURCE_EXTENSIONS
    }

    private fun looksLikeCommitHash(value: String): Boolean {
        if (value.length !in 7..40) return false
        if (!value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return false
        // Require at least one letter to avoid misclassifying pure integers as hashes.
        return value.any { it in 'a'..'f' || it in 'A'..'F' }
    }

    /**
     * The part of a value the popup shows: at most [MAX_VALUE_DISPLAY_LINES] lines and [MAX_VALUE_DISPLAY_LENGTH]
     * characters. A shortened value ends with "…", and with its line count when it has several lines.
     */
    fun displayText(raw: String): String {
        val lines = raw.lines()
        val kept = lines.take(MAX_VALUE_DISPLAY_LINES).joinToString("\n")
        if (lines.size <= MAX_VALUE_DISPLAY_LINES && kept.length <= MAX_VALUE_DISPLAY_LENGTH) return raw
        val cut = kept.take(MAX_VALUE_DISPLAY_LENGTH)
        // Keep a character outside the BMP (e.g. an emoji) whole.
        val shown = if (cut.lastOrNull()?.isHighSurrogate() == true) cut.dropLast(1) else cut
        return if (lines.size > 1) "$shown… (${lines.size} lines)" else "$shown…"
    }

    /**
     * Read-only text that wraps at word boundaries to the width its container gives it, keeps the text's own line
     * breaks, and can be selected.
     */
    fun wrappedText(text: String, font: Font = UIUtil.getLabelFont()): JBTextArea = JBTextArea(text).apply {
        lineWrap = true
        wrapStyleWord = true
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty()
        this.font = font
        alignmentX = Component.LEFT_ALIGNMENT
    }

    /**
     * Builds the parameters panel for display in [ToolCallPopup].
     *
     * @param paramsJson Raw JSON arguments string, or null when the tool takes no arguments.
     * @param project    IDE project context (used for file navigation).
     * @param bg         Background colour inherited from the popup panel.
     */
    fun build(paramsJson: String?, project: Project, bg: Color): JComponent {
        val rows = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = bg
            alignmentX = Component.LEFT_ALIGNMENT
        }
        if (paramsJson.isNullOrBlank()) return rows

        try {
            val json = JsonParser.parseString(paramsJson)
            if (json.isJsonObject) {
                for ((key, element) in json.asJsonObject.entrySet()) {
                    rows.add(row(key, valueComponent(element, project), bg))
                }
            }
        } catch (_: Exception) {
            rows.add(row("args", wrappedText(displayText(paramsJson)), bg))
        }
        return rows
    }

    /** The key in a fixed-width column, and the value in the rest of the width, so that wrapped text fits it. */
    private fun row(key: String, value: JComponent, bg: Color): JComponent {
        val keyLabel = JBLabel(key).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = JBUI.Fonts.smallFont()
            verticalAlignment = SwingConstants.TOP
            preferredSize = Dimension(JBUI.scale(KEY_COLUMN_WIDTH_PX), preferredSize.height)
        }
        return object : JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(8), 0)) {
            // Rows keep their height; the popup's glue below them takes any extra space.
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            background = bg
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(2, 0)
            add(keyLabel, BorderLayout.WEST)
            add(value, BorderLayout.CENTER)
        }
    }

    private fun valueComponent(element: JsonElement, project: Project): JComponent {
        if (element.isJsonNull) return mutedLabel("null")
        if (element.isJsonArray || element.isJsonObject) return wrappedText(displayText(element.toString()), monoFont())
        val raw = element.asString
        if (raw.isBlank()) return mutedLabel("(empty)")
        if (raw.contains('\n')) return wrappedText(displayText(raw), monoFont())
        return when (val type = classifyValue(raw)) {
            is ValueType.Url -> wrappedLink(type.url) { BrowserUtil.browse(type.url) }
            is ValueType.FilePath -> wrappedLink(type.path) { openFileInIde(type.path, project) }
            is ValueType.CommitHash -> commitHashLabel(type.hash)
            ValueType.Plain -> wrappedText(displayText(raw))
        }
    }

    /** A link that wraps like [wrappedText], breaking a long path or URL wherever it has to. */
    private fun wrappedLink(text: String, onClick: () -> Unit): JBTextArea = wrappedText(displayText(text)).apply {
        foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onClick()
        })
    }

    private fun commitHashLabel(hash: String): JBLabel =
        JBLabel("<html><code>$hash</code></html>").apply {
            toolTipText = "Click to copy"
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(hash), null)
                }

                override fun mouseEntered(e: MouseEvent) {
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                }

                override fun mouseExited(e: MouseEvent) {
                    cursor = Cursor.getDefaultCursor()
                }
            })
        }

    private fun openFileInIde(path: String, project: Project) {
        val expandedPath = if (path.startsWith("~/")) {
            System.getProperty("user.home") + path.substring(1)
        } else {
            path
        }
        val vf = LocalFileSystem.getInstance().findFileByPath(expandedPath)
            ?: project.basePath?.let { base ->
                LocalFileSystem.getInstance().findFileByPath(Paths.get(base, expandedPath).toString())
            } ?: return
        ApplicationManager.getApplication().invokeLater {
            OpenFileDescriptor(project, vf).navigate(true)
        }
    }

    private fun monoFont(): Font = JBUI.Fonts.create("JetBrains Mono", UIUtil.getLabelFont().size - 1)

    private fun mutedLabel(text: String): JBLabel = JBLabel(text).apply {
        foreground = UIUtil.getContextHelpForeground()
        font = JBUI.Fonts.smallFont()
    }
}
