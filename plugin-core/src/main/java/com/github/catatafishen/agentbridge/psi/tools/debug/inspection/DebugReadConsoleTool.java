package com.github.catatafishen.agentbridge.psi.tools.debug.inspection;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.tools.debug.DebugTool;
import com.google.gson.JsonObject;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XDebugSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.concurrent.atomic.AtomicReference;

public final class DebugReadConsoleTool extends DebugTool {

    public DebugReadConsoleTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "debug_read_console";
    }

    @Override
    public @NotNull String displayName() {
        return "Read Debug Console";
    }

    @Override
    public @NotNull String description() {
        return "Read stdout/stderr output from the active debug session's console (the Debug tool window Console tab)";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.READ;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
                {"max_chars", TYPE_INTEGER, "Maximum characters to return (default: 8000)"},
        });
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        XDebugSession session = requireSession();
        int maxChars = args.has("max_chars") ? args.get("max_chars").getAsInt() : 8000;
        String sessionName = session.getSessionName();

        // First try getConsoleView() directly from the session
        ConsoleView consoleView = session.getConsoleView();
        if (consoleView != null) {
            String text = readConsoleText(consoleView);
            if (text != null && !text.isBlank()) {
                if (text.length() > maxChars) text = "...(truncated)\n" + text.substring(text.length() - maxChars);
                return "=== Debug Console: " + sessionName + " ===\n" + text;
            }
        }

        // Fallback: search RunContentManager
        var textRef = new AtomicReference<String>();
        EdtUtil.invokeAndWait(() -> {
            RunContentManager rcm = RunContentManager.getInstance(project);
            for (RunContentDescriptor descriptor : rcm.getAllDescriptors()) {
                if (sessionName.equals(descriptor.getDisplayName())) {
                    var console = descriptor.getExecutionConsole();
                    if (console instanceof ConsoleView cv) {
                        textRef.set(readConsoleText(cv));
                    }
                    return;
                }
            }
        });

        String text = textRef.get();
        if (text == null || text.isBlank()) {
            return "Debug console for '" + sessionName + "' is empty or could not be read.";
        }
        if (text.length() > maxChars) text = "...(truncated)\n" + text.substring(text.length() - maxChars);
        return "=== Debug Console: " + sessionName + " ===\n" + text;
    }

    @Nullable
    private String readConsoleText(@NotNull ConsoleView cv) {
        // Try to get the underlying editor document (ConsoleViewImpl wraps an Editor)
        try {
            var method = cv.getClass().getMethod("getEditor");
            Object editorObj = method.invoke(cv);
            if (editorObj instanceof Editor editor) {
                return editor.getDocument().getText();
            }
        } catch (Exception ignored) {
            // fall through to component search
        }
        return extractTextFromComponent(cv.getComponent());
    }

    @Nullable
    private String extractTextFromComponent(@Nullable Component component) {
        if (component == null) return null;
        if (component instanceof javax.swing.text.JTextComponent tc) return tc.getText();
        if (component instanceof Container c) {
            for (Component child : c.getComponents()) {
                String text = extractTextFromComponent(child);
                if (text != null && !text.isBlank()) return text;
            }
        }
        return null;
    }
}
