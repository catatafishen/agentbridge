package com.github.catatafishen.agentbridge.memory

import com.github.catatafishen.agentbridge.memory.mining.BackfillMiner
import com.github.catatafishen.agentbridge.memory.mining.MiningTracker
import com.github.catatafishen.agentbridge.session.v2.SessionStoreV2
import com.github.catatafishen.agentbridge.settings.AgentBridgeStorageSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import java.nio.file.Path

class MemorySettingsConfigurable(private val project: Project) :
    BoundConfigurable("Memory"),
    SearchableConfigurable {

    override fun getId(): String = "com.github.catatafishen.agentbridge.memory"

    private val s get() = MemorySettings.getInstance(project)
    private val storageLocationLabel = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
    }
    private val backfillStatusLabel = JBLabel()
    private var backfillButton: javax.swing.JButton? = null

    @Volatile private var miningInProgress = false

    override fun createPanel() = panel {
        updateStorageLocationLabel()
        updateBackfillStatus()

        row {
            comment(
                "Semantic memory powered by concepts from " +
                    "<a href=\"https://github.com/milla-jovovich/mempalace\">MemPalace</a>. " +
                    "Stores decisions, preferences, and milestones from conversations for cross-session recall."
            )
        }
        lateinit var enabledCheckbox: Cell<JBCheckBox>
        row {
            enabledCheckbox = checkBox("Enable semantic memory")
                .bindSelected({ s.isEnabled }, { s.isEnabled = it })
        }
        row {
            cell(storageLocationLabel)
        }
        row {
            checkBox("Automatically mine memories after each agent turn")
                .bindSelected(
                    { s.isAutoMineOnTurnComplete },
                    { s.isAutoMineOnTurnComplete = it }
                )
                .enabledIf(enabledCheckbox.selected)
        }
        row {
            checkBox("Mine remaining entries when a session is archived")
                .bindSelected(
                    { s.isAutoMineOnSessionArchive },
                    { s.isAutoMineOnSessionArchive = it }
                )
                .enabledIf(enabledCheckbox.selected)
        }
        row("Minimum chunk length (chars):") {
            spinner(50..2000, 50)
                .bindIntValue({ s.minChunkLength }, { s.minChunkLength = it })
                .enabledIf(enabledCheckbox.selected)
        }
        row("Max drawers per turn:") {
            spinner(1..100, 1)
                .bindIntValue({ s.maxDrawersPerTurn }, { s.maxDrawersPerTurn = it })
                .enabledIf(enabledCheckbox.selected)
        }
        row("Palace wing (empty = project name):") {
            textField()
                .bindText({ s.palaceWing }, { s.palaceWing = it.trim() })
                .enabledIf(enabledCheckbox.selected)
        }
        separator()
        row {
            cell(backfillStatusLabel)
        }
        row {
            backfillButton = button("Mine Existing History") { runBackfill() }
                .applyToComponent { toolTipText = "Mine all past conversation sessions into the memory store" }
                .enabled(s.isEnabled)
                .component
            comment("⚠ Can be slow if you have many sessions. Runs in the background.")
        }
        onApply {
            // Refresh button enablement after settings persist
            backfillButton?.isEnabled = s.isEnabled
            // Offer backfill when memory was just enabled
            if (s.isEnabled && !s.isBackfillCompleted) offerBackfill()
        }
        onReset {
            updateStorageLocationLabel()
            updateBackfillStatus()
            backfillButton?.isEnabled = s.isEnabled
        }
    }

    private fun updateStorageLocationLabel() {
        val dir: Path = AgentBridgeStorageSettings.getInstance().getProjectMemoryDir(project)
        storageLocationLabel.text =
            "<html>Stored in <code>${formatPathForHtml(dir)}</code>.</html>"
    }

    private fun formatPathForHtml(path: Path): String =
        StringUtil.escapeXmlEntities(path.toString())
            .replace("/", "/<wbr>")
            .replace("\\", "\\<wbr>")

    private fun updateBackfillStatus() {
        if (miningInProgress) return
        if (s.isBackfillCompleted) {
            backfillStatusLabel.text = "✓ History has been mined into memory."
        } else {
            val sessionCount = SessionStoreV2.getInstance(project)
                .listSessions(project.basePath).size
            backfillStatusLabel.text = if (sessionCount > 0) {
                "<html><b>$sessionCount past sessions</b> available to mine. " +
                    "Click below to populate memory from your conversation history.</html>"
            } else {
                "No past sessions found."
            }
        }
    }

    private fun runBackfill() {
        if (!s.isEnabled) {
            Messages.showWarningDialog(
                project,
                "Please enable semantic memory first, then apply settings before running the backfill.",
                "Memory Not Enabled"
            )
            return
        }

        s.isBackfillCompleted = false
        miningInProgress = true
        backfillButton?.isEnabled = false
        backfillStatusLabel.text = "Starting backfill…"

        ProgressManager.getInstance().run(object :
            Task.Backgroundable(project, "Mining conversation history", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.fraction = 0.0
                val tracker = MiningTracker.getInstance(project)
                tracker.startBackfill()
                val miner = BackfillMiner(project)
                try {
                    miner.runSync(
                        { text ->
                            indicator.text = text
                            tracker.reportProgress(text)
                            ApplicationManager.getApplication().invokeLater {
                                backfillStatusLabel.text = text
                            }
                        },
                        { f -> indicator.fraction = f },
                        { indicator.isCanceled }
                    )
                    tracker.stop()
                    ApplicationManager.getApplication().invokeLater {
                        miningInProgress = false
                        backfillButton?.isEnabled = s.isEnabled
                        updateBackfillStatus()
                    }
                } catch (e: Exception) {
                    tracker.stop()
                    ApplicationManager.getApplication().invokeLater {
                        miningInProgress = false
                        backfillButton?.isEnabled = s.isEnabled
                        backfillStatusLabel.text = "Backfill failed: ${e.message}"
                    }
                }
            }
        })
    }

    private fun offerBackfill() {
        val sessionCount = SessionStoreV2.getInstance(project)
            .listSessions(project.basePath).size
        if (sessionCount == 0) return
        val choice = Messages.showYesNoDialog(
            project,
            "You have $sessionCount past conversation sessions.\n\n" +
                "Would you like to mine them into memory now?\n" +
                "This runs in the background but may take a while for large histories.",
            "Mine Existing History?",
            "Mine Now",
            "Later",
            Messages.getQuestionIcon()
        )
        if (choice == Messages.YES) runBackfill()
    }
}
