package com.github.catatafishen.agentbridge.ui.side;

import com.github.catatafishen.agentbridge.ui.BillingManager;
import com.github.catatafishen.agentbridge.ui.ProcessingTimerPanel;
import com.github.catatafishen.agentbridge.ui.UsageGraphPanel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * Side panel tab displaying session statistics: processing timer (elapsed time,
 * tool calls, token usage) and billing usage graph with quota information.
 */
public final class SessionStatsPanel extends JPanel {

    public SessionStatsPanel(
        @NotNull ProcessingTimerPanel timerPanel,
        @NotNull UsageGraphPanel usageGraphPanel,
        @NotNull BillingManager billing
    ) {
        super(new BorderLayout());

        JPanel timerSection = new JPanel(new BorderLayout());
        timerSection.setBorder(BorderFactory.createEmptyBorder(
            JBUI.scale(8), JBUI.scale(8), JBUI.scale(4), JBUI.scale(8)));
        timerSection.setOpaque(false);
        timerSection.add(timerPanel, BorderLayout.WEST);

        JPanel graphSection = new JPanel(new BorderLayout());
        graphSection.setBorder(BorderFactory.createEmptyBorder(
            JBUI.scale(4), JBUI.scale(8), JBUI.scale(4), JBUI.scale(8)));
        graphSection.setOpaque(false);
        usageGraphPanel.setPreferredSize(new Dimension(JBUI.scale(200), JBUI.scale(120)));
        graphSection.add(usageGraphPanel, BorderLayout.CENTER);

        JPanel billingSection = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        billingSection.setBorder(BorderFactory.createEmptyBorder(
            JBUI.scale(4), JBUI.scale(8), JBUI.scale(8), JBUI.scale(8)));
        billingSection.setOpaque(false);
        JLabel usageLabel = billing.getUsageLabel();
        JLabel costLabel = billing.getCostLabel();
        // Cap each label to a single line of text so long billing strings don't wrap and
        // push the graph out of view.
        int lineH = JBUI.scale(20);
        usageLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, lineH));
        costLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, lineH));
        billingSection.add(usageLabel, BorderLayout.NORTH);
        billingSection.add(costLabel, BorderLayout.SOUTH);

        JPanel content = new JPanel(new BorderLayout());
        content.setOpaque(false);
        content.add(timerSection, BorderLayout.NORTH);
        content.add(graphSection, BorderLayout.CENTER);
        content.add(billingSection, BorderLayout.SOUTH);

        add(content, BorderLayout.CENTER);
    }
}
