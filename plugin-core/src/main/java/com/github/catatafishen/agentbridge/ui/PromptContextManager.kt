package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.acp.model.ResourceReference
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.EditorTextField

/**
 * Manages inline context chips in the prompt editor: insertion, collection,
 * clearing, clipboard detection, and building ACP resource references.
 *
 * Extracted from [AgenticCopilotToolWindowContent] to reduce its complexity.
 */
class PromptContextManager(
    private val project: Project,
    private val promptTextArea: EditorTextField,
    private val appendResponse: (String) -> Unit
) {

    companion object {
        /** Unicode Object Replacement Character — placeholder for inline context chips. */
        const val ORC = '\uFFFC'
    }

    init {
        // Dispose orphaned inlays whenever their ORC placeholder character is deleted.
        // This makes backspace over a chip feel like deleting a single character.
        promptTextArea.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                val removed = event.oldFragment
                if (removed.isEmpty() || removed.chars().noneMatch { it == ORC.code }) return
                val editor = promptTextArea.editor ?: return
                val inlays = editor.inlayModel
                    .getInlineElementsInRange(0, editor.document.textLength, ContextChipRenderer::class.java)
                val orcOffsets = mutableSetOf<Int>()
                val text = editor.document.charsSequence
                for (i in text.indices) {
                    if (text[i] == ORC) orcOffsets.add(i)
                }
                for (inlay in inlays) {
                    if (inlay.offset !in orcOffsets) {
                        com.intellij.openapi.util.Disposer.dispose(inlay)
                    }
                }
            }
        })
    }

    // ── Inline chip CRUD ──────────────────────────────────────────────

    /** Insert a U+FFFC placeholder at the caret and attach an inlay chip for the given context item. */
    fun insertInlineChip(editor: EditorEx, data: ContextItemData) {
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            val offset = editor.caretModel.offset
            editor.document.insertString(offset, ORC.toString())
            editor.caretModel.moveToOffset(offset + 1)
        }
        val inlayOffset = editor.caretModel.offset - 1
        editor.inlayModel.addInlineElement(inlayOffset, true, ContextChipRenderer(data))
    }

    /** Collect all context items from active inline inlays in the prompt editor. */
    fun collectInlineContextItems(): List<ContextItemData> {
        val editor = promptTextArea.editor ?: return emptyList()
        val docLength = editor.document.textLength
        return editor.inlayModel
            .getInlineElementsInRange(0, docLength, ContextChipRenderer::class.java)
            .map { it.renderer.contextData }
    }

    /** Remove all inline context chips and their ORC placeholders from the prompt editor. */
    fun clearInlineChips(editor: EditorEx) {
        val inlays = editor.inlayModel
            .getInlineElementsInRange(0, editor.document.textLength, ContextChipRenderer::class.java)
            .toList()
        if (inlays.isEmpty()) return
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            for (inlay in inlays.sortedByDescending { it.offset }) {
                val off = inlay.offset
                if (off < editor.document.textLength && editor.document.charsSequence[off] == ORC) {
                    editor.document.deleteString(off, off + 1)
                }
                com.intellij.openapi.util.Disposer.dispose(inlay)
            }
        }
    }

    /**
     * Replace each ORC in [rawText] with a backtick-wrapped text reference
     * from the corresponding context item, e.g. `` `AuthLoginService.kt:116-170` ``.
     */
    fun replaceOrcsWithTextRefs(rawText: String, items: List<ContextItemData>): String {
        if (items.isEmpty()) return rawText.replace(ORC.toString(), "").trim()
        val sb = StringBuilder()
        var idx = 0
        for (ch in rawText) {
            if (ch == ORC && idx < items.size) {
                sb.append('`').append(items[idx++].name).append('`')
            } else {
                sb.append(ch)
            }
        }
        return sb.toString().trim()
    }

    // ── Clipboard detection ───────────────────────────────────────────

    fun getClipboardText(): String? {
        return try {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
        } catch (_: Exception) {
            null
        }
    }

    fun findClipboardSourceInProject(clipText: String): ContextItemData? {
        val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        val selectedEditor = fileEditorManager.selectedTextEditor ?: return null
        val selectedFile = fileEditorManager.selectedFiles.firstOrNull() ?: return null

        val isInContent = ApplicationManager.getApplication().runReadAction<Boolean> {
            com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).isInContent(selectedFile)
        }
        if (!isInContent) return null

        return matchEditorSelection(selectedEditor, selectedFile, clipText)
    }

    /**
     * Matches clipboard text against the editor's current selection using
     * whitespace-normalized comparison. Tabs and spaces are normalized so
     * that partial-indentation mismatches on the first line don't prevent detection.
     */
    private fun matchEditorSelection(
        editor: com.intellij.openapi.editor.Editor,
        file: com.intellij.openapi.vfs.VirtualFile,
        clipText: String
    ): ContextItemData? {
        val selModel = editor.selectionModel
        if (!selModel.hasSelection()) return null

        val selectedText = selModel.selectedText ?: return null
        if (!normalizedEquals(selectedText, clipText, editor.settings.getTabSize(project))) return null

        val doc = editor.document
        val startLine = doc.getLineNumber(selModel.selectionStart) + 1
        val endLine = doc.getLineNumber(selModel.selectionEnd) + 1
        return ContextItemData(
            path = file.path,
            name = "${file.name}:$startLine-$endLine",
            startLine = startLine,
            endLine = endLine,
            fileTypeName = file.fileType.name,
            isSelection = true
        )
    }

    /**
     * Compare two text snippets after normalizing tabs to spaces and
     * stripping trailing whitespace per line, so minor indentation
     * mismatches (partial first-line selection, mixed tabs/spaces) still match.
     */
    private fun normalizedEquals(a: String, b: String, tabSize: Int): Boolean {
        if (a == b) return true
        val spaces = " ".repeat(tabSize.coerceAtLeast(1))
        val normA = a.replace("\t", spaces).lines().joinToString("\n") { it.trimEnd() }
        val normB = b.replace("\t", spaces).lines().joinToString("\n") { it.trimEnd() }
        return normA == normB
    }

    // ── File attachment actions ────────────────────────────────────────

    fun handleAddCurrentFile() {
        val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        val currentFile = fileEditorManager.selectedFiles.firstOrNull()

        if (currentFile == null) {
            Messages.showWarningDialog(project, "No file is currently open in the editor", "No File")
            return
        }

        val path = currentFile.path
        val lineCount = try {
            fileEditorManager.selectedTextEditor?.document?.lineCount ?: 0
        } catch (_: Exception) {
            0
        }

        if (collectInlineContextItems().any { it.path == path }) {
            Messages.showInfoMessage(project, "File already in context: ${currentFile.name}", "Duplicate File")
            return
        }

        val promptEditor = promptTextArea.editor as? EditorEx ?: return
        insertInlineChip(
            promptEditor,
            ContextItemData(
                path = path, name = currentFile.name, startLine = 1, endLine = lineCount,
                fileTypeName = currentFile.fileType.name, isSelection = false
            )
        )
    }

    fun handleAddSelection() {
        val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        val editor = fileEditorManager.selectedTextEditor
        val currentFile = fileEditorManager.selectedFiles.firstOrNull()

        if (editor == null || currentFile == null) {
            Messages.showWarningDialog(project, "No editor is currently open", "No Editor")
            return
        }

        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) {
            Messages.showWarningDialog(project, "No text is selected. Select some code first.", "No Selection")
            return
        }

        val document = editor.document
        val startLine = document.getLineNumber(selectionModel.selectionStart) + 1
        val endLine = document.getLineNumber(selectionModel.selectionEnd) + 1

        val promptEditor = promptTextArea.editor as? EditorEx ?: return
        insertInlineChip(
            promptEditor,
            ContextItemData(
                path = currentFile.path, name = "${currentFile.name}:$startLine-$endLine",
                startLine = startLine, endLine = endLine,
                fileTypeName = currentFile.fileType.name, isSelection = true
            )
        )
    }

    fun openFileSearchPopup() {
        val model = com.intellij.ide.util.gotoByName.GotoFileModel(project)
        val popup = com.intellij.ide.util.gotoByName.ChooseByNamePopup.createPopup(
            project,
            model,
            null as com.intellij.psi.PsiElement?
        )
        popup.invoke(object : com.intellij.ide.util.gotoByName.ChooseByNamePopupComponent.Callback() {
            override fun elementChosen(element: Any?) {
                val psiFile = element as? com.intellij.psi.PsiFile ?: return
                val vf = psiFile.virtualFile ?: return
                val path = vf.path
                if (collectInlineContextItems().any { it.path == path }) return

                val lineCount = try {
                    com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)?.lineCount ?: 0
                } catch (_: Exception) {
                    0
                }

                val promptEditor = promptTextArea.editor as? EditorEx ?: return
                insertInlineChip(
                    promptEditor,
                    ContextItemData(
                        path = path, name = vf.name, startLine = 1, endLine = lineCount,
                        fileTypeName = vf.fileType.name, isSelection = false
                    )
                )
            }
        }, com.intellij.openapi.application.ModalityState.current(), false)
    }

    // ── Reference building ────────────────────────────────────────────

    fun buildContextReferences(items: List<ContextItemData>? = null): List<ResourceReference> {
        val contextItems = items ?: collectInlineContextItems()
        val references = mutableListOf<ResourceReference>()
        for (item in contextItems) {
            try {
                val ref = buildSingleReference(item)
                if (ref != null) references.add(ref)
            } catch (_: Exception) {
                appendResponse("\u26a0 Could not read context: ${item.name}\n")
            }
        }
        return references
    }

    private fun buildSingleReference(item: ContextItemData): ResourceReference? {
        val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(item.path)
            ?: return null
        var doc: com.intellij.openapi.editor.Document? = null
        ApplicationManager.getApplication().runReadAction {
            doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)
        }
        val document = doc ?: return null

        var text = ""
        ApplicationManager.getApplication().runReadAction {
            if (item.isSelection && item.startLine > 0) {
                val startOffset = document.getLineStartOffset((item.startLine - 1).coerceIn(0, document.lineCount - 1))
                val endOffset = document.getLineEndOffset((item.endLine - 1).coerceIn(0, document.lineCount - 1))
                val snippet = document.getText(com.intellij.openapi.util.TextRange(startOffset, endOffset))
                val fileName = item.path.substringAfterLast("/")
                text = "// Selected lines ${item.startLine}-${item.endLine} of $fileName\n$snippet"
            } else {
                text = document.text
            }
        }

        val uri = buildString {
            append("file://")
            append(item.path.replace("\\", "/"))
            if (item.isSelection && item.startLine > 0) {
                append("#L${item.startLine}-L${item.endLine}")
            }
        }
        val mimeType = getMimeTypeForFileType(file.fileType.name.lowercase())
        return ResourceReference(uri, mimeType, text)
    }

    private fun getMimeTypeForFileType(fileTypeName: String): String {
        return when (fileTypeName) {
            "java" -> "text/x-java"
            "kotlin" -> "text/x-kotlin"
            "python" -> "text/x-python"
            "javascript" -> "text/javascript"
            "typescript" -> "text/typescript"
            "xml", "html" -> "text/$fileTypeName"
            else -> "text/plain"
        }
    }
}
