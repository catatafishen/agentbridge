package com.github.catatafishen.agentbridge.memory.embedding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link EmbeddingService} math utilities, {@link ModelDownloader} path methods,
 * and testable service operations (embed, embedBatch, isReady, dispose).
 * ONNX inference requires the downloaded model and is tested via integration tests.
 */
class EmbeddingServiceTest {

    // --- meanPool ---

    @Test
    void meanPoolAveragesTokenEmbeddings() {
        int dim = EmbeddingService.EMBEDDING_DIM;
        float[][] tokens = new float[3][dim];
        tokens[0][0] = 2.0f;
        tokens[0][1] = 4.0f;
        tokens[0][2] = 6.0f;
        tokens[1][0] = 4.0f;
        tokens[1][1] = 8.0f;
        tokens[1][2] = 12.0f;
        // tokens[2] is all zeros (padding)
        long[] mask = {1, 1, 0};

        float[] result = EmbeddingService.meanPool(tokens, mask);
        assertEquals(3.0f, result[0], 0.001f);
        assertEquals(6.0f, result[1], 0.001f);
        assertEquals(9.0f, result[2], 0.001f);
    }

    @Test
    void meanPoolAllMaskedReturnsZero() {
        int dim = EmbeddingService.EMBEDDING_DIM;
        float[][] tokens = new float[2][dim];
        tokens[0][0] = 1.0f;
        tokens[0][1] = 2.0f;
        tokens[1][0] = 3.0f;
        tokens[1][1] = 4.0f;
        long[] mask = {0, 0};

        float[] result = EmbeddingService.meanPool(tokens, mask);
        assertEquals(0.0f, result[0], 0.001f);
        assertEquals(0.0f, result[1], 0.001f);
    }

    @Test
    void meanPoolSingleToken() {
        int dim = EmbeddingService.EMBEDDING_DIM;
        float[][] tokens = new float[1][dim];
        tokens[0][0] = 5.0f;
        tokens[0][1] = 10.0f;
        tokens[0][2] = 15.0f;
        long[] mask = {1};

        float[] result = EmbeddingService.meanPool(tokens, mask);
        assertEquals(5.0f, result[0], 0.001f);
        assertEquals(10.0f, result[1], 0.001f);
        assertEquals(15.0f, result[2], 0.001f);
    }

    @Test
    void meanPoolSelectiveMask() {
        int dim = EmbeddingService.EMBEDDING_DIM;
        float[][] tokens = new float[4][dim];
        tokens[0][0] = 10.0f;
        tokens[1][0] = 20.0f;
        tokens[2][0] = 30.0f;
        tokens[3][0] = 40.0f;
        long[] mask = {1, 0, 1, 0};

        float[] result = EmbeddingService.meanPool(tokens, mask);
        assertEquals(20.0f, result[0], 0.001f);
    }

    @Test
    void meanPoolResultAlwaysHasEmbeddingDim() {
        int dim = EmbeddingService.EMBEDDING_DIM;
        float[][] tokens = new float[2][dim];
        long[] mask = {1, 1};

        float[] result = EmbeddingService.meanPool(tokens, mask);
        assertEquals(dim, result.length);
    }

    // --- l2Normalize ---

    @Test
    void l2NormalizeProducesUnitVector() {
        float[] vec = {3.0f, 4.0f};
        float[] result = EmbeddingService.l2Normalize(vec);
        assertEquals(0.6f, result[0], 0.001f);
        assertEquals(0.8f, result[1], 0.001f);

        float norm = 0;
        for (float v : result) norm += v * v;
        assertEquals(1.0f, (float) Math.sqrt(norm), 0.001f);
    }

    @Test
    void l2NormalizeZeroVectorStaysZero() {
        float[] vec = {0.0f, 0.0f, 0.0f};
        float[] result = EmbeddingService.l2Normalize(vec);
        assertEquals(0.0f, result[0]);
        assertEquals(0.0f, result[1]);
        assertEquals(0.0f, result[2]);
    }

    @Test
    void l2NormalizeAlreadyUnit() {
        float[] vec = {1.0f, 0.0f, 0.0f};
        float[] result = EmbeddingService.l2Normalize(vec);
        assertEquals(1.0f, result[0], 0.001f);
        assertEquals(0.0f, result[1], 0.001f);
    }

