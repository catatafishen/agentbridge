package com.github.catatafishen.agentbridge.psi.tools.editor;

import com.github.catatafishen.agentbridge.session.v2.SessionStoreV2;
import com.github.catatafishen.agentbridge.ui.EntryData;
import com.github.catatafishen.agentbridge.ui.renderers.IdeInfoRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lists, reads, and searches past conversation sessions from the chat history.
 * Reads V2 JSONL sessions via {@link SessionStoreV2}.
 */
public final class SearchConversationHistoryTool extends EditorTool {

    private static final String PARAM_QUERY = "query";
    private static final String PARAM_MAX_CHARS = "max_chars";
    private static final String PARAM_SINCE = "since";
    private static final String PARAM_UNTIL = "until";
    private static final String PARAM_LAST_N = "last_n";
    private static final String PARAM_TURN_ID = "turn_id";
    private static final String PARAM_OFFSET = "offset";
    private static final String CONVERSATION_CURRENT = "current";

    private static final DateTimeFormatter DISPLAY_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final class FilterOptions {
        String query;
        Instant since;
        Instant until;
        Integer lastN;
        Integer offset;
        String turnId;
        int maxChars;

        FilterOptions(String query, Instant since, Instant until,
                      Integer lastN, Integer offset, String turnId, int maxChars) {
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
        return Kind.SEARCH;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional(PARAM_QUERY, TYPE_STRING, "Text to search for across conversations (case-insensitive)"),
            Param.optional("file", TYPE_STRING, "Conversation identifier: 'current' for the active session, or an archive timestamp (e.g., '2026-03-04T15-30-00'). Not a filesystem path."),
            Param.optional(PARAM_TURN_ID, TYPE_STRING, "Turn ID from conversation summary (e.g. 't3'). Fetches that specific turn in full. Defaults to file='current'."),
            Param.optional(PARAM_SINCE, TYPE_STRING, "Filter entries since this time. Accepted: \"5m\", \"2h\", \"16:57:30\", \"2026-03-17\", \"2026-03-17 10:00:00\", \"2026-03-17T10:00:00Z\""),
            Param.optional(PARAM_UNTIL, TYPE_STRING, "Filter entries until this time. Same formats as since."),
            Param.optional(PARAM_LAST_N, TYPE_INTEGER, "Number of turns (prompts) to return from the end"),
            Param.optional(PARAM_OFFSET, TYPE_INTEGER, "Number of turns to skip from the end before returning last_n"),
            Param.optional(PARAM_MAX_CHARS, TYPE_INTEGER, "Maximum characters to return (default: 8000)")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return IdeInfoRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        String basePath = project.getBasePath();
        if (basePath == null) return "Error: project base path unavailable";

        SessionStoreV2 store = SessionStoreV2.getInstance(project);

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
            return listConversations(store, basePath);
        }

        if (file != null && query == null && since == null && until == null && lastN == null) {
            return readConversation(store, basePath, file, options);
        }

        return searchConversations(store, basePath, file, options);
    }

