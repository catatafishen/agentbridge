package com.github.catatafishen.ideagentforcopilot.psi.tools.editor;

import com.github.catatafishen.ideagentforcopilot.psi.ToolUtils;
import com.github.catatafishen.ideagentforcopilot.session.v2.EntryDataJsonAdapter;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.IdeInfoRenderer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Lists, reads, and searches past conversation sessions from the chat history.
 */
public final class SearchConversationHistoryTool extends EditorTool {

    private static final String JSON_EXT = ".json";
    private static final String PARAM_QUERY = "query";
    private static final String PARAM_MAX_CHARS = "max_chars";
    private static final String PARAM_SINCE = "since";
    private static final String PARAM_UNTIL = "until";
    private static final String PARAM_LAST_N = "last_n";
    private static final String PARAM_TURN_ID = "turn_id";
    private static final String PARAM_OFFSET = "offset";
    private static final String CONVERSATION_CURRENT = "current";
    private static final String CONVERSATION_PREFIX = "conversation-";
    private static final String JSON_TITLE = "title";
    private static final String JSON_TIMESTAMP = "timestamp";

    private static final class FilterOptions {
        String query;
        Instant since;
        Instant until;
        Integer lastN;
        Integer offset;
        String turnId;
        int maxChars;

        FilterOptions(String query, Instant since, Instant until, Integer lastN, Integer offset, String turnId, int maxChars) {
            this.query = query != null ? query.toLowerCase(Locale.ROOT) : null;
            this.since = since;
            this.until = until;
            this.lastN = lastN;
            this.offset = offset;
            this.turnId = turnId;
            this.maxChars = maxChars;
        }
    }

