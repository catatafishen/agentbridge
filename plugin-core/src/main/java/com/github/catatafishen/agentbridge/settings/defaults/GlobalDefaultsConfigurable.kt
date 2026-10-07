package com.github.catatafishen.agentbridge.settings.defaults

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent

/**
 * Settings page for the global defaults.
 *
 * Every group of settings is either followed from the global defaults or overridden in this project (the choice
 * next to it). The buttons act on the checked groups: save this project's values as the global defaults, reset this
 * project to them, or delete them. See [DefaultsEngine] for how following and overriding work.
 */
class GlobalDefaultsConfigurable(private val project: Project) : SearchableConfigurable {

    private val engine by lazy { DefaultsSections.engine() }
    private val checkboxes = LinkedHashMap<String, JBCheckBox>()
    private val modes = LinkedHashMap<String, ComboBox<String>>()
    private val statuses = LinkedHashMap<String, JBLabel>()

    override fun getId(): String = ID

    override fun getDisplayName(): String = TITLE

    override fun createComponent(): JComponent {
        val component = panel {
            row {
                comment(
                    "Save the settings of one project as your global defaults, and every project that follows " +
                        "them uses those values. New projects follow them from the start; a project that already " +
                        "had settings keeps them until you choose to follow the defaults."
                )
            }
            group("Settings") {
                for (section in engine.sections) {
                    row {
                        val box = checkBox(section.title).comment(section.description).component
                        box.isSelected = section.id !in DefaultsSections.sensitiveIds
                        checkboxes[section.id] = box
                        cell(ComboBox(arrayOf(FOLLOWS, OVERRIDES)).also { modes[section.id] = it })
                        cell(JBLabel().also { statuses[section.id] = it })
                    }
                }
            }
            row {
                button("Save This Project's Settings as Global Defaults") { onSave() }
                button("Reset This Project to Global Defaults") { onReset() }
                button("Clear Global Defaults") { onClear() }
            }
            row {
                comment(
                    "The buttons use the checked groups. A group that follows the defaults is brought up to date " +
                        "when the project opens and when the defaults are saved. If you change a followed group " +
                        "in this project it becomes an override by itself. The web server and the MCP port are " +
                        "never shared: they belong to one project. Custom MCP servers start unchecked because " +
                        "they can hold tokens."
                )
            }
        }
        refresh()
        return component
    }

    override fun isModified(): Boolean = engine.sections.any { chosenOverride(it) != engine.isOverridden(project, it) }

    override fun apply() {
        for (section in engine.sections) {
            val overridden = chosenOverride(section)
            if (overridden != engine.isOverridden(project, section)) engine.setOverridden(project, section, overridden)
        }
        refresh()
    }

    override fun reset() = refresh()

    private fun chosenOverride(section: DefaultsSection): Boolean = modes[section.id]?.selectedItem == OVERRIDES

    private fun selected(): List<DefaultsSection> = engine.sections.filter { checkboxes[it.id]?.isSelected == true }

    private fun refresh() {
        for (section in engine.sections) {
            modes[section.id]?.selectedItem = if (engine.isOverridden(project, section)) OVERRIDES else FOLLOWS
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
        Messages.showInfoMessage(project, "Check at least one group of settings first.", TITLE)
        return true
    }

    private fun confirm(message: String, title: String): Boolean =
        Messages.showYesNoDialog(project, message, title, Messages.getQuestionIcon()) == Messages.YES

    private fun onSave() {
        if (nothingSelected()) return
        val chosen = selected()
        val replacing = chosen.filter { engine.hasDefaults(it) }
        if (replacing.isNotEmpty() && !confirm(
                "This replaces the saved global defaults for: ${names(replacing)}.\n\n" +
                    "Every open project that follows them is updated.\n\nContinue?",
                "Save Global Defaults",
            )
        ) return
        val others = try {
            engine.save(project, chosen)
            engine.syncAll(ProjectManager.getInstance().openProjects.filter { it != project && !it.isDisposed })
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "Could not save the defaults: ${e.message}", TITLE)
            return
        }
        refresh()
        val failed = others.values.flatMap { it.failed.keys }.distinct()
        val failure = if (failed.isEmpty()) "" else
            "\n\nCould not update some open projects for: ${names(failed)}. They are updated when they next open."
        Messages.showInfoMessage(
            project,
            "Saved this project's settings for: ${names(chosen)}.\n\n" +
                "This project and every project that follows the defaults now use them.$failure",
            TITLE,
        )
    }

    private fun onReset() {
        if (nothingSelected()) return
        val chosen = selected()
        val withDefaults = chosen.filter { engine.hasDefaults(it) }
        if (withDefaults.isEmpty()) {
            Messages.showInfoMessage(project, "None of the checked groups has global defaults yet.", TITLE)
            return
        }
        if (!confirm(
                "This replaces this project's current values for: ${names(withDefaults)}, and makes the project " +
                    "follow the global defaults for them.\n\nContinue?",
                "Reset to Global Defaults",
            )
        ) return
        val result = try {
            engine.apply(project, chosen)
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "Could not apply the defaults: ${e.message}", TITLE)
            return
        }
        refresh()
        val skipped = if (result.withoutDefaults.isEmpty()) "" else
            "\n\nLeft alone, because they have no global defaults: ${names(result.withoutDefaults)}."
        Messages.showInfoMessage(
            project,
            "This project now follows the global defaults for: ${names(result.applied)}.$skipped\n\n" +
                "Options the servers read when they start (MCP server, memory, code graph) apply after the " +
                "MCP server is restarted or the project is reopened. Reopen this dialog to see the new values " +
                "on the other settings pages.",
            TITLE,
        )
    }

    private fun onClear() {
        if (nothingSelected()) return
        val saved = selected().filter { engine.hasDefaults(it) }
        if (saved.isEmpty()) {
            Messages.showInfoMessage(project, "None of the checked groups has global defaults.", TITLE)
            return
        }
        if (!confirm(
                "This deletes the saved global defaults for: ${names(saved)}.\n\n" +
                    "No project is changed; projects keep the values they have now, and new projects go back " +
                    "to the built-in values for them.",
                "Clear Global Defaults",
            )
        ) return
        engine.clear(saved)
        refresh()
    }

    companion object {
        const val ID = "com.github.catatafishen.agentbridge.globalDefaults"
        private const val TITLE = "Global Defaults"
        private const val FOLLOWS = "Follows global defaults"
        private const val OVERRIDES = "Overrides in this project"
    }
}
