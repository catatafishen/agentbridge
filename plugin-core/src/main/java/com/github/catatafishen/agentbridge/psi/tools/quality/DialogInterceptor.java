package com.github.catatafishen.agentbridge.psi.tools.quality;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Intercepts modal dialogs that refactoring actions may show during {@code invoke()}.
 *
 * <p>Uses an {@link AWTEventListener} to receive {@code WINDOW_OPENED} events on the EDT.
 * When a modal dialog opens inside a write-command action, AWT fires {@code WINDOW_OPENED}
 * in the nested modal event loop — our listener captures the dialog's options and either
 * disposes it (capture mode) or selects the requested option and clicks OK (selection mode).</p>
 *
 * <p>All methods must be called from the EDT.</p>
 */
final class DialogInterceptor {

    /** Snapshot of the options exposed by a dialog. */
    record DialogInfo(
        List<String> radioButtons,
        List<String> checkBoxes,
        List<String> textInputs,
        List<String> buttons
    ) {
        boolean isEmpty() {
            return radioButtons.isEmpty() && checkBoxes.isEmpty()
                && textInputs.isEmpty() && buttons.isEmpty();
        }
    }

    private DialogInterceptor() {
    }

    /**
     * Runs {@code action}, intercepts the first modal dialog that opens, extracts its options,
     * and disposes it (so no changes are committed). Returns {@code null} if no dialog appeared.
     */
    @Nullable
    static DialogInfo runAndCapture(@NotNull Runnable action) {
        AtomicReference<DialogInfo> captured = new AtomicReference<>();
        AWTEventListener listener = event -> {
            if (event instanceof WindowEvent we && we.getID() == WindowEvent.WINDOW_OPENED
                && we.getWindow() instanceof Dialog d
                && captured.get() == null) {
                captured.set(extractInfo(d));
                d.dispose();
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.WINDOW_EVENT_MASK);
        try {
            action.run();
        } finally {
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
        }
        return captured.get();
    }

    /**
     * Runs {@code action} and, when a dialog opens, finds the component whose text matches
     * {@code optionText} (radio button or checkbox), selects it, and clicks the confirm button
     * (OK / Refactor / Apply / Run). Returns {@code true} if the option was found and selected.
     */
    static boolean runAndSelectOption(@NotNull Runnable action, @NotNull String optionText) {
        AtomicBoolean found = new AtomicBoolean(false);
        AWTEventListener listener = event -> {
            if (event instanceof WindowEvent we && we.getID() == WindowEvent.WINDOW_OPENED
                && we.getWindow() instanceof Dialog d) {
                boolean selected = selectOption(d, optionText);
                found.set(selected);
                if (selected) {
                    clickConfirmButton(d);
                } else {
                    d.dispose();
                }
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.WINDOW_EVENT_MASK);
        try {
            action.run();
        } finally {
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
        }
        return found.get();
    }

    // ── Private helpers ──────────────────────────────────────

    private static DialogInfo extractInfo(Dialog dialog) {
        List<String> radios = new ArrayList<>();
        List<String> checks = new ArrayList<>();
        List<String> inputs = new ArrayList<>();
        List<String> buttons = new ArrayList<>();
        collectComponents(dialog, radios, checks, inputs, buttons);
        return new DialogInfo(radios, checks, inputs, buttons);
    }

    private static void collectComponents(Container container,
                                          List<String> radios, List<String> checks,
                                          List<String> inputs, List<String> buttons) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JRadioButton rb && !rb.getText().isBlank()) {
                radios.add(rb.getText());
            } else if (comp instanceof JCheckBox cb && !cb.getText().isBlank()) {
                checks.add(cb.getText());
            } else if (comp instanceof JButton btn && !btn.getText().isBlank()) {
                buttons.add(btn.getText());
            } else if (comp instanceof JTextField tf && !tf.getText().isBlank()) {
                inputs.add(tf.getText());
            } else if (comp instanceof JTextArea ta && !ta.getText().isBlank()) {
                inputs.add(ta.getText());
            }
            if (comp instanceof Container c) {
                collectComponents(c, radios, checks, inputs, buttons);
            }
        }
    }

    /**
     * Selects the first radio button or checkbox whose text equals {@code optionText}
     * (case-insensitive) anywhere in the component tree. Returns {@code true} on success.
     */
    private static boolean selectOption(Container container, String optionText) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JRadioButton rb && optionText.equalsIgnoreCase(rb.getText())) {
                rb.doClick();
                return true;
            }
            if (comp instanceof JCheckBox cb && optionText.equalsIgnoreCase(cb.getText())) {
                cb.doClick();
                return true;
            }
            if (comp instanceof Container c && selectOption(c, optionText)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds and clicks the primary confirm button (OK / Refactor / Apply / Run / Yes).
     * Skips disabled buttons.
     */
    private static void clickConfirmButton(Container container) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JButton btn && btn.isEnabled()) {
                String text = btn.getText();
                if ("OK".equalsIgnoreCase(text) || "Refactor".equalsIgnoreCase(text)
                    || "Apply".equalsIgnoreCase(text) || "Run".equalsIgnoreCase(text)
                    || "Yes".equalsIgnoreCase(text)) {
                    btn.doClick();
                    return;
                }
            }
            if (comp instanceof Container c) {
                clickConfirmButton(c);
            }
        }
    }
}
