package com.github.catatafishen.ideagentforcopilot.psi;

import com.google.gson.JsonObject;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles infrastructure tool calls: http_request, run_command, read_ide_log,
 * get_notifications, read_run_output, read_build_output.
 */
@SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
public final class InfrastructureTools extends AbstractToolHandler {
    private static final Logger LOG = Logger.getInstance(InfrastructureTools.class);
    private static final String PARAM_OFFSET = "offset";

    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";
    private static final String ERROR_NO_PROJECT_PATH = "No project base path";
    private static final String PARAM_TIMEOUT = "timeout";
    private static final String PARAM_LEVEL = "level";
    private static final String PARAM_METHOD = "method";
    private static final String JSON_HEADERS = "headers";
    private static final String JSON_TITLE = "title";
    private static final String JSON_TAB_NAME = "tab_name";
    private static final String OS_NAME_PROPERTY = "os.name";
    private static final String PARAM_MAX_CHARS = "max_chars";
    private static final String JAVA_HOME_ENV = "JAVA_HOME";
    private static final String IDEA_LOG_FILENAME = "idea.log";
    private static final String METHOD_GET_CONSOLE = "getConsole";

    InfrastructureTools(Project project) {
        super(project);
    }

    public String httpRequest(JsonObject args) throws Exception {
        String urlStr = args.get("url").getAsString();
        String method = args.has(PARAM_METHOD) ? args.get(PARAM_METHOD).getAsString().toUpperCase() : "GET";
        String body = args.has("body") ? args.get("body").getAsString() : null;

        URL url = URI.create(urlStr).toURL();
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);

        // Set headers
        if (args.has(JSON_HEADERS)) {
            JsonObject headers = args.getAsJsonObject(JSON_HEADERS);
            for (String key : headers.keySet()) {
                conn.setRequestProperty(key, headers.get(key).getAsString());
            }
        }

