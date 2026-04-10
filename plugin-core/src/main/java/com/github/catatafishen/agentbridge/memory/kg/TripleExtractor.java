package com.github.catatafishen.agentbridge.memory.kg;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts subject-predicate-object triples from conversation exchange text
 * using pattern-based heuristics.
 *
 * <p>Designed for developer conversations — captures technology decisions,
 * preferences, dependencies, implementations, and problem resolutions.
 *
 * <p>Each pattern targets a common conversational structure and maps it
 * to a structured triple suitable for {@link KnowledgeGraph} storage.
 */
public final class TripleExtractor {

    private static final int MAX_OBJECT_LENGTH = 120;
    private static final int MAX_TRIPLES_PER_TEXT = 8;

    private static final List<ExtractionRule> RULES = buildRules();

    private TripleExtractor() {
    }

    /**
     * Extract structured triples from exchange text.
     *
     * @param text     combined prompt + response text
     * @param wing     project wing name (used as default subject)
     * @param drawerId source drawer ID for provenance tracking
     * @return list of extracted triples (may be empty, never null)
     */
    public static @NotNull List<ExtractedTriple> extract(@NotNull String text,
                                                          @NotNull String wing,
                                                          @NotNull String drawerId) {
        List<ExtractedTriple> triples = new ArrayList<>();

        for (ExtractionRule rule : RULES) {
            Matcher matcher = rule.pattern.matcher(text);
            while (matcher.find() && triples.size() < MAX_TRIPLES_PER_TEXT) {
                String rawObject = matcher.group(rule.objectGroup).strip();
                String object = cleanObject(rawObject);
                if (object.isEmpty() || object.length() < 3) continue;

                String subject = rule.subjectGroup > 0
                    ? cleanSubject(matcher.group(rule.subjectGroup))
                    : wing;

                triples.add(new ExtractedTriple(subject, rule.predicate, object, drawerId));
            }
        }

        return triples;
    }

    /**
     * A triple extracted from conversation text, ready for KG storage.
     */
    public record ExtractedTriple(
        @NotNull String subject,
        @NotNull String predicate,
        @NotNull String object,
        @NotNull String sourceDrawerId
    ) {
    }

    private static @NotNull String cleanObject(@NotNull String raw) {
        String cleaned = raw.replaceAll("[.,:;!?]+$", "").strip();
        if (cleaned.length() > MAX_OBJECT_LENGTH) {
            int cutoff = cleaned.lastIndexOf(' ', MAX_OBJECT_LENGTH);
            if (cutoff > 30) {
                cleaned = cleaned.substring(0, cutoff);
            } else {
                cleaned = cleaned.substring(0, MAX_OBJECT_LENGTH);
            }
        }
        return cleaned;
    }

    private static @NotNull String cleanSubject(@NotNull String raw) {
        return raw.strip().toLowerCase().replaceAll("\\s+", "-");
    }

    private record ExtractionRule(
        @NotNull Pattern pattern,
        @NotNull String predicate,
        int subjectGroup,
        int objectGroup
    ) {
    }

    private static List<ExtractionRule> buildRules() {
        List<ExtractionRule> rules = new ArrayList<>();

        // Decision patterns: "decided to use X", "went with X", "chose X"
        rules.add(new ExtractionRule(
            Pattern.compile("(?:we |i )?(?:decided to|chose|went with|going with)\\s+(.+?)(?:\\s+(?:because|since|due to|for|instead)|[.\\n])",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
            "decided", 0, 1));

        // Usage patterns: "we use X", "project uses X", "using X for"
        rules.add(new ExtractionRule(
            Pattern.compile("(?:we |project |it )?(?:uses?|using)\\s+(.+?)(?:\\s+(?:for|to|in|because|since|which|that|and)|[.,\\n])",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
            "uses", 0, 1));

        // Preference patterns: "prefer X", "always use X"
        rules.add(new ExtractionRule(
            Pattern.compile("(?:we |i )?(?:prefer|prefers|always use|always do)\\s+(.+?)(?:\\s+(?:over|instead|because|since|for)|[.,\\n])",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
            "prefers", 0, 1));

        // Dependency patterns: "depends on X", "requires X"
        rules.add(new ExtractionRule(
            Pattern.compile("(?:it |this )?(?:depends on|requires|needs)\\s+(.+?)(?:\\s+(?:for|to|because|in order)|[.,\\n])",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
            "depends-on", 0, 1));

        // Implementation patterns: "implemented X", "created X", "added X"
        rules.add(new ExtractionRule(
            Pattern.compile("(?:we |i )?(?:implemented|created|added|built)\\s+(?:the |a |an )?(.+?)(?:\\s+(?:for|to|in|using|that|which|with)|[.,\\n])",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
            "implemented", 0, 1));

        // Resolution patterns: "fixed X", "resolved X", "solved X"
        rules.add(new ExtractionRule(
            Pattern.compile("(?:we |i )?(?:fixed|resolved|solved)\\s+(?:the |a |an )?(.+?)(?:\\s+(?:by|with|using|via)|[.,\\n])",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
            "resolved", 0, 1));

        // Root cause patterns: "root cause was X", "caused by X"
        rules.add(new ExtractionRule(
            Pattern.compile("(?:root cause (?:is|was)|caused by|due to)\\s+(.+?)(?:\\s+(?:which|that|so|and)|[.,\\n])",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
            "caused-by", 0, 1));

        // Technology stack: "written in X", "built with X"
        rules.add(new ExtractionRule(
            Pattern.compile("(?:written in|built with|powered by|runs on)\\s+(.+?)(?:\\s+(?:and|with|for|using)|[.,\\n])",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
            "built-with", 0, 1));

        return List.copyOf(rules);
    }
}
