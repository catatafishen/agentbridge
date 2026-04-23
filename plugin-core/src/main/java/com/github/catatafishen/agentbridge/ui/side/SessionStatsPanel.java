package com.github.catatafishen.agentbridge.ui.side;

import com.github.catatafishen.agentbridge.services.ActiveAgentManager;
import com.github.catatafishen.agentbridge.services.ToolCallStatisticsService;
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
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
    private static final String LABEL_PREMIUM_REQ = "Premium req";
    private static final String TOKENS_IN_OUT_SEP = " in / ";
    private static final String TOKENS_OUT_SUFFIX = " out";

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

    // "Today" section — aggregates persisted turn_stats rows for the current local date,
    // across all agents. Independent of the in-memory Session totals (which only track
    // the current chat session).
    private final Project project;
    private final JLabel todayTimeValue = new JLabel();
    private final JLabel todayTurnsValue = new JLabel();
    private final JLabel todayToolsValue = new JLabel();
    private final JLabel todayLinesValue = new JLabel();
    private final JLabel todayTokensRowLabel = new JLabel(LABEL_TOKENS);
    private final JLabel todayTokensValue = new JLabel();
    private final JPanel todayToolsRow;
    private final JPanel todayLinesRow;
    private final JPanel todayTokensRow;
    private final JPanel todaySection;
    private final AtomicReference<TodayTotals> todayTotalsRef = new AtomicReference<>(TodayTotals.EMPTY);
    private long lastTodayQueryNanos = 0L;
    private static final long TODAY_REFRESH_INTERVAL_NANOS = 5_000_000_000L;

    public SessionStatsPanel(
        @NotNull Project project,
        @NotNull ProcessingTimerPanel timerPanel,
        @NotNull UsageGraphPanel usageGraphPanel,
        @NotNull BillingManager billing
    ) {
        super(new BorderLayout());
        this.project = project;
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
        leftAlignSection(clientSection);
        // Populate the icon + name BEFORE measuring preferred size in leftAlignChild —
        // otherwise the row is capped at the empty-label height (≈0px) and the value
        // becomes invisible after the asynchronous refreshClientSection() updates it.
        refreshClientSection();
        leftAlignChild(clientRow);

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
        leftAlignSection(turnSection);
        leftAlignChild(turnGrid);

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

        // Today section — same row layout as Session, sourced from the persistent turn_stats DB.
        // Spans across all chat sessions for the current local date so users see how much they've
        // used the assistant today regardless of how many times they've reopened the IDE.
        JPanel todayGrid = new JPanel(new GridBagLayout());
        todayGrid.setOpaque(false);
        todayGrid.setBorder(BorderFactory.createEmptyBorder(
            JBUI.scale(2), JBUI.scale(8), JBUI.scale(4), JBUI.scale(8)));
        int dRow = 0;
        addStatRow(todayGrid, dRow++, "Time", todayTimeValue);
        addStatRow(todayGrid, dRow++, "Turns", todayTurnsValue);
        todayToolsRow = addStatRow(todayGrid, dRow++, "Tool calls", todayToolsValue);
        todayLinesRow = addStatRow(todayGrid, dRow++, "Lines changed", todayLinesValue);
        todayTokensRow = addStatRowWithLabel(todayGrid, dRow, todayTokensRowLabel, todayTokensValue);

        todaySection = new JPanel();
        todaySection.setLayout(new BoxLayout(todaySection, BoxLayout.Y_AXIS));
        todaySection.setOpaque(false);
        todaySection.add(createSectionHeader("Today"));
        todaySection.add(todayGrid);
        todaySection.setVisible(false);
        leftAlignSection(todaySection);
        leftAlignChild(todayGrid);

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
        leftAlignSection(billingSection);
        leftAlignChild(billingGrid);
        leftAlignChild(graphSection);

        // Assemble the stats content (pinned to the top)
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        JPanel sessionHeader = createSectionHeader("Session");
        JPanel filesHeader = createSectionHeader("Project files");
        content.add(clientSection);
        content.add(turnSection);
        content.add(sessionHeader);
        content.add(statsGrid);
        content.add(todaySection);
        content.add(billingSection);
        content.add(filesHeader);
        // BoxLayout.Y_AXIS centers children with default CENTER_ALIGNMENT and sizes them
        // to their preferredWidth — section panels would visually float in the middle of
        // the side panel. Anchor each direct child of `content` at the left edge and let
        // it grow to the full width.
        leftAlignChild(sessionHeader);
        leftAlignChild(statsGrid);
        leftAlignChild(filesHeader);

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

    /**
     * Anchors a section wrapper panel (BoxLayout.Y_AXIS) at the left edge of its parent
     * BoxLayout.Y_AXIS container and stretches it horizontally so its left-aligned children
     * read against the side panel's left margin instead of being centered.
     */
    private static void leftAlignSection(JComponent section) {
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    /**
     * Anchors a single child component within a BoxLayout.Y_AXIS parent at the left edge
     * and lets it grow to full width while keeping its preferred height. Used for grids
     * and rows whose preferredWidth is less than the panel's width.
     */
    private static void leftAlignChild(JComponent child) {
        child.setAlignmentX(Component.LEFT_ALIGNMENT);
        Dimension pref = child.getPreferredSize();
        child.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
    }

    private JPanel createSectionHeader(String title) {
        return createSectionHeader(new JLabel(title));
    }

    private JPanel createSectionHeader(JLabel label) {
        label.setFont(smallFont.deriveFont(Font.BOLD));
        label.setForeground(dimColor);
        JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        titleRow.setOpaque(false);
        titleRow.setBorder(BorderFactory.createEmptyBorder(
            JBUI.scale(8), 0, JBUI.scale(2), 0));
        titleRow.add(label);

        // Hairline separator below the title visually unifies all section headers
        // across the side panel (Selected client / Active turn / Session / Monthly quota
        // / Project files) — the same divider treatment makes them read as a single
        // family of headers regardless of which createSectionHeader variant produced them.
        JSeparator divider = new JSeparator(SwingConstants.HORIZONTAL);
        divider.setForeground(JBUI.CurrentTheme.ToolWindow.borderColor());
        divider.setOpaque(false);
        divider.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(
            0, JBUI.scale(8), JBUI.scale(2), JBUI.scale(8)));
        titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(titleRow);
        header.add(divider);
        // Force the header to fill the full width of its BoxLayout.Y_AXIS parent so
        // the FlowLayout-LEFT title row can anchor at the left edge. Without this,
        // BoxLayout sizes the header to its (small) preferredSize and the default
        // CENTER_ALIGNMENT centers it, making the title appear in the middle.
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, header.getPreferredSize().height));
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
        // The header is now a vertical box (title row + divider). The first child
        // is the title FlowLayout row — append the suffix label there so it appears
        // inline next to the bold title (matching the original behaviour).
        if (header.getComponentCount() > 0 && header.getComponent(0) instanceof JPanel titleRow) {
            titleRow.add(suffixLabel);
        } else {
            header.add(suffixLabel);
        }
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
        refreshTodayStats(snap);
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
            turnTokensRowLabel.setText(LABEL_PREMIUM_REQ);
            turnTokensValue.setText(BillingCalculator.INSTANCE.formatPremium(snap.getTurnPremiumRequests()));
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
                        TOKENS_IN_OUT_SEP +
                        TimerDisplayFormatter.INSTANCE.formatTokenCount(snap.getTurnOutputTokens()) +
                        TOKENS_OUT_SUFFIX);
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
            tokensRowLabel.setText(LABEL_PREMIUM_REQ);
            tokensValue.setText(BillingCalculator.INSTANCE.formatPremium(snap.getLocalSessionPremiumRequests()));
            tokensRow.setVisible(true);
            costRow.setVisible(false);
        } else {
            long totalTokens = snap.getSessionInputTokens() + snap.getSessionOutputTokens();
            if (totalTokens > 0 || snap.getSessionCostUsd() > 0.0) {
                tokensRowLabel.setText(LABEL_TOKENS);
                tokensValue.setText(
                    TimerDisplayFormatter.INSTANCE.formatTokenCount(snap.getSessionInputTokens()) +
                        TOKENS_IN_OUT_SEP +
                        TimerDisplayFormatter.INSTANCE.formatTokenCount(snap.getSessionOutputTokens()) +
                        TOKENS_OUT_SUFFIX);
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
     * Aggregates today's persisted turn_stats rows across all agents and updates the
     * Today section. Throttled — at most one DB query every {@code TODAY_REFRESH_INTERVAL_NANOS}
     * nanoseconds, executed on a pooled thread; UI updates are marshalled to the EDT.
     * The cached {@link TodayTotals} is rendered immediately so the panel stays responsive
     * even when no fresh query has fired yet.
     */
    private void refreshTodayStats(SessionStatsSnapshot snap) {
        long nowNanos = System.nanoTime();
        if (nowNanos - lastTodayQueryNanos > TODAY_REFRESH_INTERVAL_NANOS) {
            lastTodayQueryNanos = nowNanos;
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    LocalDate today = LocalDate.now();
                    String iso = today.format(DateTimeFormatter.ISO_LOCAL_DATE);
                    List<ToolCallStatisticsService.DailyTurnAggregate> rows =
                        ToolCallStatisticsService.getInstance(project).queryDailyTurnStats(iso, iso);
                    int turns = 0;
                    int tools = 0;
                    long inTok = 0;
                    long outTok = 0;
                    long linesAdded = 0;
                    long linesRemoved = 0;
                    long durMs = 0;
                    double premium = 0.0;
                    for (ToolCallStatisticsService.DailyTurnAggregate r : rows) {
                        turns += r.turns();
                        tools += r.toolCalls();
                        inTok += r.inputTokens();
                        outTok += r.outputTokens();
                        linesAdded += r.linesAdded();
                        linesRemoved += r.linesRemoved();
                        durMs += r.durationMs();
                        premium += r.premiumRequests();
                    }
                    TodayTotals totals = new TodayTotals(turns, tools, inTok, outTok,
                        linesAdded, linesRemoved, durMs, premium);
                    todayTotalsRef.set(totals);
                    SwingUtilities.invokeLater(() -> applyTodayTotals(totals, snap.getMultiplierMode()));
                } catch (Exception ignored) {
                    // Stats are advisory — never let a query failure crash the UI refresh loop.
                }
            });
        }
        applyTodayTotals(todayTotalsRef.get(), snap.getMultiplierMode());
    }

    private void applyTodayTotals(TodayTotals t, boolean multiplierMode) {
        if (t.turns() <= 0) {
            todaySection.setVisible(false);
            return;
        }
        todaySection.setVisible(true);
        todayTimeValue.setText(TimerDisplayFormatter.INSTANCE.formatElapsedTime(t.durationMs() / 1000));
        todayTurnsValue.setText(String.valueOf(t.turns()));
        todayToolsValue.setText(String.valueOf(t.toolCalls()));
        todayToolsRow.setVisible(t.toolCalls() > 0);
        long lines = t.linesAdded() + t.linesRemoved();
        String linesHtml = TimerDisplayFormatter.formatDiffCountHtml(
            (int) t.linesAdded(), (int) t.linesRemoved(),
            ToolRenderers.INSTANCE.getADD_COLOR(), ToolRenderers.INSTANCE.getDEL_COLOR());
        todayLinesValue.setText(lines > 0 ? linesHtml : "0");
        todayLinesRow.setVisible(lines > 0);

        if (multiplierMode) {
            todayTokensRowLabel.setText(LABEL_PREMIUM_REQ);
            todayTokensValue.setText(BillingCalculator.INSTANCE.formatPremium(t.premiumRequests()));
            todayTokensRow.setVisible(true);
        } else {
            long totalTokens = t.inputTokens() + t.outputTokens();
            if (totalTokens > 0) {
                todayTokensRowLabel.setText(LABEL_TOKENS);
                todayTokensValue.setText(
                    TimerDisplayFormatter.INSTANCE.formatTokenCount(t.inputTokens()) +
                        TOKENS_IN_OUT_SEP +
                        TimerDisplayFormatter.INSTANCE.formatTokenCount(t.outputTokens()) +
                        TOKENS_OUT_SUFFIX);
                todayTokensRow.setVisible(true);
            } else {
                todayTokensRow.setVisible(false);
            }
        }
    }

    /**
     * Snapshot of today's aggregated turn stats across all agents. Held in an
     * {@link AtomicReference} so the pooled-thread query and the EDT render path
     * never trip over each other.
     */
    private record TodayTotals(
        int turns,
        int toolCalls,
        long inputTokens,
        long outputTokens,
        long linesAdded,
        long linesRemoved,
        long durationMs,
        double premiumRequests
    ) {
        static final TodayTotals EMPTY = new TodayTotals(0, 0, 0, 0, 0, 0, 0, 0.0);
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
