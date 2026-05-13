package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.github.catatafishen.agentbridge.ui.renderers.SimpleStatusRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Queries or interacts with a modal dialog that is blocking IDE operations.
 *
 * <p>Designed to be called when another tool fails because a modal dialog has appeared.
 * Unlike most tools this one is <em>read-only</em> (bypasses the write semaphore) so it
 * can execute concurrently with a tool that is blocked waiting for a lock it cannot
 * acquire because the EDT is stuck in the modal's nested event loop.</p>
 *
 * <p>Button clicks are dispatched with {@link ModalityState#any()} so they run inside
 * the modal's own nested event loop — the only modality state that is permitted during
 * an open dialog.</p>
 */
public final class InteractWithModalTool extends InfrastructureTool {

    private static final int CLICK_TIMEOUT_SECONDS = 5;
    private static final String PARAM_BUTTON = "button";
    private static final String PARAM_TEXT_INPUT = "text_input";

    public InteractWithModalTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "interact_with_modal";
    }

    @Override
    public @NotNull String displayName() {
        return "Interact With Modal";
    }

    @Override
    public @NotNull String description() {
        return "Query or respond to a modal dialog that is blocking IDE operations. "
            + "With no arguments, returns the dialog title, message text, available buttons, "
            + "and current text-field contents. "
            + "With a 'button' argument, clicks the named button to dismiss the dialog "
            + "so the blocked tool call can continue. "
            + "With a 'text_input' argument, sets the text in the first editable text field "
            + "of the dialog (e.g. a commit message editor) — combine with 'button' to set "
            + "the text and confirm in one call.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.OTHER;
    }

    /**
     * Read-only so that the write semaphore in PsiBridgeService is not required.
     * This allows the tool to execute even when another tool holds the semaphore
     * while blocked on the modal.
     */
    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional(PARAM_BUTTON, TYPE_STRING, "The button text to click (e.g. 'OK', 'Cancel', 'Yes', 'No'). "
                + "Omit to inspect the dialog without interacting with it."),
            Param.optional(PARAM_TEXT_INPUT, TYPE_STRING, "Text to set in the first editable text field or text area "
                + "of the dialog (e.g. a commit message). Can be combined with 'button' to set text and confirm in one call.")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SimpleStatusRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        Dialog dialog = findTopmostModal();
        if (dialog == null) {
            return "No modal dialog is currently visible.";
        }

        boolean hasButton = args.has(PARAM_BUTTON) && !args.get(PARAM_BUTTON).isJsonNull()
            && !args.get(PARAM_BUTTON).getAsString().isBlank();
        boolean hasTextInput = args.has(PARAM_TEXT_INPUT) && !args.get(PARAM_TEXT_INPUT).isJsonNull()
            && !args.get(PARAM_TEXT_INPUT).getAsString().isBlank();

        if (!hasButton && !hasTextInput) {
            return describeDialog(dialog);
        }

        if (hasTextInput) {
            String setError = setTextInDialog(dialog, args.get(PARAM_TEXT_INPUT).getAsString());
            if (setError != null) return setError;
            if (!hasButton) return "Set text field to: " + args.get(PARAM_TEXT_INPUT).getAsString();
        }

        return clickDialogButton(dialog, args.get(PARAM_BUTTON).getAsString());
    }

    // ── Private helpers ──────────────────────────────────────

    @Nullable
    private static Dialog findTopmostModal() {
        Dialog topmost = null;
        for (Window w : Window.getWindows()) {
            if (w instanceof Dialog d && d.isModal() && d.isVisible()) {
                topmost = d; // last visible modal wins (topmost in z-order)
            }
        }
        return topmost;
    }

    private static String describeDialog(Dialog dialog) {
        StringBuilder sb = new StringBuilder();
        String title = dialog.getTitle();
        sb.append("Modal dialog: '").append(title != null && !title.isBlank() ? title : "(untitled)").append("'\n");

        List<String> labels = new ArrayList<>();
        List<String> textFields = new ArrayList<>();
        List<String> radioButtons = new ArrayList<>();
        List<String> checkBoxes = new ArrayList<>();
        List<String> buttons = new ArrayList<>();
        collectComponents(dialog, labels, textFields, radioButtons, checkBoxes, buttons);

        if (!labels.isEmpty()) {
            sb.append("Message: ").append(String.join(" ", labels)).append("\n");
        }
        if (!textFields.isEmpty()) {
            sb.append("Text fields: ").append(textFields).append("\n");
        }
        if (!radioButtons.isEmpty()) {
            sb.append("Radio buttons: ").append(radioButtons).append("\n");
        }
        if (!checkBoxes.isEmpty()) {
            sb.append("Checkboxes: ").append(checkBoxes).append("\n");
        }
        if (!buttons.isEmpty()) {
            sb.append("Buttons: ").append(buttons).append("\n");
        }
        sb.append("Use interact_with_modal with button=<text> to click a button");
        if (!textFields.isEmpty()) {
            sb.append(", or text_input=<value> to set the text field");
        }
        sb.append(".");
        return sb.toString();
    }

    @Nullable
    private static String setTextInDialog(Dialog dialog, String text) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        ApplicationManager.getApplication().invokeLater(() ->
                future.complete(doSetText(dialog, text)),
            ModalityState.any());
        try {
            boolean found = future.get(CLICK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return found ? null : "Error: no editable text field found in dialog.";
        } catch (TimeoutException e) {
            return "Error: timed out waiting to set text field.";
        } catch (ExecutionException e) {
            return "Error setting text: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted while setting text field.";
        }
    }

    private static boolean doSetText(Container container, String text) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JTextArea ta && ta.isEditable()) {
                ta.setText(text);
                ta.requestFocusInWindow();
                return true;
            }
            if (comp instanceof JTextField tf && tf.isEditable()) {
                tf.setText(text);
                tf.requestFocusInWindow();
                return true;
            }
            if (comp instanceof Container c && doSetText(c, text)) {
                return true;
            }
        }
        return false;
    }

    private static String clickDialogButton(Dialog dialog, String buttonText) {
        // Must click on EDT using ModalityState.any() — defaultModalityState() is blocked by the modal.
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        ApplicationManager.getApplication().invokeLater(() ->
                future.complete(doClick(dialog, buttonText)),
            ModalityState.any());
        String notFound = "Button '" + buttonText + "' not found. Available buttons: " + collectButtons(dialog) + ".";
        return awaitBooleanFuture(future,
            "Clicked button '" + buttonText + "' on dialog '"
                + (dialog.getTitle() != null ? dialog.getTitle() : "(untitled)") + "'.",
            notFound,
            "Timed out waiting to click button '" + buttonText + "'.",
            "Interrupted while clicking button '" + buttonText + "'.");
    }

    /**
     * Waits for a boolean future submitted to the EDT with {@link ModalityState#any()}.
     * Returns {@code successMsg} on {@code true}, {@code missingMsg} on {@code false},
     * or an error string on timeout / execution error / interrupt.
     */
    private static String awaitBooleanFuture(CompletableFuture<Boolean> future,
                                             String successMsg, String missingMsg,
                                             String timeoutMsg, String interruptedMsg) {
        try {
            boolean found = future.get(CLICK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return found ? successMsg : missingMsg;
        } catch (TimeoutException e) {
            return timeoutMsg;
        } catch (ExecutionException e) {
            return "Error: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return interruptedMsg;
        }
    }

    private static boolean doClick(Container container, String buttonText) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JButton btn && btn.isEnabled()
                && btn.getText() != null && buttonText.equalsIgnoreCase(btn.getText())) {
                btn.doClick();
                return true;
            }
            if (comp instanceof Container c && doClick(c, buttonText)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> collectButtons(Container container) {
        List<String> result = new ArrayList<>();
        collectButtons(container, result);
        return result;
    }

    private static void collectButtons(Container container, List<String> result) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JButton btn && btn.isEnabled() && btn.getText() != null && !btn.getText().isBlank()) {
                result.add(btn.getText());
            }
            if (comp instanceof Container c) {
                collectButtons(c, result);
            }
        }
    }

    private static void collectComponents(Container container,
                                          List<String> labels, List<String> textFields,
                                          List<String> radioButtons,
                                          List<String> checkBoxes, List<String> buttons) {
        for (Component comp : container.getComponents()) {
            classifyComponent(comp, labels, textFields, radioButtons, checkBoxes, buttons);
            if (comp instanceof Container c) {
                collectComponents(c, labels, textFields, radioButtons, checkBoxes, buttons);
            }
        }
    }

    private static void classifyComponent(Component comp,
                                          List<String> labels, List<String> textFields,
                                          List<String> radioButtons, List<String> checkBoxes, List<String> buttons) {
        if (comp instanceof JRadioButton rb && rb.getText() != null && !rb.getText().isBlank()) {
            radioButtons.add(rb.getText());
        } else if (comp instanceof JCheckBox cb && cb.getText() != null && !cb.getText().isBlank()) {
            checkBoxes.add(cb.getText());
        } else if (comp instanceof JButton btn && btn.getText() != null && !btn.getText().isBlank()) {
            buttons.add(btn.getText());
        } else if (comp instanceof JLabel lbl && lbl.getText() != null && !lbl.getText().isBlank() && lbl.getIcon() == null) {
            labels.add(lbl.getText());
        } else if (comp instanceof JTextArea ta && ta.isEditable()) {
            textFields.add(describeTextContent("text area", ta.getText()));
        } else if (comp instanceof JTextField tf && tf.isEditable()) {
            textFields.add(describeTextContent("text field", tf.getText()));
        }
    }

    private static String describeTextContent(String type, String content) {
        return "(" + type + "): " + (content.isBlank() ? "(empty)" : content.replace('\n', '↵'));
    }
}
