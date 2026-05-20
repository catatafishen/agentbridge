package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.services.ActiveAgentManager
import com.github.catatafishen.agentbridge.services.AgentNudgeService
import com.github.catatafishen.agentbridge.client.acp.KiroClient
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.EditorTextField
import java.awt.*
import javax.swing.*

/**
 * Encapsulates all prompt editor wiring: key bindings, paste interception,
 * drag-and-drop handling, trigger-character detection, context menu, and
 * slash-command autocomplete. Constructed once per chat session when the
 * editor is created, and delegates user actions back to the host via [Callbacks].
 */
internal class PromptEditorSetup(
    private val project: Project,
    private val promptTextArea: EditorTextField,
    private val contextManager: PromptContextManager,
    private val pasteToScratchHandler: PasteToScratchHandler,
    private val pasteAttachmentHandler: PasteAttachmentHandler,
    private val agentManager: ActiveAgentManager,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onSendOrStop()
        fun onNudge()
        fun onQueue()
        fun onForceStopAndSend()
        fun onNewConversation()
        fun clearAndRemoveNudge(id: String)
        fun refreshShortcutHints()
        val isSending: Boolean
        val activeBubbleId: String?
        val queuedTexts: ArrayDeque<String>
        val consolePanel: ChatPanelApi
        val authPendingError: Any?
    }

    private var autocompletePopup: com.intellij.openapi.ui.popup.JBPopup? = null

    fun setupKeyBindings(editor: EditorEx) {
        val contentComponent = editor.contentComponent
        registerEnterSend(contentComponent)
        registerShiftEnterNewLine(editor, contentComponent)
        registerCtrlEnterNudge(contentComponent)
        registerCtrlShiftEnterQueue(contentComponent)
        registerShowShortcutsPopup(contentComponent)
        registerUpArrowRecall(contentComponent)
        registerPasteIntercept(editor, contentComponent)
        registerTriggerCharDetection(editor)
    }

    fun setupContextMenu(editor: EditorEx) {
        val group = DefaultActionGroup().apply {
            val editorPopup = ActionManager.getInstance().getAction("EditorPopupMenu")
            if (editorPopup != null) {
                add(editorPopup)
            }

            addSeparator()

            add(object : AnAction("Attach Current File", null, AllIcons.Actions.AddFile) {
                override fun actionPerformed(e: AnActionEvent) = contextManager.handleAddCurrentFile()
            })
            add(object : AnAction("Attach Editor Selection", null, AllIcons.Actions.AddMulticaret) {
                override fun actionPerformed(e: AnActionEvent) = contextManager.handleAddSelection()
            })
            add(object : AnAction("Clear Attachments", null, AllIcons.Actions.GC) {
                override fun actionPerformed(e: AnActionEvent) {
                    contextManager.clearInlineChips(editor)
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = contextManager.collectInlineContextItems().isNotEmpty()
                }
            })

            addSeparator()

            add(object : AnAction("New Conversation", null, AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) {
                    callbacks.onNewConversation()
                }
            })
        }

        editor.installPopupHandler(
            com.intellij.openapi.editor.impl.ContextMenuPopupHandler.Simple(group)
        )
    }

    fun setupDragDrop(editor: EditorEx) {
        editor.contentComponent.dropTarget = java.awt.dnd.DropTarget(
            editor.contentComponent, java.awt.dnd.DnDConstants.ACTION_COPY,
            object : java.awt.dnd.DropTargetAdapter() {
                override fun dragEnter(dtde: java.awt.dnd.DropTargetDragEvent) {
                    dtde.acceptDrag(java.awt.dnd.DnDConstants.ACTION_COPY)
                }

                override fun dragOver(dtde: java.awt.dnd.DropTargetDragEvent) {
                    dtde.acceptDrag(java.awt.dnd.DnDConstants.ACTION_COPY)
                }

                override fun drop(dtde: java.awt.dnd.DropTargetDropEvent) {
                    handleDrop(dtde, editor)
                }
            })
    }

    fun checkSlashCommandAutocomplete() {
        val client = agentManager.getClient()
        if (client !is KiroClient) {
            autocompletePopup?.cancel()
            return
        }

        val text = promptTextArea.text
        if (!text.startsWith("/") || text.contains("\n")) {
            autocompletePopup?.cancel()
            return
        }

        val commands = client.availableCommands
        if (commands.size() == 0) return

        val matches = mutableListOf<String>()
        for (i in 0 until commands.size()) {
            val cmdObj = commands[i].asJsonObject
            val cmd = cmdObj["name"]?.asString ?: continue
            if (cmd.startsWith(text, ignoreCase = true)) {
                matches.add(cmd)
            }
        }

        if (matches.isEmpty()) {
            autocompletePopup?.cancel()
            return
        }

        showAutocompletePopup(matches)
    }

    private fun showAutocompletePopup(commands: List<String>) {
        autocompletePopup?.cancel()

        autocompletePopup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(commands)
            .setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            .setItemChosenCallback { selected -> promptTextArea.text = selected.toString() }
            .createPopup()

        autocompletePopup?.showInBestPositionFor(promptTextArea.editor ?: return)
    }

    private fun registerEnterSend(contentComponent: JComponent) {
        object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                if (promptTextArea.text.isBlank() || callbacks.authPendingError != null) return
                when {
                    callbacks.consolePanel.hasPendingAskUserRequest() -> callbacks.onSendOrStop()
                    callbacks.isSending -> callbacks.onNudge()
                    else -> callbacks.onSendOrStop()
                }
            }
        }.registerCustomShortcutSet(
            PromptShortcutAction.resolveShortcutSet(
                PromptShortcutAction.SEND_ID,
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0)
            ),
            contentComponent
        )
    }

    private fun registerShiftEnterNewLine(editor: EditorEx, contentComponent: JComponent) {
        object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                val offset = editor.caretModel.offset
                com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                    editor.document.insertString(offset, "\n")
                }
                editor.caretModel.moveToOffset(offset + 1)
            }
        }.registerCustomShortcutSet(
            PromptShortcutAction.resolveShortcutSet(
                PromptShortcutAction.NEW_LINE_ID,
                KeyStroke.getKeyStroke(
                    java.awt.event.KeyEvent.VK_ENTER,
                    java.awt.event.InputEvent.SHIFT_DOWN_MASK
                )
            ),
            contentComponent
        )
    }

    private fun registerCtrlEnterNudge(contentComponent: JComponent) {
        object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) = callbacks.onForceStopAndSend()
        }.registerCustomShortcutSet(
            PromptShortcutAction.resolveShortcutSet(
                PromptShortcutAction.STOP_AND_SEND_ID,
                KeyStroke.getKeyStroke(
                    java.awt.event.KeyEvent.VK_ENTER,
                    java.awt.event.InputEvent.CTRL_DOWN_MASK
                )
            ),
            contentComponent
        )
    }

    private fun registerCtrlShiftEnterQueue(contentComponent: JComponent) {
        object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) = callbacks.onQueue()
        }.registerCustomShortcutSet(
            PromptShortcutAction.resolveShortcutSet(
                PromptShortcutAction.QUEUE_ID,
                KeyStroke.getKeyStroke(
                    java.awt.event.KeyEvent.VK_ENTER,
                    java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK
                )
            ),
            contentComponent
        )
    }

    private fun registerShowShortcutsPopup(contentComponent: JComponent) {
        object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) = ShortcutCheatSheetPopup.show(promptTextArea)
        }.registerCustomShortcutSet(
            PromptShortcutAction.resolveShortcutSet(
                PromptShortcutAction.SHOW_SHORTCUTS_ID,
                KeyStroke.getKeyStroke(
                    java.awt.event.KeyEvent.VK_SLASH,
                    java.awt.event.InputEvent.CTRL_DOWN_MASK
                )
            ),
            contentComponent
        )
    }

    /**
     * Up-arrow recall: when the prompt is empty and a nudge or queued message
     * is pending, pop the most recent one back into the input box for editing.
     */
    private fun registerUpArrowRecall(contentComponent: JComponent) {
        object : AnAction() {
            override fun getActionUpdateThread(): ActionUpdateThread =
                ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                val empty = promptTextArea.text.isEmpty()
                val hasPending = callbacks.activeBubbleId != null || callbacks.queuedTexts.isNotEmpty()
                e.presentation.isEnabledAndVisible = empty && hasPending
            }

            override fun actionPerformed(e: AnActionEvent) {
                if (promptTextArea.text.isNotEmpty()) return
                val nudgeId = callbacks.activeBubbleId
                if (nudgeId != null) {
                    val nudgeText = AgentNudgeService.getInstance(project).getPendingNudgesText()
                    if (!nudgeText.isNullOrEmpty()) promptTextArea.text = nudgeText
                    callbacks.clearAndRemoveNudge(nudgeId)
                    callbacks.refreshShortcutHints()
                    return
                }
                val lastQueued = callbacks.queuedTexts.removeLastOrNull() ?: return
                promptTextArea.text = lastQueued
                val nudgeService = AgentNudgeService.getInstance(project)
                nudgeService.removeQueuedMessage(lastQueued)
                ApplicationManager.getApplication().invokeLater {
                    callbacks.consolePanel.removeQueuedMessageByText(lastQueued)
                    callbacks.refreshShortcutHints()
                }
            }
        }.registerCustomShortcutSet(
            CustomShortcutSet(
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_UP, 0)
            ),
            contentComponent
        )
    }

    private fun registerPasteIntercept(editor: EditorEx, contentComponent: JComponent) {
        val pasteStrokes = setOf(
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V, java.awt.event.InputEvent.CTRL_DOWN_MASK),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V, java.awt.event.InputEvent.META_DOWN_MASK),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_INSERT, java.awt.event.InputEvent.SHIFT_DOWN_MASK)
        )
        com.intellij.ide.IdeEventQueue.getInstance().addPreprocessor(
            { event ->
                handlePastePreprocess(event, editor, contentComponent, pasteStrokes)
            },
            project
        )
    }

    private fun registerTriggerCharDetection(editor: EditorEx) {
        editor.document.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                val trigger = ActiveAgentManager.getAttachTriggerChar()
                if (trigger.isEmpty()) return
                val inserted = event.newFragment.toString()
                if (inserted != trigger) return

                val offset = event.offset
                val text = editor.document.text
                val isAtStart = offset == 0
                val isAfterSpace = offset > 0 && text[offset - 1] == ' '
                if (!isAtStart && !isAfterSpace) return

                ApplicationManager.getApplication().invokeLater {
                    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                        val doc = editor.document
                        val end = offset + trigger.length
                        if (end <= doc.textLength && doc.getText(
                                com.intellij.openapi.util.TextRange(offset, end)
                            ) == trigger
                        ) {
                            doc.deleteString(offset, end)
                        }
                    }
                    contextManager.openFileSearchPopup()
                }
            }
        }, project)
    }

    private fun handlePastePreprocess(
        event: java.util.EventObject,
        editor: EditorEx,
        contentComponent: JComponent,
        pasteStrokes: Set<KeyStroke>
    ): Boolean {
        if (event !is java.awt.event.KeyEvent) return false
        if (editor.isDisposed) return false
        if (event.id != java.awt.event.KeyEvent.KEY_PRESSED) return false
        if (KeyStroke.getKeyStrokeForEvent(event) !in pasteStrokes) return false
        val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        if (!SwingUtilities.isDescendingFrom(focused, contentComponent)) return false

        if (handleImageOrFilePaste(event)) return true

        val chatInputSettings = com.github.catatafishen.agentbridge.settings.ChatInputSettings.getInstance()
        if (!chatInputSettings.isSmartPasteEnabled) return false

        val clipText = contextManager.getClipboardText()
        val minLines = chatInputSettings.smartPasteMinLines
        val minChars = chatInputSettings.smartPasteMinChars
        if (clipText == null || (clipText.lines().size <= minLines && clipText.length <= minChars)) return false

        val projectSource = contextManager.findClipboardSourceInProject(clipText)
        event.consume()
        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed) return@invokeLater
            if (projectSource != null) {
                contextManager.insertInlineChip(editor, projectSource)
            } else {
                pasteToScratchHandler.handlePasteToScratch(clipText)
            }
        }
        return true
    }

    private fun handleImageOrFilePaste(event: java.awt.event.KeyEvent): Boolean {
        val clipboard = try {
            Toolkit.getDefaultToolkit().systemClipboard
        } catch (_: Exception) {
            return false
        }
        val contents = try {
            clipboard.getContents(null) ?: return false
        } catch (_: IllegalStateException) {
            return false
        }

        if (contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor)) {
            val image = try {
                contents.getTransferData(java.awt.datatransfer.DataFlavor.imageFlavor) as? Image
            } catch (_: Exception) {
                null
            }
            if (image != null) {
                event.consume()
                ApplicationManager.getApplication().executeOnPooledThread {
                    pasteAttachmentHandler.handleImagePaste(image)
                }
                return true
            }
        }

        if (contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
            @Suppress("UNCHECKED_CAST")
            val files = try {
                contents.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor)
                    as? List<java.io.File>
            } catch (_: Exception) {
                null
            }
            if (!files.isNullOrEmpty()) {
                event.consume()
                ApplicationManager.getApplication().executeOnPooledThread {
                    pasteAttachmentHandler.handleFilePaste(files)
                }
                return true
            }
        }

        return false
    }

    private fun handleDrop(dtde: java.awt.dnd.DropTargetDropEvent, editor: EditorEx) {
        try {
            dtde.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY)
            val transferable = dtde.transferable

            if (transferable.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                @Suppress("UNCHECKED_CAST")
                val files = transferable.getTransferData(
                    java.awt.datatransfer.DataFlavor.javaFileListFlavor
                ) as List<java.io.File>
                for (file in files) {
                    val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                        .findFileByIoFile(file) ?: continue
                    val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                        .getDocument(vf) ?: continue
                    val existing = contextManager.collectInlineContextItems().any { it.path == vf.path }
                    if (!existing) {
                        val data = ContextItemData(
                            path = vf.path, name = vf.name,
                            startLine = 1, endLine = doc.lineCount,
                            fileTypeName = vf.fileType.name, isSelection = false
                        )
                        contextManager.insertInlineChip(editor, data)
                    }
                }
                dtde.dropComplete(true)
                return
            }

            if (transferable.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                val text = transferable.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
                if (!text.isNullOrBlank()) {
                    handleTextDrop(text, editor)
                    dtde.dropComplete(true)
                    return
                }
            }

            dtde.dropComplete(false)
        } catch (_: Exception) {
            dtde.dropComplete(false)
        }
    }

    private fun handleTextDrop(text: String, editor: EditorEx) {
        val chatInputSettings = com.github.catatafishen.agentbridge.settings.ChatInputSettings.getInstance()
        val minLines = chatInputSettings.smartPasteMinLines
        val minChars = chatInputSettings.smartPasteMinChars

        if (text.lines().size <= minLines && text.length <= minChars) {
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                val offset = editor.caretModel.offset
                editor.document.insertString(offset, text)
                editor.caretModel.moveToOffset(offset + text.length)
            }
            return
        }

        val projectSource = contextManager.findTextSourceInOpenEditors(text)
        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed) return@invokeLater
            if (projectSource != null) {
                contextManager.insertInlineChip(editor, projectSource)
            } else {
                pasteToScratchHandler.handlePasteToScratch(text)
            }
        }
    }
}
