package com.github.catatafishen.agentbridge.client.koog

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.nio.file.Files
import java.nio.file.Path

/**
 * Spike only: when the IDE is started with `-Dagentbridge.koogSpike=<output file>` this runs
 * [KoogSpike] inside the plugin classloader and writes the report to that file. No-op otherwise.
 */
class KoogSpikeStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        val out = System.getProperty("agentbridge.koogSpike") ?: return
        val report = try {
            KoogSpike.run()
        } catch (t: Throwable) {
            "SPIKE FAILED TO RUN\n${t.stackTraceToString()}"
        }
        Files.writeString(Path.of(out), report)
    }
}
