package com.github.catatafishen.agentbridge.settings.defaults

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent

/**
 * Settings page for the global defaults: choose which groups of settings to copy, then save this project's values
 * as the defaults, apply the defaults to this project, or clear them.
 *
 * New projects get the defaults automatically the first time they open (see [GlobalDefaultsStartupActivity]).
 * Changing the defaults later does not change projects that already have them: each project keeps its own copy.
 */
class GlobalDefaultsConfigurable(private val project: Project) : SearchableConfigurable {

    private val engine by lazy { DefaultsSections.engine() }
    private val checkboxes = LinkedHashMap<String, JBCheckBox>()
    private val statuses = LinkedHashMap<String, JBLabel>()

    override fun getId(): String = ID

    override fun getDisplayName(): String = "Global Defaults"

    override fun createComponent(): JComponent {
        val component = panel {
            row {
                comment(
                    "Save the settings of this project as your defaults, and every new project starts with " +
                        "them instead of the built-in ones. Existing projects keep what they have: copy the " +
                        "defaults into one with <b>Apply</b>."
                )
            }
            group("Settings to Copy") {
                for (section in engine.sections) {
                    row {
                        val box = checkBox(section.title).comment(section.description).component
                        box.isSelected = section.id !in DefaultsSections.sensitiveIds
                        checkboxes[section.id] = box
                        cell(JBLabel().also { statuses[section.id] = it })
                    }
                }
            }
            row {
                button("Save This Project's Settings as Global Defaults") { onSave() }
                button("Apply Global Defaults to This Project") { onApplyToProject() }
                button("Clear Global Defaults") { onClear() }
            }
            row {
                comment(
                    "Only the checked groups are used. The web server and the MCP port are never copied: " +
                        "they belong to one project. Custom MCP servers start unchecked because they can hold tokens."
                )
            }
        }
        refresh()
        return component
    }

    override fun isModified(): Boolean = false

    override fun apply() = Unit

    override fun reset() = refresh()

    private fun selected(): List<DefaultsSection> = engine.sections.filter { checkboxes[it.id]?.isSelected == true }

    private fun refresh() {
        for (section in engine.sections) {
            statuses[section.id]?.apply {
                if (engine.hasDefaults(section)) {
                    text = "Saved"
                    foreground = UIUtil.getLabelForeground()
                } else {
                    text = "Not set"
                    foreground = UIUtil.getContextHelpForeground()
                }
            }
        }
    }

    private fun names(sections: Collection<DefaultsSection>) = sections.joinToString(", ") { it.title }

    private fun nothingSelected(): Boolean {
        if (selected().isNotEmpty()) return false
        Messages.showInfoMessage(project, "Check at least one group of settings first.", "Global Defaults")
        return true
    }

    private fun confirm(message: String, title: String): Boolean =
        Messages.showYesNoDialog(project, message, title, Messages.getQuestionIcon()) == Messages.YES

    private fun onSave() {
        if (nothingSelected()) return
        val chosen = selected()
        val replacing = chosen.filter { engine.hasDefaults(it) }
        if (replacing.isNotEmpty() && !confirm(
                "This replaces the saved global defaults for: ${names(replacing)}.\n\nContinue?",
                "Save Global Defaults",
            )
        ) return
        try {
            engine.save(project, chosen)
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "Could not save the defaults: ${e.message}", "Global Defaults")
            return
        }
        refresh()
        Messages.showInfoMessage(
            project,
            "Saved this project's settings for: ${names(chosen)}.\n\nNew projects will start with them.",
            "Global Defaults",
        )
    }

    private fun onApplyToProject() {
        if (nothingSelected()) return
        val chosen = selected()
        val withDefaults = chosen.filter { engine.hasDefaults(it) }
        if (withDefaults.isEmpty()) {
            Messages.showInfoMessage(project, "None of the checked groups has global defaults yet.", "Global Defaults")
            return
        }
        if (!confirm(
                "This replaces this project's current values for: ${names(withDefaults)}.\n\nContinue?",
                "Apply Global Defaults",
            )
        ) return
        val result = try {
            engine.apply(project, chosen)
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "Could not apply the defaults: ${e.message}", "Global Defaults")
            return
        }
        val skipped = if (result.withoutDefaults.isEmpty()) "" else
            "\n\nLeft alone, because they have no global defaults: ${names(result.withoutDefaults)}."
        Messages.showInfoMessage(
            project,
            "Applied the global defaults for: ${names(result.applied)}.$skipped\n\n" +
                "Options the servers read when they start (MCP server, memory, code graph) apply after the " +
                "MCP server is restarted or the project is reopened. Reopen this dialog to see the new values " +
                "on the other settings pages.",
            "Global Defaults",
        )
    }

    private fun onClear() {
        if (nothingSelected()) return
        val saved = selected().filter { engine.hasDefaults(it) }
        if (saved.isEmpty()) {
            Messages.showInfoMessage(project, "None of the checked groups has global defaults.", "Global Defaults")
            return
        }
        if (!confirm(
                "This deletes the saved global defaults for: ${names(saved)}.\n\n" +
                    "No project is changed; new projects go back to the built-in values for them.",
                "Clear Global Defaults",
            )
        ) return
        engine.clear(saved)
        refresh()
    }

    companion object {
        const val ID = "com.github.catatafishen.agentbridge.globalDefaults"
    }
}
