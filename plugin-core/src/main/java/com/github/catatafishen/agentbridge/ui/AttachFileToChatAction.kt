package com.github.catatafishen.agentbridge.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages

/**
 * Editor context-menu action that attaches the whole right-clicked file to
 * the chat prompt as an inline context chip — the editor-menu counterpart of
 * the "Attach Current File" entry in the chat input's attach popup.
 *
 * Uses [CommonDataKeys.VIRTUAL_FILE] from the action context (the file the
 * menu was invoked on), so it works in split editors regardless of which
 * tab is currently selected.
 *
 * Note: [PromptEditorSetup] embeds the whole EditorPopupMenu inside the chat
 * prompt's own context menu; there [CommonDataKeys.VIRTUAL_FILE] is null, so
 * update() hides this action there — do not remove that guard.
 */
class AttachFileToChatAction : AnAction(
    "Add File",
    "Attach the whole file to the AgentBridge chat prompt as a context chip",
    AllIcons.Actions.AddFile
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val enabled = e.project != null && file != null && file.isInLocalFileSystem
        e.presentation.isEnabledAndVisible = enabled
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        if (ChatToolWindowContent.getInstance(project)?.hasContextChipFor(file.path) == true) {
            Messages.showInfoMessage(project, "File already in context: ${file.name}", "Duplicate File")
            return
        }

        val lineCount = com.intellij.openapi.application.ApplicationManager.getApplication()
            .runReadAction<Int> {
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)?.lineCount ?: 0
            }

        val data = ContextItemData(
            path = file.path,
            name = file.name,
            startLine = 1,
            endLine = lineCount,
            fileTypeName = file.fileType.name,
            isSelection = false,
            attachmentKind = AttachmentKind.forFile(file)
        )

        ChatAttachHelper.insertChip(project, data)
    }
}
