package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.psi.PsiBridgeService
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.vcs.log.impl.HashImpl
import com.intellij.vcs.log.impl.VcsProjectLog
import git4idea.repo.GitRepositoryManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private data class PathLineRange(val path: String, val startLine: Int, val endLine: Int?)

/** Handles file and git-commit link navigation from the chat JCEF panel. */
class FileNavigator(private val project: Project) {

    private val log = Logger.getInstance(FileNavigator::class.java)

    /**
     * Cache of SHA → isCommit results. Populated by background checks; EDT only reads.
     * Negative results are also cached to avoid repeated background submissions.
     */
    private val commitCache = ConcurrentHashMap<String, Boolean>()

    /** SHAs currently being checked in the background — prevents duplicate submissions. */
    private val pendingChecks: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun handleFileLink(href: String) {
        if (href.startsWith("http://") || href.startsWith("https://")) {
            BrowserUtil.browse(href)
            return
        }
        if (href.startsWith("gitshow://")) {
            handleGitShowLink(href.removePrefix("gitshow://"))
            return
        }
        val pathAndLine = href.removePrefix("openfile://")
        val parsed = parsePathAndLine(pathAndLine)
        val normalizedPath = parsed.path.replace('\\', '/')
        val vf = LocalFileSystem.getInstance().findFileByPath(normalizedPath) ?: return
        ApplicationManager.getApplication().invokeLater {
            try {
                val focus = !PsiBridgeService.isUserTypingInChat(project)
                if (parsed.endLine != null && parsed.startLine > 0) {
                    val editor = FileEditorManager.getInstance(project)
                        .openTextEditor(OpenFileDescriptor(project, vf, maxOf(0, parsed.startLine - 1), 0), focus)
                    if (editor != null) {
                        val doc = editor.document
                        val startOffset = doc.getLineStartOffset(maxOf(0, parsed.startLine - 1))
                        val endLine = maxOf(parsed.startLine - 1, minOf(doc.lineCount - 1, parsed.endLine - 1))
                        val endOffset = doc.getLineEndOffset(endLine)
                        editor.selectionModel.setSelection(startOffset, endOffset)
                    }
                } else {
                    OpenFileDescriptor(project, vf, maxOf(0, parsed.startLine - 1), 0).navigate(focus)
                }
            } catch (e: Exception) {
                log.warn("Failed to navigate to file $normalizedPath:${parsed.startLine}", e)
            }
        }
    }

    fun markdownToHtml(text: String): String =
        MarkdownRenderer.markdownToHtml(text, ::resolveFileReference, ::resolveFilePath, ::isGitCommit)

    private fun parsePathAndLine(pathAndLine: String): PathLineRange {
        val lastColon = pathAndLine.lastIndexOf(':')
        if (lastColon > 0) {
            val afterColon = pathAndLine.substring(lastColon + 1)
            val path = pathAndLine.substring(0, lastColon)
            val dashIdx = afterColon.indexOf('-')
            if (dashIdx > 0) {
                val start = afterColon.substring(0, dashIdx).toIntOrNull()
                val end = afterColon.substring(dashIdx + 1).toIntOrNull()
                if (start != null) return PathLineRange(path, start, end)
            }
            val lineNum = afterColon.toIntOrNull()
            if (lineNum != null) return PathLineRange(path, lineNum, null)
        }
        return PathLineRange(pathAndLine, 0, null)
    }

    private fun handleGitShowLink(hash: String) {
        // Resolve full hash off EDT — git rev-parse blocks otherwise.
        AppExecutorUtil.getAppExecutorService().submit {
            try {
                val repos = GitRepositoryManager.getInstance(project).repositories.toList()
                val root = repos.firstOrNull()?.root
                if (root == null) {
                    log.warn("No VCS root found for git commit link $hash")
                    return@submit
                }
                val fullHash = resolveFullHash(hash) ?: hash
                val hashObj = HashImpl.build(fullHash)
                ApplicationManager.getApplication().invokeLater {
                    try {
                        val vcsLog = VcsProjectLog.getInstance(project)
                        vcsLog.dataManager?.refresh(listOf(root))
                        showRevisionWhenIndexed(root, hashObj, attemptsLeft = 25, delayMs = 200)
                    } catch (e: Exception) {
                        log.warn("Failed to open git commit $hash", e)
                    }
                }
            } catch (e: Exception) {
                log.warn("Failed to resolve git commit $hash", e)
            }
        }
    }

