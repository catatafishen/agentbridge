package com.github.catatafishen.agentbridge.memory.embedding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Factory for creating test {@link EmbeddingService} instances that bypass ONNX Runtime.
 *
 * <p>Must live in the {@code embedding} package to access the package-private
 * test constructor and {@link EmbeddingService.InferenceFunction}.
 */
public final class TestEmbeddingFactory {

    private TestEmbeddingFactory() {
    }

    /**
     * Create an EmbeddingService that returns a constant vector for any input.
     *
     * @param tempDir directory for the minimal vocab file
     * @param vector  constant embedding to return (must be {@link EmbeddingService#EMBEDDING_DIM}-dimensional)
     * @return a ready EmbeddingService backed by a fake inference function
     */
    public static EmbeddingService constant(Path tempDir, float[] vector) throws IOException {
        WordPieceTokenizer tokenizer = createMinimalTokenizer(tempDir);
        return new EmbeddingService(tokenizer, input -> vector);
    }

    /**
     * Create an EmbeddingService that counts calls and returns zero vectors.
     *
     * @param tempDir   directory for the minimal vocab file
     * @param callCount single-element array incremented on each embed call
     * @return a ready EmbeddingService
     */
    public static EmbeddingService counting(Path tempDir, int[] callCount) throws IOException {
        WordPieceTokenizer tokenizer = createMinimalTokenizer(tempDir);
        return new EmbeddingService(tokenizer, input -> {
            callCount[0]++;
            return new float[EmbeddingService.EMBEDDING_DIM];
        });
    }

    private static WordPieceTokenizer createMinimalTokenizer(Path dir) throws IOException {
        List<String> vocab = List.of("[PAD]", "[UNK]", "[CLS]", "[SEP]", "hello", "world", "test", "##ing");
        Path vocabPath = dir.resolve("vocab.txt");
        Files.write(vocabPath, vocab, StandardCharsets.UTF_8);
        return new WordPieceTokenizer(vocabPath, 16);
    }
}
