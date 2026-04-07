package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat
import com.github.catatafishen.agentbridge.settings.ScratchTypeSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.EditorTextField
import javax.swing.KeyStroke

/**
 * Handles paste-to-scratch and new-scratch-file features in the chat prompt toolbar.
 * Owns the language popup, keyboard intercept, and scratch file creation logic.
 */
internal class PasteToScratchHandler(
    private val project: Project,
    private val promptTextArea: EditorTextField,
    private val contextManager: PromptContextManager,
) {
    private val log = Logger.getInstance(PasteToScratchHandler::class.java)

    @Volatile
    private var suppressNextPaste = false

    fun handlePasteToScratch(text: String) {
        val settings = ScratchTypeSettings.getInstance()
        val enabledLanguages = settings.enabledLanguages
        if (enabledLanguages.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "No languages are enabled. Configure them in Settings → Tools → Scratch File Types.",
                "No Scratch Languages"
            )
            return
        }
        val detected = PlatformApiCompat.detectLanguageFromContent(text)
        val detectedMatch = if (detected != null) enabledLanguages.find { it.id == detected.id } else null
        val plainText = enabledLanguages.find { it.id == PlainTextLanguage.INSTANCE.id }
        val orderedLanguages = buildList {
            if (detectedMatch != null) add(detectedMatch)
            if (plainText != null && plainText != detectedMatch) add(plainText)
            for (lang in enabledLanguages) {
                if (lang != detectedMatch && lang != plainText) add(lang)
            }
        }
        val popup = com.intellij.ide.scratch.LRUPopupBuilder
            .languagePopupBuilder(project, "Paste as Scratch File (Paste Again to Skip)") { lang ->
                lang.associatedFileType?.icon ?: AllIcons.FileTypes.Any_type
            }
            .forValues(orderedLanguages)
            .onChosen { lang ->
                val ext = lang.associatedFileType?.defaultExtension ?: return@onChosen
                createAndAttachScratch(ext, text)
            }
            .buildPopup()
        registerPasteToSkip(popup, text)
        popup.showCenteredInCurrentWindow(project)
    }

    fun handleCreateScratch() {
        val settings = ScratchTypeSettings.getInstance()
        val enabledLanguages = settings.enabledLanguages
        if (enabledLanguages.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "No languages are enabled. Configure them in Settings → Tools → Scratch File Types.",
                "No Scratch Languages"
            )
            return
        }
        com.intellij.ide.scratch.LRUPopupBuilder
            .languagePopupBuilder(project, "New Scratch File") { lang ->
                lang.associatedFileType?.icon ?: AllIcons.FileTypes.Any_type
            }
            .forValues(enabledLanguages)
            .onChosen { lang ->
                val ext = lang.associatedFileType?.defaultExtension ?: return@onChosen
                createAndAttachScratch(ext)
            }
            .buildPopup()
            .showCenteredInCurrentWindow(project)
    }

    private fun registerPasteToSkip(popup: com.intellij.openapi.ui.popup.JBPopup, text: String) {
        val pasteStrokes = setOf(
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V, java.awt.event.InputEvent.CTRL_DOWN_MASK),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V, java.awt.event.InputEvent.META_DOWN_MASK),
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_INSERT, java.awt.event.InputEvent.SHIFT_DOWN_MASK)
        )
        var swallowFollowUp = false
        var pasteIntercepted = false
        val disposable = com.intellij.openapi.util.Disposer.newDisposable("pasteToSkip")
        com.intellij.ide.IdeEventQueue.getInstance().addPreprocessor(
            com.intellij.ide.IdeEventQueue.EventDispatcher { event ->
                if (event !is java.awt.event.KeyEvent) return@EventDispatcher false
                if (swallowFollowUp) {
                    return@EventDispatcher handleFollowUpEvent(event, disposable) { swallowFollowUp = false }
                }
                if (!popup.isVisible) return@EventDispatcher false
                if (event.id != java.awt.event.KeyEvent.KEY_PRESSED) return@EventDispatcher false
                if (KeyStroke.getKeyStrokeForEvent(event) !in pasteStrokes) return@EventDispatcher false
                event.consume()
                swallowFollowUp = true
                pasteIntercepted = true
                executePasteFromPopup(popup, text)
                true
            },
            disposable
        )
        popup.addListener(object : com.intellij.openapi.ui.popup.JBPopupListener {
            override fun onClosed(event: com.intellij.openapi.ui.popup.LightweightWindowEvent) {
                if (!pasteIntercepted) com.intellij.openapi.util.Disposer.dispose(disposable)
            }
        })
    }

    /**
     * Handles KEY_TYPED and KEY_RELEASED follow-up events after a paste stroke was consumed.
     * Returns true to swallow the event. Self-disposes on KEY_RELEASED via [clearSwallow].
     */
    private fun handleFollowUpEvent(
        event: java.awt.event.KeyEvent,
        disposable: com.intellij.openapi.Disposable,
        clearSwallow: () -> Unit,
    ): Boolean {
        if (event.id == java.awt.event.KeyEvent.KEY_RELEASED) {
            clearSwallow()
            ApplicationManager.getApplication().invokeLater {
                com.intellij.openapi.util.Disposer.dispose(disposable)
            }
        }
        return true
    }

    private fun executePasteFromPopup(popup: com.intellij.openapi.ui.popup.JBPopup, text: String) {
        popup.cancel()
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            val editor = promptTextArea.editor ?: return@runWriteCommandAction
            val offset = editor.caretModel.offset
            editor.document.insertString(offset, text)
            editor.caretModel.moveToOffset(offset + text.length)
        }
        // Return focus to prompt so user can keep typing
        ApplicationManager.getApplication().invokeLater {
            promptTextArea.editor?.contentComponent?.requestFocusInWindow()
        }
    }

    private fun createAndAttachScratch(ext: String, initialContent: String? = null) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val scratchService = com.intellij.ide.scratch.ScratchFileService.getInstance()
                val scratchRoot = com.intellij.ide.scratch.ScratchRootType.getInstance()
                val name = "scratch.$ext"

                @Suppress("RedundantCast") // Explicit Computable needed: runWriteAction is overloaded
                val file = ApplicationManager.getApplication().runWriteAction(
                    com.intellij.openapi.util.Computable<com.intellij.openapi.vfs.VirtualFile?> {
                        try {
                            val vf = scratchService.findFile(
                                scratchRoot, name,
                                com.intellij.ide.scratch.ScratchFileService.Option.create_new_always
                            )
                            if (vf != null && !initialContent.isNullOrEmpty()) {
                                vf.setBinaryContent(initialContent.toByteArray(Charsets.UTF_8))
                            }
                            vf
                        } catch (e: java.io.IOException) {
                            log.warn("Failed to create scratch file", e)
                            null
                        }
                    }
                )
                if (file != null) {
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(file, true)
                    contextManager.addContextItem(
                        ContextItemData(
                            path = file.path, name = file.name,
                            startLine = 1, endLine = 0,
                            fileTypeName = file.fileType.name, isSelection = false
                        )
                    )
                    // openFile(focus=true) steals focus; schedule a second invokeLater so
                    // this runs after the file editor has finished grabbing focus.
                    ApplicationManager.getApplication().invokeLater {
                        promptTextArea.editor?.contentComponent?.requestFocusInWindow()
                    }
                }
            } catch (e: Exception) {
                log.warn("Failed to create scratch file from attach menu", e)
            }
        }
    }
}
