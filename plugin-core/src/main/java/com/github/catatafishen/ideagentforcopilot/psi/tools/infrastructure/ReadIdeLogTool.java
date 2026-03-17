package com.github.catatafishen.ideagentforcopilot.psi.tools.infrastructure;

import com.github.catatafishen.ideagentforcopilot.ui.renderers.IdeInfoRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ReadIdeLogTool extends InfrastructureTool {

    private static final String IDEA_LOG_FILENAME = "idea.log";
    private static final String PARAM_LINES = "lines";
    private static final String PARAM_FILTER = "filter";
    private static final String PARAM_LEVEL = "level";
    private static final String PARAM_REGEX = "regex";

    public ReadIdeLogTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "read_ide_log";
    }

    @Override
    public @NotNull String displayName() {
        return "Read IDE Log";
    }

    @Override
    public @NotNull String description() {
        return "Read recent IntelliJ IDE log entries, optionally filtered by level or text";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {PARAM_LINES, TYPE_INTEGER, "Number of recent lines to return (default: 50)"},
            {PARAM_FILTER, TYPE_STRING, "Only return lines matching this text or regex"},
            {PARAM_REGEX, TYPE_BOOLEAN, "If true, treat filter as a regular expression (default: false)"},
            {PARAM_LEVEL, TYPE_STRING, "Filter by log level: INFO, WARN, ERROR (comma-separated for multiple)"}
        });
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws IOException {
        int lines = args.has(PARAM_LINES) ? args.get(PARAM_LINES).getAsInt() : 50;
        String filter = args.has(PARAM_FILTER) ? args.get(PARAM_FILTER).getAsString() : null;
        boolean useRegex = args.has(PARAM_REGEX) && args.get(PARAM_REGEX).getAsBoolean();
        String levelParam = args.has(PARAM_LEVEL) ? args.get(PARAM_LEVEL).getAsString().toUpperCase() : null;

        Path logFile = findIdeLogFile();
        if (logFile == null) {
            return "Could not locate idea.log";
        }

        java.util.regex.Pattern pattern = null;
        if (filter != null && useRegex) {
            try {
                pattern = java.util.regex.Pattern.compile(filter, java.util.regex.Pattern.CASE_INSENSITIVE);
            } catch (java.util.regex.PatternSyntaxException e) {
                return "Invalid regex: " + e.getMessage();
            }
        }

        final java.util.regex.Pattern finalPattern = pattern;
        final String finalFilter = filter;
        final java.util.List<String> levels = levelParam != null
            ? java.util.Arrays.stream(levelParam.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(java.util.stream.Collectors.toList())
            : null;

        java.util.Deque<String> buffer = new java.util.ArrayDeque<>(lines);

        try (java.util.stream.Stream<String> stream = Files.lines(logFile)) {
            stream.filter(line -> {
                if (levels != null) {
                    boolean match = false;
                    for (String level : levels) {
                        // Standard IntelliJ log format: timestamp [  thread]   LEVEL - ...
                        if (line.contains(" " + level + " ")) {
                            match = true;
                            break;
                        }
                    }
                    if (!match) return false;
                }
                if (finalFilter != null) {
                    if (finalPattern != null) {
                        return finalPattern.matcher(line).find();
                    } else {
                        return line.toLowerCase().contains(finalFilter.toLowerCase());
                    }
                }
                return true;
            }).forEach(line -> {
                if (buffer.size() >= lines) {
                    buffer.removeFirst();
                }
                buffer.addLast(line);
            });
        }

        if (buffer.isEmpty()) {
            return "No matching log entries found.";
        }

        return String.join("\n", buffer);
    }

    @Override
    public @NotNull Object resultRenderer() {
        return IdeInfoRenderer.INSTANCE;
    }

    private static @Nullable Path findIdeLogFile() {
        Path logFile = Path.of(System.getProperty("idea.log.path", ""), IDEA_LOG_FILENAME);
        if (Files.exists(logFile)) return logFile;

        String logDir = System.getProperty("idea.system.path");
        if (logDir != null) {
            logFile = Path.of(logDir, "..", "log", IDEA_LOG_FILENAME);
            if (Files.exists(logFile)) return logFile;
        }

        try {
            Class<?> pm = Class.forName("com.intellij.openapi.application.PathManager");
            String logPath = (String) pm.getMethod("getLogPath").invoke(null);
            logFile = Path.of(logPath, IDEA_LOG_FILENAME);
            if (Files.exists(logFile)) return logFile;
        } catch (Exception ignored) {
            // PathManager not available or reflection failed
        }

        return null;
    }
}
