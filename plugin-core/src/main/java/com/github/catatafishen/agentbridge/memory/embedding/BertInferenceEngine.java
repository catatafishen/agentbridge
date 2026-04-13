package com.github.catatafishen.agentbridge.memory.embedding;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import static com.github.catatafishen.agentbridge.memory.embedding.BertWeights.HEAD_DIM;
import static com.github.catatafishen.agentbridge.memory.embedding.BertWeights.HIDDEN_DIM;
import static com.github.catatafishen.agentbridge.memory.embedding.BertWeights.INTERMEDIATE_DIM;
import static com.github.catatafishen.agentbridge.memory.embedding.BertWeights.NUM_HEADS;
import static com.github.catatafishen.agentbridge.memory.embedding.BertWeights.NUM_LAYERS;

/**
 * Pure-Java 6-layer BERT forward pass for the
 * <a href="https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2">all-MiniLM-L6-v2</a>
 * sentence-transformer model.
 *
 * <p>Implements the complete inference pipeline: embedding lookup, 6 encoder layers
 * (self-attention + FFN), mean pooling, and L2 normalization. This produces
 * 384-dimensional sentence embeddings without any native dependencies.
 *
 * <p>All weights are supplied via a {@link BertWeights} instance loaded from a
 * safetensors file.
 */
final class BertInferenceEngine implements EmbeddingService.InferenceFunction {

    private static final Logger LOG = Logger.getInstance(BertInferenceEngine.class);

    private static final float SQRT_2_OVER_PI = (float) Math.sqrt(2.0 / Math.PI);
    private static final float GELU_COEFF = 0.044715f;
    private static final float ATTENTION_SCALE = (float) (1.0 / Math.sqrt(HEAD_DIM));
    private static final float LAYER_NORM_EPS = 1e-12f;
    private static final float MASK_VALUE = -10000.0f;

    private final BertWeights weights;

    BertInferenceEngine(@NotNull BertWeights weights) {
        this.weights = weights;
    }

    /**
     * Run the full BERT forward pass on tokenized input, returning a 384-dimensional
     * L2-normalized sentence embedding.
     *
     * <p>Pipeline:
     * <ol>
     *   <li>Embedding lookup (word + position + token type) followed by LayerNorm</li>
     *   <li>6 × encoder layer (self-attention → FFN)</li>
     *   <li>Mean pooling over non-padding tokens</li>
     *   <li>L2 normalization</li>
     * </ol>
     *
     * @param input tokenized text with input IDs, attention mask, and token type IDs
     * @return 384-dimensional normalized embedding vector
     */
    @Override
    public float[] run(@NotNull WordPieceTokenizer.TokenizedInput input) {
        long startNanos = System.nanoTime();

        int seqLen = input.sequenceLength();
        long[] inputIds = input.inputIds();
        long[] attentionMask = input.attentionMask();
        long[] tokenTypeIds = input.tokenTypeIds();

        // ---- Step 1: Embedding lookup + LayerNorm --------------------------------
        float[][] hidden = new float[seqLen][HIDDEN_DIM];
        for (int i = 0; i < seqLen; i++) {
            int wordIdx = (int) inputIds[i];
            int typeIdx = (int) tokenTypeIds[i];
            int wordOffset = wordIdx * HIDDEN_DIM;
            int posOffset = i * HIDDEN_DIM;
            int typeOffset = typeIdx * HIDDEN_DIM;
            for (int j = 0; j < HIDDEN_DIM; j++) {
                hidden[i][j] = weights.wordEmbeddings[wordOffset + j]
                    + weights.positionEmbeddings[posOffset + j]
                    + weights.tokenTypeEmbeddings[typeOffset + j];
            }
            layerNorm(hidden[i], HIDDEN_DIM,
                weights.embeddingLayerNormWeight, weights.embeddingLayerNormBias);
        }

        // ---- Step 2: 6 × Encoder layer ------------------------------------------
        for (int layer = 0; layer < NUM_LAYERS; layer++) {
            BertWeights.LayerWeights lw = weights.layers[layer];
            selfAttention(hidden, seqLen, lw, attentionMask);
            feedForward(hidden, seqLen, lw);
        }

        // ---- Step 3: Mean pool + L2 normalize -----------------------------------
        float[] pooled = EmbeddingService.meanPool(hidden, attentionMask);
        float[] result = EmbeddingService.l2Normalize(pooled);

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        LOG.debug("BertInferenceEngine forward pass: " + elapsedMs + " ms (seqLen=" + seqLen + ")");

        return result;
    }

