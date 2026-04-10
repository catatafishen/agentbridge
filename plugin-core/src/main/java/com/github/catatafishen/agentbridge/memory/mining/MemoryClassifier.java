package com.github.catatafishen.agentbridge.memory.mining;

import com.github.catatafishen.agentbridge.memory.store.DrawerDocument;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Classifies exchange chunks into one of 4 memory types using regex marker scoring.
 *
 * <p><b>Attribution:</b> classification markers and disambiguation logic adapted from
 * MemPalace's general_extractor.py (MIT License).
 *
 * <p>Types: context, decision, problem, solution.
 * Falls back to {@link DrawerDocument#TYPE_GENERAL} if no markers match.
 *
 * <p>Types are orthogonal to rooms (see {@link RoomDetector}): type = "what kind
 * of knowledge", room = "what topic area".
 */
public final class MemoryClassifier {

    private static final Map<String, List<Pattern>> TYPE_MARKERS = buildTypeMarkers();

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
            Pattern.compile("\\btrade-off\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bpros and cons\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brationale\\b", Pattern.CASE_INSENSITIVE)
        ));

        markers.put(DrawerDocument.TYPE_PROBLEM, List.of(
            Pattern.compile("\\bbug\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bbroken\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\broot cause\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bworkaround\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bfailing\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcrash\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bregression\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdoesn't work\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcan't\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bunable to\\b", Pattern.CASE_INSENSITIVE)
        ));

        markers.put(DrawerDocument.TYPE_SOLUTION, List.of(
            Pattern.compile("\\bfixed\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bresolved\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bworking now\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bthat solved\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bthe fix\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bsolution\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bthe key was\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bturns out\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bfigured out\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bby changing\\b", Pattern.CASE_INSENSITIVE)
        ));

        markers.put(DrawerDocument.TYPE_CONTEXT, List.of(
            Pattern.compile("\\barchitecture\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bpattern\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brefactor\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\binterface\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\babstraction\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bimplementation\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bstructure\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bresponsible for\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bconsists of\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bworks by\\b", Pattern.CASE_INSENSITIVE)
        ));

        return Map.copyOf(markers);
    }
}
