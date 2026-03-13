package com.github.catatafishen.ideagentforcopilot.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public final class BillingConfigurable implements Configurable {

    public static final String ID = "com.github.catatafishen.ideagentforcopilot.billing";

    private JBCheckBox showCopilotUsageCb;
    private BillingSettings settings;
    private JPanel mainPanel;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Billing Data";
    }

    @Override
    public @NotNull JComponent createComponent() {
        settings = BillingSettings.getInstance();

        showCopilotUsageCb = new JBCheckBox("Show Copilot usage graph in toolbar");

        mainPanel = FormBuilder.createFormBuilder()
            .addComponent(new JBLabel(
                "<html>Configure how billing and usage data is displayed in the IDE.</html>"))
            .addSeparator()
            .addComponent(showCopilotUsageCb)
            .addTooltip("Shows a usage graph icon in the main toolbar when Copilot usage data is available.")
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        mainPanel.setBorder(JBUI.Borders.empty(8));

        reset();
        return mainPanel;
    }

    @Override
    public boolean isModified() {
        return showCopilotUsageCb.isSelected() != settings.isShowCopilotUsage();
    }

    @Override
    public void apply() {
        settings.setShowCopilotUsage(showCopilotUsageCb.isSelected());
    }

    @Override
    public void reset() {
        showCopilotUsageCb.setSelected(settings.isShowCopilotUsage());
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        showCopilotUsageCb = null;
    }
}
