package com.github.catatafishen.agentbridge.memory.embedding;

import org.jetbrains.annotations.NotNull;

/**
 * Functional interface for text-to-vector embedding.
 * Implemented by {@link EmbeddingService}; test code can provide
 * a lightweight fake that returns deterministic vectors.
 */
@FunctionalInterface
public interface Embedder {

    /**
     * Produce a 384-dimensional embedding for the given text.
     *
     * @param text the text to embed
     * @return float array of dimension {@link EmbeddingService#EMBEDDING_DIM}
     * @throws Exception if embedding fails (I/O, ONNX inference, etc.)
     */
    float[] embed(@NotNull String text) throws Exception;
}
