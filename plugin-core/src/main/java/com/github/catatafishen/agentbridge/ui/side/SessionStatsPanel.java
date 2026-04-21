package com.github.catatafishen.agentbridge.ui.side;

import com.github.catatafishen.agentbridge.services.ActiveAgentManager;
import com.github.catatafishen.agentbridge.ui.AgentIconProvider;
import com.github.catatafishen.agentbridge.ui.BillingCalculator;
import com.github.catatafishen.agentbridge.ui.BillingDisplayData;
import com.github.catatafishen.agentbridge.ui.BillingManager;
import com.github.catatafishen.agentbridge.ui.ProcessingTimerPanel;
import com.github.catatafishen.agentbridge.ui.SessionStatsSnapshot;
import com.github.catatafishen.agentbridge.ui.TimerDisplayFormatter;
import com.github.catatafishen.agentbridge.ui.UsageGraphPanel;
import com.github.catatafishen.agentbridge.ui.renderers.ToolRenderers;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Side panel tab displaying session statistics as labeled rows: an optional
 * "Active turn" section (visible while the agent is processing, with elapsed time
 * inline in the header) and cumulative session totals (time, turns, tools, lines,
 * tokens, cost), followed by a thin billing usage graph with quota information,
 * and a project-files tree at the bottom.
 *
 * <p>Lines-changed values are rendered with colored numbers (green for additions,
 * red for removals) and animate smoothly when the counts update.
 *
 * <p>Subscribes to change callbacks from both {@link ProcessingTimerPanel} and
 * {@link BillingManager} for a single, consistent refresh model.
 */
public final class SessionStatsPanel extends JPanel implements Disposable {

    private static final DateTimeFormatter RESET_DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy");
    private static final String LABEL_TOKENS = "Tokens";

    private final ProcessingTimerPanel timerPanel;
    private final BillingManager billing;
    private final ActiveAgentManager agentManager;
    private final Font smallFont;
    private final Color dimColor;
    private final Runnable switchListener;

    private final SessionDiffAnimator sessionDiffAnimator = new SessionDiffAnimator();
    private final SessionDiffAnimator turnDiffAnimator = new SessionDiffAnimator();
    private final Timer animationTimer;

    // Selected client section
    private final JLabel clientIconLabel = new JLabel();
    private final JLabel clientNameLabel = new JLabel();

    // Current turn section
    private final JLabel turnHeaderLabel = new JLabel("Active turn");
    private final JLabel turnToolsValue = new JLabel();
    private final JLabel turnLinesValue = new JLabel();
    private final JLabel turnTokensRowLabel = new JLabel(LABEL_TOKENS);
    private final JLabel turnTokensValue = new JLabel();
    private final JLabel turnCostRowLabel = new JLabel("Cost");
    private final JLabel turnCostValue = new JLabel();
    private final JPanel turnTokensRow;
    private final JPanel turnCostRow;
    private final JPanel turnSection;

    // Session stats value labels
    private final JLabel timeValue = new JLabel();
    private final JLabel turnsValue = new JLabel();
    private final JLabel toolsValue = new JLabel();
    private final JLabel linesValue = new JLabel();
    private final JLabel tokensValue = new JLabel();
    private final JLabel costValue = new JLabel();

    // Dynamic labels whose text changes based on provider mode
    private final JLabel tokensRowLabel = new JLabel(LABEL_TOKENS);
    private final JLabel costRowLabel = new JLabel("Cost");
    private final JPanel tokensRow;
    private final JPanel costRow;

    // Billing section widgets
    private final JLabel usageValue = new JLabel();
    private final JLabel remainingValue = new JLabel();
    private final JLabel resetsValue = new JLabel();
    private final JPanel usageRow;
    private final JPanel remainingRow;
    private final JPanel resetsRow;
    private final JPanel billingHeader;
    private final JLabel billingNoteLabel;
    private final ProjectFilesPanel filesPanel;

