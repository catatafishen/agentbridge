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
 * get_notifications, read_run_output.
 */
@SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
class InfrastructureTools extends AbstractToolHandler {
    private static final Logger LOG = Logger.getInstance(InfrastructureTools.class);

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

    InfrastructureTools(Project project) {
        super(project);
        register("http_request", this::httpRequest);
        register("run_command", this::runCommand);
        register("read_ide_log", this::readIdeLog);
        register("get_notifications", this::getNotifications);
        register("read_run_output", this::readRunOutput);
    }

    private String httpRequest(JsonObject args) throws Exception {
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

    private String runCommand(JsonObject args) throws Exception {
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
        int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
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
        if (failed && !args.has("offset") && fullOutput.length() > maxChars) {
            effectiveOffset = fullOutput.length() - maxChars;
        }
        String header = failed
            ? "❌ Command failed (exit code " + result.exitCode() + ")"
            : "✅ Command succeeded";
        if (failed && effectiveOffset > 0) {
            header += "\n(showing last " + maxChars + " chars — use offset=0 for beginning)";
        }
        return header + "\n\n" + ToolUtils.truncateOutput(fullOutput, maxChars, effectiveOffset);
    }

    private static String truncateForTitle(String command) {
        return command.length() > 40 ? command.substring(0, 37) + "..." : command;
    }

    private String readIdeLog(JsonObject args) throws IOException {
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
    private String getNotifications(JsonObject args) {
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

    private String readRunOutput(JsonObject args) {
        int maxChars = args.has(PARAM_MAX_CHARS) ? args.get(PARAM_MAX_CHARS).getAsInt() : 8000;
        String tabName = args.has(JSON_TAB_NAME) ? args.get(JSON_TAB_NAME).getAsString() : null;

        //noinspection RedundantCast — needed for Computable vs ThrowableComputable overload resolution
        return ApplicationManager.getApplication().runReadAction((com.intellij.openapi.util.Computable<String>) () -> {
            try {
                List<com.intellij.execution.ui.RunContentDescriptor> descriptors = collectRunDescriptors();
                if (descriptors.isEmpty()) {
                    return "No Run or Debug panel tabs available.";
                }

                var result = findTargetRunDescriptor(descriptors, tabName);
                if (result instanceof String errorMsg) {
                    return errorMsg;
                }

                var target = (com.intellij.execution.ui.RunContentDescriptor) result;
                var console = target.getExecutionConsole();
                if (console == null) {
                    return "Tab '" + target.getDisplayName() + "' has no console.";
                }

                String text = extractConsoleText(console);
                if (text == null || text.isEmpty()) {
                    return "Tab '" + target.getDisplayName() + "' has no text content (console may still be loading or is an unsupported type).";
                }

                return formatRunOutput(target.getDisplayName(), text, maxChars);
            } catch (Exception e) {
                return "Error reading Run output: " + e.getMessage();
            }
        });
    }

    private List<com.intellij.execution.ui.RunContentDescriptor> collectRunDescriptors() {
        var manager = com.intellij.execution.ui.RunContentManager.getInstance(project);
        var descriptors = new ArrayList<>(manager.getAllDescriptors());
        return descriptors;
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

    /**
     * Extract text from any type of ExecutionConsole (regular, test runner, etc.)
     */
    private String extractConsoleText(com.intellij.execution.ui.ExecutionConsole console) {
        try {
            var getResultsViewer = console.getClass().getMethod("getResultsViewer");
            var viewer = getResultsViewer.invoke(console);
            if (viewer != null) {
                String testOutput = extractTestRunnerResults(viewer, console);
                if (!testOutput.isEmpty()) return testOutput;
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
        StringBuilder testOutput = new StringBuilder();
        var getAllTests = viewer.getClass().getMethod("getAllTests");
        var tests = (java.util.List<?>) getAllTests.invoke(viewer);
        if (tests != null && !tests.isEmpty()) {
            testOutput.append("=== Test Results ===\n");
            for (var test : tests) {
                appendTestResult(test, testOutput);
            }
        }
        appendTestConsoleOutput(console, testOutput);
        return testOutput.toString();
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
            status = "\u2705 PASSED";
        } else if (defect) {
            status = "\u274C FAILED";
        } else {
            status = "\u26A0 UNKNOWN";
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
            var getConsole = console.getClass().getMethod("getConsole");
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
     * Extract plain text from a ConsoleView via getText() or editor document.
     */
    private String extractPlainConsoleText(Object console) {
        // Try getText()
        try {
            var getTextMethod = console.getClass().getMethod("getText");
            String text = (String) getTextMethod.invoke(console);
            if (text != null && !text.isEmpty()) return text;
        } catch (NoSuchMethodException ignored) {
            // Method not available in this version
        } catch (Exception e) {
            LOG.warn("getText() failed", e);
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

