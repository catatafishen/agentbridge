package com.github.catatafishen.agentbridge.memory.embedding;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class EmbeddingService implements Disposable, Embedder {

    private static final Logger LOG = Logger.getInstance(EmbeddingService.class);

    public static final int EMBEDDING_DIM = 384;
    private static final int MAX_SEQ_LENGTH = 256;

    private final Project project;
    private volatile WordPieceTokenizer tokenizer;
    private volatile InferenceFunction inference;
    private volatile SafetensorsReader safetensorsReader;
    private volatile boolean initialized;
    private final Object initLock = new Object();

    /**
     * Runs inference on tokenized input, returning a 384-dim embedding.
     */
    @FunctionalInterface
    interface InferenceFunction {
        float[] run(WordPieceTokenizer.TokenizedInput input) throws Exception;
    }

    public EmbeddingService(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Package-private for testing — pre-initializes with a custom tokenizer and inference function,
     * bypassing model download and weight loading.
     */
    EmbeddingService(WordPieceTokenizer tokenizer, InferenceFunction inference) {
        this.project = null;
        this.tokenizer = tokenizer;
        this.inference = inference;
        this.initialized = true;
    }

    /**
     * Produce a 384-dimensional embedding for the given text.
     * Initializes the inference engine on first call (downloads model if needed).
     *
     * @throws IOException if the model cannot be downloaded
     * @throws Exception   if inference fails
     */
    @Override
    public float[] embed(@NotNull String text) throws Exception {
        ensureInitialized();

        WordPieceTokenizer.TokenizedInput input = tokenizer.tokenize(text);
        return inference.run(input);
    }

    /**
     * Batch-embed multiple texts. More efficient than repeated single calls
     * when processing multiple chunks from a turn.
     */
    public List<float[]> embedBatch(@NotNull List<String> texts) throws Exception {
        ensureInitialized();

        List<float[]> results = new ArrayList<>(texts.size());
        for (String text : texts) {
            WordPieceTokenizer.TokenizedInput input = tokenizer.tokenize(text);
            results.add(inference.run(input));
        }
        return results;
    }

    /**
     * Check whether the model is downloaded and ready (without triggering download).
     */
    public boolean isReady() {
        return initialized || ModelDownloader.isModelAvailable();
    }

    private void ensureInitialized() throws IOException {
        if (initialized) return;
        synchronized (initLock) {
            if (initialized) return;

            ModelDownloader.ensureModelAvailable(project);

            Path vocabPath = ModelDownloader.getVocabPath();
            Path modelPath = ModelDownloader.getModelPath();

            tokenizer = new WordPieceTokenizer(vocabPath, MAX_SEQ_LENGTH);
            SafetensorsReader localReader = new SafetensorsReader(modelPath);
            boolean success = false;
            try {
                BertWeights weights = new BertWeights(localReader);
                inference = new BertInferenceEngine(weights);
                safetensorsReader = localReader;
                initialized = true;
                success = true;
                LOG.info("EmbeddingService initialized with pure-Java BERT inference from " + modelPath);
            } finally {
                if (!success) {
                    localReader.close();
                }
            }
        }
    }

    /**
     * Mean pooling: average token embeddings, weighted by the attention mask
     * to exclude padding tokens.
     */
    static float[] meanPool(float[][] tokenEmbeddings, long[] attentionMask) {
        float[] sum = new float[EMBEDDING_DIM];
        int count = 0;

        for (int i = 0; i < tokenEmbeddings.length; i++) {
            if (attentionMask[i] == 1) {
                for (int j = 0; j < EMBEDDING_DIM; j++) {
                    sum[j] += tokenEmbeddings[i][j];
                }
                count++;
            }
        }

        if (count > 0) {
            float divisor = count;
            for (int j = 0; j < EMBEDDING_DIM; j++) {
                sum[j] /= divisor;
            }
        }
        return sum;
    }

    /**
     * L2-normalize a vector in place and return it.
     */
    static float[] l2Normalize(float[] vec) {
        float norm = 0;
        for (float v : vec) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vec.length; i++) {
                vec[i] /= norm;
            }
        }
        return vec;
    }

    @Override
    public void dispose() {
        if (safetensorsReader != null) {
            try {
                safetensorsReader.close();
            } catch (IOException e) {
                LOG.warn("Error closing SafetensorsReader", e);
            }
            safetensorsReader = null;
        }
        initialized = false;
    }
}
