package com.github.catatafishen.agentbridge.ui.side;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import javax.swing.*;

/**
 * Opens an IntelliJ diff popup comparing the original (pre-hook) and modified (post-hook)
 * arguments for an MCP tool call.
 * <p>
 * Both sides are JSON-pretty-printed before display, so the diff highlights meaningful
 * field-level differences rather than whitespace.
 */
final class ToolCallInputDiffViewer {

    private ToolCallInputDiffViewer() {}

    static void showDiff(@NotNull Project project,
                         @NotNull String originalJson,
                         @NotNull String modifiedJson,
                         @NotNull String toolName) {
        String prettyOriginal = prettify(originalJson);
        String prettyModified = prettify(modifiedJson);

        var disposable = Disposer.newDisposable("ToolCallInputDiff");
        DiffRequestPanel panel = DiffManager.getInstance().createRequestPanel(project, disposable, null);

        DocumentContent left = DiffContentFactory.getInstance()
            .create(project, prettyOriginal, PlainTextFileType.INSTANCE);
        DocumentContent right = DiffContentFactory.getInstance()
            .create(project, prettyModified, PlainTextFileType.INSTANCE);

        SimpleDiffRequest request = new SimpleDiffRequest(
            "Hook input modification \u2014 " + toolName, left, right, "Before hook", "After hook");
        panel.setRequest(request);

        JComponent component = panel.getComponent();
        component.setPreferredSize(new Dimension(900, 400));

        JBPopup popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(component, component)
            .setTitle("Hook input modification \u2014 " + toolName)
            .setMovable(true)
            .setResizable(true)
            .setFocusable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(false)
            .createPopup();
        Disposer.register(popup, disposable);
        popup.showCenteredInCurrentWindow(project);
    }

    private static String prettify(String json) {
        try {
            var parsed = JsonParser.parseString(json);
            return new GsonBuilder().setPrettyPrinting().create().toJson(parsed);
        } catch (JsonSyntaxException e) {
            return json;
        }
    }
}
