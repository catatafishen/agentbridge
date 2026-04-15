package com.github.catatafishen.agentbridge.memory.mining;

import com.github.catatafishen.agentbridge.memory.store.DrawerDocument;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects the "room" (topic area) for a memory drawer using keyword scoring.
 *
 * <p><b>Attribution:</b> room detection heuristics adapted from MemPalace's
 * detect_convo_room() in convo_miner.py (MIT License).
 *
 * <p>Rooms: codebase, debugging, workflow, decisions, preferences, general.
 *
 * <p>Rooms are orthogonal to types (see {@link MemoryClassifier}): room = "what
 * topic area", type = "what kind of knowledge".
 */
public final class RoomDetector {

    /**
     * Minimum weighted score required to confidently assign a room.
     * A single keyword match (score 1) is too ambiguous — falls back to general.
     */
    private static final int MIN_CONFIDENCE = 2;

    /**
     * Keywords per room. Each match adds 1 to that room's score.
     */
    private static final Map<String, List<String>> ROOM_KEYWORDS = buildRoomKeywords();

    private RoomDetector() {
    }

    /**
     * Detect the best-fit room for the given text.
     * Requires at least {@code MIN_CONFIDENCE} keyword matches to classify;
     * otherwise falls back to "general". Multi-word phrases score higher
     * (weight 2) than single-word matches (weight 1) since they're more specific.
     *
     * @param text combined prompt + response text
     * @return room name (lowercase, suitable for use as DrawerDocument.room)
     */
    public static @NotNull String detect(@NotNull String text) {
        String lowerText = text.toLowerCase();

        String bestRoom = DrawerDocument.ROOM_GENERAL;
        int bestScore = 0;

        for (var entry : ROOM_KEYWORDS.entrySet()) {
            int score = 0;
            for (String keyword : entry.getValue()) {
                if (lowerText.contains(keyword)) {
                    // Multi-word phrases are more specific → higher weight
                    score += keyword.contains(" ") ? 2 : 1;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestRoom = entry.getKey();
            }
        }

        return bestScore >= MIN_CONFIDENCE ? bestRoom : DrawerDocument.ROOM_GENERAL;
    }

    private static Map<String, List<String>> buildRoomKeywords() {
        Map<String, List<String>> keywords = new LinkedHashMap<>();

        keywords.put(DrawerDocument.ROOM_CODEBASE, List.of(
            "class", "method", "function", "field", "type",
            "interface", "annotation", "module", "component", "service",
            "endpoint", "api", "implementation", "behavior",
            "inherits", "extends", "overrides", "dependency",
            "library", "version", "configuration", "property"
        ));

        keywords.put(DrawerDocument.ROOM_DEBUGGING, List.of(
            "bug", "crash", "error", "exception", "stack trace",
            "fail", "regression", "root cause", "workaround",
            "null pointer", "timeout", "deadlock", "race condition",
            "memory leak", "investigate", "reproduce", "debug"
        ));

        keywords.put(DrawerDocument.ROOM_WORKFLOW, List.of(
            "build", "gradle", "maven", "npm", "deploy",
            "ci", "cd", "pipeline", "release", "docker",
            "environment", "staging", "production", "lint",
            "format", "script", "tool", "automation", "git",
            "branch", "merge", "test runner"
        ));

        keywords.put(DrawerDocument.ROOM_DECISIONS, List.of(
            "decision", "chose", "trade-off", "tradeoff", "alternative",
            "option", "pros and cons", "instead of", "went with",
            "reasoning", "rationale", "because", "justification",
            "evaluated", "approach", "strategy"
        ));

        keywords.put(DrawerDocument.ROOM_PREFERENCES, List.of(
            "prefer", "always use", "never do", "don't like",
            "my style", "my convention", "please always",
            "naming", "formatting", "style", "convention", "rule",
            "standard", "consistent", "principle"
        ));

        return Map.copyOf(keywords);
    }
}