        // Write body
        if (body != null && ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))) {
            if (!args.has(JSON_HEADERS) || !args.getAsJsonObject(JSON_HEADERS).has(CONTENT_TYPE_HEADER)) {
                conn.setRequestProperty(CONTENT_TYPE_HEADER, APPLICATION_JSON);
            }
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }

        int status = conn.getResponseCode();
        StringBuilder result = new StringBuilder();
        result.append("HTTP ").append(status).append(" ").append(conn.getResponseMessage()).append("\n");

        // Response headers
        result.append("\n--- Headers ---\n");
        conn.getHeaderFields().forEach((k, v) -> {
            if (k != null) result.append(k).append(": ").append(String.join(", ", v)).append("\n");
        });

        // Response body
        result.append("\n--- Body ---\n");
        try (InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
            if (is != null) {
                String responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                result.append(ToolUtils.truncateOutput(responseBody));
            }
        }
        return result.toString();
    }

    public String runCommand(JsonObject args) throws Exception {
        String command = args.get("command").getAsString();
        // Block abusive commands — MCP tools bypass the ACP permission system
        String abuseType = ToolUtils.detectCommandAbuseType(command);
        if (abuseType != null) return ToolUtils.getCommandAbuseMessage(abuseType);

        // Flush all editor buffers to disk so CLI tools see current content
        EdtUtil.invokeAndWait(() ->
            com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction(() ->
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()));

        String title = args.has(JSON_TITLE) ? args.get(JSON_TITLE).getAsString() : null;
        String basePath = project.getBasePath();
        if (basePath == null) return ERROR_NO_PROJECT_PATH;
        int timeoutSec = args.has(PARAM_TIMEOUT) ? args.get(PARAM_TIMEOUT).getAsInt() : 60;
        int offset = args.has(PARAM_OFFSET) ? args.get(PARAM_OFFSET).getAsInt() : 0;
        int maxChars = args.has(PARAM_MAX_CHARS) ? args.get(PARAM_MAX_CHARS).getAsInt() : 8000;
        String tabTitle = title != null ? title : "Command: " + truncateForTitle(command);

        GeneralCommandLine cmd;
        if (System.getProperty(OS_NAME_PROPERTY).contains("Win")) {
            cmd = new GeneralCommandLine("cmd", "/c", command);
        } else {
            cmd = new GeneralCommandLine("sh", "-c", command);
        }
        cmd.setWorkDirectory(basePath);

        // Set JAVA_HOME from project SDK if available
        String javaHome = getProjectJavaHome();
        if (javaHome != null) {
            cmd.withEnvironment(JAVA_HOME_ENV, javaHome);
        }

        ProcessResult result = executeInRunPanel(cmd, tabTitle, timeoutSec);

        String fullOutput = result.output();
        if (result.timedOut()) {
            return "Command timed out after " + timeoutSec + " seconds.\n\n"
                + ToolUtils.truncateOutput(fullOutput, maxChars, offset);
        }

        boolean failed = result.exitCode() != 0;
        // On failure with no explicit offset, show the tail so stack traces / errors are visible
        int effectiveOffset = offset;
        if (failed && !args.has(PARAM_OFFSET) && fullOutput.length() > maxChars) {
            effectiveOffset = fullOutput.length() - maxChars;
        }
        String header = failed
            ? "Command failed (exit code " + result.exitCode() + ")"
            : "Command succeeded";
        if (failed && effectiveOffset > 0) {
            header += "\n(showing last " + maxChars + " chars — use offset=0 for beginning)";
        }
        return header + "\n\n" + ToolUtils.truncateOutput(fullOutput, maxChars, effectiveOffset);
    }

    private static String truncateForTitle(String command) {
        return command.length() > 40 ? command.substring(0, 37) + "..." : command;
    }

    public String readIdeLog(JsonObject args) throws IOException {
        int lines = args.has("lines") ? args.get("lines").getAsInt() : 50;
        String filter = args.has("filter") ? args.get("filter").getAsString() : null;
        String level = args.has(PARAM_LEVEL) ? args.get(PARAM_LEVEL).getAsString().toUpperCase() : null;

        Path logFile = Path.of(System.getProperty("idea.log.path", ""), IDEA_LOG_FILENAME);
        if (!Files.exists(logFile)) {
            // Try standard location
            String logDir = System.getProperty("idea.system.path");
            if (logDir != null) {
                logFile = Path.of(logDir, "..", "log", IDEA_LOG_FILENAME);
            }
        }
        if (!Files.exists(logFile)) {
            // Try via PathManager
            try {
                Class<?> pm = Class.forName("com.intellij.openapi.application.PathManager");
                String logPath = (String) pm.getMethod("getLogPath").invoke(null);
                logFile = Path.of(logPath, IDEA_LOG_FILENAME);
            } catch (Exception ignored) {
                // PathManager not available or reflection failed
            }
        }
        if (!Files.exists(logFile)) {
            return "Could not locate idea.log";
        }

        List<String> filtered = Files.readAllLines(logFile);

        if (level != null) {
            final String lvl = level;
            filtered = filtered.stream()
                .filter(l -> l.contains(lvl))
                .toList();
        }
        if (filter != null) {
            final String f = filter;
            filtered = filtered.stream()
                .filter(l -> l.contains(f))
                .toList();
        }

        int start = Math.max(0, filtered.size() - lines);
        List<String> result = filtered.subList(start, filtered.size());
        return String.join("\n", result);
    }

    @SuppressWarnings("unused") // ToolHandler interface requires JsonObject parameter
    public String getNotifications(JsonObject args) {
        StringBuilder result = new StringBuilder();
        try {
            var notifications = com.intellij.notification.NotificationsManager.getNotificationsManager()
                .getNotificationsOfType(com.intellij.notification.Notification.class, project);
            if (notifications.length == 0) {
                return "No recent notifications.";
            }
            for (var notification : notifications) {
                result.append("[").append(notification.getType()).append("] ");
                if (!notification.getTitle().isEmpty()) {
                    result.append(notification.getTitle()).append(": ");
                }
                result.append(notification.getContent()).append("\n");
            }
        } catch (Exception e) {
            return "Could not read notifications: " + e.getMessage();
        }
        return result.toString();
    }

    public String readRunOutput(JsonObject args) {
        int maxChars = args.has(PARAM_MAX_CHARS) ? args.get(PARAM_MAX_CHARS).getAsInt() : 8000;
        String tabName = args.has(JSON_TAB_NAME) ? args.get(JSON_TAB_NAME).getAsString() : null;

        try {
            // Step 1: Find the target descriptor (needs read action)
            //noinspection RedundantCast — needed for Computable vs ThrowableComputable overload resolution
            var findResult = ApplicationManager.getApplication()
                .runReadAction((com.intellij.openapi.util.Computable<Object>) () -> {
                    var descriptors = collectRunDescriptors();
                    if (descriptors.isEmpty()) return "No Run or Debug panel tabs available.";
                    return findTargetRunDescriptor(descriptors, tabName);
                });

            if (findResult instanceof String errorMsg) return errorMsg;

            var target = (com.intellij.execution.ui.RunContentDescriptor) findResult;
            var console = target.getExecutionConsole();
            if (console == null) {
                return "Tab '" + target.getDisplayName() + "' has no console.";
            }

            // Step 2: Flush and read on EDT — flushDeferredText() only works
            // synchronously on the EDT; from other threads it posts async and returns
            // immediately, so the text would still be empty when we read it.
            var textRef = new java.util.concurrent.atomic.AtomicReference<String>();
            EdtUtil.invokeAndWait(() ->
                textRef.set(readConsoleTextOnEdt(console)));

            String text = textRef.get();
            if (text == null || text.isEmpty()) {
                var consoleClass = target.getExecutionConsole() != null
                    ? target.getExecutionConsole().getClass().getName() : "null";
                return "Tab '" + target.getDisplayName()
                    + "' has no text content (console type: " + consoleClass
                    + "). The run may still be in progress or the console type is unsupported.";
            }
            return formatRunOutput(target.getDisplayName(), text, maxChars);
        } catch (Exception e) {
            return "Error reading Run output: " + e.getMessage();
        }
    }

    /**
     * Flush deferred console output and extract text. Must be called on EDT.
     */
    private String readConsoleTextOnEdt(com.intellij.execution.ui.ExecutionConsole console) {
        // Unwrap delegate wrappers (e.g. JavaConsoleWithProfilerWidget in Ultimate)
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
        // Don't unwrap test runner consoles — extractConsoleText handles them specially
        try {
            console.getClass().getMethod("getResultsViewer");
            return console;
        } catch (NoSuchMethodException ignored) {
            // Not a test runner console, proceed with unwrapping
        }

        if (console instanceof com.intellij.execution.ui.ConsoleViewWithDelegate wrapper) {
            return wrapper.getDelegate();
        }
        // Try getConsole() for plugin wrappers (e.g. Node.js BaseConsoleView)
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

    private List<com.intellij.execution.ui.RunContentDescriptor> collectRunDescriptors() {
        var manager = com.intellij.execution.ui.RunContentManager.getInstance(project);
        return new ArrayList<>(manager.getAllDescriptors());
    }

    private Object findTargetRunDescriptor(List<com.intellij.execution.ui.RunContentDescriptor> descriptors,
                                           String tabName) {
        if (tabName == null) {
            return descriptors.getLast();
        }

        // Find by name
        for (var d : descriptors) {
            if (d.getDisplayName() != null && d.getDisplayName().contains(tabName)) {
                return d;
            }
        }

        // Not found - return error message
        StringBuilder available = new StringBuilder("No tab matching '").append(tabName).append("'. Available tabs:\n");
        for (var d : descriptors) {
            available.append("  - ").append(d.getDisplayName()).append("\n");
        }
        return available.toString();
    }

    private String formatRunOutput(String displayName, String text, int maxChars) {
        StringBuilder result = new StringBuilder();
        result.append("Tab: ").append(displayName).append("\n");
        result.append("Total length: ").append(text.length()).append(" chars\n\n");

        if (text.length() > maxChars) {
            result.append("...(truncated, showing last ").append(maxChars).append(" of ").append(text.length())
                .append(" chars. Use max_chars parameter to read more.)\n");
            result.append(text.substring(text.length() - maxChars));
        } else {
            result.append(text);
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
                                            com.intellij.execution.ui.ExecutionConsole console) throws Exception {
        // getAllTests() lives on AbstractTestProxy (the root node), not on the viewer/form
        var getTestsRootNode = viewer.getClass().getMethod("getTestsRootNode");
        Object root = getTestsRootNode.invoke(viewer);
        if (root == null) {
            return "(No test results yet — the run may not have started)\n";
        }

        var getAllTests = root.getClass().getMethod("getAllTests");
        var tests = (java.util.List<?>) getAllTests.invoke(root);

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

    /**
     * Returns a human-readable status message when no test results are available yet.
     * Checks the root node to distinguish "in progress" from "not started".
     */
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

    private void appendTestResult(Object test, StringBuilder testOutput) throws Exception {
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

    /**
     * Flush deferred text from console buffers so getText() returns complete output.
     * Process consoles buffer output and only write to the document periodically.
     */
    private void flushConsoleOutput(Object console) {
        if (console instanceof com.intellij.execution.impl.ConsoleViewImpl consoleView) {
            consoleView.flushDeferredText();
            return;
        }
        // For terminal consoles, call flushImmediately()
        try {
            var flushMethod = console.getClass().getMethod("flushImmediately");
            flushMethod.invoke(console);
            return;
        } catch (NoSuchMethodException ignored) {
            // Not a terminal console
        } catch (Exception e) {
            LOG.debug("flushImmediately() failed", e);
        }
        // For wrapped consoles (e.g. test runners), try to get the inner console
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

    /**
     * Extract plain text from a ConsoleView via getText() or editor document.
     */
    private String extractPlainConsoleText(Object console) {
        // Try getText() directly on console (works for ConsoleViewImpl)
        try {
            var getTextMethod = console.getClass().getMethod("getText");
            String text = (String) getTextMethod.invoke(console);
            if (text != null && !text.isEmpty()) return text;
        } catch (NoSuchMethodException ignored) {
            // Method not available in this version
        } catch (Exception e) {
            LOG.warn("getText() failed", e);
        }

        // Try terminal widget getText() (for TerminalExecutionConsole used by Node.js, etc.)
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

        // Try editor → document
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

        // Last resort: walk the Swing component tree looking for a ConsoleViewImpl.
        // Handles external system (Gradle) consoles where ExternalSystemRunnableState
        // wraps a BuildView containing an inner ConsoleViewImpl.
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

    /**
     * Recursively walk the Swing component tree to find a ConsoleViewImpl and read its text.
     * Used as a fallback for Gradle/external-system consoles.
     */
    private String findConsoleTextInComponentTree(java.awt.Container container) {
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

    /**
     * Read output from a tab in the Build tool window.
     * Works for Gradle builds, Maven builds, and incremental compiler output.
     */
    public String readBuildOutput(JsonObject args) {
        int maxChars = args.has(PARAM_MAX_CHARS) ? args.get(PARAM_MAX_CHARS).getAsInt() : 8000;
        String tabName = args.has(JSON_TAB_NAME) ? args.get(JSON_TAB_NAME).getAsString() : null;

        try {
            var textRef = new java.util.concurrent.atomic.AtomicReference<String>();
            EdtUtil.invokeAndWait(() -> textRef.set(readBuildOutputOnEdt(tabName, maxChars)));
            return textRef.get();
        } catch (Exception e) {
            LOG.warn("Failed to read Build output", e);
            return "Error reading Build output: " + e.getMessage();
        }
    }

    private String readBuildOutputOnEdt(String tabName, int maxChars) {
        var toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("Build");
        if (toolWindow == null) {
            return "Build tool window is not available (no Java/Kotlin project, or no build has run yet).";
        }

        var contentManager = toolWindow.getContentManager();
        var contents = contentManager.getContents();
        if (contents.length == 0) {
            return "Build tool window is empty — no build has been run yet.";
        }

        com.intellij.ui.content.Content target;
        if (tabName != null) {
            target = findBuildContentByName(contents, tabName);
            if (target == null) {
                StringBuilder available = new StringBuilder(
                    "No Build tab matching '").append(tabName).append("'. Available tabs:\n");
                for (var c : contents) available.append("  - ").append(c.getDisplayName()).append("\n");
                return available.toString();
            }
        } else {
            var selected = contentManager.getSelectedContent();
            target = selected != null ? selected : contents[contents.length - 1];
        }

        String displayName = target.getDisplayName() != null ? target.getDisplayName() : "Build";
        String text = extractBuildTabText(target);
        if (text == null || text.isBlank()) {
            StringBuilder msg = new StringBuilder("Build tab '").append(displayName)
                .append("' has no text content yet (build may still be running).\n");
            if (contents.length > 1) {
                msg.append("Other available tabs:\n");
                for (var c : contents) {
                    if (c != target) msg.append("  - ").append(c.getDisplayName()).append("\n");
                }
            }
            return msg.toString();
        }
        return formatRunOutput(displayName, text, maxChars);
    }

    private com.intellij.ui.content.Content findBuildContentByName(
        com.intellij.ui.content.Content[] contents, String tabName) {
        for (var c : contents) {
            if (c.getDisplayName() != null && c.getDisplayName().contains(tabName)) return c;
        }
        return null;
    }

    /**
     * Extract text from a Build tool window content tab.
     * Tries multiple strategies to handle BuildView and other component types.
     */
    private String extractBuildTabText(com.intellij.ui.content.Content content) {
        var component = content.getComponent();
        if (component == null) return null;

        // Strategy 1: BuildView.getConsoleView() — standard Build tool window component
        try {
            var getConsoleView = component.getClass().getMethod("getConsoleView");
            var consoleView = getConsoleView.invoke(component);
            if (consoleView != null) {
                flushConsoleOutput(consoleView);
                String text = extractPlainConsoleText(consoleView);
                if (text != null && !text.isEmpty()) return text;
            }
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            LOG.debug("getConsoleView() failed for Build tab", e);
        }

        // Strategy 2: Extract directly from the component itself
        flushConsoleOutput(component);
        String text = extractPlainConsoleText(component);
        if (text != null && !text.isEmpty()) return text;

        // Strategy 3: Traverse component hierarchy (depth-limited to avoid deep Swing trees)
        return findConsoleTextInComponentTree(component, 8);
    }

    /**
     * Depth-limited component tree traversal to find embedded console text.
     */
    private String findConsoleTextInComponentTree(java.awt.Component component, int maxDepth) {
        if (maxDepth <= 0) return null;

        if (component instanceof com.intellij.execution.impl.ConsoleViewImpl cv) {
            cv.flushDeferredText();
            String text = extractPlainConsoleText(cv);
            if (text != null && !text.isEmpty()) return text;
        } else {
            String text = extractPlainConsoleText(component);
            if (text != null && !text.isEmpty()) return text;
        }

        if (component instanceof java.awt.Container container) {
            for (var child : container.getComponents()) {
                String childText = findConsoleTextInComponentTree(child, maxDepth - 1);
                if (childText != null && !childText.isEmpty()) return childText;
            }
        }
        return null;
    }

    private String getProjectJavaHome() {
        try {
            Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
            if (sdk != null && sdk.getHomePath() != null) {
                return sdk.getHomePath();
            }
        } catch (Exception ignored) {
            // SDK access errors are non-fatal
        }
        return System.getenv(JAVA_HOME_ENV);
    }
}

