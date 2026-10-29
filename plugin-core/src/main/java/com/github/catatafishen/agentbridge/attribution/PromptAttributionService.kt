package com.github.catatafishen.agentbridge.attribution

import com.github.catatafishen.agentbridge.session.db.ConversationDatabase
import com.github.catatafishen.agentbridge.session.db.LineAttributionStore
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Remembers which prompt (turn) last wrote which lines of each file through an editing tool call, so
 * the editor can show it next to the code, like git blame does for commits.
 *
 * Capture is exact because edit tools run inside the IDE:
 *  * while a turn is active, [beginTurn] sets the owner; document changes made inside an
 *    agent edit ([agentEditStarted]..[agentEditFinished]) are attributed to that turn,
 *  * every other change (user typing, formatters, refactorings) only shifts or splits existing ownership,
 *  * ownership is persisted per file ([LineAttributionStore]) together with a hash of the file, and
 *    re-anchored by content when the file changed while the IDE was not watching.
 *
 * Scope: edits made through IDE tool calls that bracket their change with [agentEditStarted] (the editing
 * tools, but also quick-fix/action tools and file moves) are attributed live. Backfill from recorded history
 * only replays the explicit editing tools, and its owners are flagged inferred. Changes made by shell
 * commands show up as unattributed.
 */
