package com.github.catatafishen.agentbridge.attribution

import com.github.catatafishen.agentbridge.session.db.LineAttributionStore
import com.github.catatafishen.agentbridge.ui.side.HistoryContextWindow
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorGutterAction
import com.intellij.openapi.editor.TextAnnotationGutterProvider
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.EditorGutterComponentEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.Color
import java.awt.Cursor
import java.util.concurrent.ConcurrentHashMap

/**
 * The gutter column of "Annotate with AgentBridge Prompts": for each line, the prompt that last wrote
 * it through an editing tool, in the style of git annotate.
 *
 * Painting must be fast, so everything painted comes from memory; prompt details are fetched in the
 * background and the column repaints when they arrive.
 */
internal class PromptAnnotationProvider(
    private val project: Project,
    private val file: VirtualFile,
    initialInfos: Map<String, LineAttributionStore.TurnInfo>,
) : TextAnnotationGutterProvider, EditorGutterAction {

    private val service = PromptAttributionService.getInstance(project)
    private val infos = ConcurrentHashMap(initialInfos)
    private val lastRequestMs = ConcurrentHashMap<String, Long>()
    private val colors = ConcurrentHashMap<String, Color>()

    @Volatile
    private var editor: Editor? = null

    private val changeListener = Runnable {
        ApplicationManager.getApplication().invokeLater { refresh() }
    }

    fun attach(editor: Editor) {
        this.editor = editor
        service.addChangeListener(changeListener)
    }

    private fun refresh() {
        val e = editor ?: return
        if (e.isDisposed) return
        (e.gutter as? EditorGutterComponentEx)?.revalidateMarkup()
    }

    private fun owner(line: Int): LineOwner? = service.peekOwnership(file)?.ownerAt(line)

    private fun requestInfo(turnId: String) {
        if (infos.containsKey(turnId)) return
        val now = System.currentTimeMillis()
        val previous = lastRequestMs[turnId]
        if (previous != null && now - previous < RETRY_MS) return
        lastRequestMs[turnId] = now
        AppExecutorUtil.getAppExecutorService().execute {
            val loaded = service.turnInfo(listOf(turnId))
            if (loaded.isNotEmpty()) {
                infos.putAll(loaded)
                ApplicationManager.getApplication().invokeLater { refresh() }
            }
        }
    }

    // ── TextAnnotationGutterProvider ─────────────────────────────────────────

    override fun getLineText(line: Int, editor: Editor): String? {
        val o = owner(line) ?: return null
        val ownership = service.peekOwnership(file) ?: return null
        if (!AnnotationText.startsRun(ownership, line)) return null
        requestInfo(o.turnId)
        return AnnotationText.gutterLabel(infos[o.turnId], o)
    }

    override fun getToolTip(line: Int, editor: Editor): String? {
        val o = owner(line) ?: return null
        requestInfo(o.turnId)
        return AnnotationText.tooltipHtml(infos[o.turnId], o)
    }

    override fun getStyle(line: Int, editor: Editor): EditorFontType = EditorFontType.PLAIN

    override fun getColor(line: Int, editor: Editor): ColorKey = EditorColors.ANNOTATIONS_COLOR

    override fun getBgColor(line: Int, editor: Editor): Color? {
        val o = owner(line) ?: return null
        return colors.getOrPut(o.turnId) {
            val hue = AnnotationText.hueFor(o.turnId)
            JBColor(Color.getHSBColor(hue, 0.18f, 0.97f), Color.getHSBColor(hue, 0.30f, 0.28f))
        }
    }

    override fun getPopupActions(line: Int, editor: Editor): List<AnAction> {
        val o = owner(line) ?: return emptyList()
        return listOf(
            object : AnAction("Open Prompt in Conversation History") {
                override fun actionPerformed(e: AnActionEvent) = openHistory(o.turnId)
            },
            object : AnAction("Show Files Changed by This Prompt") {
                override fun actionPerformed(e: AnActionEvent) = showFilesOf(o.turnId, editor)
            },
        )
    }

    override fun gutterClosed() {
        service.removeChangeListener(changeListener)
        editor = null
    }

    // ── EditorGutterAction (click) ───────────────────────────────────────────

    override fun doAction(line: Int) {
        owner(line)?.let { openHistory(it.turnId) }
    }

    override fun getCursor(line: Int): Cursor =
        Cursor.getPredefinedCursor(if (owner(line) != null) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)

    // ── Navigation ───────────────────────────────────────────────────────────

    private fun openHistory(turnId: String) {
        AppExecutorUtil.getAppExecutorService().execute {
            val info = infos[turnId] ?: service.turnInfo(listOf(turnId))[turnId]
            ApplicationManager.getApplication().invokeLater {
                if (info != null) HistoryContextWindow.open(project, info.sessionId(), turnId)
            }
        }
    }

    private fun showFilesOf(turnId: String, editor: Editor) {
        AppExecutorUtil.getAppExecutorService().execute {
            val files = service.filesForTurn(turnId)
            ApplicationManager.getApplication().invokeLater {
                if (files.isEmpty()) return@invokeLater
                val entries = files.entries.toList()
                val step = object : BaseListPopupStep<Map.Entry<String, Int>>(
                    "Files Changed by This Prompt", entries
                ) {
                    override fun getTextFor(value: Map.Entry<String, Int>): String =
                        AnnotationText.fileEntryLabel(displayPath(value.key), value.value)

                    override fun onChosen(selected: Map.Entry<String, Int>, finalChoice: Boolean): PopupStep<*>? {
                        openFile(selected.key)
                        return FINAL_CHOICE
                    }
                }
                JBPopupFactory.getInstance().createListPopup(step).showInBestPositionFor(editor)
            }
        }
    }

    private fun displayPath(path: String): String {
        val base = project.basePath ?: return path
        return path.removePrefix("$base/")
    }

    private fun openFile(path: String) {
        val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        FileEditorManager.getInstance(project).openFile(vf, true)
    }

    companion object {
        private const val RETRY_MS = 5_000L
    }
}