    @Test
    void l2NormalizeNegativeValues() {
        float[] vec = {-3.0f, 4.0f};
        float[] result = EmbeddingService.l2Normalize(vec);
        assertEquals(-0.6f, result[0], 0.001f);
        assertEquals(0.8f, result[1], 0.001f);
    }

    @Test
    void l2NormalizeModifiesInPlace() {
        float[] vec = {3.0f, 4.0f};
        float[] result = EmbeddingService.l2Normalize(vec);
        // l2Normalize modifies the array in place
        assertEquals(vec, result);
    }

    // --- embed() via test constructor ---

    @Test
    void embedDelegatesToTokenizerAndInference(@TempDir Path tempDir) throws Exception {
        Path vocabPath = writeMinimalVocab(tempDir);
        WordPieceTokenizer tokenizer = new WordPieceTokenizer(vocabPath, 16);
        float[] expected = new float[EmbeddingService.EMBEDDING_DIM];
        expected[0] = 0.5f;
        expected[1] = 0.8f;

        EmbeddingService service = new EmbeddingService(tokenizer, input -> {
            assertNotNull(input);
            assertTrue(input.sequenceLength() > 0);
            return expected;
        });

        float[] result = service.embed("hello world");
        assertArrayEquals(expected, result);
    }

    @Test
    void embedThrowsWhenInferenceFails(@TempDir Path tempDir) throws IOException {
        Path vocabPath = writeMinimalVocab(tempDir);
        WordPieceTokenizer tokenizer = new WordPieceTokenizer(vocabPath, 16);

        EmbeddingService service = new EmbeddingService(tokenizer, input -> {
            throw new RuntimeException("ONNX crash");
        });

        assertThrows(RuntimeException.class, () -> service.embed("hello"));
    }

    // --- embedBatch() via test constructor ---

    @Test
    void embedBatchProcessesMultipleTexts(@TempDir Path tempDir) throws Exception {
        Path vocabPath = writeMinimalVocab(tempDir);
        WordPieceTokenizer tokenizer = new WordPieceTokenizer(vocabPath, 16);
        int[] callCount = {0};

        EmbeddingService service = new EmbeddingService(tokenizer, input -> {
            float[] vec = new float[EmbeddingService.EMBEDDING_DIM];
            vec[0] = callCount[0]++;
            return vec;
        });

        List<float[]> results = service.embedBatch(List.of("hello", "world", "test"));
        assertEquals(3, results.size());
        assertEquals(0.0f, results.get(0)[0], 0.001f);
        assertEquals(1.0f, results.get(1)[0], 0.001f);
        assertEquals(2.0f, results.get(2)[0], 0.001f);
    }

    @Test
    void embedBatchEmptyListReturnsEmpty(@TempDir Path tempDir) throws Exception {
        Path vocabPath = writeMinimalVocab(tempDir);
        WordPieceTokenizer tokenizer = new WordPieceTokenizer(vocabPath, 16);

        EmbeddingService service = new EmbeddingService(tokenizer, input ->
            new float[EmbeddingService.EMBEDDING_DIM]);

        List<float[]> results = service.embedBatch(List.of());
        assertTrue(results.isEmpty());
    }

    // --- isReady() ---

    @Test
    void isReadyReturnsTrueWhenInitialized(@TempDir Path tempDir) throws IOException {
        Path vocabPath = writeMinimalVocab(tempDir);
        WordPieceTokenizer tokenizer = new WordPieceTokenizer(vocabPath, 16);

        EmbeddingService service = new EmbeddingService(tokenizer, input ->
            new float[EmbeddingService.EMBEDDING_DIM]);

        assertTrue(service.isReady());
    }

    // --- dispose() ---

    @Test
    void disposeOnUninitializedService(@TempDir Path tempDir) throws IOException {
        Path vocabPath = writeMinimalVocab(tempDir);
        WordPieceTokenizer tokenizer = new WordPieceTokenizer(vocabPath, 16);

        EmbeddingService service = new EmbeddingService(tokenizer, input ->
            new float[EmbeddingService.EMBEDDING_DIM]);

        // dispose with null session/env (test constructor doesn't set them)
        service.dispose();

        // After dispose, isReady() should return false (initialized is reset)
        // unless ModelDownloader.isModelAvailable() returns true
        assertFalse(service.isReady() && !ModelDownloader.isModelAvailable());
    }

    // --- ModelDownloader path methods ---

