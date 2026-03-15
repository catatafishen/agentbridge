package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.bridge.TransportType;
import com.github.catatafishen.ideagentforcopilot.services.AgentProfile;
import com.github.catatafishen.ideagentforcopilot.services.AgentProfileManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Root settings group for all AI agent clients.
 * Registered in plugin.xml under the main plugin settings node.
 * <p>
 * Built-in clients (GitHub Copilot, OpenCode, Claude Code, Claude CLI) are registered
 * as static children in plugin.xml. User-added generic ACP profiles are listed after
 * them via {@link Configurable.Composite#getConfigurables()}.
 * <p>
 * The group overview page shows a brief description and an "Add Custom ACP Client" form.
 */
public final class ClientAgentsGroupConfigurable implements Configurable, Configurable.Composite {

    private final Project project;

    public ClientAgentsGroupConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Client Agents";
    }

    // ── Group overview page ───────────────────────────────────────────────────

    private JBTextField newClientNameField;
    private JPanel panel;

    @Override
    public @Nullable JComponent createComponent() {
        newClientNameField = new JBTextField();
        newClientNameField.setColumns(24);
        newClientNameField.getEmptyText().setText("Name for your custom agent");

        JButton addBtn = new JButton("Add");
        addBtn.addActionListener(e -> addGenericClient());

        JPanel addRow = new JPanel(new BorderLayout(6, 0));
        addRow.add(newClientNameField, BorderLayout.CENTER);
        addRow.add(addBtn, BorderLayout.EAST);

        panel = FormBuilder.createFormBuilder()
            .addComponent(new JBLabel(
                "<html>"
                    + "<b>Client Agents</b><br><br>"
                    + "Choose a built-in agent client from the list on the left to configure it.<br><br>"
                    + "Built-in clients: <b>GitHub Copilot</b>, <b>OpenCode</b>, "
                    + "<b>Claude Code</b>, <b>Claude CLI</b>.<br><br>"
                    + "<b>Custom ACP clients</b> let you connect to any ACP-compatible agent.<br>"
                    + "Give it a name and click <i>Add</i> — it will appear in the list on "
                    + "the left after you reopen Settings."
                    + "</html>"))
            .addSeparator(12)
            .addLabeledComponent("New custom client:", addRow)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        panel.setBorder(JBUI.Borders.empty(8));
        return panel;
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public void apply() {
        // Nothing to apply — the name field is consumed immediately on Add.
    }

    @Override
    public void disposeUIResources() {
        newClientNameField = null;
        panel = null;
    }

    // ── Dynamic children (user-added generic ACP profiles) ───────────────────

    /**
     * Returns one {@link GenericAcpClientConfigurable} per user-added generic ACP profile.
     * Built-in client pages are registered statically in plugin.xml.
     */
    @Override
    public Configurable @NotNull [] getConfigurables() {
        List<Configurable> children = new ArrayList<>();
        for (AgentProfile p : AgentProfileManager.getInstance().getAllProfiles()) {
            if (!p.isBuiltIn()) {
                children.add(new GenericAcpClientConfigurable(project, p.getId()));
            }
        }
        return children.toArray(new Configurable[0]);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void addGenericClient() {
        String name = newClientNameField != null ? newClientNameField.getText().trim() : "";
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(panel,
                "Please enter a name for the new client.",
                "Name required", JOptionPane.WARNING_MESSAGE);
            return;
        }

        AgentProfile p = new AgentProfile();
        p.setId(UUID.randomUUID().toString());
        p.setDisplayName(name);
        p.setBuiltIn(false);
        p.setTransportType(TransportType.ACP);
        AgentProfileManager.getInstance().addProfile(p);

        if (newClientNameField != null) newClientNameField.setText("");
        JOptionPane.showMessageDialog(panel,
            "\"" + name + "\" has been added.\nReopen Settings to configure it.",
            "Client added", JOptionPane.INFORMATION_MESSAGE);
    }
}