@Service(Service.Level.PROJECT)
class PromptAttributionService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(PromptAttributionService::class.java)

    private val activeTurn = AtomicReference<LineOwner?>()
    private val models = ConcurrentHashMap<String, LineOwnership>()
    private val dirty: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Files that have, or may have, stored attribution. Loaded in the background at start. */
    private val attributedPaths: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val listeners = CopyOnWriteArrayList<Runnable>()

    @Volatile
    private var started = false

    private val flushTask = Runnable { flushDirty() }

    fun ensureStarted() {
        if (started || project.isDisposed) return
        synchronized(this) {
            if (started) return
            started = true
            EditorFactory.getInstance().eventMulticaster.addDocumentListener(DocListener(), this)
            AppExecutorUtil.getAppExecutorService().execute {
                store()?.pathsWithAttribution()?.let { attributedPaths.addAll(it) }
            }
            AppExecutorUtil.getAppScheduledExecutorService()
                .scheduleWithFixedDelay(flushTask, FLUSH_INTERVAL_SEC, FLUSH_INTERVAL_SEC, TimeUnit.SECONDS)
        }
    }

    // ── Turn lifecycle ────────────────────────────────────────────────────────

    /** Called when a prompt starts; edits by tools from now on belong to [turnId]. */
    fun beginTurn(turnId: String) {
        ensureStarted()
        activeTurn.set(if (turnId.isNotEmpty()) LineOwner(turnId, System.currentTimeMillis()) else null)
    }

    /** Called when the prompt ends. Pending attribution is written so the turn row exists by then. */
    fun endTurn() {
        activeTurn.set(null)
        val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
        scheduler.execute(flushTask)
        // The turn row may be saved slightly after the turn ends; retry once so nothing waits for the periodic flush.
        scheduler.schedule(flushTask, TURN_END_RETRY_SEC, TimeUnit.SECONDS)
    }

    val currentTurnId: String? get() = activeTurn.get()?.turnId

    // ── Agent edit bracketing (called by FileTool.notifyBeforeEdit / notifyEditComplete) ──────

    private fun currentOwner(): LineOwner? =
        if (editDepth.get() > 0) activeTurn.get()?.copy(timestampMs = System.currentTimeMillis()) else null

    /** A tool created [file]; every line of it belongs to the active turn. */
    fun fileCreatedByAgent(file: VirtualFile) {
        val owner = activeTurn.get() ?: return
        // Called from a pooled tool thread: Document access needs a read action.
        val doc = ApplicationManager.getApplication()
            .runReadAction<Document?> { FileDocumentManager.getInstance().getDocument(file) } ?: return
        val model = LineOwnership.empty(doc.lineCount.coerceAtLeast(1))
        model.claimAll(owner.copy(kind = LineOwner.KIND_CREATE, timestampMs = System.currentTimeMillis()))
        models[file.path] = model
        attributedPaths.add(file.path)
        markDirty(file.path)
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    /** The ownership of [file], restored from the database on first use; null when nothing is known. */
    fun ownershipFor(file: VirtualFile): LineOwnership? {
        models[file.path]?.let { return it }
        // Called from a pooled thread: Document access needs a read action.
        val doc = ApplicationManager.getApplication()
            .runReadAction<Document?> { FileDocumentManager.getInstance().getDocument(file) } ?: return null
        val known = file.path in attributedPaths || store()?.load(file.path) != null
        val restored = (if (known) restore(file.path, doc) else null) ?: backfill(file, doc) ?: return null
        return models.putIfAbsent(file.path, restored) ?: restored
    }

    /**
     * Best-effort attribution for a file that was edited before live capture existed, rebuilt by replaying
     * the editing tool calls recorded for it. Never overrides live data (only runs when none is stored), and
     * every owner it produces is flagged inferred. Returns null when nothing could be placed.
     */
    private fun backfill(file: VirtualFile, doc: Document): LineOwnership? {
        val store = store() ?: return null
        val base = project.basePath
        val relative = if (base != null) file.path.removePrefix("$base/") else file.path
        val edits = store.editsForFile(relative, file.path).mapNotNull {
            RecordedEditParser.parse(it.turnId(), it.timestampMs(), it.toolName(), it.arguments())
        }
        if (edits.isEmpty()) return null
        val lines = LineOwnership.splitLines(doc.immutableCharSequence)
        val model = HistoryBackfill.replay(lines, edits)
        if (model.isEmpty()) return null
        attributedPaths.add(file.path)
        markDirty(file.path) // persist, so the next open is instant and the result stays stable
        return model
    }

    /** The in-memory ownership of [file] without touching the database; safe to call while painting. */
    fun peekOwnership(file: VirtualFile): LineOwnership? = models[file.path]

    /** Prompt details for [turnIds]; blocks on the database, so call from a background thread. */
    fun turnInfo(turnIds: Collection<String>): Map<String, LineAttributionStore.TurnInfo> =
        store()?.turnInfo(turnIds) ?: emptyMap()

    /**
     * Files written by [turnId]. Files with known attribution carry their line count; files only known from
     * the recorded tool calls (attribution is computed lazily, when a file is annotated) carry [UNKNOWN_LINES].
     * Files that no longer exist are dropped. Call from a background thread.
     */
    fun filesForTurn(turnId: String): Map<String, Int> {
        flushDirty()
        val store = store() ?: return emptyMap()
        val result = LinkedHashMap(store.filesForTurn(turnId))
        val base = project.basePath
        for (recorded in store.editedFilesForTurn(turnId)) {
            val absolute = if (recorded.startsWith("/") || base == null) recorded else "$base/$recorded"
            if (absolute !in result && File(absolute).isFile) result[absolute] = UNKNOWN_LINES
        }
        return result
    }

    /** Forgets everything held in memory (not the database). Used when the project's files are reset. */
    fun reset() {
        activeTurn.set(null)
        models.clear()
        dirty.clear()
        attributedPaths.clear()
    }

    fun addChangeListener(listener: Runnable) {
        listeners.add(listener)
    }

    fun removeChangeListener(listener: Runnable) {
        listeners.remove(listener)
    }

    private fun fireChanged() = listeners.forEach { it.run() }

    // ── Persistence ──────────────────────────────────────────────────────────

    private fun store(): LineAttributionStore? =
        ConversationDatabase.getInstance(project).takeIf { it.isReady }?.let { LineAttributionStore(it) }

    private fun restore(path: String, doc: Document): LineOwnership? {
        val stored = store()?.load(path) ?: return null
        val runs = stored.runs().map {
            OwnerRun(
                LineOwner(it.turnId(), it.timestampMs(), it.kind(), it.inferred()),
                it.startLine(), it.endLine(), it.contentHash()
            )
        }
        val lines = LineOwnership.splitLines(doc.immutableCharSequence)
        return LineOwnership.restore(runs, stored.fileHash(), lines)
    }

    private fun markDirty(path: String) {
        dirty.add(path)
    }

    fun flushDirty() {
        if (project.isDisposed || dirty.isEmpty()) return
        val store = store() ?: return
        for (path in dirty.toList()) {
            val model = models[path] ?: run { dirty.remove(path); continue }
            val text = ApplicationManager.getApplication().runReadAction<String?> {
                LocalFileSystem.getInstance().findFileByPath(path)
                    ?.let { FileDocumentManager.getInstance().getDocument(it)?.text }
            } ?: run { dirty.remove(path); continue }
            val lines = LineOwnership.splitLines(text)
            val rows = model.toRuns(lines).map {
                LineAttributionStore.RunRow(
                    it.owner.turnId, it.owner.timestampMs, it.owner.kind,
                    it.startLine, it.endLine, it.contentHash, it.owner.inferred
                )
            }
            val stored = store.replaceFile(path, LineOwnership.hashText(lines), rows)
            // A run whose turn is not in the database yet is dropped by the store. Keep the file dirty
            // until everything was accepted so the next flush retries once the turn has been saved.
            if (stored >= rows.size) dirty.remove(path)
        }
    }

    // ── Document tracking ────────────────────────────────────────────────────

    private inner class DocListener : DocumentListener {
        override fun beforeDocumentChange(event: DocumentEvent) {
            if (project.isDisposed) return
            val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
            // Stored attribution must be loaded against the text *before* the change.
            if (file.path in attributedPaths && !models.containsKey(file.path)) {
                restore(file.path, event.document)?.let { models.putIfAbsent(file.path, it) }
            }
        }

        override fun documentChanged(event: DocumentEvent) {
            if (project.isDisposed) return
            val doc = event.document
            val file = FileDocumentManager.getInstance().getFile(doc) ?: return
            val owner = currentOwner()

            // Line count of the document before this change, derived from the event.
            val oldLineCount = doc.lineCount - newlines(event.newFragment) + newlines(event.oldFragment)
            var model = models[file.path]
            if (model != null && model.lineCount != oldLineCount) {
                // The model drifted from the document (reload from disk, external change). Starting over is
                // safer than attributing lines to the wrong prompt.
                log.debug("Dropping out-of-sync attribution for ${file.path}")
                models.remove(file.path)
                model = null
            }
            if (model == null) {
                // Nothing known about this file. Only an agent edit can start tracking it.
                if (owner == null) return
                model = LineOwnership.empty(oldLineCount).also { models[file.path] = it }
            }
            applyChange(model, doc, event, owner)
            attributedPaths.add(file.path)
            markDirty(file.path)
            fireChanged()
        }

        private fun newlines(s: CharSequence): Int = s.count { it == '\n' }
    }

    private fun applyChange(model: LineOwnership, doc: Document, event: DocumentEvent, owner: LineOwner?) {
        val newText = doc.immutableCharSequence
        val start = event.offset
        val firstLine = doc.getLineNumber(start.coerceAtMost(doc.textLength))
        val lineStartOffset = doc.getLineStartOffset(firstLine)
        val newEnd = start + event.newLength
        val lastLineNew = doc.getLineNumber(newEnd.coerceAtMost(doc.textLength))
        val newRegion = newText.subSequence(lineStartOffset, doc.getLineEndOffset(lastLineNew)).toString()

        val prefix = newText.subSequence(lineStartOffset, start).toString()
        val suffixEnd = doc.getLineEndOffset(lastLineNew)
        val suffix = newText.subSequence(newEnd.coerceAtMost(suffixEnd), suffixEnd).toString()
        val oldRegion = prefix + event.oldFragment + suffix

        model.applyEdit(
            firstLine,
            LineOwnership.splitLines(oldRegion),
            LineOwnership.splitLines(newRegion),
            owner,
        )
    }

    override fun dispose() {
        flushDirty()
        models.clear()
        listeners.clear()
    }

    companion object {
        private const val FLUSH_INTERVAL_SEC = 10L
        private const val TURN_END_RETRY_SEC = 2L

        /** Line count for a file known to be edited by a turn whose attribution has not been computed yet. */
        const val UNKNOWN_LINES = -1

        /**
         * Number of agent edits in flight. Deliberately not thread-local: the tool thread typically
         * dispatches its write command to the EDT, where the document listener fires, so a ThreadLocal set
         * on the tool thread would never be seen. Edits are short, so a user keystroke racing one is
         * negligible. Static so the no-argument `FileTool.notifyEditComplete()` can close it.
         */
        private val editDepth = AtomicInteger(0)

        @JvmStatic
        fun getInstance(project: Project): PromptAttributionService =
            project.getService(PromptAttributionService::class.java)

        /** Called before an agent edit. No-op when the project is gone. */
        @JvmStatic
        fun agentEditStarted(project: Project) {
            if (project.isDisposed) return
            editDepth.incrementAndGet()
            getInstance(project).ensureStarted()
        }

        /** Called after an agent edit, paired with [agentEditStarted]. */
        @JvmStatic
        fun agentEditFinished() {
            editDepth.updateAndGet { if (it > 0) it - 1 else 0 }
        }

        /** Called when a tool created [file] with the active turn's content. */
        @JvmStatic
        fun fileCreated(project: Project, file: VirtualFile) {
            if (project.isDisposed) return
            getInstance(project).fileCreatedByAgent(file)
        }
    }
}