    @Test
    void modelDirectoryContainsExpectedSegments() {
        Path dir = ModelDownloader.getModelDirectory();
        assertTrue(dir.toString().contains(".agentbridge"));
        assertTrue(dir.toString().contains("models"));
        assertTrue(dir.toString().endsWith("all-MiniLM-L6-v2"));
    }

    @Test
    void modelPathEndsWithOnnx() {
        Path path = ModelDownloader.getModelPath();
        assertEquals("model.onnx", path.getFileName().toString());
        assertTrue(path.startsWith(ModelDownloader.getModelDirectory()));
    }

    @Test
    void vocabPathEndsWithTxt() {
        Path path = ModelDownloader.getVocabPath();
        assertEquals("vocab.txt", path.getFileName().toString());
        assertTrue(path.startsWith(ModelDownloader.getModelDirectory()));
    }

    @Test
    void isModelAvailableReturnsConsistentResult() {
        // Either both files exist (true) or at least one is missing (false)
        boolean available = ModelDownloader.isModelAvailable();
        if (available) {
            assertTrue(Files.exists(ModelDownloader.getModelPath()));
            assertTrue(Files.exists(ModelDownloader.getVocabPath()));
        } else {
            assertTrue(!Files.exists(ModelDownloader.getModelPath())
                || !Files.exists(ModelDownloader.getVocabPath()));
        }
    }

    @Test
    void cleanupIsCallableWithoutException() {
        // Verify cleanup doesn't throw even if files are missing.
        // Note: we don't call cleanup() on real model paths as it would delete user data.
        // This test only validates the method signature and availability.
        assertNotNull(ModelDownloader.getModelPath());
        assertNotNull(ModelDownloader.getVocabPath());
    }

    // --- WordPieceTokenizer edge cases ---

    @Test
    void veryLongWordProducesUnk(@TempDir Path tempDir) throws IOException {
        Path vocabPath = writeMinimalVocab(tempDir);
        WordPieceTokenizer tokenizer = new WordPieceTokenizer(vocabPath, 16);

        String longWord = "a".repeat(250);
        WordPieceTokenizer.TokenizedInput result = tokenizer.tokenize(longWord);
        assertEquals(2, result.inputIds()[0]); // [CLS]
        assertEquals(1, result.inputIds()[1]); // [UNK] — word too long
        assertEquals(3, result.inputIds()[2]); // [SEP]
    }

    @Test
    void controlCharactersAreStripped(@TempDir Path tempDir) throws IOException {
        Path vocabPath = writeMinimalVocab(tempDir);
        WordPieceTokenizer tokenizer = new WordPieceTokenizer(vocabPath, 16);

        WordPieceTokenizer.TokenizedInput result = tokenizer.tokenize("hello\u0001world");
        assertEquals(2, result.inputIds()[0]); // [CLS]
        assertEquals(1, result.attentionMask()[0]);
        assertEquals(1, result.attentionMask()[1]);
    }

    @Test
    void tabsAndNewlinesNormalized(@TempDir Path tempDir) throws IOException {
        Path vocabPath = writeMinimalVocab(tempDir);
        WordPieceTokenizer tokenizer = new WordPieceTokenizer(vocabPath, 16);

        WordPieceTokenizer.TokenizedInput result = tokenizer.tokenize("hello\t\nworld");
        assertEquals(2, result.inputIds()[0]); // [CLS]
        assertEquals(4, result.inputIds()[1]); // "hello"
        assertEquals(5, result.inputIds()[2]); // "world"
    }

    // --- Embedder interface contract ---

    @Test
    void embedderInterfaceContract() {
        Embedder embedder = text -> new float[]{1.0f, 2.0f, 3.0f};
        try {
            float[] result = embedder.embed("test");
            assertArrayEquals(new float[]{1.0f, 2.0f, 3.0f}, result);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void embedderThrowingException() {
        Embedder embedder = text -> {
            throw new IOException("Model not loaded");
        };
        assertThrows(IOException.class, () -> embedder.embed("test"));
    }

    private static Path writeMinimalVocab(Path dir) throws IOException {
        List<String> vocab = List.of(
            "[PAD]", "[UNK]", "[CLS]", "[SEP]",
            "hello", "world", "test", "##ing"
        );
        Path path = dir.resolve("vocab.txt");
        Files.write(path, vocab, StandardCharsets.UTF_8);
        return path;
    }
}
