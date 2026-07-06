package com.github.catatafishen.agentbridge.ui

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.util.Locale
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Paths
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Renders tool-call parameters (raw JSON arguments) as a structured key-value panel.
 *
 * Each JSON field is displayed as a muted key label + value component. Values are classified
 * and rendered as:
 * - **URL** (`http://`, `https://`): clickable link, opens in browser
 * - **File path** (starts with `/`, `~/`, `./`, `../`, or Windows drive): clickable link, opens in IDE editor
 * - **Commit hash** (7–40 hex chars with at least one letter a–f): styled as code, click-to-copy
 * - **Multi-line text**: first-line preview with line count; full text in tooltip
 * - **Plain text**: truncated to [MAX_VALUE_DISPLAY_LENGTH] chars; full text in tooltip
 *
 * A synthetic `tool` row is always prepended (showing the raw API tool name), and an
 * `input size` row is always appended (showing char count and rough token estimate at 4 chars/token).
 */
internal object ToolCallParamsPanel {

    private const val MAX_VALUE_DISPLAY_LENGTH = 200
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
     * Builds the parameters panel for display in [ToolCallPopup].
     *
     * @param toolName   Raw API tool name (e.g. `read_file`), shown as the first row.
     * @param paramsJson Raw JSON arguments string, or null when the tool takes no arguments.
     * @param project    IDE project context (used for file navigation).
     * @param bg         Background colour inherited from the popup panel.
     */
    fun build(toolName: String, paramsJson: String?, project: Project, bg: Color): JComponent {
        val grid = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            background = bg
            alignmentX = Component.LEFT_ALIGNMENT
        }
        var row = 0

        // Tool name is always the first row
        addRow(grid, row++, "tool", boldLabel(toolName), bg)

        // One row per field in the JSON object
        if (!paramsJson.isNullOrBlank()) {
            try {
                val json = JsonParser.parseString(paramsJson)
                if (json.isJsonObject) {
                    for ((key, element) in json.asJsonObject.entrySet()) {
                        addRow(grid, row++, key, valueComponent(element, project), bg)
                    }
                }
            } catch (_: Exception) {
                val preview = paramsJson.take(MAX_VALUE_DISPLAY_LENGTH)
                addRow(
                    grid, row++, "args",
                    plainLabel(preview, paramsJson.takeIf { it.length > MAX_VALUE_DISPLAY_LENGTH }),
                    bg
                )
            }
        }

        // Input size is always the last row
        val charCount = paramsJson?.length ?: 0
        val approxTokens = charCount / 4
        addRow(grid, row++, "input size", mutedLabel("$charCount chars (~$approxTokens tokens)"), bg)

        // Copy raw JSON row
        if (!paramsJson.isNullOrBlank()) {
            val copyLink = JBLabel("<html><a href=''>Copy raw JSON</a></html>").apply {
                toolTipText = "Copy the raw JSON arguments to clipboard"
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(paramsJson), null)
                    }

                    override fun mouseEntered(e: MouseEvent) {
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    }

                    override fun mouseExited(e: MouseEvent) {
                        cursor = Cursor.getDefaultCursor()
                    }
                })
            }
            addRow(grid, row++, "", copyLink, bg)
        }

        // Vertical filler prevents GridBagLayout from distributing rows evenly when extra height is available
        grid.add(JBPanel<JBPanel<*>>().apply { isOpaque = false }, GridBagConstraints().apply {
            gridx = 0; gridy = row; gridwidth = 2
            weighty = 1.0; fill = GridBagConstraints.VERTICAL
        })

        return grid
    }

    private fun addRow(panel: JPanel, row: Int, key: String, value: JComponent, bg: Color) {
        val keyLabel = JBLabel(key).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = JBUI.Fonts.smallFont()
            preferredSize = Dimension(JBUI.scale(KEY_COLUMN_WIDTH_PX), preferredSize.height)
            background = bg
        }
        panel.add(keyLabel, GridBagConstraints().apply {
            gridx = 0; gridy = row
            anchor = GridBagConstraints.NORTHWEST
            insets = JBUI.insets(2, 0, 2, 8)
        })
        panel.add(value, GridBagConstraints().apply {
            gridx = 1; gridy = row
            weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
            insets = JBUI.insets(2, 0, 2, 0)
        })
    }

    private fun valueComponent(element: JsonElement, project: Project): JComponent {
        if (element.isJsonNull) return mutedLabel("null")
        if (element.isJsonArray || element.isJsonObject) {
            val raw = element.toString()
            val display = if (raw.length > MAX_VALUE_DISPLAY_LENGTH) raw.take(MAX_VALUE_DISPLAY_LENGTH) + "…" else raw
            return monoLabel(display, raw.takeIf { it.length > MAX_VALUE_DISPLAY_LENGTH })
        }
        val raw = element.asString
        if (raw.isBlank()) return mutedLabel("(empty)")
        if (raw.contains('\n')) {
            val lines = raw.lines()
            val preview = "${lines.first().take(120)}… (${lines.size} lines)"
            return monoLabel(preview, raw)
        }
        return when (val type = classifyValue(raw)) {
            is ValueType.Url -> urlLink(type.url)
            is ValueType.FilePath -> filePathLink(type.path, project)
            is ValueType.CommitHash -> commitHashLabel(type.hash)
            ValueType.Plain -> {
                val display =
                    if (raw.length > MAX_VALUE_DISPLAY_LENGTH) raw.take(MAX_VALUE_DISPLAY_LENGTH) + "…" else raw
                plainLabel(display, raw.takeIf { it.length > MAX_VALUE_DISPLAY_LENGTH })
            }
        }
    }

    private fun urlLink(url: String): JBLabel =
        JBLabel("<html><a href=''>${escapeHtml(url.take(120))}</a></html>").apply {
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = BrowserUtil.browse(url)
                override fun mouseEntered(e: MouseEvent) {
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                }

                override fun mouseExited(e: MouseEvent) {
                    cursor = Cursor.getDefaultCursor()
                }
            })
        }

    private fun filePathLink(path: String, project: Project): JBLabel =
        JBLabel("<html><a href=''>${escapeHtml(path)}</a></html>").also { label ->
            label.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = openFileInIde(path, project)
                override fun mouseEntered(e: MouseEvent) {
                    label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                }

                override fun mouseExited(e: MouseEvent) {
                    label.cursor = Cursor.getDefaultCursor()
                }
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

    private fun boldLabel(text: String): JBLabel = JBLabel(text).apply {
        font = JBUI.Fonts.label().asBold()
    }

    private fun plainLabel(display: String, tooltip: String? = null): JBLabel = JBLabel(display).apply {
        if (tooltip != null) toolTipText = tooltip.take(500)
    }

    private fun monoLabel(display: String, tooltip: String? = null): JBLabel = JBLabel(display).apply {
        font = JBUI.Fonts.create("JetBrains Mono", UIUtil.getLabelFont().size - 1)
        if (tooltip != null) toolTipText = tooltip.take(500)
    }

    private fun mutedLabel(text: String): JBLabel = JBLabel(text).apply {
        foreground = UIUtil.getContextHelpForeground()
        font = JBUI.Fonts.smallFont()
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
