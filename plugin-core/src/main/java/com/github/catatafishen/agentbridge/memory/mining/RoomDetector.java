package com.github.catatafishen.agentbridge.memory.mining;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects the "room" (topic category) for a memory drawer using keyword scoring.
 *
 * <p><b>Attribution:</b> room detection heuristics adapted from MemPalace's
 * detect_convo_room() in convo_miner.py (MIT License).
 *
 * <p>Rooms: technical, architecture, planning, decisions, problems, general.
 */
public final class RoomDetector {

    private static final String ROOM_TECHNICAL = "technical";
    private static final String ROOM_ARCHITECTURE = "architecture";
    private static final String ROOM_PLANNING = "planning";
    private static final String ROOM_DECISIONS = "decisions";
    private static final String ROOM_PROBLEMS = "problems";
    private static final String ROOM_GENERAL = "general";

    /**
     * Keywords per room. Each match adds 1 to that room's score.
     */
    private static final Map<String, List<String>> ROOM_KEYWORDS = buildRoomKeywords();

    private RoomDetector() {
    }

    /**
     * Detect the best-fit room for the given text.
     *
     * @param text combined prompt + response text
     * @return room name (lowercase, suitable for use as DrawerDocument.room)
     */
    public static @NotNull String detect(@NotNull String text) {
        String lowerText = text.toLowerCase();

        String bestRoom = ROOM_GENERAL;
        int bestScore = 0;

        for (var entry : ROOM_KEYWORDS.entrySet()) {
            int score = 0;
            for (String keyword : entry.getValue()) {
                if (lowerText.contains(keyword)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestRoom = entry.getKey();
            }
        }

        return bestRoom;
    }

    private static Map<String, List<String>> buildRoomKeywords() {
        Map<String, List<String>> keywords = new LinkedHashMap<>();

        keywords.put(ROOM_TECHNICAL, List.of(
            "code", "function", "method", "class", "variable", "import",
            "compile", "build", "test", "debug", "exception", "stack trace",
            "dependency", "library", "framework", "runtime", "performance",
            "thread", "async", "concurrent", "memory", "cache"
        ));

        keywords.put(ROOM_ARCHITECTURE, List.of(
            ROOM_ARCHITECTURE, "design", "pattern", "module", "component",
            "service", "layer", "interface", "abstraction", "coupling",
            "cohesion", "separation of concerns", "solid", "clean",
            "hexagonal", "microservice", "monolith", "plugin"
        ));

        keywords.put(ROOM_PLANNING, List.of(
            "plan", "roadmap", "milestone", "sprint", "phase",
            "priority", "backlog", "schedule", "deadline", "scope",
            "estimate", "requirement", "specification", "feature"
        ));

        keywords.put(ROOM_DECISIONS, List.of(
            "decision", "chose", "trade-off", "tradeoff", "alternative",
            "option", "pros and cons", "instead of", "went with",
            "reasoning", "rationale", "because", "justification"
        ));

        keywords.put(ROOM_PROBLEMS, List.of(
            "bug", "issue", "problem", "broken", "crash", "error",
            "fail", "regression", "root cause", "workaround", "fix",
            "debug", "investigate", "reproduce", "stack trace"
        ));

        return Map.copyOf(keywords);
    }
}
