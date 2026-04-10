package com.github.catatafishen.agentbridge.memory.mining;

import com.github.catatafishen.agentbridge.memory.store.DrawerDocument;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Classifies exchange chunks into one of 5 memory types using regex marker scoring.
 *
 * <p><b>Attribution:</b> classification markers and disambiguation logic adapted from
 * MemPalace's general_extractor.py (MIT License).
 *
 * <p>Types: decision, preference, milestone, problem, technical.
 * Falls back to {@link DrawerDocument#TYPE_GENERAL} if no markers match.
 */
public final class MemoryClassifier {

    private static final Map<String, List<Pattern>> TYPE_MARKERS = buildTypeMarkers();

    /**
     * Patterns indicating a resolved problem (should be reclassified as milestone).
     */
    private static final List<Pattern> RESOLVED_PATTERNS = List.of(
        Pattern.compile("\\bfixed\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bresolved\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bworking now\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bthat solved\\b", Pattern.CASE_INSENSITIVE)
    );

    private MemoryClassifier() {
    }

    /**
     * Classify the combined prompt + response text into a memory type.
     *
     * @param text combined text of the exchange chunk
     * @return one of the DrawerDocument.TYPE_* constants
     */
    public static @NotNull String classify(@NotNull String text) {
        String lowerText = text.toLowerCase();

        String bestType = DrawerDocument.TYPE_GENERAL;
        int bestScore = 0;

        for (var entry : TYPE_MARKERS.entrySet()) {
            int score = 0;
            for (Pattern pattern : entry.getValue()) {
                if (pattern.matcher(lowerText).find()) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestType = entry.getKey();
            }
        }

        // Disambiguation: a "problem" with resolution markers → milestone
        if (DrawerDocument.TYPE_PROBLEM.equals(bestType)) {
            for (Pattern resolved : RESOLVED_PATTERNS) {
                if (resolved.matcher(lowerText).find()) {
                    return DrawerDocument.TYPE_MILESTONE;
                }
            }
        }

        return bestType;
    }

    private static Map<String, List<Pattern>> buildTypeMarkers() {
        // LinkedHashMap preserves insertion order for deterministic tie-breaking
        Map<String, List<Pattern>> markers = new LinkedHashMap<>();

        markers.put(DrawerDocument.TYPE_DECISION, List.of(
            Pattern.compile("\\bwe went with\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\blet's use\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\binstead of\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdecided to\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bgoing with\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bchose\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\btradeoff\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\btrade-off\\b", Pattern.CASE_INSENSITIVE)
        ));

        markers.put(DrawerDocument.TYPE_PREFERENCE, List.of(
            Pattern.compile("\\bi prefer\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\balways use\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bnever do\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdon't like\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmy style\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmy convention\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bplease always\\b", Pattern.CASE_INSENSITIVE)
        ));

        markers.put(DrawerDocument.TYPE_MILESTONE, List.of(
            Pattern.compile("\\bit works\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bfinally\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bbreakthrough\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bshipped\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcompleted\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bsuccessfully\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\breleased\\b", Pattern.CASE_INSENSITIVE)
        ));

        markers.put(DrawerDocument.TYPE_PROBLEM, List.of(
            Pattern.compile("\\bbug\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bbroken\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\broot cause\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bworkaround\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bfailing\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcrash\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\berror\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bregression\\b", Pattern.CASE_INSENSITIVE)
        ));

        markers.put(DrawerDocument.TYPE_TECHNICAL, List.of(
            Pattern.compile("\\barchitecture\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bpattern\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brefactor\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bapi\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\binterface\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\babstraction\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bimplementation\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdesign\\b", Pattern.CASE_INSENSITIVE)
        ));

        return Map.copyOf(markers);
    }
}