    public SessionStatsPanel(
        @NotNull Project project,
        @NotNull ProcessingTimerPanel timerPanel,
        @NotNull UsageGraphPanel usageGraphPanel,
        @NotNull BillingManager billing
    ) {
        super(new BorderLayout());
        this.timerPanel = timerPanel;
        this.billing = billing;
        this.agentManager = ActiveAgentManager.getInstance(project);

        this.smallFont = UIManager.getFont("Label.font").deriveFont((float) JBUI.scale(11));
        this.dimColor = JBUI.CurrentTheme.Label.disabledForeground();

        // Selected client section
        clientNameLabel.setFont(smallFont);
        JPanel clientRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        clientRow.setOpaque(false);
        clientRow.setBorder(BorderFactory.createEmptyBorder(0, JBUI.scale(8), JBUI.scale(4), JBUI.scale(8)));
        clientRow.add(clientIconLabel);
        clientRow.add(clientNameLabel);

        JPanel clientSection = new JPanel();
        clientSection.setLayout(new BoxLayout(clientSection, BoxLayout.Y_AXIS));
        clientSection.setOpaque(false);
        clientSection.add(createSectionHeader("Selected client"));
        clientSection.add(clientRow);

        // Current turn section
        JPanel turnHeader = createSectionHeader(turnHeaderLabel);

        JPanel turnGrid = new JPanel(new GridBagLayout());
        turnGrid.setOpaque(false);
        turnGrid.setBorder(BorderFactory.createEmptyBorder(
            JBUI.scale(2), JBUI.scale(8), JBUI.scale(4), JBUI.scale(8)));

        int tRow = 0;
        addStatRow(turnGrid, tRow++, "Tool calls", turnToolsValue);
        addStatRow(turnGrid, tRow++, "Lines changed", turnLinesValue);
        turnTokensRow = addStatRowWithLabel(turnGrid, tRow++, turnTokensRowLabel, turnTokensValue);
        turnCostRow = addStatRowWithLabel(turnGrid, tRow, turnCostRowLabel, turnCostValue);

        turnSection = new JPanel();
        turnSection.setLayout(new BoxLayout(turnSection, BoxLayout.Y_AXIS));
        turnSection.setOpaque(false);
        turnSection.add(turnHeader);
        turnSection.add(turnGrid);
        turnSection.setVisible(false);

        // Session stats grid
        JPanel statsGrid = new JPanel(new GridBagLayout());
        statsGrid.setOpaque(false);
        statsGrid.setBorder(BorderFactory.createEmptyBorder(
            JBUI.scale(4), JBUI.scale(8), JBUI.scale(4), JBUI.scale(8)));

        int row = 0;
        addStatRow(statsGrid, row++, "Time", timeValue);
        addStatRow(statsGrid, row++, "Turns", turnsValue);
        addStatRow(statsGrid, row++, "Tool calls", toolsValue);
        addStatRow(statsGrid, row++, "Lines changed", linesValue);

        tokensRow = addStatRowWithLabel(statsGrid, row++, tokensRowLabel, tokensValue);
        costRow = addStatRowWithLabel(statsGrid, row, costRowLabel, costValue);

        // Usage graph — thin full-width sparkline
        JPanel graphSection = new JPanel(new BorderLayout());
        graphSection.setOpaque(false);
        graphSection.setBorder(BorderFactory.createEmptyBorder(
            JBUI.scale(4), JBUI.scale(8), JBUI.scale(2), JBUI.scale(8)));
        int graphH = JBUI.scale(20);
        usageGraphPanel.setPreferredSize(new Dimension(0, graphH));
        usageGraphPanel.setMinimumSize(new Dimension(0, graphH));
        usageGraphPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, graphH));
        graphSection.add(usageGraphPanel, BorderLayout.CENTER);

        // Billing stats grid
        JPanel billingGrid = new JPanel(new GridBagLayout());
        billingGrid.setOpaque(false);
        billingGrid.setBorder(BorderFactory.createEmptyBorder(
            JBUI.scale(2), JBUI.scale(8), JBUI.scale(8), JBUI.scale(8)));

        billingHeader = createSectionHeader("Monthly quota");
        billingNoteLabel = new JLabel("via gh CLI");
        billingNoteLabel.setFont(smallFont);
        billingNoteLabel.setForeground(dimColor);
        billingNoteLabel.setBorder(BorderFactory.createEmptyBorder(0, JBUI.scale(8), JBUI.scale(2), JBUI.scale(8)));

        int brow = 0;
        usageRow = addStatRow(billingGrid, brow++, "Used", usageValue);
        remainingRow = addStatRow(billingGrid, brow++, "Remaining", remainingValue);
        resetsRow = addStatRow(billingGrid, brow, "Resets", resetsValue);

        // Assemble the stats content (pinned to the top)
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        content.add(clientSection);
        content.add(turnSection);
        content.add(createSectionHeader("Session"));
        content.add(statsGrid);
        content.add(billingHeader);
        content.add(billingNoteLabel);
        content.add(graphSection);
        content.add(billingGrid);
        content.add(createSectionHeader("Project files"));

        // Project files tree fills remaining height
        filesPanel = new ProjectFilesPanel(project);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(content, BorderLayout.NORTH);
        wrapper.add(filesPanel, BorderLayout.CENTER);

        add(wrapper, BorderLayout.CENTER);

        animationTimer = new Timer(33, e -> {
            long now = System.currentTimeMillis();
            updateDiffLabels(now);
            repaint();
            if (!sessionDiffAnimator.isAnimating(now) && !turnDiffAnimator.isAnimating(now)) {
                ((Timer) e.getSource()).stop();
            }
        });
        animationTimer.setRepeats(true);

        switchListener = () -> SwingUtilities.invokeLater(this::refreshClientSection);
        agentManager.addSwitchListener(switchListener);

        timerPanel.setOnStatsChanged(this::refresh);
        billing.setOnBillingChanged(this::refresh);
        refreshClientSection();
        refresh();
    }

    /**
     * Refreshes the project-files tree. Called when the Session tab is selected.
     */
    void refreshFiles() {
        filesPanel.refresh();
    }

    @Override
    public void dispose() {
        agentManager.removeSwitchListener(switchListener);
        timerPanel.setOnStatsChanged(null);
        billing.setOnBillingChanged(null);
        animationTimer.stop();
    }

    private JPanel createSectionHeader(String title) {
        return createSectionHeader(new JLabel(title));
    }

    private JPanel createSectionHeader(JLabel label) {
        label.setFont(smallFont.deriveFont(Font.BOLD));
        label.setForeground(dimColor);
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(
            JBUI.scale(6), 0, JBUI.scale(2), 0));
        header.add(label);
        return header;
    }

    private JPanel addStatRow(JPanel grid, int row, String labelText, JLabel value) {
        return addStatRowWithLabel(grid, row, new JLabel(labelText), value);
    }

    private JPanel addStatRowWithLabel(JPanel grid, int row, JLabel label, JLabel value) {
        label.setFont(smallFont);
        label.setForeground(UIManager.getColor("Label.foreground"));
        value.setFont(smallFont);

        JPanel rowPanel = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        rowPanel.setOpaque(false);
        rowPanel.add(label, BorderLayout.WEST);
        rowPanel.add(value, BorderLayout.CENTER);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, JBUI.scale(4), 0);
        grid.add(rowPanel, gbc);
        return rowPanel;
    }

    private void refresh() {
        SessionStatsSnapshot snap = timerPanel.getSessionSnapshot();
        BillingDisplayData bill = billing.getBillingDisplayData();
        long now = System.currentTimeMillis();

        sessionDiffAnimator.update(snap.getSessionLinesAdded(), snap.getSessionLinesRemoved(), now);
        turnDiffAnimator.update(snap.getTurnLinesAdded(), snap.getTurnLinesRemoved(), now);

        refreshTurnSection(snap);
        refreshSessionStats(snap);
        refreshBilling(bill);
        updateDiffLabels(now);
        startAnimationTimerIfNeeded(now);

        revalidate();
        repaint();
    }

    private void refreshClientSection() {
        String profileId = agentManager.getActiveProfileId();
        Icon icon = AgentIconProvider.INSTANCE.getIconForProfile(profileId);
        clientIconLabel.setIcon(icon);
        clientNameLabel.setText(agentManager.getActiveProfile().getDisplayName());
    }

    private void refreshTurnSection(SessionStatsSnapshot snap) {
        if (!snap.isRunning()) {
            turnSection.setVisible(false);
            return;
        }

        String elapsed = TimerDisplayFormatter.INSTANCE.formatElapsedTime(snap.getTurnElapsedSec());
        turnHeaderLabel.setText("Active turn  " + elapsed);
        turnSection.setVisible(true);

        turnToolsValue.setText(String.valueOf(snap.getTurnToolCalls()));

        if (snap.getMultiplierMode()) {
            turnTokensRowLabel.setText("Premium req");
            turnTokensValue.setText("1");
            turnTokensRow.setVisible(true);
            turnCostRow.setVisible(false);
        } else {
            long turnTok = snap.getTurnInputTokens() + snap.getTurnOutputTokens();
            Double turnCost = snap.getTurnCostUsd();
            boolean hasTurnUsage = turnTok > 0 || (turnCost != null && turnCost > 0.0);
            if (hasTurnUsage) {
                turnTokensRowLabel.setText(LABEL_TOKENS);
                turnTokensValue.setText(
                    TimerDisplayFormatter.INSTANCE.formatTokenCount(snap.getTurnInputTokens()) +
                        " in / " +
                        TimerDisplayFormatter.INSTANCE.formatTokenCount(snap.getTurnOutputTokens()) +
                        " out");
                turnTokensRow.setVisible(true);
                turnCostRowLabel.setText("Cost");
                turnCostValue.setText(TimerDisplayFormatter.INSTANCE.formatCost(turnCost != null ? turnCost : 0.0));
                turnCostRow.setVisible(true);
            } else {
                turnTokensRow.setVisible(false);
                turnCostRow.setVisible(false);
            }
        }
    }

    private void refreshSessionStats(SessionStatsSnapshot snap) {
        timeValue.setText(TimerDisplayFormatter.INSTANCE.formatElapsedTime(snap.getSessionTotalTimeSec()));
        turnsValue.setText(String.valueOf(snap.getSessionTurnCount()));
        toolsValue.setText(String.valueOf(snap.getSessionToolCalls()));

        if (snap.getMultiplierMode()) {
            tokensRowLabel.setText("Premium req");
            tokensValue.setText(BillingCalculator.INSTANCE.formatPremium(snap.getLocalSessionPremiumRequests()));
            tokensRow.setVisible(true);
            costRow.setVisible(false);
        } else {
            long totalTokens = snap.getSessionInputTokens() + snap.getSessionOutputTokens();
            if (totalTokens > 0 || snap.getSessionCostUsd() > 0.0) {
                tokensRowLabel.setText(LABEL_TOKENS);
                tokensValue.setText(
                    TimerDisplayFormatter.INSTANCE.formatTokenCount(snap.getSessionInputTokens()) +
                        " in / " +
                        TimerDisplayFormatter.INSTANCE.formatTokenCount(snap.getSessionOutputTokens()) +
                        " out");
                tokensRow.setVisible(true);
                costRowLabel.setText("Cost");
                costValue.setText(TimerDisplayFormatter.INSTANCE.formatCost(snap.getSessionCostUsd()));
                costRow.setVisible(true);
            } else {
                tokensRow.setVisible(false);
                costRow.setVisible(false);
            }
        }
    }

    private void updateDiffLabels(long now) {
        Color addColor = ToolRenderers.INSTANCE.getADD_COLOR();
        Color delColor = ToolRenderers.INSTANCE.getDEL_COLOR();

        SessionDiffAnimator.DiffCounts sCounts = sessionDiffAnimator.displayCounts(now);
        String sHtml = TimerDisplayFormatter.formatDiffCountHtml(
            sCounts.added(), sCounts.removed(), addColor, delColor);
        linesValue.setText(sHtml.isEmpty() ? "—" : sHtml);

        if (turnSection.isVisible()) {
            SessionDiffAnimator.DiffCounts tCounts = turnDiffAnimator.displayCounts(now);
            String tHtml = TimerDisplayFormatter.formatDiffCountHtml(
                tCounts.added(), tCounts.removed(), addColor, delColor);
            turnLinesValue.setText(tHtml.isEmpty() ? "—" : tHtml);
        }
    }

    private void startAnimationTimerIfNeeded(long now) {
        if (sessionDiffAnimator.isAnimating(now) || turnDiffAnimator.isAnimating(now)) {
            if (!animationTimer.isRunning()) animationTimer.start();
        } else {
            animationTimer.stop();
        }
    }

    private void refreshBilling(BillingDisplayData bill) {
        boolean hasBilling = bill.getEntitlement() > 0 || bill.getUnlimited();
        billingHeader.setVisible(hasBilling);
        billingNoteLabel.setVisible(hasBilling);

        if (bill.getUnlimited()) {
            usageValue.setText("Unlimited");
            usageRow.setVisible(true);
            remainingRow.setVisible(false);
        } else if (bill.getEntitlement() > 0) {
            usageValue.setText(bill.getEstimatedUsed() + " / " + bill.getEntitlement());
            usageRow.setVisible(true);
            int remaining = bill.getEstimatedRemaining();
            if (remaining < 0) {
                remainingValue.setText("Over by " + (-remaining));
                remainingValue.setForeground(JBUI.CurrentTheme.Label.errorForeground());
            } else {
                remainingValue.setText(String.valueOf(remaining));
                remainingValue.setForeground(UIManager.getColor("Label.foreground"));
            }
            remainingRow.setVisible(true);
        } else {
            usageRow.setVisible(false);
            remainingRow.setVisible(false);
        }

        if (!bill.getResetDate().isEmpty()) {
            try {
                LocalDate reset = LocalDate.parse(bill.getResetDate(), DateTimeFormatter.ISO_LOCAL_DATE);
                resetsValue.setText(reset.format(RESET_DATE_FMT));
                resetsRow.setVisible(hasBilling);
            } catch (DateTimeParseException ignored) {
                resetsRow.setVisible(false);
            }
        } else {
            resetsRow.setVisible(false);
        }
    }
}