    // ---- Encoder sub-layers (package-private for testability) --------------------

    /**
     * Multi-head self-attention with residual connection and LayerNorm.
     * Modifies {@code hidden} in place.
     */
    void selfAttention(float[][] hidden, int seqLen,
                       @NotNull BertWeights.LayerWeights layer,
                       long[] attentionMask) {
        // Save residual
        float[][] residual = new float[seqLen][HIDDEN_DIM];
        for (int i = 0; i < seqLen; i++) {
            System.arraycopy(hidden[i], 0, residual[i], 0, HIDDEN_DIM);
        }

        // Project Q, K, V: [seqLen, 384] each
        float[][] q = new float[seqLen][];
        float[][] k = new float[seqLen][];
        float[][] v = new float[seqLen][];
        for (int i = 0; i < seqLen; i++) {
            q[i] = linear(hidden[i], HIDDEN_DIM, layer.queryWeight(), layer.queryBias(), HIDDEN_DIM);
            k[i] = linear(hidden[i], HIDDEN_DIM, layer.keyWeight(), layer.keyBias(), HIDDEN_DIM);
            v[i] = linear(hidden[i], HIDDEN_DIM, layer.valueWeight(), layer.valueBias(), HIDDEN_DIM);
        }

        // Multi-head attention
        float[][] context = multiHeadAttention(q, k, v, seqLen, attentionMask);

        // Output projection + residual + LayerNorm
        for (int i = 0; i < seqLen; i++) {
            float[] projected = linear(context[i], HIDDEN_DIM,
                layer.attentionOutputWeight(), layer.attentionOutputBias(), HIDDEN_DIM);
            for (int j = 0; j < HIDDEN_DIM; j++) {
                hidden[i][j] = projected[j] + residual[i][j];
            }
            layerNorm(hidden[i], HIDDEN_DIM,
                layer.attentionLayerNormWeight(), layer.attentionLayerNormBias());
        }
    }

    /**
     * Computes multi-head scaled dot-product attention across all heads.
     *
     * @return context vectors of shape [seqLen][HIDDEN_DIM]
     */
    private float[][] multiHeadAttention(float[][] q, float[][] k, float[][] v,
                                         int seqLen, long[] attentionMask) {
        float[][] context = new float[seqLen][HIDDEN_DIM];
        float[] scores = new float[seqLen];

        for (int h = 0; h < NUM_HEADS; h++) {
            int headOffset = h * HEAD_DIM;
            for (int i = 0; i < seqLen; i++) {
                computeAttentionScores(q, k, scores, seqLen, headOffset, i, attentionMask);
                softmax(scores, seqLen);
                accumulateWeightedValues(v, scores, context, seqLen, headOffset, i);
            }
        }
        return context;
    }

    /**
     * Computes scaled dot-product attention scores for a single query position.
     */
    private void computeAttentionScores(float[][] q, float[][] k, float[] scores,
                                        int seqLen, int headOffset, int queryPos,
                                        long[] attentionMask) {
        for (int j = 0; j < seqLen; j++) {
            float dot = 0.0f;
            for (int d = 0; d < HEAD_DIM; d++) {
                dot += q[queryPos][headOffset + d] * k[j][headOffset + d];
            }
            scores[j] = dot * ATTENTION_SCALE;
            if (attentionMask[j] == 0) {
                scores[j] += MASK_VALUE;
            }
        }
    }

    /**
     * Accumulates weighted value vectors for a single query position within one head.
     */
    private void accumulateWeightedValues(float[][] v, float[] scores, float[][] context,
                                          int seqLen, int headOffset, int queryPos) {
        for (int d = 0; d < HEAD_DIM; d++) {
            float sum = 0.0f;
            for (int j = 0; j < seqLen; j++) {
                sum += scores[j] * v[j][headOffset + d];
            }
            context[queryPos][headOffset + d] = sum;
        }
    }