    private fun showRevisionWhenIndexed(
        root: com.intellij.openapi.vfs.VirtualFile,
        hash: com.intellij.vcs.log.Hash,
        attemptsLeft: Int,
        delayMs: Long,
    ) {
        val dm = VcsProjectLog.getInstance(project).dataManager
        val commitId = com.intellij.vcs.log.CommitId(hash, root)
        val indexed = dm != null && dm.storage.containsCommit(commitId)
        if (indexed || attemptsLeft <= 0) {
            ApplicationManager.getApplication().invokeLater {
                VcsProjectLog.showRevisionInMainLog(project, root, hash)
            }
            return
        }
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            showRevisionWhenIndexed(root, hash, attemptsLeft - 1, delayMs)
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    // Antipattern (DESIGN-PRINCIPLES.md): ProcessBuilder for git commands. Should use git4idea APIs
    // (e.g. Git.getInstance().resolveReference()). Kept because adding an optional dependency on the
    // git4idea plugin requires careful class-loading setup for these UI-layer utilities.
    private fun resolveFullHash(shortHash: String): String? {
        val basePath = project.basePath ?: return null
        var process: Process? = null
        return try {
            process = ProcessBuilder("git", "rev-parse", shortHash)
                .directory(File(basePath))
                .redirectErrorStream(true)
                .start()
            val exited = process.waitFor(2, TimeUnit.SECONDS)
            if (exited && process.exitValue() == 0) process.inputStream.bufferedReader().readLine()?.trim()
            else null
        } catch (_: Exception) {
            null
        } finally {
            process?.destroyForcibly()
        }
    }

    /**
     * Returns whether [sha] is a known git commit. EDT-safe: returns the cached result immediately,
     * or `false` while scheduling a single background check that populates the cache.
     *
     * During streaming the same SHA appears across many render cycles, so the cache is warm by the
     * time streaming completes. On cold cache (e.g. after IDE restart followed by monitor recovery)
     * short SHAs may not be linkified on the very first render — this is acceptable and avoids
     * blocking the EDT.
     */
    private fun isGitCommit(sha: String): Boolean {
        commitCache[sha]?.let { return it }
        // Deduplicated: only one background task per SHA at a time.
        if (pendingChecks.add(sha)) {
            AppExecutorUtil.getAppExecutorService().submit {
                try {
                    if (!project.isDisposed) {
                        commitCache[sha] = checkGitProcess(sha)
                    }
                } finally {
                    pendingChecks.remove(sha)
                }
            }
        }
        return false
    }

    private fun checkGitProcess(sha: String): Boolean {
        val basePath = project.basePath ?: return false
        var process: Process? = null
        return try {
            process = ProcessBuilder("git", "cat-file", "-t", sha)
                .directory(File(basePath))
                .redirectErrorStream(true)
                .start()
            val exited = process.waitFor(2, TimeUnit.SECONDS)
            exited && process.exitValue() == 0
        } catch (_: Exception) {
            false
        } finally {
            process?.destroyForcibly()
        }
    }

    private fun resolveFileReference(ref: String): Pair<String, Int?>? {
        // Skip a Windows drive-letter colon (e.g. "C:") before searching for the line-number separator.
        val searchFrom = if (ref.length > 2 && ref[1] == ':' && ref[0].isLetter()) 2 else 0
        val colonIdx = ref.indexOf(':', searchFrom)
        val (name, lineNum) = if (colonIdx > 0) {
            val afterColon = ref.substring(colonIdx + 1)
            val num = afterColon.split(",", " ", "-").firstOrNull()?.toIntOrNull()
            if (num != null) ref.substring(0, colonIdx) to num else ref to null
        } else ref to null
        val path = resolveFilePath(name)
            ?: if (!name.contains("/") && name.contains(".")) findProjectFileByName(name) else null
        return if (path != null) Pair(path, lineNum) else null
    }

    private fun resolveFilePath(path: String): String? {
        val f = File(path)
        if (f.isAbsolute) return if (f.exists()) f.absolutePath else null
        val base = project.basePath ?: return null
        val rel = File(base, path)
        return if (rel.exists()) rel.absolutePath else null
    }

    private fun findProjectFileByName(name: String): String? = try {
        var result: String? = null
        ApplicationManager.getApplication().runReadAction {
            val files = FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project)).toList()
            if (files.size == 1) result = files.first().path
        }
        result
    } catch (_: Exception) {
        null
    }
}
