package com.github.catatafishen.agentbridge.memory.kg;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts subject-predicate-object triples from conversation exchange text
 * using pattern-based heuristics.
 *
 * <p>Designed for developer conversations — captures technology decisions,
 * preferences, dependencies, implementations, and problem resolutions.
 *
 * <p>Input text is preprocessed to strip markdown formatting and split
 * into sentences before pattern matching. This prevents false matches
 * from markdown artifacts (e.g. {@code **bold**}) and cross-sentence
 * regex captures.
 *
 * <p>Each pattern targets a common conversational structure and maps it
 * to a structured triple suitable for {@link KnowledgeGraph} storage.
 */
public final class TripleExtractor {

    private static final int MAX_OBJECT_LENGTH = 120;
    private static final int MAX_TRIPLES_PER_TEXT = 8;
    private static final int MIN_OBJECT_LENGTH = 3;
    private static final int MAX_OBJECT_WORDS = 8;

    private static final List<ExtractionRule> RULES = buildRules();

    /**
     * Words that should not constitute the entire object of a triple.
     * An object is rejected only when ALL of its words are in this set.
     * Individual stopwords within a larger phrase are fine (e.g. "the plugin
     * classloader" passes because "plugin" and "classloader" are not stopwords).
     */
    private static final Set<String> STOPWORDS = Set.of(
        "a", "an", "the", "this", "that", "these", "those",
        "it", "its", "they", "them", "we", "i", "my", "our", "you", "your",
        "is", "are", "was", "were", "be", "been", "being",
        "do", "does", "did", "has", "have", "had",
        "not", "no", "so", "then", "also", "just", "only",
        "very", "really", "quite", "some", "any", "all",
        "new", "old", "same", "other",
        "method", "function", "class", "file", "code",
        "data", "value", "object", "thing", "way",
        "type", "part", "memory", "system", "one"
    );

    private TripleExtractor() {
    }

    /**
     * Negation words that appear before a verb phrase and invert its meaning.
     * When detected within 4 words before the pattern match, the triple is
     * skipped entirely (e.g., "Never use eval()" should not produce
     * {@code (project, uses, eval())}).
     */
    private static final Pattern NEGATION_PATTERN = Pattern.compile(
        "\\b(never|don't|do not|doesn't|does not|should not|shouldn't|"
            + "avoid|stop using|must not|mustn't|cannot|can't|won't|will not)\\b",
        Pattern.CASE_INSENSITIVE);

    /**
     * Trailing words that weaken an extracted object and should be trimmed.
     * E.g., "JWT for authentication and" → "JWT for authentication".
     */
    private static final Set<String> TRAILING_TRIM_WORDS = Set.of(
        "and", "or", "but", "so", "then", "because", "since", "when",
        "where", "which", "that", "to", "for", "with", "from", "by",
        "as", "at", "on", "in", "of");