    public SearchConversationHistoryTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "search_conversation_history";
    }

    @Override
    public @NotNull String displayName() {
        return "Search Conversation History";
    }

    @Override
    public @NotNull String description() {
        return "List, read, and search past conversation sessions from the chat history";
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
            {PARAM_QUERY, TYPE_STRING, "Text to search for across conversations (case-insensitive)"},
            {"file", TYPE_STRING, "Conversation identifier: 'current' for the active session, or an archive timestamp (e.g., '2026-03-04T15-30-00'). Not a filesystem path."},
            {PARAM_TURN_ID, TYPE_STRING, "Turn ID from conversation summary (e.g. 't3'). Fetches that specific turn in full. Defaults to file='current'."},
            {PARAM_SINCE, TYPE_STRING, "Filter entries since this time. Accepted: \"5m\", \"2h\", \"16:57:30\", \"2026-03-17\", \"2026-03-17 10:00:00\", \"2026-03-17T10:00:00Z\""},
            {PARAM_UNTIL, TYPE_STRING, "Filter entries until this time. Same formats as since."},
            {PARAM_LAST_N, TYPE_INTEGER, "Number of turns (prompts) to return from the end"},
            {PARAM_OFFSET, TYPE_INTEGER, "Number of turns to skip from the end before returning last_n"},
            {PARAM_MAX_CHARS, TYPE_INTEGER, "Maximum characters to return (default: 8000)"}
        });
    }

    @Override
    public @NotNull Object resultRenderer() {
        return IdeInfoRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        String basePath = project.getBasePath();
        if (basePath == null) return "Error: project base path unavailable";

        File agentDir = new File(basePath, ".agent-work");
        File archiveDir = new File(agentDir, "conversations");
        File currentFile = new File(agentDir, "conversation" + JSON_EXT);

        String query = args.has(PARAM_QUERY) ? args.get(PARAM_QUERY).getAsString() : null;
        String file = args.has("file") ? args.get("file").getAsString() : null;
        int maxChars = args.has(PARAM_MAX_CHARS) ? args.get(PARAM_MAX_CHARS).getAsInt() : 8000;

        Instant since;
        Instant until;
        try {
            since = parseTimestampParam(args, PARAM_SINCE);
            until = parseTimestampParam(args, PARAM_UNTIL);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }

        Integer lastN = args.has(PARAM_LAST_N) ? args.get(PARAM_LAST_N).getAsInt() : null;
        Integer offset = args.has(PARAM_OFFSET) ? args.get(PARAM_OFFSET).getAsInt() : null;
        String turnId = args.has(PARAM_TURN_ID) ? args.get(PARAM_TURN_ID).getAsString() : null;

        // Default to current conversation when only turn_id is specified
        if (file == null && turnId != null) file = CONVERSATION_CURRENT;

        FilterOptions options = new FilterOptions(query, since, until, lastN, offset, turnId, maxChars);

        if (file == null && query == null && since == null && until == null && lastN == null) {
            return listConversations(currentFile, archiveDir);
        }

        if (file != null && query == null && since == null && until == null && lastN == null) {
            return readConversation(file, currentFile, archiveDir, options);
        }

        return searchConversations(file, currentFile, archiveDir, options);
    }

    private static Instant parseTimestampParam(JsonObject args, String param) {
        if (!args.has(param)) return null;
        String value = args.get(param).getAsString();
        try {
            return com.github.catatafishen.ideagentforcopilot.psi.TimeArgParser.parseInstant(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid '" + param + "' value: " + e.getMessage());
        }
    }

    private static String listConversations(File currentFile, File archiveDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("Conversations:\n\n");
        if (currentFile.exists() && currentFile.length() > 10) {
            sb.append("  current (").append(ToolUtils.formatFileSize(currentFile.length())).append(")\n");
        }
        if (archiveDir.exists()) {
            File[] archives = archiveDir.listFiles((d, n) -> n.endsWith(JSON_EXT));
            if (archives != null && archives.length > 0) {
                Arrays.sort(archives, Comparator.comparing(File::getName).reversed());
                for (File f : archives) {
                    String name = f.getName().replace(CONVERSATION_PREFIX, "").replace(JSON_EXT, "");
                    sb.append("  ").append(name).append(" (").append(ToolUtils.formatFileSize(f.length())).append(")\n");
                }
            }
        }
        if (sb.length() < 20) {
            return "No conversation history found.";
        }
        sb.append("\nUse 'file' parameter to read a specific conversation (e.g., file='current' or file='2026-03-04T15-30-00').");
        sb.append("\nUse 'query' parameter to search across all conversations.");
        return sb.toString();
    }

    private static String readConversation(String file, File currentFile,
                                           File archiveDir, FilterOptions options) {
        File target = resolveConversationFile(file, currentFile, archiveDir);
        if (target == null || !target.exists()) {
            return "Error: Conversation file not found: " + file;
        }
        return conversationJsonToText(target, options);
    }

    private static String searchConversations(String file, File currentFile,
                                              File archiveDir, FilterOptions options) {
        List<File> files = collectFilesToSearch(file, currentFile, archiveDir);

        int totalMatches = 0;
        StringBuilder sb = new StringBuilder();
        for (File f : files) {
            totalMatches += appendFileSearchResult(f, currentFile, options, sb);
            if (sb.length() >= options.maxChars) break;
        }

        if (totalMatches == 0) {
            if (options.query != null) return "No matches found for: " + options.query;
            return "No conversation history found matching constraints.";
        }
        return sb.toString().trim();
    }

    private static List<File> collectFilesToSearch(String file, File currentFile, File archiveDir) {
        List<File> files = new ArrayList<>();
        if (file != null) {
            File target = resolveConversationFile(file, currentFile, archiveDir);
            if (target != null && target.exists()) files.add(target);
        } else {
            if (currentFile.exists() && currentFile.length() > 10) files.add(currentFile);
            if (archiveDir.exists()) {
                File[] archives = archiveDir.listFiles((d, n) -> n.endsWith(JSON_EXT));
                if (archives != null) {
                    Arrays.sort(archives, Comparator.comparing(File::getName).reversed());
                    files.addAll(Arrays.asList(archives));
                }
            }
        }
        return files;
    }

    private static int appendFileSearchResult(File f, File currentFile,
                                              FilterOptions options,
                                              StringBuilder sb) {
        String label = f.equals(currentFile)
            ? CONVERSATION_CURRENT
            : f.getName().replace(CONVERSATION_PREFIX, "").replace(JSON_EXT, "");
        String result = conversationJsonToText(f, options);
        if (result.isEmpty()) return 0;

        String lowerQuery = options.query;
        long matchCount = lowerQuery == null ? 1 : result.lines()
            .filter(l -> l.toLowerCase(Locale.ROOT).contains(lowerQuery))
            .count();
        sb.append("── ").append(label).append(" (").append(matchCount).append(" matches) ──\n");
        sb.append(result).append("\n");
        return (int) matchCount;
    }

    private static File resolveConversationFile(String name, File currentFile, File archiveDir) {
        if (CONVERSATION_CURRENT.equalsIgnoreCase(name)) return currentFile;
        File direct = new File(archiveDir, CONVERSATION_PREFIX + name + JSON_EXT);
        if (direct.exists()) return direct;
        if (archiveDir.exists()) {
            File[] archives = archiveDir.listFiles((d, n) -> n.contains(name) && n.endsWith(JSON_EXT));
            if (archives != null && archives.length > 0) return archives[0];
        }
        return null;
    }

    private static String conversationJsonToText(File file, FilterOptions options) {
        try {
            String json = Files.readString(file.toPath());
            var arr = JsonParser.parseString(json).getAsJsonArray();
            List<JsonObject> entries = new ArrayList<>();
            for (var el : arr) {
                if (el.isJsonObject()) {
                    entries.add(el.getAsJsonObject());
                }
            }

            // 1. Filter by time
            entries = filterByTime(entries, options);

            // 2. Filter by turns (last_n and offset)
            entries = filterByTurns(entries, options);

            // 3. Format and filter by query
            return formatAndFilterEntries(entries, options);
        } catch (Exception e) {
            return "Error reading " + file.getName() + ": " + e.getMessage();
        }
    }

    private static List<JsonObject> filterByTime(List<JsonObject> entries, FilterOptions options) {
        if (options.since == null && options.until == null) return entries;
        return entries.stream()
            .filter(obj -> isWithinTimeRange(obj, options.since, options.until))
            .toList();
    }

    private static List<JsonObject> filterByTurns(List<JsonObject> entries, FilterOptions options) {
        List<Integer> promptIndices = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (EntryDataJsonAdapter.TYPE_PROMPT.equals(entries.get(i).get("type").getAsString())) {
                promptIndices.add(i);
            }
        }

        if (options.turnId != null) {
            return filterByTurnId(entries, promptIndices, options.turnId);
        }

        if (options.lastN == null && options.offset == null) return entries;

        int offset = options.offset != null ? options.offset : 0;
        int endPromptIdx = promptIndices.size() - 1 - offset;
        if (endPromptIdx < 0) return Collections.emptyList();

        int lastN = options.lastN != null ? options.lastN : promptIndices.size();
        int startPromptIdx = Math.max(0, endPromptIdx - lastN + 1);
        int startIdx = promptIndices.get(startPromptIdx);
        int endIdx = endPromptIdx + 1 < promptIndices.size()
            ? promptIndices.get(endPromptIdx + 1) - 1
            : entries.size() - 1;
        return entries.subList(startIdx, endIdx + 1);
    }

    private static List<JsonObject> filterByTurnId(List<JsonObject> entries,
                                                   List<Integer> promptIndices, String turnId) {
        int n = parseTurnNumber(turnId);
        if (n <= 0 || n > promptIndices.size()) return Collections.emptyList();
        int promptIdx = n - 1; // 0-based
        int startIdx = promptIndices.get(promptIdx);
        int endIdx = promptIdx + 1 < promptIndices.size()
            ? promptIndices.get(promptIdx + 1) - 1
            : entries.size() - 1;
        return entries.subList(startIdx, endIdx + 1);
    }

    /**
     * Parses a turn ID like "t3" or "3" into the 1-based turn number. Returns -1 on parse failure.
     */
    private static int parseTurnNumber(String turnId) {
        String s = turnId.toLowerCase(Locale.ROOT).trim();
        if (s.startsWith("t")) s = s.substring(1);
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String formatAndFilterEntries(List<JsonObject> entries, FilterOptions options) {
        StringBuilder sb = new StringBuilder();
        for (var obj : entries) {
            String type = obj.has("type") ? obj.get("type").getAsString() : "";
            String line = formatConversationEntry(obj, type);
            if (isMatchingEntry(line, options.query)) {
                sb.append(line).append("\n");
                if (sb.length() >= options.maxChars) {
                    sb.append("...[truncated at ").append(options.maxChars).append(" chars]\n");
                    break;
                }
            }
        }
        return sb.toString();
    }

    private static boolean isWithinTimeRange(JsonObject obj, Instant since, Instant until) {
        String tsKey = obj.has("ts") ? "ts" : JSON_TIMESTAMP;
        if (!obj.has(tsKey)) return true;
        String tsStr = obj.get(tsKey).getAsString();
        try {
            Instant ts = Instant.parse(tsStr);
            return (since == null || !ts.isBefore(since)) && (until == null || !ts.isAfter(until));
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean isMatchingEntry(String line, String searchQuery) {
        if (line == null || line.isEmpty()) return false;
        return searchQuery == null || line.toLowerCase(Locale.ROOT).contains(searchQuery);
    }

    private static String formatConversationEntry(JsonObject obj, String type) {
        return switch (type) {
            case EntryDataJsonAdapter.TYPE_PROMPT -> {
                String text = obj.has("text") ? obj.get("text").getAsString() : "";
                String ts = obj.has("ts") ? " [" + formatTimestamp(obj.get("ts").getAsString()) + "]" : "";
                yield ">>> " + text + ts;
            }
            case EntryDataJsonAdapter.TYPE_TEXT -> {
                String raw = obj.has("raw") ? obj.get("raw").getAsString() : "";
                yield raw.isEmpty() ? null : raw.trim();
            }
            case EntryDataJsonAdapter.TYPE_THINKING -> {
                String raw = obj.has("raw") ? obj.get("raw").getAsString() : "";
                yield raw.isEmpty() ? null : "[thinking] " + raw.trim();
            }
            case EntryDataJsonAdapter.TYPE_TOOL -> {
                String title = obj.has(JSON_TITLE) ? obj.get(JSON_TITLE).getAsString() : "tool";
                String toolArgs = obj.has("args") ? obj.get("args").getAsString() : "";
                yield title + (toolArgs.isEmpty() ? "" : " " + toolArgs);
            }
            case EntryDataJsonAdapter.TYPE_SUBAGENT -> {
                String agentType = obj.has("agentType") ? obj.get("agentType").getAsString() : "";
                String desc = obj.has("description") ? obj.get("description").getAsString() : "";
                yield "SubAgent: " + agentType + " — " + desc;
            }
            case EntryDataJsonAdapter.TYPE_CONTEXT -> "Context files attached";
            case EntryDataJsonAdapter.TYPE_STATUS -> {
                String msg = obj.has("message") ? obj.get("message").getAsString() : "";
                yield msg.isEmpty() ? null : "Status: " + msg;
            }
            case EntryDataJsonAdapter.TYPE_SEPARATOR -> {
                String ts = obj.has(JSON_TIMESTAMP) ? obj.get(JSON_TIMESTAMP).getAsString() : "";
                yield "--- Session " + formatTimestamp(ts) + " ---";
            }
            default -> null;
        };
    }

    /**
     * Formats a stored timestamp for human-readable display.
     * Handles ISO 8601 (new format, e.g. "2026-03-12T14:29:47Z") as well as legacy
     * formats ("14:29", "Mar 11, 2026 1:29 PM") for backward compatibility.
     */
    private static String formatTimestamp(String ts) {
        try {
            java.time.ZonedDateTime zdt = java.time.Instant.parse(ts).atZone(java.time.ZoneId.systemDefault());
            return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(zdt);
        } catch (Exception e) {
            return ts;
        }
    }
}
