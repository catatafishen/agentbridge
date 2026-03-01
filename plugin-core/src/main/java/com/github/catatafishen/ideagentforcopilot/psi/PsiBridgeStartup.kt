package com.github.catatafishen.ideagentforcopilot.psi

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.nio.file.Files
import java.nio.file.Path

/**
 * Starts the PSI Bridge HTTP server when a project opens.
 * Uses [ProjectActivity] (not legacy StartupActivity) so the plugin supports
 * dynamic loading/unloading without IDE restart.
 */
class PsiBridgeStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        LOG.info("Starting PSI Bridge for project: ${project.name}")

        createAgentWorkspace(project)

        PsiBridgeService.getInstance(project).start()
    }

    /**
     * Creates the .agent-work/ directory structure for agent session state.
     * This directory is typically gitignored and provides a safe workspace
     * for the agent to store plans, checkpoints, and analysis files.
     */
    private fun createAgentWorkspace(project: Project) {
        val basePath = project.basePath ?: return

        try {
            val agentWork = Path.of(basePath, ".agent-work")
            Files.createDirectories(agentWork.resolve("checkpoints"))
            Files.createDirectories(agentWork.resolve("files"))

            val planFile = agentWork.resolve("plan.md")
            if (!Files.exists(planFile)) {
                Files.writeString(planFile, PLAN_TEMPLATE)
            }

            LOG.info("Agent workspace initialized at: $agentWork")
        } catch (e: Exception) {
            LOG.warn("Failed to create agent workspace", e)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(PsiBridgeStartup::class.java)

        private val PLAN_TEMPLATE = """
            # Agent Work Plan

            ## Project Principles

            When working on this IntelliJ plugin project:
            - **Write clean, well-formatted code** following Java/Kotlin best practices
            - **Use IntelliJ tools first**: `intellij_read_file`, `intellij_write_file`, `search_symbols`, `find_references`
            - **Always format and optimize** after changes: `format_code` + `optimize_imports`
            - **Test before commit**: `build_project` + `run_tests` to ensure nothing breaks
            - **Make logical commits**: Group related changes, separate unrelated changes

            ## Multi-Step Task Workflow

            When fixing multiple issues:
            1. Scan and group by problem TYPE (not by file)
            2. Fix ONE problem type completely (may span multiple files)
            3. Format, build, test, commit
            4. ⚠️ **STOP and ASK** before continuing to next type

            ## Current Tasks

            _Use checkboxes below to track your progress:_

            - [ ] Task 1
            - [ ] Task 2

            ## Notes

            _Add any context, decisions, or findings here_
        """.trimIndent()
    }
}
