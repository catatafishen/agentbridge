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
import com.intellij.ui.components.JBScrollPane;
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

    // Current turn section (also displays the most recent completed turn between turns)
    private final JLabel turnHeaderLabel = new JLabel("Active turn");
    private final JLabel turnTimeValue = new JLabel();
    private final JLabel turnToolsValue = new JLabel();
    private final JLabel turnLinesValue = new JLabel();
    private final JLabel turnTokensRowLabel = new JLabel(LABEL_TOKENS);
    private final JLabel turnTokensValue = new JLabel();
    private final JLabel turnCostRowLabel = new JLabel("Cost");
    private final JLabel turnCostValue = new JLabel();
    private final JPanel turnToolsRow;
    private final JPanel turnLinesRow;
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
    private final JPanel turnsRow;
    private final JPanel sessionToolsRow;
    private final JPanel linesRow;
    private final JPanel tokensRow;
    private final JPanel costRow;

    // Billing section widgets
    private final JLabel usageValue = new JLabel();
    private final JLabel remainingValue = new JLabel();
    private final JLabel resetsValue = new JLabel();
    private final JPanel usageRow;
    private final JPanel remainingRow;
    private final JPanel resetsRow;
    private final JPanel billingSection;
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

        // Current turn section — mirrors the Session grid layout (Time row first) so the
        // two visually align. Stays visible after the turn ends, then re-labels as "Last turn".
        JPanel turnHeader = createSectionHeader(turnHeaderLabel);

        JPanel turnGrid = new JPanel(new GridBagLayout());
        turnGrid.setOpaque(false);
        turnGrid.setBorder(BorderFactory.createEmptyBorder(
            JBUI.scale(2), JBUI.scale(8), JBUI.scale(4), JBUI.scale(8)));

        int tRow = 0;
        addStatRow(turnGrid, tRow++, "Time", turnTimeValue);
        turnToolsRow = addStatRow(turnGrid, tRow++, "Tool calls", turnToolsValue);
        turnLinesRow = addStatRow(turnGrid, tRow++, "Lines changed", turnLinesValue);
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
        turnsRow = addStatRow(statsGrid, row++, "Turns", turnsValue);
        sessionToolsRow = addStatRow(statsGrid, row++, "Tool calls", toolsValue);
        linesRow = addStatRow(statsGrid, row++, "Lines changed", linesValue);

        tokensRow = addStatRowWithLabel(statsGrid, row++, tokensRowLabel, tokensValue);
        costRow = addStatRowWithLabel(statsGrid, row, costRowLabel, costValue);

        // Usage graph — full-width sparkline rendered last in the Monthly quota section.
        // 5x taller than the original 20px to make trends visually readable at a glance.
        JPanel graphSection = new JPanel(new BorderLayout());
        graphSection.setOpaque(false);
        graphSection.setBorder(BorderFactory.createEmptyBorder(
            JBUI.scale(6), JBUI.scale(8), JBUI.scale(2), JBUI.scale(8)));
        int graphH = JBUI.scale(100);
        usageGraphPanel.setPreferredSize(new Dimension(0, graphH));
        usageGraphPanel.setMinimumSize(new Dimension(0, graphH));
        usageGraphPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, graphH));
        graphSection.add(usageGraphPanel, BorderLayout.CENTER);

        // Billing stats grid
        JPanel billingGrid = new JPanel(new GridBagLayout());
        billingGrid.setOpaque(false);
        billingGrid.setBorder(BorderFactory.createEmptyBorder(
            JBUI.scale(2), JBUI.scale(8), JBUI.scale(2), JBUI.scale(8)));

        // Section header inlines the data-source note ("via gh CLI") next to the bold
        // title — replacing the old standalone subtitle row that looked disconnected.
        JPanel billingHeader = createSectionHeaderWithSuffix("Monthly quota", "via gh CLI");

        int brow = 0;
        usageRow = addStatRow(billingGrid, brow++, "Used", usageValue);
        remainingRow = addStatRow(billingGrid, brow++, "Remaining", remainingValue);
        resetsRow = addStatRow(billingGrid, brow, "Resets", resetsValue);

        // Wrap the entire billing area in one section so we can hide all of it (including
        // the now-tall graph) when no billing data is available — avoids leaving a 100px gap.
        billingSection = new JPanel();
        billingSection.setLayout(new BoxLayout(billingSection, BoxLayout.Y_AXIS));
        billingSection.setOpaque(false);
        billingSection.add(billingHeader);
        billingSection.add(billingGrid);
        billingSection.add(graphSection);

        // Assemble the stats content (pinned to the top)
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        content.add(clientSection);
        content.add(turnSection);
        content.add(createSectionHeader("Session"));
        content.add(statsGrid);
        content.add(billingSection);
        content.add(createSectionHeader("Project files"));

        // Project files tree expands to its full preferred height; the outer
        // scroll pane (below) handles scrolling for the entire side panel.
        filesPanel = new ProjectFilesPanel(project);

        JPanel wrapper = new ScrollablePanel();
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
        wrapper.setOpaque(false);
        // Each child sticks to its preferred height; together they grow the
        // wrapper beyond the viewport so the outer scroll pane can scroll it.
        content.setAlignmentX(Component.LEFT_ALIGNMENT);
        filesPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrapper.add(content);
        wrapper.add(filesPanel);

        JBScrollPane scrollPane = new JBScrollPane(wrapper);
        scrollPane.setBorder(JBUI.Borders.empty());
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

        add(scrollPane, BorderLayout.CENTER);

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

    /**
     * Section header with a non-bold dim suffix (e.g. data-source note) shown next to the
     * bold title. Keeps the title's visual weight while inlining the supplemental info that
     * would otherwise need its own subtitle row.
     */
    private JPanel createSectionHeaderWithSuffix(String title, String suffix) {
        JPanel header = createSectionHeader(title);
        JLabel suffixLabel = new JLabel(suffix);
        suffixLabel.setFont(smallFont);
        suffixLabel.setForeground(dimColor);
        header.add(suffixLabel);
        return header;
    }

    private JPanel addStatRow(JPanel grid, int row, String labelText, JLabel value) {
        return addStatRowWithLabel(grid, row, new JLabel(labelText), value);
    }

    private JPanel addStatRowWithLabel(JPanel grid, int row, JLabel label, JLabel value) {
        label.setFont(smallFont);
        label.setForeground(UIManager.getColor("Label.foreground"));
        value.setFont(smallFont);
        // Right-align values so columns line up cleanly across sections; label stays left.
        value.setHorizontalAlignment(SwingConstants.RIGHT);

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
        // Show the section whenever there's any turn worth displaying — either an active
        // turn or at least one completed turn in this session. Previously the section was
        // hidden between turns, leaving users without a record of their last prompt's cost.
        boolean hasTurn = snap.isRunning() || snap.getSessionTurnCount() > 0;
        if (!hasTurn) {
            turnSection.setVisible(false);
            return;
        }
        turnSection.setVisible(true);
        turnHeaderLabel.setText(snap.isRunning() ? "Active turn" : "Last turn");

        // Time as a labeled row (mirrors the Session section) instead of inline in the
        // header — the two sections now align visually.
        turnTimeValue.setText(TimerDisplayFormatter.INSTANCE.formatElapsedTime(snap.getTurnElapsedSec()));
        int turnTools = snap.getTurnToolCalls();
        turnToolsValue.setText(String.valueOf(turnTools));
        // Hide zero-value rows to reduce visual noise — a row of "0"s conveys no signal.
        turnToolsRow.setVisible(turnTools > 0);
        long turnLines = snap.getTurnLinesAdded() + snap.getTurnLinesRemoved();
        turnLinesRow.setVisible(turnLines > 0);

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
        int turns = snap.getSessionTurnCount();
        turnsValue.setText(String.valueOf(turns));
        turnsRow.setVisible(turns > 0);
        int sessionTools = snap.getSessionToolCalls();
        toolsValue.setText(String.valueOf(sessionTools));
        sessionToolsRow.setVisible(sessionTools > 0);
        long sessionLines = snap.getSessionLinesAdded() + snap.getSessionLinesRemoved();
        linesRow.setVisible(sessionLines > 0);

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
        // Hide the entire section (header + grid + 100px graph) when no billing data —
        // otherwise a tall empty graph leaves a visually broken gap in the side panel.
        billingSection.setVisible(hasBilling);

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

    /**
     * A {@link JPanel} that implements {@link Scrollable} so that the containing
     * {@link JBScrollPane} tracks the viewport width and never shows a horizontal
     * scrollbar even when a child (e.g. the file tree) has a wide preferred width.
     */
    private static final class ScrollablePanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return visibleRect.height;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
