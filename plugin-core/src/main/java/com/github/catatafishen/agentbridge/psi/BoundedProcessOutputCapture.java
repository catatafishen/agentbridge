package com.github.catatafishen.agentbridge.psi;

import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;

/**
 * Retains a bounded tail of a process's stdout and stderr for tool-result fallbacks.
 */
public final class BoundedProcessOutputCapture implements ProcessListener {

    private static final int MAX_CHARS = 12_000;
    private static final String TRUNCATED_PREFIX = "[Earlier process output omitted]\n";

    private final StringBuilder output = new StringBuilder();
    private boolean truncated;

    @Override
    public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
        append(event.getText());
    }

    public synchronized void append(@NotNull String text) {
        output.append(text);
        int excess = output.length() - MAX_CHARS;
        if (excess > 0) {
            output.delete(0, excess);
            truncated = true;
        }
    }

    public synchronized @NotNull String content() {
        if (output.isEmpty()) return "";
        return truncated ? TRUNCATED_PREFIX + output : output.toString();
    }
}
