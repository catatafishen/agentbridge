package com.github.catatafishen.agentbridge.ui.sessions

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager

/**
 * Looks up the checked-out branch through git4idea. Git4Idea is an optional dependency, so any
 * failure to load or query it simply means "unknown".
 */
object GitBranchLookup {
    private val log = Logger.getInstance(GitBranchLookup::class.java)

    fun current(project: Project): String? = try {
        GitRepositoryManager.getInstance(project).repositories.firstOrNull()?.currentBranchName
    } catch (e: LinkageError) {
        log.debug("git4idea unavailable", e)
        null
    } catch (e: RuntimeException) {
        log.debug("Could not determine current git branch", e)
        null
    }
}
