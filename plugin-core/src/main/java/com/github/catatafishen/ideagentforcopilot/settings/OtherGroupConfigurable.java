package com.github.catatafishen.ideagentforcopilot.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

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
    public @Nullable JComponent createComponent() {
        return FormBuilder.createFormBuilder()
            .addComponent(new JBLabel(
                "<html>"
                    + "<b>Additional Settings</b><br><br>"
                    + "Miscellaneous plugin configuration:<br><br>"
                    + "• <b>Scratch File Types</b> — language dropdown and alias mappings for scratch files<br>"
                    + "• <b>Project Files</b> — file shortcuts in the toolbar dropdown"
                    + "</html>"))
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
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
