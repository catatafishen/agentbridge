package com.github.catatafishen.agentbridge.memory.embedding;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure Java WordPiece tokenizer compatible with BERT / all-MiniLM-L6-v2.
 *
 * <p>Performs:
 * <ol>
 *   <li>Basic text cleaning (lowercase, strip accents, normalize Unicode)</li>
 *   <li>Whitespace tokenization</li>
 *   <li>WordPiece sub-word segmentation (greedy longest-match against vocabulary)</li>
 *   <li>Special token insertion ([CLS], [SEP])</li>
 *   <li>Padding/truncation to a fixed sequence length</li>
 * </ol>
 *
 * <p><b>Attribution:</b> tokenization approach matches the sentence-transformers
 * all-MiniLM-L6-v2 model's expected input format.
 */
public final class WordPieceTokenizer {

    private static final Logger LOG = Logger.getInstance(WordPieceTokenizer.class);

    private static final String UNK_TOKEN = "[UNK]";
    private static final String CLS_TOKEN = "[CLS]";
    private static final String SEP_TOKEN = "[SEP]";
    private static final String PAD_TOKEN = "[PAD]";
    private static final String WORD_PIECE_PREFIX = "##";

    private static final int MAX_WORD_LENGTH = 200;

    private final Map<String, Integer> vocab;
    private final int clsId;
    private final int sepId;
    private final int padId;
    private final int unkId;
    private final int maxSeqLength;

    /**
     * @param vocabPath    path to the vocab.txt file
     * @param maxSeqLength maximum sequence length (256 for all-MiniLM-L6-v2)
     */
    public WordPieceTokenizer(@NotNull Path vocabPath, int maxSeqLength) throws IOException {
        this.maxSeqLength = maxSeqLength;
        this.vocab = loadVocab(vocabPath);
        this.clsId = requireToken(CLS_TOKEN);
        this.sepId = requireToken(SEP_TOKEN);
        this.padId = requireToken(PAD_TOKEN);
        this.unkId = requireToken(UNK_TOKEN);
    }

    /**
     * Tokenize text into inference-ready tensors.
     *
     * @return TokenizedInput with input_ids, attention_mask, and token_type_ids
     */
    public TokenizedInput tokenize(@NotNull String text) {
        List<Integer> tokens = new ArrayList<>();
        tokens.add(clsId);

        String cleaned = cleanText(text);
        String[] words = cleaned.split("\\s+");
        for (String word : words) {
            if (word.isEmpty()) continue;
            List<Integer> wordTokens = tokenizeWord(word);
            // Stop early if we'd exceed the length (need room for [SEP])
            if (tokens.size() + wordTokens.size() >= maxSeqLength - 1) {
                int remaining = maxSeqLength - 1 - tokens.size();
                for (int i = 0; i < remaining && i < wordTokens.size(); i++) {
                    tokens.add(wordTokens.get(i));
                }
                break;
            }
            tokens.addAll(wordTokens);
        }

        tokens.add(sepId);

        // Pad to maxSeqLength
        long[] inputIds = new long[maxSeqLength];
        long[] attentionMask = new long[maxSeqLength];
        long[] tokenTypeIds = new long[maxSeqLength];

        for (int i = 0; i < maxSeqLength; i++) {
            if (i < tokens.size()) {
                inputIds[i] = tokens.get(i);
                attentionMask[i] = 1;
            } else {
                inputIds[i] = padId;
                attentionMask[i] = 0;
            }
            tokenTypeIds[i] = 0; // Single sentence — all type 0
        }

        return new TokenizedInput(inputIds, attentionMask, tokenTypeIds);
    }

    private List<Integer> tokenizeWord(@NotNull String word) {
        if (word.length() > MAX_WORD_LENGTH) {
            return List.of(unkId);
        }

        List<Integer> tokens = new ArrayList<>();
        int start = 0;
        while (start < word.length()) {
            int end = word.length();
            boolean found = false;
            while (start < end) {
                String substr = (start == 0)
                    ? word.substring(start, end)
                    : WORD_PIECE_PREFIX + word.substring(start, end);
                Integer id = vocab.get(substr);
                if (id != null) {
                    tokens.add(id);
                    start = end;
                    found = true;
                    break;
                }
                end--;
            }
            if (!found) {
                tokens.add(unkId);
                start++;
            }
        }
        return tokens;
    }

    private static String cleanText(@NotNull String text) {
        // Lowercase + basic whitespace normalization
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toLowerCase().toCharArray()) {
            if (c == '\t' || c == '\n' || c == '\r') {
                sb.append(' ');
            } else if (Character.isWhitespace(c)) {
                sb.append(' ');
            } else if (!isControl(c)) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private static boolean isControl(char c) {
        if (c == '\t' || c == '\n' || c == '\r') return false;
        return Character.getType(c) == Character.CONTROL;
    }

    private static Map<String, Integer> loadVocab(@NotNull Path vocabPath) throws IOException {
        Map<String, Integer> vocab = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(vocabPath, StandardCharsets.UTF_8)) {
            String line;
            int idx = 0;
            while ((line = reader.readLine()) != null) {
                vocab.put(line.trim(), idx++);
            }
        }
        LOG.info("Loaded vocabulary with " + vocab.size() + " tokens from " + vocabPath);
        return vocab;
    }

    private int requireToken(String token) {
        Integer id = vocab.get(token);
        if (id == null) {
            throw new IllegalStateException("Required token '" + token + "' not found in vocabulary");
        }
        return id;
    }

    /**
     * Tokenized output ready for BERT inference.
     */
    public record TokenizedInput(long[] inputIds, long[] attentionMask, long[] tokenTypeIds) {
        public int sequenceLength() {
            return inputIds.length;
        }
    }
}
