package com.github.catatafishen.agentbridge.ui.renderers

import com.github.catatafishen.agentbridge.ui.FileNavigator
import com.github.catatafishen.agentbridge.ui.MarkdownRenderer
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent

object HtmlToolRendererSupport {
    fun markdownPane(markdown: String): JComponent {
        val font = UIUtil.getLabelFont()
        val html = MarkdownRenderer.markdownToHtml(markdown)
        return JEditorPane(
            "text/html",
            """
            <html>
              <head>
                <style>
                  body { font-family: '${font.family}'; font-size: ${font.size}pt; margin: 0; }
                  p, ul, table, blockquote, pre { margin-top: 0; margin-bottom: 6px; }
                  code, pre { font-family: 'JetBrains Mono', 'Consolas', monospace; }
                </style>
              </head>
              <body>$html</body>
            </html>
            """.trimIndent()
        ).apply {
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            isEditable = false
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
            alignmentX = JComponent.LEFT_ALIGNMENT
            addHyperlinkListener(::handleActivatedLink)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun handleActivatedLink(event: HyperlinkEvent) {
        if (event.eventType != HyperlinkEvent.EventType.ACTIVATED) return
        val href = event.description ?: event.url?.toExternalForm() ?: return
        when {
            href.startsWith("openfile://") || href.startsWith("gitshow://") -> {
                ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }?.let { project ->
                    FileNavigator(project).handleFileLink(href)
                }
            }

            href.startsWith("http://") || href.startsWith("https://") -> BrowserUtil.browse(href)
        }
    }
}
