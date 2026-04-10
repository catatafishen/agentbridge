package com.github.catatafishen.agentbridge.memory.embedding;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class EmbeddingService implements Disposable, Embedder {

    private static final Logger LOG = Logger.getInstance(EmbeddingService.class);

    public static final int EMBEDDING_DIM = 384;
    private static final int MAX_SEQ_LENGTH = 256;

    private final Project project;
    private volatile OrtEnvironment env;
    private volatile OrtSession session;
    private volatile WordPieceTokenizer tokenizer;
    private volatile InferenceFunction inference;
    private volatile boolean initialized;
    private final Object initLock = new Object();

    /**
     * Runs ONNX inference on tokenized input, returning a 384-dim embedding.
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
     * bypassing ONNX Runtime and model download.
     */
    EmbeddingService(WordPieceTokenizer tokenizer, InferenceFunction inference) {
        this.project = null;
        this.tokenizer = tokenizer;
        this.inference = inference;
        this.initialized = true;
    }

    /**
     * Produce a 384-dimensional embedding for the given text.
     * Initializes the ONNX session on first call (downloads model if needed).
     *
     * @throws IOException if the model cannot be downloaded
     * @throws Exception   if ONNX inference fails
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

            try {
                tokenizer = new WordPieceTokenizer(vocabPath, MAX_SEQ_LENGTH);
                env = OrtEnvironment.getEnvironment();
                OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
                opts.setIntraOpNumThreads(1);
                session = env.createSession(modelPath.toString(), opts);
                inference = this::runOnnxInference;
                initialized = true;
                LOG.info("EmbeddingService initialized with model from " + modelPath);
            } catch (OrtException e) {
                throw new IOException("Failed to initialize ONNX Runtime session", e);
            }
        }
    }

    /**
     * Run inference on a single tokenized input via ONNX Runtime.
     * Performs mean pooling over token embeddings, then L2-normalizes the result.
     */
    private float[] runOnnxInference(@NotNull WordPieceTokenizer.TokenizedInput input) throws OrtException {
        int seqLen = input.sequenceLength();

        OnnxTensor inputIds = OnnxTensor.createTensor(env,
            LongBuffer.wrap(input.inputIds()), new long[]{1, seqLen});
        OnnxTensor attentionMask = OnnxTensor.createTensor(env,
            LongBuffer.wrap(input.attentionMask()), new long[]{1, seqLen});
        OnnxTensor tokenTypeIds = OnnxTensor.createTensor(env,
            LongBuffer.wrap(input.tokenTypeIds()), new long[]{1, seqLen});

        try (OrtSession.Result result = session.run(Map.of(
            "input_ids", inputIds,
            "attention_mask", attentionMask,
            "token_type_ids", tokenTypeIds
        ))) {
            // Output shape: [1, seq_len, 384] — token-level embeddings
            float[][][] tokenEmbeddings = (float[][][]) result.get(0).getValue();

            // Mean pooling: average over non-padding tokens
            float[] pooled = meanPool(tokenEmbeddings[0], input.attentionMask());

            // L2 normalize
            return l2Normalize(pooled);
        } finally {
            inputIds.close();
            attentionMask.close();
            tokenTypeIds.close();
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
        try {
            if (session != null) {
                session.close();
                session = null;
            }
            if (env != null) {
                env.close();
                env = null;
            }
        } catch (OrtException e) {
            LOG.warn("Error closing ONNX Runtime session", e);
        }
        initialized = false;
    }
}
