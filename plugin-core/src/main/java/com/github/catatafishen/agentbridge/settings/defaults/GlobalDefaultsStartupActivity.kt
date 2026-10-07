package com.github.catatafishen.agentbridge.settings.defaults

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.nio.file.Files
import java.nio.file.Path

/** What to do with global defaults when a project opens. */
internal enum class SeedDecision {
    /** A new project: give it the global defaults. */
    SEED,

    /** An existing project, or one this cannot judge: leave it alone, and remember that it was looked at. */
    MARK_ONLY,

    /** Already looked at. */
    NOTHING,
}

internal object FreshProject {

    /**
     * A project gets the global defaults once, when it is new. "New" means no AgentBridge settings file exists in
     * its `.idea` directory yet: every project that was already configured before global defaults existed has
     * one, and must keep exactly what it has.
     *
     * Without a `.idea` directory (an old-style project) there is nothing to tell new from existing by, so the
     * project is left alone.
     */
    fun decide(alreadyHandled: Boolean, hasConfigDir: Boolean, hasSettingsFile: Boolean): SeedDecision = when {
        alreadyHandled -> SeedDecision.NOTHING
        !hasConfigDir || hasSettingsFile -> SeedDecision.MARK_ONLY
        else -> SeedDecision.SEED
    }
}

/**
 * Gives a new project the user's global defaults the first time it opens. Registered to run first, so the
 * defaults are in place before the MCP server and the other services read their settings.
 */
class GlobalDefaultsStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val properties = PropertiesComponent.getInstance(project)
        val configDir = project.basePath?.let { Path.of(it, ".idea") }
        val hasConfigDir = configDir != null && Files.isDirectory(configDir)

        val decision = FreshProject.decide(
            alreadyHandled = properties.getBoolean(HANDLED_KEY, false),
            hasConfigDir = hasConfigDir,
            // Settings kept in properties leave no file of their own, so a project that only ever changed those
            // (the active agent, auto-connect...) would look new without the second test.
            hasSettingsFile = (configDir != null &&
                DefaultsSections.projectFiles.any { Files.exists(configDir.resolve(it)) }) ||
                DefaultsSections.hasProjectProperties(project),
        )
        val engine = DefaultsSections.engine()
        try {
            // A project configured before the defaults existed keeps what it has until the user opts in.
            if (decision == SeedDecision.MARK_ONLY) engine.overrideAll(project)
            properties.setValue(HANDLED_KEY, true)
            report(engine.sync(project))
        } catch (e: Exception) {
            // Never stop the project from opening because a default could not be applied.
            LOG.warn("Could not apply the global defaults to this project", e)
        }
    }

    private fun report(result: DefaultsEngine.SyncResult) {
        if (result.applied.isNotEmpty()) {
            LOG.info("Applied global defaults for ${result.applied.joinToString { it.id }}")
        }
        if (result.diverged.isNotEmpty()) {
            LOG.info("Edited in this project, so now overriding the global defaults: ${result.diverged.joinToString { it.id }}")
        }
        for ((section, error) in result.failed) LOG.warn("Could not apply the global defaults for ${section.id}", error)
    }

    private companion object {
        val LOG = Logger.getInstance(GlobalDefaultsStartupActivity::class.java)
        const val HANDLED_KEY = "agentbridge.globalDefaults.handled"
    }
}
