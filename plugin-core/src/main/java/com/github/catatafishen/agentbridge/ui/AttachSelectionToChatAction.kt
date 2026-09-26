package com.github.catatafishen.agentbridge.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages

/**
 * Editor context-menu action that attaches the current selection to the chat
 * prompt as an inline context chip — the same mechanism as the "Editor
 * Selection" entry in the chat input's attach popup and the "Attach Editor
 * Selection" item in the prompt editor's context menu.
 *
 * Unlike [PromptContextManager.handleAddSelection] (which operates on the
 * currently selected editor tab), this action uses the editor that was
 * right-clicked, so it works correctly in split editors.
 *
 * Note: [PromptEditorSetup] embeds the whole EditorPopupMenu inside the chat
 * prompt's own context menu; there [CommonDataKeys.VIRTUAL_FILE] is null, so
 * update() hides this action there — do not remove that guard.
 */
class AttachSelectionToChatAction : AnAction(
    "Add Selection",
    "Attach the selected code to the AgentBridge chat prompt as a context chip",
    AllIcons.Actions.AddMulticaret
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val enabled = e.project != null
            && editor != null
            && file != null
            && file.isInLocalFileSystem
            && editor.selectionModel.hasSelection()
        e.presentation.isEnabledAndVisible = enabled
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) return

        val document = editor.document
        val startLine = document.getLineNumber(selectionModel.selectionStart) + 1
        val endLine = document.getLineNumber(selectionModel.selectionEnd) + 1

        if (ChatToolWindowContent.getInstance(project)?.hasContextChipFor(file.path, startLine, endLine) == true) {
            Messages.showInfoMessage(project, "Selection already in context: ${file.name}:$startLine-$endLine", "Duplicate Selection")
            return
        }

        val data = ContextItemData(
            path = file.path,
            name = "${file.name}:$startLine-$endLine",
            startLine = startLine,
            endLine = endLine,
            fileTypeName = file.fileType.name,
            isSelection = true,
            attachmentKind = AttachmentKind.forFile(file)
        )

        ChatAttachHelper.insertChip(project, data)
    }
}
