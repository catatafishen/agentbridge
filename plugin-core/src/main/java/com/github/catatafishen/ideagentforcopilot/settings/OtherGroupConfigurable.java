package com.github.catatafishen.ideagentforcopilot.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * Group node: Settings → Tools → IDE Agent for Copilot → Other.
 * Contains child pages for Scratch File Types and Project Files.
 */
public final class OtherGroupConfigurable implements Configurable {

    public static final String ID = "com.github.catatafishen.ideagentforcopilot.other";

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Other";
    }

    @Override
    public @NotNull JComponent createComponent() {
        JPanel panel = FormBuilder.createFormBuilder()
            .addComponent(new JBLabel(
                "<html>"
                    + "<b>Additional Settings</b><br><br>"
                    + "Miscellaneous plugin configuration:<br><br>"
                    + "&#8226; <b>Scratch File Types</b> — language dropdown and alias mappings for scratch files<br>"
                    + "&#8226; <b>Project Files</b> — file shortcuts in the toolbar dropdown<br>"
                    + "&#8226; <b>Chat History</b> — manage stored conversation files"
                    + "</html>"))
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
        // group page — no settings
    }
}
