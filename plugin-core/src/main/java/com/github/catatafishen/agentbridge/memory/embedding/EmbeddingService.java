package com.github.catatafishen.agentbridge.memory.embedding;

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

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Manages the ONNX Runtime session for all-MiniLM-L6-v2 and produces
 * 384-dimensional float embeddings from text.
 *
 * <p>Lazy initialization: the model is downloaded on first use and the
 * ONNX session is created on the first {@link #embed} call.
 *
 * <p><b>Attribution:</b> embedding pipeline adapted from the
 * sentence-transformers all-MiniLM-L6-v2 model's expected workflow.
 */
public final class EmbeddingService implements Disposable {

    private static final Logger LOG = Logger.getInstance(EmbeddingService.class);

    public static final int EMBEDDING_DIM = 384;
    private static final int MAX_SEQ_LENGTH = 256;

    private final Project project;
    private volatile OrtEnvironment env;
    private volatile OrtSession session;
    private volatile WordPieceTokenizer tokenizer;
    private volatile boolean initialized;
    private final Object initLock = new Object();

    public EmbeddingService(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Produce a 384-dimensional embedding for the given text.
     * Initializes the ONNX session on first call (downloads model if needed).
     *
     * @throws IOException  if the model cannot be downloaded
     * @throws OrtException if ONNX inference fails
     */
    public float[] embed(@NotNull String text) throws IOException, OrtException {
        ensureInitialized();

        WordPieceTokenizer.TokenizedInput input = tokenizer.tokenize(text);
        return runInference(input);
    }

    /**
     * Batch-embed multiple texts. More efficient than repeated single calls
     * when processing multiple chunks from a turn.
     */
    public List<float[]> embedBatch(@NotNull List<String> texts) throws IOException, OrtException {
        ensureInitialized();

        List<float[]> results = new ArrayList<>(texts.size());
        for (String text : texts) {
            WordPieceTokenizer.TokenizedInput input = tokenizer.tokenize(text);
            results.add(runInference(input));
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
                initialized = true;
                LOG.info("EmbeddingService initialized with model from " + modelPath);
            } catch (OrtException e) {
                throw new IOException("Failed to initialize ONNX Runtime session", e);
            }
        }
    }

    /**
     * Run inference on a single tokenized input.
     * Performs mean pooling over token embeddings, then L2-normalizes the result.
     */
    private float[] runInference(@NotNull WordPieceTokenizer.TokenizedInput input) throws OrtException {
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
    private static float[] meanPool(float[][] tokenEmbeddings, long[] attentionMask) {
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
    private static float[] l2Normalize(float[] vec) {
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