    /**
     * Position-wise feed-forward network with residual connection and LayerNorm.
     * Modifies {@code hidden} in place.
     */
    void feedForward(float[][] hidden, int seqLen,
                     @NotNull BertWeights.LayerWeights layer) {
        for (int i = 0; i < seqLen; i++) {
            // Save residual
            float[] residual = new float[HIDDEN_DIM];
            System.arraycopy(hidden[i], 0, residual, 0, HIDDEN_DIM);

            // Up-project: [384] → [1536] + GELU
            float[] intermediate = linear(hidden[i], HIDDEN_DIM,
                layer.intermediateWeight(), layer.intermediateBias(), INTERMEDIATE_DIM);
            gelu(intermediate, INTERMEDIATE_DIM);

            // Down-project: [1536] → [384]
            float[] output = linear(intermediate, INTERMEDIATE_DIM,
                layer.outputWeight(), layer.outputBias(), HIDDEN_DIM);

            // Residual + LayerNorm
            for (int j = 0; j < HIDDEN_DIM; j++) {
                hidden[i][j] = output[j] + residual[j];
            }
            layerNorm(hidden[i], HIDDEN_DIM,
                layer.outputLayerNormWeight(), layer.outputLayerNormBias());
        }
    }

    // ---- Primitive ops (package-private for testability) -------------------------

    /**
     * Layer normalization in place: {@code x = (x − μ) / √(σ² + ε) × weight + bias}.
     */
    void layerNorm(float[] x, int len, float[] weight, float[] bias) {
        float mean = 0.0f;
        for (int i = 0; i < len; i++) {
            mean += x[i];
        }
        mean /= len;

        float variance = 0.0f;
        for (int i = 0; i < len; i++) {
            float diff = x[i] - mean;
            variance += diff * diff;
        }
        variance /= len;

        float invStd = (float) (1.0 / Math.sqrt(variance + LAYER_NORM_EPS));
        for (int i = 0; i < len; i++) {
            x[i] = (x[i] - mean) * invStd * weight[i] + bias[i];
        }
    }

    /**
     * Dense linear layer: {@code output[j] = bias[j] + Σ_k input[k] × weight[j × inputDim + k]}.
     *
     * <p>Weight layout follows PyTorch convention: {@code [outputDim, inputDim]}.
     */
    float[] linear(float[] input, int inputDim, float[] weight, float[] bias, int outputDim) {
        float[] output = new float[outputDim];
        for (int j = 0; j < outputDim; j++) {
            float sum = bias[j];
            int weightOffset = j * inputDim;
            for (int k = 0; k < inputDim; k++) {
                sum += input[k] * weight[weightOffset + k];
            }
            output[j] = sum;
        }
        return output;
    }

    /**
     * GELU activation in place using the standard approximation:
     * {@code 0.5 × x × (1 + tanh(√(2/π) × (x + 0.044715 × x³)))}.
     */
    void gelu(float[] x, int len) {
        for (int i = 0; i < len; i++) {
            float val = x[i];
            float cube = val * val * val;
            float inner = SQRT_2_OVER_PI * (val + GELU_COEFF * cube);
            x[i] = 0.5f * val * (1.0f + (float) Math.tanh(inner));
        }
    }

    /**
     * Softmax in place over {@code scores[0..len-1]}.
     */
    void softmax(float[] scores, int len) {
        float max = scores[0];
        for (int i = 1; i < len; i++) {
            if (scores[i] > max) {
                max = scores[i];
            }
        }

        float expSum = 0.0f;
        for (int i = 0; i < len; i++) {
            float exp = (float) Math.exp(scores[i] - max);
            scores[i] = exp;
            expSum += exp;
        }

        // expSum is always > 0 because at least one exp(scores[max_i] - max) = exp(0) = 1.0
        if (expSum == 0.0f) return;
        float invSum = 1.0f / expSum;
        for (int i = 0; i < len; i++) {
            scores[i] *= invSum;
        }
    }
}
