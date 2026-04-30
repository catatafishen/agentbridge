package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.services.ActiveAgentManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import javax.swing.UIManager

class ClientAgentsGroupConfigurable(private val project: Project) :
    BoundConfigurable("Agents"),
    SearchableConfigurable {

    override fun getId(): String = "com.github.catatafishen.agentbridge.agents"

    private val instructionsArea = JBTextArea().apply {
        font = JBUI.Fonts.create("monospace", 12)
        rows = 12
        wrapStyleWord = true
        lineWrap = true
    }

    override fun createPanel() = panel {
        val manager = ActiveAgentManager.getInstance(project)
        val instr = StartupInstructionsSettings.getInstance()

        row {
            comment(
                "Global behavior settings for all agent sessions. " +
                    "Configure individual agent clients in the sub-pages below."
            )
        }
        separator()
        row("Turn timeout (minutes):") {
            spinner(1..1440, 1)
                .comment("Maximum wall-clock time allowed for a turn (1–1440 minutes).")
                .bindIntValue(
                    { manager.sharedTurnTimeoutMinutes },
                    { manager.sharedTurnTimeoutMinutes = it }
                )
        }
        row("Inactivity timeout (seconds):") {
            spinner(30..86400, 30)
                .comment("Maximum silence before a turn is considered stalled (30–86400 seconds).")
                .bindIntValue(
                    { manager.sharedInactivityTimeoutSeconds },
                    { manager.sharedInactivityTimeoutSeconds = it }
                )
        }
        row("Max tool calls per turn:") {
            spinner(0..500, 1)
                .comment("Limit how many tools the agent can call in a single turn. 0 = unlimited.")
                .bindIntValue(
                    { manager.sharedMaxToolCallsPerTurn },
                    { manager.sharedMaxToolCallsPerTurn = it }
                )
        }
        separator()
        row {
            checkBox("Branch session at startup")
                .comment(
                    "Snapshot the current session before each new session starts, " +
                        "so you can restore it from the session history picker."
                )
                .bindSelected(
                    { manager.isBranchSessionAtStartup },
                    { manager.isBranchSessionAtStartup = it }
                )
        }
        group("Agent Instructions") {
            lateinit var customCheck: com.intellij.ui.dsl.builder.Cell<com.intellij.ui.components.JBCheckBox>
            row {
                customCheck = checkBox("Use custom startup instructions")
                    .bindSelected(
                        { instr.isUsingCustomInstructions },
                        { useCustom ->
                            if (useCustom) {
                                val text = instructionsArea.text
                                instr.customInstructions = text.trim().ifEmpty { null }
                            } else {
                                instr.customInstructions = null
                            }
                        }
                    )
            }
            row {
                comment(
                    "<i>Sent to all agents at the start of each session. " +
                        "Changes take effect for new sessions only.</i>"
                )
            }
            row {
                cell(JBScrollPane(instructionsArea))
                    .align(AlignX.FILL).align(AlignY.FILL).resizableColumn()
                    .onIsModified {
                        if (!customCheck.component.isSelected) false
                        else {
                            val current = instructionsArea.text.trim()
                            val stored = instr.customInstructions?.trim().orEmpty()
                            current != stored
                        }
                    }
                    .onApply {
                        if (customCheck.component.isSelected) {
                            val text = instructionsArea.text
                            instr.customInstructions = text.trim().ifEmpty { null }
                        }
                    }
                    .onReset {
                        if (instr.isUsingCustomInstructions) {
                            instructionsArea.text = instr.customInstructions
                        } else {
                            instructionsArea.text = instr.defaultTemplate
                        }
                        updateInstructionsState(instr, customCheck.component.isSelected)
                    }
            }.resizableRow().layout(RowLayout.PARENT_GRID)
            row {
                button("Reset to Default") {
                    instructionsArea.text = instr.defaultTemplate
                    customCheck.component.isSelected = false
                    updateInstructionsState(instr, false)
                }
            }
            // Re-apply enabled state whenever the checkbox toggles
            customCheck.applyToComponent {
                addActionListener { updateInstructionsState(instr, isSelected) }
            }
        }
    }

    private fun updateInstructionsState(instr: StartupInstructionsSettings, useCustom: Boolean) {
        instructionsArea.isEnabled = useCustom
        if (!useCustom) {
            instructionsArea.text = instr.defaultTemplate
            instructionsArea.isEditable = false
            instructionsArea.background = UIManager.getColor("TextField.disabledBackground")
        } else {
            instructionsArea.isEditable = true
            instructionsArea.background = UIManager.getColor("TextField.background")
        }
    }
}
