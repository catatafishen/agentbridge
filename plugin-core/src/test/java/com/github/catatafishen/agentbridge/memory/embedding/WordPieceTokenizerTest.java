package com.github.catatafishen.agentbridge.memory.embedding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link WordPieceTokenizer}. Uses a minimal synthetic vocabulary
 * with the required special tokens ([PAD], [UNK], [CLS], [SEP]) and a handful
 * of real word-piece entries to exercise tokenization, truncation, and padding.
 */
class WordPieceTokenizerTest {

    private static final int MAX_SEQ = 16;

    @TempDir
    Path tempDir;

    private WordPieceTokenizer tokenizer;

    @BeforeEach
    void setUp() throws IOException {
        // Build a minimal vocab.txt with special tokens + some word pieces
        List<String> vocabLines = List.of(
            "[PAD]",    // 0
            "[UNK]",    // 1
            "[CLS]",    // 2
            "[SEP]",    // 3
            "hello",    // 4
            "world",    // 5
            "java",     // 6
            "##script", // 7
            "test",     // 8
            "##ing",    // 9
            "the",      // 10
            "a",        // 11
            "is",       // 12
            "##s",      // 13
            "code",     // 14
            "##r",      // 15
            "run",      // 16
            "##time",   // 17
            "in",       // 18
            "##put",    // 19
            "plug",     // 20
            "##in",     // 21
            "memory"    // 22
        );
        Path vocabPath = tempDir.resolve("vocab.txt");
        Files.write(vocabPath, vocabLines, StandardCharsets.UTF_8);
        tokenizer = new WordPieceTokenizer(vocabPath, MAX_SEQ);
    }

    @Test
    void basicTokenization() {
        WordPieceTokenizer.TokenizedInput result = tokenizer.tokenize("hello world");
        assertEquals(MAX_SEQ, result.sequenceLength());
        // First token is always [CLS]=2
        assertEquals(2, result.inputIds()[0]);
        // "hello" -> 4, "world" -> 5
        assertEquals(4, result.inputIds()[1]);
        assertEquals(5, result.inputIds()[2]);
        // [SEP]=3 follows the last real token
        assertEquals(3, result.inputIds()[3]);
        // Rest is [PAD]=0
        for (int i = 4; i < MAX_SEQ; i++) {
            assertEquals(0, result.inputIds()[i]);
        }
    }

    @Test
    void attentionMaskReflectsRealTokens() {
        WordPieceTokenizer.TokenizedInput result = tokenizer.tokenize("hello");
        // [CLS], hello, [SEP] => 3 real tokens
        assertEquals(1, result.attentionMask()[0]);
        assertEquals(1, result.attentionMask()[1]);
        assertEquals(1, result.attentionMask()[2]);
        assertEquals(0, result.attentionMask()[3]);
    }

    @Test
    void tokenTypeIdsAllZeroForSingleSentence() {
        WordPieceTokenizer.TokenizedInput result = tokenizer.tokenize("hello world test");
        for (int i = 0; i < MAX_SEQ; i++) {
            assertEquals(0, result.tokenTypeIds()[i]);
        }
    }

    @Test
    void subWordTokenization() {
        // "testing" should split into "test" (8) + "##ing" (9)
        WordPieceTokenizer.TokenizedInput result = tokenizer.tokenize("testing");
        assertEquals(2, result.inputIds()[0]); // [CLS]
        assertEquals(8, result.inputIds()[1]); // "test"
        assertEquals(9, result.inputIds()[2]); // "##ing"
        assertEquals(3, result.inputIds()[3]); // [SEP]
    }

    @Test
    void unknownWordMapsToUnk() {
        // "xyz" is not in vocab — each char should produce [UNK]=1
        WordPieceTokenizer.TokenizedInput result = tokenizer.tokenize("xyz");
        assertEquals(2, result.inputIds()[0]); // [CLS]
        boolean hasUnk = false;
        for (int i = 1; i < 4 && i < result.sequenceLength(); i++) {
            if (result.inputIds()[i] == 1) {
                hasUnk = true;
                break;
            }
        }
        assertTrue(hasUnk, "Expected at least one [UNK] token for unknown word");
    }

    @Test
    void truncationAtMaxSeqLength() {
        // With MAX_SEQ=16, sending many words should be truncated
        String longText = "hello world test java code run memory the a is hello world test java code";
        WordPieceTokenizer.TokenizedInput result = tokenizer.tokenize(longText);
        assertEquals(MAX_SEQ, result.sequenceLength());
        // First is [CLS]
        assertEquals(2, result.inputIds()[0]);
        // Last real token position should be [SEP]
        // Find the last non-PAD token
        int lastRealIdx = MAX_SEQ - 1;
        while (lastRealIdx > 0 && result.inputIds()[lastRealIdx] == 0) {
            lastRealIdx--;
        }
        assertEquals(3, result.inputIds()[lastRealIdx]); // [SEP] must always be present
    }

    @Test
    void emptyStringProducesOnlySpecialTokens() {
        WordPieceTokenizer.TokenizedInput result = tokenizer.tokenize("");
        assertEquals(2, result.inputIds()[0]); // [CLS]
        assertEquals(3, result.inputIds()[1]); // [SEP]
        for (int i = 2; i < MAX_SEQ; i++) {
            assertEquals(0, result.inputIds()[i]); // all [PAD]
        }
    }

    @Test
    void lowercasesInput() {
        // "HELLO" should tokenize the same as "hello" -> id 4
        WordPieceTokenizer.TokenizedInput result = tokenizer.tokenize("HELLO");
        assertEquals(4, result.inputIds()[1]);
    }

    @Test
    void normalizesWhitespace() {
        // Tabs and newlines should be treated as spaces
        WordPieceTokenizer.TokenizedInput result = tokenizer.tokenize("hello\tworld\ntest");
        assertEquals(4, result.inputIds()[1]); // "hello"
        assertEquals(5, result.inputIds()[2]); // "world"
        assertEquals(8, result.inputIds()[3]); // "test"
    }

    @Test
    void multipleSubWordPieces() {
        // "javascript" -> "java" (6) + "##script" (7)
        WordPieceTokenizer.TokenizedInput result = tokenizer.tokenize("javascript");
        assertEquals(6, result.inputIds()[1]); // "java"
        assertEquals(7, result.inputIds()[2]); // "##script"
        assertEquals(3, result.inputIds()[3]); // [SEP]
    }

    @Test
    void vocabMissingRequiredTokenThrows() throws IOException {
        List<String> badVocab = List.of("[PAD]", "[UNK]", "hello"); // missing [CLS] and [SEP]
        Path badVocabPath = tempDir.resolve("bad-vocab.txt");
        Files.write(badVocabPath, badVocab, StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> new WordPieceTokenizer(badVocabPath, MAX_SEQ));
    }

    @Test
    void pluginSubWordTokenization() {
        // "plugin" -> "plug" (20) + "##in" (21)
        WordPieceTokenizer.TokenizedInput result = tokenizer.tokenize("plugin");
        assertEquals(20, result.inputIds()[1]); // "plug"
        assertEquals(21, result.inputIds()[2]); // "##in"
    }

    @Test
    void sequenceLengthMatchesConstructorParam() {
        WordPieceTokenizer.TokenizedInput result = tokenizer.tokenize("hello");
        assertEquals(MAX_SEQ, result.inputIds().length);
        assertEquals(MAX_SEQ, result.attentionMask().length);
        assertEquals(MAX_SEQ, result.tokenTypeIds().length);
    }
}
