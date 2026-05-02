package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.github.catatafishen.agentbridge.ui.renderers.IdeInfoRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * MCP tool that reads recent IntelliJ IDE log entries with filtering and compact output.
 *
 * <h2>Design for coding-agent use</h2>
 * <ul>
 *   <li><b>filter</b> is always treated as a case-insensitive regex. Use {@code |} for OR
 *       without any extra flag.</li>
 *   <li><b>since / until</b> accept relative durations ({@code "5m"}, {@code "30s"}) or
 *       absolute times ({@code "HH:mm:ss"} or {@code "yyyy-MM-dd HH:mm:ss"}).</li>
 *   <li>Output is compact: date and thread-id are stripped; the logger is shortened to its
 *       simple class name.</li>
 * </ul>
 */
public final class ReadIdeLogTool extends InfrastructureTool {

    private static final String IDEA_LOG_FILENAME = "idea.log";

    private static final String PARAM_LINES = "lines";
    private static final String PARAM_FILTER = "filter";
    private static final String PARAM_LEVEL = "level";
    private static final String PARAM_SINCE = "since";
    private static final String PARAM_UNTIL = "until";

    // Matches: 2026-03-22 16:58:04,345 [  49065]   INFO - #com.example.Foo - message
    private static final Pattern LOG_LINE_PATTERN = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2}),(\\d{3}) \\[[^]]++]\\s++(\\w+)\\s++- #?([^\\s-][^-]*+)\\s*+-\\s*+(.*)$"
    );

    private static final DateTimeFormatter DATETIME_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Overrides the log directory resolved via system properties. Package-private for testing.
     */
    @Nullable
    private final Path logDirOverride;

    public ReadIdeLogTool(Project project) {
        this(project, null);
    }

    /**
     * Package-private constructor that bypasses the system-property fallback chain. For testing only.
     */
    ReadIdeLogTool(@Nullable Project project, @Nullable Path logDirOverride) {
        super(project);
        this.logDirOverride = logDirOverride;
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
        return """
            Read recent IntelliJ IDE log entries with compact output.

            FILTER is always a case-insensitive regex - use | for OR:
              filter="ToolChipRegistry|git_diff"

            SINCE / UNTIL narrow to a time window. All formats accepted:
              Relative: "5m", "30s", "2h"
              Time today: "16:57:30" or "16:57"
              Date: "2026-03-22"
              Date-time: "2026-03-22 16:57:30"
              ISO 8601: "2026-03-22T16:57:30Z"

            Output format: HH:mm:ss.SSS  LEVEL  ShortClass: message
            (date and thread-id stripped; logger shortened to simple class name)
            """;
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
        return schema(
            Param.optional(PARAM_FILTER, TYPE_STRING,
                "Case-insensitive regex. Use | for OR: \"ToolChipRegistry|git_diff\""),
            Param.optional(PARAM_SINCE, TYPE_STRING,
                "Show entries at or after. Accepted: \"5m\", \"2h\", \"16:57:30\", \"2026-03-22 16:57:30\", \"2026-03-22T16:57:30Z\""),
            Param.optional(PARAM_UNTIL, TYPE_STRING,
                "Show entries at or before. Same formats as since."),
            Param.optional(PARAM_LEVEL, TYPE_STRING,
                "Filter by level: INFO, WARN, ERROR (comma-separated). Default: all levels."),
            Param.optional(PARAM_LINES, TYPE_INTEGER,
                "Max matching lines to return from the end (default: 200).")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws IOException {
        int maxLines = args.has(PARAM_LINES) ? args.get(PARAM_LINES).getAsInt() : 200;
        String filterStr = args.has(PARAM_FILTER) ? args.get(PARAM_FILTER).getAsString() : null;
        String sinceStr = args.has(PARAM_SINCE) ? args.get(PARAM_SINCE).getAsString() : null;
        String untilStr = args.has(PARAM_UNTIL) ? args.get(PARAM_UNTIL).getAsString() : null;
        String levelParam = args.has(PARAM_LEVEL) ? args.get(PARAM_LEVEL).getAsString().toUpperCase() : null;

        Path logFile = findIdeLogFile();
        if (logFile == null) return "Could not locate idea.log";

        Pattern filterPattern = compileFilter(filterStr);
        if (filterPattern == null && filterStr != null && !filterStr.isBlank()) {
            return "Invalid filter regex - check syntax";
        }

        LocalDateTime since;
        LocalDateTime until;
        try {
            since = parseTimeArgOrError(sinceStr);
            until = parseTimeArgOrError(untilStr);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }

        List<String> levels = parseLevels(levelParam);

        Deque<String> outputBuffer = new ArrayDeque<>(maxLines + 1);
        LineProcessor processor = new LineProcessor(filterPattern, since, until, levels, outputBuffer, maxLines);

        try (var stream = Files.lines(logFile)) {
            for (String raw : (Iterable<String>) stream::iterator) {
                processor.accept(raw);
            }
        }
        processor.flush();

        if (outputBuffer.isEmpty()) return "No matching log entries found.";
        return String.join("\n", outputBuffer);
    }

    // ── Per-line processing ───────────────────────────────────────────────────

    /**
     * Bundles all filter criteria and output state for the line loop.
     */
    private static final class LineProcessor {
        @Nullable
        final Pattern filterPattern;
        @Nullable
        final LocalDateTime since;
        @Nullable
        final LocalDateTime until;
        @Nullable
        final List<String> levels;
        final Deque<String> outputBuffer;
        final int maxLines;
        StringBuilder pending = null;

        LineProcessor(
            @Nullable Pattern filterPattern,
            @Nullable LocalDateTime since,
            @Nullable LocalDateTime until,
            @Nullable List<String> levels,
            Deque<String> outputBuffer,
            int maxLines
        ) {
            this.filterPattern = filterPattern;
            this.since = since;
            this.until = until;
            this.levels = levels;
            this.outputBuffer = outputBuffer;
            this.maxLines = maxLines;
        }

        void accept(String raw) {
            Matcher m = LOG_LINE_PATTERN.matcher(raw);
            if (!m.matches()) {
                if (pending != null) pending.append("\n  ").append(raw);
                return;
            }
            flush();
            String compact = buildCompact(m);
            if (compact != null) pending = new StringBuilder(compact);
        }

        void flush() {
            if (pending == null) return;
            if (outputBuffer.size() >= maxLines) outputBuffer.removeFirst();
            outputBuffer.addLast(pending.toString());
            pending = null;
        }

        private static final int MAX_LINE_CHARS = 2000;

        private @Nullable String buildCompact(Matcher m) {
            String level = m.group(4);
            if (levels != null && !levels.contains(level)) return null;
            if (!isInTimeRange(m.group(1), m.group(2))) return null;

            String message = m.group(6);
            String compact = m.group(2) + "." + m.group(3)
                + "  " + level
                + "  " + shortLogger(m.group(5).trim())
                + ": " + message;
            if (filterPattern != null && !filterPattern.matcher(compact).find()) return null;
            if (compact.length() > MAX_LINE_CHARS) {
                compact = compact.substring(0, MAX_LINE_CHARS) + "... [+" + (compact.length() - MAX_LINE_CHARS) + "]";
            }
            return compact;
        }

        private boolean isInTimeRange(String date, String time) {
            if (since == null && until == null) return true;
            LocalDateTime lineTime = parseLogTimestamp(date, time);
            if (lineTime == null) return true;
            return (since == null || !lineTime.isBefore(since))
                && (until == null || !lineTime.isAfter(until));
        }

        private static @Nullable LocalDateTime parseLogTimestamp(String date, String time) {
            try {
                return LocalDateTime.parse(date + " " + time, DATETIME_FMT);
            } catch (DateTimeParseException e) {
                return null;
            }
        }

        private static @NotNull String shortLogger(@NotNull String logger) {
            int dot = logger.lastIndexOf('.');
            return dot >= 0 ? logger.substring(dot + 1) : logger;
        }
    }

    private static @Nullable Pattern compileFilter(@Nullable String filterStr) {
        if (filterStr == null || filterStr.isBlank()) return null;
        try {
            return Pattern.compile(filterStr, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    private static @Nullable List<String> parseLevels(@Nullable String levelParam) {
        if (levelParam == null) return null;
        List<String> result = new ArrayList<>();
        for (String s : levelParam.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) result.add(t);
        }
        return result.isEmpty() ? null : result;
    }

    private static @Nullable LocalDateTime parseTimeArgOrError(@Nullable String value) {
        return com.github.catatafishen.agentbridge.psi.TimeArgParser.parseLocalDateTime(value);
    }

    // ── Log file location ─────────────────────────────────────────────────────

    private @Nullable Path findIdeLogFile() {
        if (logDirOverride != null) {
            Path f = logDirOverride.resolve(IDEA_LOG_FILENAME);
            return Files.exists(f) ? f : null;
        }

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

    @Override
    public @NotNull Object resultRenderer() {
        return IdeInfoRenderer.INSTANCE;
    }
}