    /**
     * Extract structured triples from exchange text.
     *
     * <p>Text is first stripped of markdown formatting (code blocks, bold/italic,
     * headers, URLs, etc.) then split into individual sentences. Patterns are
     * applied per sentence to prevent cross-boundary false matches.
     *
     * @param text     combined prompt + response text
     * @param wing     project wing name (used as default subject)
     * @param drawerId source drawer ID for provenance tracking
     * @return list of extracted triples (may be empty, never null)
     */
    public static @NotNull List<ExtractedTriple> extract(@NotNull String text,
                                                         @NotNull String wing,
                                                         @NotNull String drawerId) {
        String cleaned = stripMarkdown(text);
        List<String> sentences = splitSentences(cleaned);
        List<ExtractedTriple> triples = new ArrayList<>();

        for (String sentence : sentences) {
            if (triples.size() >= MAX_TRIPLES_PER_TEXT) break;
            extractFromSentence(sentence, wing, drawerId, triples);
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

    /**
     * Strip markdown formatting and tool-call fragments from text.
     * Delegates to {@link com.github.catatafishen.agentbridge.memory.mining.TextPreprocessor#stripMarkdown(String)}.
     */
    static @NotNull String stripMarkdown(@NotNull String text) {
        return com.github.catatafishen.agentbridge.memory.mining.TextPreprocessor.stripMarkdown(text);
    }

    /**
     * Split text into individual sentences for per-sentence pattern matching.
     * Splits on newlines and on sentence-ending punctuation followed by an
     * uppercase letter (standard sentence boundary heuristic).
     */
    static @NotNull List<String> splitSentences(@NotNull String text) {
        String[] lines = text.split("\\n+");
        List<String> sentences = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            // Further split on sentence boundaries within the line
            String[] subSentences = trimmed.split("(?<=[.!?])\\s+(?=[A-Z])");
            for (String s : subSentences) {
                String ss = s.strip();
                if (!ss.isEmpty()) {
                    sentences.add(ss);
                }
            }
        }
        return sentences;
    }

    /**
     * Check whether an extracted object is specific enough to be useful in the KG.
     * Rejects objects that are too short, too long (word count), or consist
     * entirely of stopwords (e.g. "the memory", "a new method").
     */
    static boolean isQualityObject(@NotNull String object) {
        if (object.length() < MIN_OBJECT_LENGTH) return false;

        String[] words = object.toLowerCase().split("[\\s-]+");
        if (words.length > MAX_OBJECT_WORDS) return false;

        // Reject if ALL words are stopwords
        for (String word : words) {
            String cleaned = word.replaceAll("[^a-z]", "");
            if (!cleaned.isEmpty() && !STOPWORDS.contains(cleaned)) {
                return true;
            }
        }
        return false;
    }

    private static void extractFromSentence(@NotNull String sentence, @NotNull String wing,
                                            @NotNull String drawerId,
                                            @NotNull List<ExtractedTriple> triples) {
        for (ExtractionRule rule : RULES) {
            if (triples.size() >= MAX_TRIPLES_PER_TEXT) return;
            ExtractedTriple triple = tryMatchRule(rule, sentence, wing, drawerId, triples);
            if (triple != null) {
                triples.add(triple);
            }
        }
    }

    private static ExtractedTriple tryMatchRule(@NotNull ExtractionRule rule,
                                                @NotNull String sentence,
                                                @NotNull String wing,
                                                @NotNull String drawerId,
                                                @NotNull List<ExtractedTriple> existing) {
        Matcher matcher = rule.pattern.matcher(sentence);
        if (!matcher.find()) return null;

        // Negation guard: check if the sentence contains negation before the match
        String prefix = sentence.substring(0, matcher.start());
        if (hasNegation(prefix) || hasNegation(sentence)) return null;

        String rawObject = matcher.group(rule.objectGroup).strip();
        String object = cleanObject(rawObject);
        if (!isQualityObject(object)) return null;

        String subject = rule.subjectGroup > 0
            ? cleanSubject(matcher.group(rule.subjectGroup))
            : wing;
        if (subject.isEmpty()) subject = wing;

        if (isDuplicate(existing, rule.predicate, object)) return null;
        return new ExtractedTriple(subject, rule.predicate, object, drawerId);
    }

    /**
     * Check whether a text fragment contains negation that would invert the
     * meaning of an extraction rule match (e.g. "don't use eval").
     */
    private static boolean hasNegation(@NotNull String text) {
        return NEGATION_PATTERN.matcher(text).find();
    }

    private static boolean isDuplicate(@NotNull List<ExtractedTriple> existing,
                                       @NotNull String predicate, @NotNull String object) {
        String objectLower = object.toLowerCase();
        return existing.stream().anyMatch(t ->
            t.predicate().equals(predicate)
                && t.object().toLowerCase().equals(objectLower));
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
        cleaned = trimTrailingWeakWords(cleaned);
        return cleaned;
    }

    /**
     * Remove trailing prepositions and conjunctions from an extracted object.
     * These are captured by the greedy regex but add no semantic value.
     * E.g., "JWT for authentication and" → "JWT for authentication".
     */
    private static @NotNull String trimTrailingWeakWords(@NotNull String text) {
        String result = text;
        boolean trimmed = true;
        while (trimmed) {
            trimmed = false;
            int lastSpace = result.lastIndexOf(' ');
            if (lastSpace > 0) {
                String lastWord = result.substring(lastSpace + 1).toLowerCase();
                if (TRAILING_TRIM_WORDS.contains(lastWord)) {
                    result = result.substring(0, lastSpace).strip();
                    trimmed = true;
                }
            }
        }
        return result;
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
        // Object terminator: sentence-ending punctuation, clause boundary, or end of string.
        // Clause boundaries ("by", "instead", "because", etc.) prevent over-capturing
        // subordinate clauses as part of the object.
        String end = "(?=[.,;!?\\n]|\\s+(?:by|because|since|when|instead|rather|although|while|so that)\\b|$)";
        List<ExtractionRule> rules = new ArrayList<>();

        // Decision: "decided to use X", "chose X", "went with X"
        rules.add(new ExtractionRule(
            Pattern.compile("(?:decided to|chose|went with|going with)\\s+(.+?)" + end,
                Pattern.CASE_INSENSITIVE),
            "decided", 0, 1));

        // Usage with subject: "The auth module uses JWT" → auth-module → uses → JWT
        // Requires "the" or "our" prefix to avoid matching pronouns as subjects
        rules.add(new ExtractionRule(
            Pattern.compile("(?:the |our )(\\w[\\w -]{1,25})\\s+uses?\\s+(.+?)" + end,
                Pattern.CASE_INSENSITIVE),
            "uses", 1, 2));

        // Usage without subject: "we use X", "using X"
        rules.add(new ExtractionRule(
            Pattern.compile("(?:we |i )?(?:use|using)\\s+(.+?)" + end,
                Pattern.CASE_INSENSITIVE),
            "uses", 0, 1));

        // Preference: "prefer X", "always use X"
        rules.add(new ExtractionRule(
            Pattern.compile("(?:we |i )?(?:prefer|prefers|always use)\\s+(.+?)" + end,
                Pattern.CASE_INSENSITIVE),
            "prefers", 0, 1));

        // Dependency with subject: "The plugin depends on X"
        // Requires "the" or "our" prefix to avoid matching pronouns as subjects
        rules.add(new ExtractionRule(
            Pattern.compile("(?:the |our )(\\w[\\w -]{1,25})\\s+(?:depends on|requires|needs)\\s+(.+?)" + end,
                Pattern.CASE_INSENSITIVE),
            "depends-on", 1, 2));

        // Dependency without subject: "depends on X"
        rules.add(new ExtractionRule(
            Pattern.compile("(?:depends on|requires|needs)\\s+(.+?)" + end,
                Pattern.CASE_INSENSITIVE),
            "depends-on", 0, 1));

        // Implementation: "implemented X", "created X", "added X"
        rules.add(new ExtractionRule(
            Pattern.compile("(?:implemented|created|added|built)\\s+(?:the |a |an )?(.+?)" + end,
                Pattern.CASE_INSENSITIVE),
            "implemented", 0, 1));

        // Resolution: "fixed X", "resolved X", "solved X"
        rules.add(new ExtractionRule(
            Pattern.compile("(?:fixed|resolved|solved)\\s+(?:the |a |an )?(.+?)" + end,
                Pattern.CASE_INSENSITIVE),
            "resolved", 0, 1));

        // Root cause: "root cause was X", "caused by X"
        rules.add(new ExtractionRule(
            Pattern.compile("(?:root cause (?:is|was)|caused by|due to)\\s+(.+?)" + end,
                Pattern.CASE_INSENSITIVE),
            "caused-by", 0, 1));

        // Technology stack: "written in X", "built with X"
        rules.add(new ExtractionRule(
            Pattern.compile("(?:written in|built with|powered by|runs on)\\s+(.+?)" + end,
                Pattern.CASE_INSENSITIVE),
            "built-with", 0, 1));

        return List.copyOf(rules);
    }
}
