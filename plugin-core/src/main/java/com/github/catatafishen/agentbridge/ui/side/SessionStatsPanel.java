package com.github.catatafishen.agentbridge.ui.side;

import com.github.catatafishen.agentbridge.ui.BillingDisplayData;
import com.github.catatafishen.agentbridge.ui.BillingManager;
import com.github.catatafishen.agentbridge.ui.ProcessingTimerPanel;
import com.github.catatafishen.agentbridge.ui.SessionStatsSnapshot;
import com.github.catatafishen.agentbridge.ui.TimerDisplayFormatter;
import com.github.catatafishen.agentbridge.ui.UsageGraphPanel;
import com.github.catatafishen.agentbridge.ui.renderers.ToolRenderers;
import com.intellij.openapi.Disposable;
import com.intellij.ui.AnimatedIcon;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Side panel tab displaying session statistics as labeled rows: an optional
 * "Current turn" section (visible while the agent is processing) and cumulative
 * session totals (time, turns, tools, lines, tokens, cost), followed by a thin
 * billing usage graph with quota information.
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

    private final SessionDiffAnimator sessionDiffAnimator = new SessionDiffAnimator();
    private final SessionDiffAnimator turnDiffAnimator = new SessionDiffAnimator();
    private final Timer animationTimer;

    // Current turn section
    private final JLabel turnHeaderLabel = new JLabel("Current turn");
    private final JLabel spinnerLabel = new JLabel(new AnimatedIcon.Default());
    private final JLabel turnStatusLabel = new JLabel();
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

    public SessionStatsPanel(
            @NotNull ProcessingTimerPanel timerPanel,
            @NotNull UsageGraphPanel usageGraphPanel,
            @NotNull BillingManager billing
    ) {
        super(new BorderLayout());
        this.timerPanel = timerPanel;
        this.billing = billing;

        Font smallFont = UIManager.getFont("Label.font").deriveFont((float) JBUI.scale(11));
        Color dimColor = JBUI.CurrentTheme.Label.disabledForeground();

        // Current turn section
        JPanel turnHeader = createSectionHeader(turnHeaderLabel, smallFont, dimColor);
        JPanel turnStatusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        turnStatusRow.setOpaque(false);
        turnStatusRow.setBorder(BorderFactory.createEmptyBorder(
                JBUI.scale(2), JBUI.scale(8), JBUI.scale(2), JBUI.scale(8)));
        spinnerLabel.setVisible(false);
        turnStatusLabel.setFont(smallFont);
        turnStatusLabel.setForeground(dimColor);
        turnStatusRow.add(spinnerLabel);
        turnStatusRow.add(turnStatusLabel);

        JPanel turnGrid = new JPanel(new GridBagLayout());
        turnGrid.setOpaque(false);
        turnGrid.setBorder(BorderFactory.createEmptyBorder(
                JBUI.scale(2), JBUI.scale(8), JBUI.scale(4), JBUI.scale(8)));

        int tRow = 0;
        addStatRow(turnGrid, tRow++, "Tool calls", turnToolsValue, smallFont, dimColor);
        addStatRow(turnGrid, tRow++, "Lines changed", turnLinesValue, smallFont, dimColor);
        turnTokensRow = addStatRowWithLabel(turnGrid, tRow++, turnTokensRowLabel, turnTokensValue, smallFont, dimColor);
        turnCostRow = addStatRowWithLabel(turnGrid, tRow, turnCostRowLabel, turnCostValue, smallFont, dimColor);

        turnSection = new JPanel();
        turnSection.setLayout(new BoxLayout(turnSection, BoxLayout.Y_AXIS));
        turnSection.setOpaque(false);
        turnSection.add(turnHeader);
        turnSection.add(turnStatusRow);
        turnSection.add(turnGrid);
        turnSection.setVisible(false);

        // Session stats grid
        JPanel statsGrid = new JPanel(new GridBagLayout());
        statsGrid.setOpaque(false);
        statsGrid.setBorder(BorderFactory.createEmptyBorder(
                JBUI.scale(4), JBUI.scale(8), JBUI.scale(4), JBUI.scale(8)));

        int row = 0;
        addStatRow(statsGrid, row++, "Time", timeValue, smallFont, dimColor);
        addStatRow(statsGrid, row++, "Turns", turnsValue, smallFont, dimColor);
        addStatRow(statsGrid, row++, "Tool calls", toolsValue, smallFont, dimColor);
        addStatRow(statsGrid, row++, "Lines changed", linesValue, smallFont, dimColor);

        tokensRow = addStatRowWithLabel(statsGrid, row++, tokensRowLabel, tokensValue, smallFont, dimColor);
        costRow = addStatRowWithLabel(statsGrid, row, costRowLabel, costValue, smallFont, dimColor);

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

        billingHeader = createSectionHeader("Monthly quota", smallFont, dimColor);
        int brow = 0;
        usageRow = addStatRow(billingGrid, brow++, "Used", usageValue, smallFont, dimColor);
        remainingRow = addStatRow(billingGrid, brow++, "Remaining", remainingValue, smallFont, dimColor);
        resetsRow = addStatRow(billingGrid, brow, "Resets", resetsValue, smallFont, dimColor);

        // Assemble the content
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        content.add(turnSection);
        content.add(createSectionHeader("Session", smallFont, dimColor));
        content.add(statsGrid);
        content.add(billingHeader);
        content.add(graphSection);
        content.add(billingGrid);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(content, BorderLayout.NORTH);

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

        timerPanel.setOnStatsChanged(this::refresh);
        billing.setOnBillingChanged(this::refresh);
        refresh();
    }

    @Override
    public void dispose() {
        timerPanel.setOnStatsChanged(() -> {
        });
        billing.setOnBillingChanged(() -> {
        });
        animationTimer.stop();
    }

    private JPanel createSectionHeader(String title, Font font, Color color) {
        return createSectionHeader(new JLabel(title), font, color);
    }

    private JPanel createSectionHeader(JLabel label, Font font, Color color) {
        label.setFont(font.deriveFont(Font.BOLD));
        label.setForeground(color);
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(
                JBUI.scale(6), 0, JBUI.scale(2), 0));
        header.add(label);
        return header;
    }

    @SuppressWarnings("SameParameterValue")
    private JPanel addStatRow(JPanel grid, int row, String labelText, JLabel value,
                              Font font, Color dimColor) {
        return addStatRowWithLabel(grid, row, new JLabel(labelText), value, font, dimColor);
    }

    private JPanel addStatRowWithLabel(JPanel grid, int row, JLabel label, JLabel value,
                                       Font font, Color dimColor) {
        label.setFont(font);
        label.setForeground(dimColor);
        value.setFont(font);

        JPanel rowPanel = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        rowPanel.setOpaque(false);
        rowPanel.add(label, BorderLayout.WEST);
        rowPanel.add(value, BorderLayout.CENTER);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, JBUI.scale(2), 0);
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

    private void refreshTurnSection(SessionStatsSnapshot snap) {
        if (!snap.isRunning()) {
            spinnerLabel.setVisible(false);
            turnSection.setVisible(false);
            return;
        }

        turnHeaderLabel.setText("Current turn");
        spinnerLabel.setVisible(true);
        String elapsed = TimerDisplayFormatter.INSTANCE.formatElapsedTime(snap.getTurnElapsedSec());
        turnStatusLabel.setText("Processing… " + elapsed);
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
            tokensValue.setText(String.valueOf(snap.getSessionTurnCount()));
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
            } catch (Exception ignored) {
                resetsRow.setVisible(false);
            }
        } else {
            resetsRow.setVisible(false);
        }
    }
}
