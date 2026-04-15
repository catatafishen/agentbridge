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
import java.util.List;

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
        backfillButton.setEnabled(enabled);
        backfillStatusLabel.setEnabled(enabled);
    }

    private void updateBackfillStatus() {
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

        backfillButton.setEnabled(false);
        backfillStatusLabel.setText("Starting backfill…");

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Mining conversation history", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);
                indicator.setFraction(0);

                SessionStoreV2 sessionStore = SessionStoreV2.getInstance(project);
                List<SessionStoreV2.SessionRecord> sessions = sessionStore.listSessions(project.getBasePath());
                int total = sessions.size();

                BackfillMiner backfillMiner = new BackfillMiner(project);
                backfillMiner.run(progress -> {
                    double fraction = parseFraction(progress, total);
                    if (fraction >= 0) {
                        indicator.setFraction(fraction);
                    }
                    String exchangeDetail = parseExchangeDetail(progress);
                    if (exchangeDetail != null) {
                        indicator.setText(stripExchangeDetail(progress));
                        indicator.setText2(exchangeDetail);
                    } else {
                        indicator.setText(progress);
                        indicator.setText2("");
                    }
                    ApplicationManager.getApplication().invokeLater(() ->
                        backfillStatusLabel.setText(progress));
                }).whenComplete((result, error) ->
                    ApplicationManager.getApplication().invokeLater(() -> {
                        backfillButton.setEnabled(enabledCheckBox.isSelected());
                        if (error != null) {
                            backfillStatusLabel.setText("Backfill failed: " + error.getMessage());
                        } else {
                            updateBackfillStatus();
                        }
                    })
                );
            }
        });
    }

    /**
     * Parse a progress string into a fraction (0.0–1.0) for the progress bar.
     * Handles both session-level ("Mining session 3 of 16: label") and
     * exchange-level ("Mining session 3 of 16 (embedding 2/8): label") formats.
     *
     * @return fraction in [0.0, 1.0], or -1 if the string doesn't match
     */
    static double parseFraction(String progress, int totalSessions) {
        if (totalSessions <= 0 || !progress.startsWith("Mining session ")) return -1;

        int session = parseSessionNumber(progress);
        if (session < 1) return -1;

        double exchangeFraction = parseExchangeFraction(progress);
        return (session - 1 + exchangeFraction) / totalSessions;
    }

    private static int parseSessionNumber(String progress) {
        int ofIndex = progress.indexOf(" of ");
        if (ofIndex < 0) return -1;
        try {
            return Integer.parseInt(progress.substring("Mining session ".length(), ofIndex));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Extract the exchange fraction from a progress string containing "(phase N/M)".
     * Returns 0.0 if no exchange detail is present (session-start message).
     */
    private static double parseExchangeFraction(String progress) {
        int parenOpen = progress.indexOf('(');
        int parenClose = progress.indexOf(')', parenOpen + 1);
        if (parenOpen < 0 || parenClose < 0) return 0.0;

        String detail = progress.substring(parenOpen + 1, parenClose);
        int slashIdx = detail.indexOf('/');
        if (slashIdx < 0) return 0.0;

        String afterPhase = detail.contains(" ") ? detail.substring(detail.lastIndexOf(' ') + 1) : detail;
        int slashInAfter = afterPhase.indexOf('/');
        if (slashInAfter < 0) return 0.0;

        try {
            int current = Integer.parseInt(afterPhase.substring(0, slashInAfter));
            int total = Integer.parseInt(afterPhase.substring(slashInAfter + 1));
            return total > 0 ? (double) current / total : 0.0;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Extract a human-readable exchange detail string for indicator.setText2().
     * Input: "Mining session 3 of 16 (embedding 2/8): Fix auth bug"
     * Output: "Embedding exchange 2 of 8"
     *
     * @return detail string, or null if no exchange detail is present
     */
    static String parseExchangeDetail(String progress) {
        int parenOpen = progress.indexOf('(');
        int parenClose = progress.indexOf(')', parenOpen + 1);
        if (parenOpen < 0 || parenClose < 0) return null;

        String detail = progress.substring(parenOpen + 1, parenClose);
        int spaceIdx = detail.indexOf(' ');
        if (spaceIdx < 0) return null;

        String phase = detail.substring(0, spaceIdx);
        String fraction = detail.substring(spaceIdx + 1);
        int slashIdx = fraction.indexOf('/');
        if (slashIdx < 0) return null;

        String current = fraction.substring(0, slashIdx);
        String total = fraction.substring(slashIdx + 1);
        String capitalizedPhase = Character.toUpperCase(phase.charAt(0)) + phase.substring(1);
        return capitalizedPhase + " exchange " + current + " of " + total;
    }

    /**
     * Strip the exchange detail "(phase N/M)" from a progress string for indicator.setText().
     * Input: "Mining session 3 of 16 (embedding 2/8): Fix auth bug"
     * Output: "Mining session 3 of 16: Fix auth bug"
     */
    static String stripExchangeDetail(String progress) {
        int parenOpen = progress.indexOf('(');
        int parenClose = progress.indexOf(')', parenOpen + 1);
        if (parenOpen < 0 || parenClose < 0) return progress;
        return progress.substring(0, parenOpen).stripTrailing()
            + progress.substring(parenClose + 1);
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
