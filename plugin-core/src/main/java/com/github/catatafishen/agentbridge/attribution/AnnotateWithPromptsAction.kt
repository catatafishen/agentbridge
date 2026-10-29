package com.github.catatafishen.agentbridge.attribution

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil

/**
 * "Annotate with AgentBridge Prompts": toggles a gutter column showing which prompt last wrote each
 * line through an editing tool, like "Annotate with Git Blame" does for commits.
 */
class AnnotateWithPromptsAction : ToggleAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabledAndVisible = editorOf(e)?.let { fileOf(it) != null && e.project != null } == true
    }

    override fun isSelected(e: AnActionEvent): Boolean {
        val editor = editorOf(e) ?: return false
        return editor.gutter.textAnnotations.any { it is PromptAnnotationProvider }
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val editor = editorOf(e) ?: return
        if (!state) {
            close(editor)
            return
        }
        val file = fileOf(editor) ?: return
        val project = e.project ?: return
        if (isSelected(e)) return

        val service = PromptAttributionService.getInstance(project)
        service.ensureStarted()
        AppExecutorUtil.getAppExecutorService().execute {
            // Restoring from the database and loading prompt details must not block the EDT.
            val ownership = service.ownershipFor(file)
            val turnIds = ownership?.let { turnIdsOf(it) }.orEmpty()
            val infos = if (turnIds.isEmpty()) emptyMap() else service.turnInfo(turnIds)
            ApplicationManager.getApplication().invokeLater {
                if (editor.isDisposed || editor.gutter.textAnnotations.any { it is PromptAnnotationProvider }) return@invokeLater
                if (ownership == null || ownership.isEmpty()) {
                    HintManager.getInstance().showInformationHint(
                        editor,
                        "No AgentBridge prompt has edited this file yet (only edits made through editing tools are tracked)"
                    )
                    return@invokeLater
                }
                val provider = PromptAnnotationProvider(project, file, infos)
                provider.attach(editor)
                editor.gutter.registerTextAnnotation(provider, provider)
            }
        }
    }

    private fun close(editor: Editor) {
        val providers = editor.gutter.textAnnotations.filterIsInstance<PromptAnnotationProvider>()
        if (providers.isNotEmpty()) editor.gutter.closeTextAnnotations(providers)
    }

    private fun turnIdsOf(ownership: LineOwnership): Set<String> {
        val ids = LinkedHashSet<String>()
        for (i in 0 until ownership.lineCount) ownership.ownerAt(i)?.let { ids.add(it.turnId) }
        return ids
    }

    private fun editorOf(e: AnActionEvent): Editor? = e.getData(CommonDataKeys.EDITOR)

    private fun fileOf(editor: Editor): VirtualFile? = FileDocumentManager.getInstance().getFile(editor.document)
}
