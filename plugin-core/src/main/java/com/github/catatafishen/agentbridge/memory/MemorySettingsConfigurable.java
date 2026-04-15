package com.github.catatafishen.agentbridge.memory;

import com.github.catatafishen.agentbridge.memory.mining.BackfillMiner;
import com.github.catatafishen.agentbridge.session.v2.SessionStoreV2;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public final class MemorySettingsConfigurable implements Configurable {

    private final Project project;

    private JCheckBox enabledCheckBox;
    private JCheckBox autoMineTurnCheckBox;
    private JCheckBox autoMineArchiveCheckBox;
    private JSpinner minChunkLengthSpinner;
    private JSpinner maxDrawersPerTurnSpinner;
    private JTextField palaceWingField;
    private JButton backfillButton;
    private JLabel backfillStatusLabel;
    private volatile boolean miningInProgress;

    private JLabel minChunkLabel;
    private JLabel maxDrawersLabel;
    private JLabel palaceWingLabel;

    public MemorySettingsConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "Memory";
    }

    @Override
    public @Nullable JComponent createComponent() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;

        // ── Description ──
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        JLabel desc = new JLabel(
            "<html>Semantic memory powered by concepts from " +
                "<a href=\"https://github.com/milla-jovovich/mempalace\">MemPalace</a>. " +
                "Stores decisions, preferences, and milestones from conversations " +
                "for cross-session recall.</html>");
        panel.add(desc, gbc);

        // ── Enabled ──
        gbc.gridy++;
        gbc.gridwidth = 2;
        enabledCheckBox = new JCheckBox("Enable semantic memory (stores memories locally in .agent-work/memory/)");
        enabledCheckBox.addItemListener(e -> updateSubOptionsEnabled());
        panel.add(enabledCheckBox, gbc);

        // ── Auto-mine on turn complete ──
        gbc.gridy++;
        gbc.gridwidth = 2;
        autoMineTurnCheckBox = new JCheckBox("Automatically mine memories after each agent turn");
        panel.add(autoMineTurnCheckBox, gbc);

        // ── Auto-mine on session archive ──
        gbc.gridy++;
        gbc.gridwidth = 2;
        autoMineArchiveCheckBox = new JCheckBox("Mine remaining entries when a session is archived");
        panel.add(autoMineArchiveCheckBox, gbc);

        // ── Min chunk length ──
        gbc.gridy++;
        gbc.gridwidth = 1;
        gbc.gridx = 0;
        minChunkLabel = new JLabel("Minimum chunk length (chars):");
        panel.add(minChunkLabel, gbc);
        gbc.gridx = 1;
        minChunkLengthSpinner = new JSpinner(new SpinnerNumberModel(200, 50, 2000, 50));
        panel.add(minChunkLengthSpinner, gbc);

        // ── Max drawers per turn ──
        gbc.gridy++;
        gbc.gridx = 0;
        maxDrawersLabel = new JLabel("Max drawers per turn:");
        panel.add(maxDrawersLabel, gbc);
        gbc.gridx = 1;
        maxDrawersPerTurnSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 100, 1));
        panel.add(maxDrawersPerTurnSpinner, gbc);

        // ── Palace wing ──
        gbc.gridy++;
        gbc.gridx = 0;
        palaceWingLabel = new JLabel("Palace wing (empty = project name):");
        panel.add(palaceWingLabel, gbc);
        gbc.gridx = 1;
        palaceWingField = new JTextField(20);
        panel.add(palaceWingField, gbc);

        // ── Backfill section ──
        gbc.gridy++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        JSeparator sep = new JSeparator();
        panel.add(sep, gbc);

        gbc.gridy++;
        gbc.gridwidth = 2;
        backfillStatusLabel = new JLabel();
        updateBackfillStatus();
        panel.add(backfillStatusLabel, gbc);

        gbc.gridy++;
        gbc.gridwidth = 1;
        gbc.gridx = 0;
        backfillButton = new JButton("Mine Existing History");
        backfillButton.setToolTipText("Mine all past conversation sessions into the memory store");
        backfillButton.addActionListener(e -> runBackfill());
        panel.add(backfillButton, gbc);

        gbc.gridx = 1;
        JLabel backfillHint = new JLabel(
            "<html><i>⚠ Can be slow if you have many sessions. " +
                "Runs in the background.</i></html>");
        backfillHint.setForeground(UIManager.getColor("Component.warningFocusColor"));
        panel.add(backfillHint, gbc);

        // ── Filler ──
        gbc.gridy++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        gbc.weighty = 1.0;
        panel.add(Box.createVerticalGlue(), gbc);

        updateSubOptionsEnabled();
        return panel;
    }

    private void updateSubOptionsEnabled() {
        boolean enabled = enabledCheckBox.isSelected();
        autoMineTurnCheckBox.setEnabled(enabled);
        autoMineArchiveCheckBox.setEnabled(enabled);
        minChunkLabel.setEnabled(enabled);
        minChunkLengthSpinner.setEnabled(enabled);
        maxDrawersLabel.setEnabled(enabled);
        maxDrawersPerTurnSpinner.setEnabled(enabled);
        palaceWingLabel.setEnabled(enabled);
        palaceWingField.setEnabled(enabled);
        // Backfill requires persisted settings — enabled only after Apply
        boolean persisted = MemorySettings.getInstance(project).isEnabled();
        backfillButton.setEnabled(persisted);
        backfillStatusLabel.setEnabled(enabled);
    }

    private void updateBackfillStatus() {
        if (miningInProgress) return;
        MemorySettings settings = MemorySettings.getInstance(project);
        if (settings.isBackfillCompleted()) {
            backfillStatusLabel.setText("✓ History has been mined into memory.");
        } else {
            int sessionCount = SessionStoreV2.getInstance(project)
                .listSessions(project.getBasePath()).size();
            if (sessionCount > 0) {
                backfillStatusLabel.setText(
                    "<html><b>" + sessionCount + " past sessions</b> available to mine. " +
                        "Click below to populate memory from your conversation history.</html>");
            } else {
                backfillStatusLabel.setText("No past sessions found.");
            }
        }
    }

    private void runBackfill() {
        MemorySettings settings = MemorySettings.getInstance(project);
        if (!settings.isEnabled()) {
            Messages.showWarningDialog(
                project,
                "Please enable semantic memory first, then apply settings before running the backfill.",
                "Memory Not Enabled");
            return;
        }

        settings.setBackfillCompleted(false);
        miningInProgress = true;
        backfillButton.setEnabled(false);
        backfillStatusLabel.setText("Starting backfill…");

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Mining conversation history", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);
                indicator.setFraction(0);

                BackfillMiner backfillMiner = new BackfillMiner(project);
                try {
                    backfillMiner.runSync(
                        text -> {
                            indicator.setText(text);
                            ApplicationManager.getApplication().invokeLater(() ->
                                backfillStatusLabel.setText(text));
                        },
                        indicator::setFraction,
                        indicator::isCanceled);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        miningInProgress = false;
                        backfillButton.setEnabled(enabledCheckBox.isSelected());
                        updateBackfillStatus();
                    });
                } catch (Exception e) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        miningInProgress = false;
                        backfillButton.setEnabled(enabledCheckBox.isSelected());
                        backfillStatusLabel.setText("Backfill failed: " + e.getMessage());
                    });
                }
            }
        });
    }

    @Override
    public boolean isModified() {
        MemorySettings settings = MemorySettings.getInstance(project);
        return enabledCheckBox.isSelected() != settings.isEnabled()
            || autoMineTurnCheckBox.isSelected() != settings.isAutoMineOnTurnComplete()
            || autoMineArchiveCheckBox.isSelected() != settings.isAutoMineOnSessionArchive()
            || (int) minChunkLengthSpinner.getValue() != settings.getMinChunkLength()
            || (int) maxDrawersPerTurnSpinner.getValue() != settings.getMaxDrawersPerTurn()
            || !palaceWingField.getText().equals(settings.getPalaceWing());
    }

    @Override
    public void apply() throws ConfigurationException {
        MemorySettings settings = MemorySettings.getInstance(project);
        boolean wasDisabled = !settings.isEnabled();
        settings.setEnabled(enabledCheckBox.isSelected());
        settings.setAutoMineOnTurnComplete(autoMineTurnCheckBox.isSelected());
        settings.setAutoMineOnSessionArchive(autoMineArchiveCheckBox.isSelected());
        settings.setMinChunkLength((int) minChunkLengthSpinner.getValue());
        settings.setMaxDrawersPerTurn((int) maxDrawersPerTurnSpinner.getValue());
        settings.setPalaceWing(palaceWingField.getText().trim());

        // Refresh backfill button now that settings are persisted
        updateSubOptionsEnabled();

        // Offer backfill when memory is first enabled
        if (wasDisabled && settings.isEnabled() && !settings.isBackfillCompleted()) {
            offerBackfill();
        }
    }

    private void offerBackfill() {
        int sessionCount = SessionStoreV2.getInstance(project)
            .listSessions(project.getBasePath()).size();
        if (sessionCount == 0) return;

        int choice = Messages.showYesNoDialog(
            project,
            "You have " + sessionCount + " past conversation sessions.\n\n" +
                "Would you like to mine them into memory now?\n" +
                "This runs in the background but may take a while for large histories.",
            "Mine Existing History?",
            "Mine Now",
            "Later",
            Messages.getQuestionIcon());

        if (choice == Messages.YES) {
            runBackfill();
        }
    }

    @Override
    public void reset() {
        MemorySettings settings = MemorySettings.getInstance(project);
        enabledCheckBox.setSelected(settings.isEnabled());
        autoMineTurnCheckBox.setSelected(settings.isAutoMineOnTurnComplete());
        autoMineArchiveCheckBox.setSelected(settings.isAutoMineOnSessionArchive());
        minChunkLengthSpinner.setValue(settings.getMinChunkLength());
        maxDrawersPerTurnSpinner.setValue(settings.getMaxDrawersPerTurn());
        palaceWingField.setText(settings.getPalaceWing());
        if (backfillStatusLabel != null) {
            updateBackfillStatus();
        }
        updateSubOptionsEnabled();
    }
}
