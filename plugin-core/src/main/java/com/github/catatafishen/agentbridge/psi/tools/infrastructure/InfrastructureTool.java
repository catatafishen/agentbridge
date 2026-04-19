package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Abstract base for infrastructure tools. Provides shared console-reading
 * utilities used by {@link ReadRunOutputTool} and {@link ReadBuildOutputTool}.
 */
public abstract class InfrastructureTool extends Tool {

    private static final Logger LOG = Logger.getInstance(InfrastructureTool.class);
    private static final String METHOD_GET_CONSOLE = "getConsole";

    protected InfrastructureTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.INFRASTRUCTURE;
    }

    // ── Shared console helpers ────────────────────────────────

    /**
     * Flush deferred console output and extract text. Must be called on EDT.
     */
    protected String readConsoleTextOnEdt(com.intellij.execution.ui.ExecutionConsole console) {
        var unwrapped = unwrapConsoleDelegate(console);
        flushConsoleOutput(unwrapped);
        return extractConsoleText(unwrapped);
    }

    /**
     * Unwrap console wrappers to get the underlying ConsoleView.
     * Handles ConsoleViewWithDelegate (IntelliJ Ultimate profiler widgets)
     * and getConsole()-style wrappers (Node.js, Python, etc.).
     * <p>
     * Test runner consoles (SMTRunnerConsoleView) are intentionally NOT unwrapped —
     * they expose getResultsViewer() which extractConsoleText() uses to read structured
     * test results. Unwrapping them would lose access to the results tree.
     */
    private com.intellij.execution.ui.ExecutionConsole unwrapConsoleDelegate(
        com.intellij.execution.ui.ExecutionConsole console) {
        try {
            console.getClass().getMethod("getResultsViewer");
            return console;
        } catch (NoSuchMethodException ignored) {
            // Not a test runner console, proceed with unwrapping
        }

        if (console instanceof com.intellij.execution.ui.ConsoleViewWithDelegate wrapper) {
            return wrapper.getDelegate();
        }
        try {
            var getConsole = console.getClass().getMethod(METHOD_GET_CONSOLE);
            var inner = getConsole.invoke(console);
            if (inner instanceof com.intellij.execution.ui.ExecutionConsole innerConsole
                && inner != console) {
                return innerConsole;
            }
        } catch (NoSuchMethodException ignored) {
            // Not a wrapper with getConsole()
        } catch (Exception e) {
            LOG.debug("getConsole() unwrap failed", e);
        }
        return console;
    }

    /**
     * Flush deferred text from console buffers so getText() returns complete output.
     */
    protected void flushConsoleOutput(Object console) {
        if (console instanceof com.intellij.execution.impl.ConsoleViewImpl consoleView) {
            consoleView.flushDeferredText();
            return;
        }
        try {
            var flushMethod = console.getClass().getMethod("flushImmediately");
            flushMethod.invoke(console);
            return;
        } catch (NoSuchMethodException ignored) {
            // Not a terminal console
        } catch (Exception e) {
            LOG.debug("flushImmediately() failed", e);
        }
        try {
            var getConsole = console.getClass().getMethod(METHOD_GET_CONSOLE);
            var innerConsole = getConsole.invoke(console);
            if (innerConsole instanceof com.intellij.execution.impl.ConsoleViewImpl inner) {
                inner.flushDeferredText();
            }
        } catch (Exception ignored) {
            // Not all console types have an inner console
        }
    }

    protected String extractPlainConsoleText(Object console) {
        String text = tryExtractViaGetText(console);
        if (text != null) return text;
        text = tryExtractViaTerminalWidget(console);
        if (text != null) return text;
        text = tryExtractViaEditor(console);
        if (text != null) return text;
        return tryExtractViaComponentTree(console);
    }

    @Nullable
    private String tryExtractViaGetText(Object console) {
        try {
            var getTextMethod = console.getClass().getMethod("getText");
            String text = (String) getTextMethod.invoke(console);
            if (text != null && !text.isEmpty()) return text;
        } catch (NoSuchMethodException ignored) {
            // Method not available in this version
        } catch (Exception e) {
            LOG.warn("getText() failed", e);
        }
        return null;
    }

    @Nullable
    private String tryExtractViaTerminalWidget(Object console) {
        try {
            var getTerminalWidget = console.getClass().getMethod("getTerminalWidget");
            var widget = getTerminalWidget.invoke(console);
            if (widget != null) {
                var getText = widget.getClass().getMethod("getText");
                String text = (String) getText.invoke(widget);
                if (text != null && !text.isEmpty()) return text;
            }
        } catch (NoSuchMethodException ignored) {
            // Not a terminal console
        } catch (Exception e) {
            LOG.warn("Terminal widget getText() failed", e);
        }
        return null;
    }

    @Nullable
    private String tryExtractViaEditor(Object console) {
        try {
            var getEditorMethod = console.getClass().getMethod("getEditor");
            var editor = getEditorMethod.invoke(console);
            if (editor != null) {
                var getDocMethod = editor.getClass().getMethod("getDocument");
                var doc = getDocMethod.invoke(editor);
                if (doc instanceof Document document) {
                    return document.getText();
                }
            }
        } catch (Exception ignored) {
            // XML parsing or file access errors are non-fatal
        }
        return null;
    }

    @Nullable
    private String tryExtractViaComponentTree(Object console) {
        try {
            var getComponent = console.getClass().getMethod("getComponent");
            var component = getComponent.invoke(console);
            if (component instanceof java.awt.Container container) {
                String found = findConsoleTextInComponentTree(container);
                if (found != null && !found.isEmpty()) return found;
            }
        } catch (Exception ignored) {
            // Not a component-based console
        }
        return null;
    }

    @Nullable
    private String extractCurrentComponentText(java.awt.Component component) {
        if (component instanceof com.intellij.execution.impl.ConsoleViewImpl cv) {
            cv.flushDeferredText();
            return extractPlainConsoleText(cv);
        }
        return extractPlainConsoleText(component);
    }

    @Nullable
    private String searchChildrenForText(java.awt.Container container, int maxDepth) {
        for (var child : container.getComponents()) {
            String childText = findConsoleTextInComponentTree(child, maxDepth - 1);
            if (childText != null && !childText.isEmpty()) return childText;
        }
        return null;
    }

    /**
     * Recursively walk the Swing component tree to find a ConsoleViewImpl and read its text.
     */
    protected @Nullable String findConsoleTextInComponentTree(java.awt.Container container) {
        if (container instanceof com.intellij.execution.impl.ConsoleViewImpl cv) {
            cv.flushDeferredText();
            String text = cv.getText();
            if (!text.isEmpty()) return text;
        }
        for (int i = 0; i < container.getComponentCount(); i++) {
            var child = container.getComponent(i);
            if (child instanceof java.awt.Container c) {
                String result = findConsoleTextInComponentTree(c);
                if (result != null && !result.isEmpty()) return result;
            }
        }
        return null;
    }

    protected @Nullable String findConsoleTextInComponentTree(java.awt.Component component, int maxDepth) {
        if (maxDepth <= 0) return null;
        String text = extractCurrentComponentText(component);
        if (text != null && !text.isEmpty()) return text;
        if (component instanceof java.awt.Container container) {
            return searchChildrenForText(container, maxDepth);
        }
        return null;
    }

    /**
     * Formats build/run output for the MCP response.
     *
     * @param offset character offset to start reading from ({@code -1} = show last {@code maxChars} chars).
     *               Pass {@code 0} or a positive value to read from the beginning or a specific position.
     */
    protected String formatRunOutput(String displayName, String text, int maxChars, int offset) {
        StringBuilder result = new StringBuilder();
        result.append("Tab: ").append(displayName).append("\n");
        result.append("Total length: ").append(text.length()).append(" chars\n\n");

        if (offset < 0) {
            // Default: show the tail (most recent output)
            if (text.length() > maxChars) {
                result.append("...(truncated, showing last ").append(maxChars)
                    .append(" of ").append(text.length())
                    .append(" chars. Use offset=0 to read from beginning.)\n");
                result.append(text, text.length() - maxChars, text.length());
            } else {
                result.append(text);
            }
        } else {
            // Paginated read from a specific position
            int start = Math.min(offset, text.length());
            int end = Math.min(start + maxChars, text.length());
            if (start > 0) {
                result.append("...(showing chars ").append(start).append("-").append(end)
                    .append(" of ").append(text.length()).append(")\n");
            }
            result.append(text, start, end);
            if (end < text.length()) {
                result.append("\n...(").append(text.length() - end)
                    .append(" more chars. Use offset=").append(end).append(" to continue.)");
            }
        }
        return result.toString();
    }

    private String extractConsoleText(com.intellij.execution.ui.ExecutionConsole console) {
        try {
            var getResultsViewer = console.getClass().getMethod("getResultsViewer");
            var viewer = getResultsViewer.invoke(console);
            if (viewer != null) {
                return extractTestRunnerResults(viewer, console);
            }
        } catch (NoSuchMethodException ignored) {
            // Not an SMTRunnerConsoleView
        } catch (Exception e) {
            LOG.warn("Failed to extract test runner output", e);
        }
        return extractPlainConsoleText(console);
    }

    private String extractTestRunnerResults(Object viewer,
                                            com.intellij.execution.ui.ExecutionConsole console) throws ReflectiveOperationException {
        var getTestsRootNode = viewer.getClass().getMethod("getTestsRootNode");
        Object root = getTestsRootNode.invoke(viewer);
        if (root == null) {
            return "(No test results yet — the run may not have started)\n";
        }

        var getAllTests = root.getClass().getMethod("getAllTests");
        var tests = (List<?>) getAllTests.invoke(root);

        StringBuilder testOutput = new StringBuilder();
        if (tests != null && !tests.isEmpty()) {
            appendTestSummary(viewer, testOutput);
            testOutput.append("=== Test Results ===\n");
            for (var test : tests) {
                if (isLeafTest(test)) {
                    appendTestResult(test, testOutput);
                }
            }
        } else {
            testOutput.append(getTestRunProgressStatus(viewer));
        }
        appendTestConsoleOutput(console, testOutput);
        return testOutput.toString();
    }

    private boolean isLeafTest(Object test) {
        try {
            var isLeaf = test.getClass().getMethod("isLeaf");
            return (boolean) isLeaf.invoke(test);
        } catch (Exception ignored) {
            return true;
        }
    }

    private void appendTestSummary(Object viewer, StringBuilder out) {
        try {
            int total = (int) viewer.getClass().getMethod("getTotalTestCount").invoke(viewer);
            int failed = (int) viewer.getClass().getMethod("getFailedTestCount").invoke(viewer);
            int ignored = (int) viewer.getClass().getMethod("getIgnoredTestCount").invoke(viewer);
            int passed = total - failed - ignored;
            out.append("=== Summary: ").append(passed).append(" passed, ")
                .append(failed).append(" failed, ")
                .append(ignored).append(" ignored (").append(total).append(" total) ===\n");
        } catch (Exception ignored) {
            // Summary not available
        }
    }

    private String getTestRunProgressStatus(Object viewer) {
        try {
            var getRootNode = viewer.getClass().getMethod("getTestsRootNode");
            Object root = getRootNode.invoke(viewer);
            if (root != null && isTestNodeInProgress(root)) {
                return "(Test run in progress — call read_run_output again after it finishes)\n";
            }
        } catch (Exception ignored) {
            // Best-effort status check
        }
        return "(No test results yet — the run may not have started)\n";
    }

    private boolean isTestNodeInProgress(Object testNode) {
        try {
            var isInProgress = testNode.getClass().getMethod("isInProgress");
            return (boolean) isInProgress.invoke(testNode);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void appendTestResult(Object test, StringBuilder testOutput) throws ReflectiveOperationException {
        var getName = test.getClass().getMethod("getPresentableName");
        var isPassed = test.getClass().getMethod("isPassed");
        var isDefect = test.getClass().getMethod("isDefect");
        String name = (String) getName.invoke(test);
        boolean passed = (boolean) isPassed.invoke(test);
        boolean defect = (boolean) isDefect.invoke(test);
        String status;
        if (passed) {
            status = "PASSED";
        } else if (defect) {
            status = "FAILED";
        } else {
            status = "UNKNOWN";
        }
        testOutput.append("  ").append(status).append(" ").append(name).append("\n");

        if (defect) {
            appendTestErrorDetails(test, testOutput);
        }
    }

    private void appendTestErrorDetails(Object test, StringBuilder testOutput) {
        try {
            var getErrorMessage = test.getClass().getMethod("getErrorMessage");
            String errorMsg = (String) getErrorMessage.invoke(test);
            if (errorMsg != null && !errorMsg.isEmpty()) {
                testOutput.append("    Error: ").append(errorMsg).append("\n");
            }
            var getStacktrace = test.getClass().getMethod("getStacktrace");
            String stacktrace = (String) getStacktrace.invoke(test);
            if (stacktrace != null && !stacktrace.isEmpty()) {
                testOutput.append("    Stacktrace:\n").append(stacktrace).append("\n");
            }
        } catch (NoSuchMethodException ignored) {
            // Method not available on this test result type
        } catch (Exception e) {
            LOG.debug("Failed to get test error details", e);
        }
    }

    private void appendTestConsoleOutput(Object console, StringBuilder testOutput) {
        try {
            var getConsole = console.getClass().getMethod(METHOD_GET_CONSOLE);
            var innerConsole = getConsole.invoke(console);
            if (innerConsole != null) {
                String consoleText = extractPlainConsoleText(innerConsole);
                if (consoleText != null && !consoleText.isEmpty()) {
                    testOutput.append("\n=== Console Output ===\n").append(consoleText);
                }
            }
        } catch (NoSuchMethodException ignored) {
            // Method not available in this version
        } catch (Exception e) {
            LOG.debug("Failed to get test console output", e);
        }
    }
}