    private static Instant parseTimestampParam(JsonObject args, String param) {
        if (!args.has(param)) return null;
        String value = args.get(param).getAsString();
        try {
            return com.github.catatafishen.agentbridge.psi.TimeArgParser.parseInstant(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid '" + param + "' value: " + e.getMessage());
        }
    }

    // ── List ──────────────────────────────────────────────────────────────────

    private static String listConversations(SessionStoreV2 store, String basePath) {
        List<SessionStoreV2.SessionRecord> sessions = store.listSessions(basePath);
        String currentId = store.getCurrentSessionId(basePath);

        if (sessions.isEmpty()) {
            // Check if at least a current session exists
            List<EntryData> current = store.loadEntries(basePath);
            if (current == null || current.isEmpty()) {
                return "No conversation history found.";
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Conversations:\n\n");

        for (SessionStoreV2.SessionRecord rec : sessions) {
            boolean isCurrent = rec.id().equals(currentId);
            String label = isCurrent ? CONVERSATION_CURRENT : rec.id();
            String nameOrAgent = !rec.name().isEmpty() ? rec.name() : rec.agent();
            String updated = formatEpochMillis(rec.updatedAt());
            sb.append("  ").append(label).append(" — ")
                .append(nameOrAgent)
                .append(" (").append(rec.turnCount()).append(" turns, updated ").append(updated).append(")\n");
        }

        // If no sessions in the index, but a current session exists
        if (sessions.isEmpty()) {
            sb.append("  current (active session)\n");
        }

        sb.append("\nUse 'file' parameter to read a specific conversation (e.g., file='current' or a session UUID).");
        sb.append("\nUse 'query' parameter to search across all conversations.");
        return sb.toString();
    }

    // ── Read single session ───────────────────────────────────────────────────

    private static String readConversation(SessionStoreV2 store, String basePath,
                                           String fileParam, FilterOptions options) {
        List<EntryData> entries = loadSessionEntries(store, basePath, fileParam);
        if (entries == null || entries.isEmpty()) {
            return "Error: Conversation not found: " + fileParam;
        }
        return entriesToText(entries, options);
    }

    // ── Search across sessions ────────────────────────────────────────────────

    private static String searchConversations(SessionStoreV2 store, String basePath,
                                              @Nullable String fileParam, FilterOptions options) {
        Map<String, List<EntryData>> sessionMap = collectSessionEntries(store, basePath, fileParam);

        int totalMatches = 0;
        StringBuilder sb = new StringBuilder();
        for (var entry : sessionMap.entrySet()) {
            totalMatches += appendSessionSearchResult(entry.getKey(), entry.getValue(), options, sb);
            if (sb.length() >= options.maxChars) break;
        }

        if (totalMatches == 0) {
            if (options.query != null) return "No matches found for: " + options.query;
            return "No conversation history found matching constraints.";
        }
        return sb.toString().trim();
    }

    // ── Session resolution ────────────────────────────────────────────────────

    @Nullable
    private static List<EntryData> loadSessionEntries(SessionStoreV2 store, String basePath, String fileParam) {
        if (CONVERSATION_CURRENT.equalsIgnoreCase(fileParam)) {
            return store.loadEntries(basePath);
        }
        // Try as session UUID
        List<EntryData> entries = store.loadEntriesBySessionId(basePath, fileParam);
        if (entries != null) return entries;

        // Try partial match against known session IDs (backward compat with archive timestamps)
        List<SessionStoreV2.SessionRecord> sessions = store.listSessions(basePath);
        for (SessionStoreV2.SessionRecord rec : sessions) {
            if (rec.id().contains(fileParam) || rec.name().contains(fileParam)) {
                return store.loadEntriesBySessionId(basePath, rec.id());
            }
        }
        return null;
    }

    private static Map<String, List<EntryData>> collectSessionEntries(
        SessionStoreV2 store, String basePath, @Nullable String fileParam) {
        Map<String, List<EntryData>> result = new LinkedHashMap<>();

        if (fileParam != null) {
            List<EntryData> entries = loadSessionEntries(store, basePath, fileParam);
            if (entries != null && !entries.isEmpty()) {
                String label = CONVERSATION_CURRENT.equalsIgnoreCase(fileParam) ? CONVERSATION_CURRENT : fileParam;
                result.put(label, entries);
            }
            return result;
        }

        String currentId = addCurrentSession(store, basePath, result);
        addIndexedSessions(store, basePath, currentId, result);
        return result;
    }

    private static String addCurrentSession(SessionStoreV2 store, String basePath,
                                            Map<String, List<EntryData>> result) {
        String currentId = store.getCurrentSessionId(basePath);
        List<EntryData> current = store.loadEntries(basePath);
        if (current != null && !current.isEmpty()) {
            result.put(CONVERSATION_CURRENT, current);
        }
        return currentId;
    }

    private static void addIndexedSessions(SessionStoreV2 store, String basePath, String currentId,
                                           Map<String, List<EntryData>> result) {
        for (SessionStoreV2.SessionRecord rec : store.listSessions(basePath)) {
            addIndexedSession(store, basePath, currentId, result, rec);
        }
    }

    private static void addIndexedSession(SessionStoreV2 store, String basePath, String currentId,
                                          Map<String, List<EntryData>> result,
                                          SessionStoreV2.SessionRecord rec) {
        if (rec.id().equals(currentId)) return;
        List<EntryData> entries = store.loadEntriesBySessionId(basePath, rec.id());
        if (entries == null || entries.isEmpty()) return;
        String label = !rec.name().isEmpty() ? rec.name() : rec.id();
        result.put(label, entries);
    }

    // ── Entries → text conversion ─────────────────────────────────────────────

    private static String entriesToText(List<EntryData> entries, FilterOptions options) {
        try {
            // 1. Filter by time
            entries = filterByTime(entries, options);

            // 2. Filter by turns (last_n and offset)
            entries = filterByTurns(entries, options);

            // 3. Format and filter by query
            return formatAndFilterEntries(entries, options);
        } catch (Exception e) {
            return "Error processing entries: " + e.getMessage();
        }
    }

    // ── Filtering ─────────────────────────────────────────────────────────────

    private static List<EntryData> filterByTime(List<EntryData> entries, FilterOptions options) {
        if (options.since == null && options.until == null) return entries;
        return entries.stream()
            .filter(e -> isWithinTimeRange(e, options.since, options.until))
            .toList();
    }

    private static List<EntryData> filterByTurns(List<EntryData> entries, FilterOptions options) {
        List<Integer> promptIndices = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) instanceof EntryData.Prompt) {
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

    private static List<EntryData> filterByTurnId(List<EntryData> entries,
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

    // ── Formatting ────────────────────────────────────────────────────────────

    private static String formatAndFilterEntries(List<EntryData> entries, FilterOptions options) {
        StringBuilder sb = new StringBuilder();
        for (EntryData entry : entries) {
            String line = formatConversationEntry(entry);
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

    private static String formatConversationEntry(EntryData e) {
        return switch (e) {
            case EntryData.Prompt p -> formatPrompt(p);
            case EntryData.Text t -> t.getRaw().trim();
            case EntryData.Thinking t -> formatThinking(t);
            case EntryData.ToolCall t -> formatToolCall(t);
            case EntryData.SubAgent s -> "SubAgent: " + s.getAgentType() + " — " + s.getDescription();
            case EntryData.ContextFiles ignored -> "Context files attached";
            case EntryData.Status s -> formatStatus(s);
            case EntryData.SessionSeparator s -> "--- Session " + formatTimestamp(s.getTimestamp()) + " ---";
            default -> null;
        };
    }

    private static String formatPrompt(EntryData.Prompt prompt) {
        String ts = prompt.getTimestamp().isEmpty() ? "" : " [" + formatTimestamp(prompt.getTimestamp()) + "]";
        return ">>> " + prompt.getText() + ts;
    }

    private static String formatThinking(EntryData.Thinking thinking) {
        String raw = thinking.getRaw().trim();
        return raw.isEmpty() ? null : "[thinking] " + raw;
    }

    private static String formatToolCall(EntryData.ToolCall toolCall) {
        String toolArgs = toolCall.getArguments() != null ? toolCall.getArguments() : "";
        return toolCall.getTitle() + (toolArgs.isEmpty() ? "" : " " + toolArgs);
    }

    private static String formatStatus(EntryData.Status status) {
        return status.getMessage().isEmpty() ? null : "Status: " + status.getMessage();
    }

    // ── Time helpers ──────────────────────────────────────────────────────────

    private static boolean isWithinTimeRange(EntryData e, Instant since, Instant until) {
        String tsStr = e.getTimestamp();
        if (tsStr.isEmpty()) return true;
        try {
            Instant ts = Instant.parse(tsStr);
            return (since == null || !ts.isBefore(since)) && (until == null || !ts.isAfter(until));
        } catch (Exception ex) {
            return true;
        }
    }

    private static boolean isMatchingEntry(String line, String searchQuery) {
        if (line == null || line.isEmpty()) return false;
        return searchQuery == null || line.toLowerCase(Locale.ROOT).contains(searchQuery);
    }

    // ── Search result assembly ────────────────────────────────────────────────

    private static int appendSessionSearchResult(String label, List<EntryData> entries,
                                                 FilterOptions options, StringBuilder sb) {
        String result = entriesToText(entries, options);
        if (result.isEmpty()) return 0;

        String lowerQuery = options.query;
        long matchCount = lowerQuery == null ? 1 : result.lines()
                                                   .filter(l -> l.toLowerCase(Locale.ROOT).contains(lowerQuery))
                                                   .count();
        sb.append("── ").append(label).append(" (").append(matchCount).append(" matches) ──\n");
        sb.append(result).append("\n");
        return (int) matchCount;
    }

    // ── Display formatting ────────────────────────────────────────────────────

    /**
     * Formats a stored timestamp for human-readable display.
     * Handles ISO 8601 (e.g. "2026-03-12T14:29:47Z") as well as legacy formats
     * for backward compatibility.
     */
    private static String formatTimestamp(String ts) {
        try {
            ZonedDateTime zdt = Instant.parse(ts).atZone(ZoneId.systemDefault());
            return DISPLAY_FMT.format(zdt);
        } catch (Exception e) {
            return ts;
        }
    }

    private static String formatEpochMillis(long epochMillis) {
        if (epochMillis <= 0) return "unknown";
        try {
            ZonedDateTime zdt = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault());
            return DISPLAY_FMT.format(zdt);
        } catch (Exception e) {
            return "unknown";
        }
    }
}
