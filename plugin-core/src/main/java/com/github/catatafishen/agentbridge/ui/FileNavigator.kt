package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.psi.PsiBridgeService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.io.File
import java.util.concurrent.TimeUnit

/** Handles file and git-commit link navigation from the chat JCEF panel. */
class FileNavigator(private val project: Project) {

    private val log = Logger.getInstance(FileNavigator::class.java)

    fun handleFileLink(href: String) {
        if (href.startsWith("gitshow://")) {
            handleGitShowLink(href.removePrefix("gitshow://"))
            return
        }
        val pathAndLine = href.removePrefix("openfile://")
        val (filePath, line) = parsePathAndLine(pathAndLine)
        val normalizedPath = filePath.replace('\\', '/')
        val vf = LocalFileSystem.getInstance().findFileByPath(normalizedPath) ?: return
        ApplicationManager.getApplication().invokeLater {
            try {
                val focus = !PsiBridgeService.isUserTypingInChat(project)
                OpenFileDescriptor(project, vf, maxOf(0, line - 1), 0).navigate(focus)
            } catch (e: Exception) {
                log.warn("Failed to navigate to file $normalizedPath:$line", e)
            }
        }
    }

    fun markdownToHtml(text: String): String =
        MarkdownRenderer.markdownToHtml(text, ::resolveFileReference, ::resolveFilePath, ::isGitCommit)

    /** Splits a path-and-optional-line string, handling Windows drive letters (e.g. C:\...:42). */
    private fun parsePathAndLine(pathAndLine: String): Pair<String, Int> {
        val lastColon = pathAndLine.lastIndexOf(':')
        if (lastColon > 0) {
            val afterColon = pathAndLine.substring(lastColon + 1)
            val lineNum = afterColon.toIntOrNull()
            if (lineNum != null) return Pair(pathAndLine.substring(0, lastColon), lineNum)
        }
        return Pair(pathAndLine, 0)
    }

    private fun handleGitShowLink(hash: String) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val repos = git4idea.repo.GitRepositoryManager.getInstance(project).repositories.toList()
                val root = repos.firstOrNull()?.root
                if (root == null) {
                    log.warn("No VCS root found for git commit link $hash")
                    return@invokeLater
                }
                val fullHash = resolveFullHash(hash) ?: hash
                val hashObj = com.intellij.vcs.log.impl.HashImpl.build(fullHash)
                val vcsLog = com.intellij.vcs.log.impl.VcsProjectLog.getInstance(project)
                vcsLog.dataManager?.refresh(listOf(root))
                showRevisionWhenIndexed(root, hashObj, attemptsLeft = 25, delayMs = 200)
            } catch (e: Exception) {
                log.warn("Failed to open git commit $hash", e)
            }
        }
    }

    private fun showRevisionWhenIndexed(
        root: com.intellij.openapi.vfs.VirtualFile,
        hash: com.intellij.vcs.log.Hash,
        attemptsLeft: Int,
        delayMs: Long,
    ) {
        val dm = com.intellij.vcs.log.impl.VcsProjectLog.getInstance(project).dataManager
        val commitId = com.intellij.vcs.log.CommitId(hash, root)
        val indexed = dm != null && dm.storage.containsCommit(commitId)
        if (indexed || attemptsLeft <= 0) {
            ApplicationManager.getApplication().invokeLater {
                com.intellij.vcs.log.impl.VcsProjectLog.showRevisionInMainLog(project, root, hash)
            }
            return
        }
        com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService().schedule({
            showRevisionWhenIndexed(root, hash, attemptsLeft - 1, delayMs)
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun resolveFullHash(shortHash: String): String? {
        val basePath = project.basePath ?: return null
        return try {
            val process = ProcessBuilder("git", "rev-parse", shortHash)
                .directory(File(basePath))
                .redirectErrorStream(true)
                .start()
            val exited = process.waitFor(2, TimeUnit.SECONDS)
            if (exited && process.exitValue() == 0) process.inputStream.bufferedReader().readLine()?.trim()
            else null
        } catch (_: Exception) {
            null
        }
    }

    private fun isGitCommit(sha: String): Boolean {
        val basePath = project.basePath ?: return false
        return try {
            val process = ProcessBuilder("git", "cat-file", "-t", sha)
                .directory(File(basePath))
                .redirectErrorStream(true)
                .start()
            val exited = process.waitFor(2, TimeUnit.SECONDS)
            exited && process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveFileReference(ref: String): Pair<String, Int?>? {
        val colonIdx = ref.indexOf(':')
        val (name, lineNum) = if (colonIdx > 0) {
            val afterColon = ref.substring(colonIdx + 1)
            val num = afterColon.split(",", " ").firstOrNull()?.toIntOrNull()
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
