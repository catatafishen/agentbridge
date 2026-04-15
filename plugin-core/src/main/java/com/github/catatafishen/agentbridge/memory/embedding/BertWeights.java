package com.github.catatafishen.agentbridge.memory.embedding;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Typed container for all weight matrices from the
 * <a href="https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2">all-MiniLM-L6-v2</a>
 * sentence-transformer model, loaded from a safetensors file.
 *
 * <p>The model has 6 encoder layers with 12 attention heads (head dim 32),
 * hidden dimension 384, and intermediate FFN dimension 1536.
 * Total: 5 embedding tensors + 16 × 6 layer tensors = 101 named tensors.
 */
public final class BertWeights {

    private static final Logger LOG = Logger.getInstance(BertWeights.class);

    // ---- Architecture constants for all-MiniLM-L6-v2 ----------------------------

    public static final int HIDDEN_DIM = 384;
    public static final int NUM_HEADS = 12;
    public static final int HEAD_DIM = 32;
    public static final int INTERMEDIATE_DIM = 1536;
    public static final int NUM_LAYERS = 6;

    // ---- Embedding weights ------------------------------------------------------

    /**
     * Word embeddings: [30522, 384] flattened to float[30522 × 384].
     */
    public final float[] wordEmbeddings;

    /**
     * Position embeddings: [512, 384] flattened to float[512 × 384].
     */
    public final float[] positionEmbeddings;

    /**
     * Token type embeddings: [2, 384] flattened to float[2 × 384].
     */
    public final float[] tokenTypeEmbeddings;

    /**
     * Embedding LayerNorm weight (gamma): [384].
     */
    public final float[] embeddingLayerNormWeight;

    /**
     * Embedding LayerNorm bias (beta): [384].
     */
    public final float[] embeddingLayerNormBias;

    // ---- Encoder layers ---------------------------------------------------------

    /**
     * Per-layer weights for all 6 encoder layers.
     */
    public final LayerWeights[] layers;

    /**
     * Holds the 16 weight tensors for a single BERT encoder layer.
     *
     * @param queryWeight              attention query weight [384, 384]
     * @param queryBias                attention query bias [384]
     * @param keyWeight                attention key weight [384, 384]
     * @param keyBias                  attention key bias [384]
     * @param valueWeight              attention value weight [384, 384]
     * @param valueBias                attention value bias [384]
     * @param attentionOutputWeight    attention output dense weight [384, 384]
     * @param attentionOutputBias      attention output dense bias [384]
     * @param attentionLayerNormWeight attention output LayerNorm weight [384]
     * @param attentionLayerNormBias   attention output LayerNorm bias [384]
     * @param intermediateWeight       intermediate (FFN up) dense weight [1536, 384]
     * @param intermediateBias         intermediate (FFN up) dense bias [1536]
     * @param outputWeight             output (FFN down) dense weight [384, 1536]
     * @param outputBias               output (FFN down) dense bias [384]
     * @param outputLayerNormWeight    output LayerNorm weight [384]
     * @param outputLayerNormBias      output LayerNorm bias [384]
     */
    public record LayerWeights(
        float[] queryWeight,
        float[] queryBias,
        float[] keyWeight,
        float[] keyBias,
        float[] valueWeight,
        float[] valueBias,
        float[] attentionOutputWeight,
        float[] attentionOutputBias,
        float[] attentionLayerNormWeight,
        float[] attentionLayerNormBias,
        float[] intermediateWeight,
        float[] intermediateBias,
        float[] outputWeight,
        float[] outputBias,
        float[] outputLayerNormWeight,
        float[] outputLayerNormBias
    ) {

        /**
         * Returns the total number of float parameters in this layer.
         */
        public long parameterCount() {
            return (long) queryWeight.length + queryBias.length
                + keyWeight.length + keyBias.length
                + valueWeight.length + valueBias.length
                + attentionOutputWeight.length + attentionOutputBias.length
                + attentionLayerNormWeight.length + attentionLayerNormBias.length
                + intermediateWeight.length + intermediateBias.length
                + outputWeight.length + outputBias.length
                + outputLayerNormWeight.length + outputLayerNormBias.length;
        }
    }

    /**
     * Package-private for testing — constructs BertWeights from pre-built arrays,
     * bypassing SafetensorsReader. Arrays are not validated for size.
     */
    BertWeights(
        float[] wordEmbeddings,
        float[] positionEmbeddings,
        float[] tokenTypeEmbeddings,
        float[] embeddingLayerNormWeight,
        float[] embeddingLayerNormBias,
        LayerWeights[] layers) {
        this.wordEmbeddings = wordEmbeddings;
        this.positionEmbeddings = positionEmbeddings;
        this.tokenTypeEmbeddings = tokenTypeEmbeddings;
        this.embeddingLayerNormWeight = embeddingLayerNormWeight;
        this.embeddingLayerNormBias = embeddingLayerNormBias;
        this.layers = layers;
    }

    /**
     * Auto-detects the tensor name prefix by probing the reader for a known tensor.
     *
     * <p>PyTorch checkpoints use {@code "bert."} prefix (e.g., {@code bert.embeddings.*}),
     * while HuggingFace safetensors exports omit it (e.g., {@code embeddings.*}).
     *
     * @return {@code "bert."} or {@code ""} depending on the model file
     * @throws IOException if neither prefix resolves to a valid tensor
     */
    private static String detectPrefix(@NotNull SafetensorsReader reader) throws IOException {
        String probeTensor = "embeddings.word_embeddings.weight";
        if (reader.hasTensor("bert." + probeTensor)) {
            return "bert.";
        }
        if (reader.hasTensor(probeTensor)) {
            return "";
        }
        throw new IOException("Cannot detect tensor prefix: neither 'bert." + probeTensor
            + "' nor '" + probeTensor + "' found in safetensors metadata");
    }

    /**
     * Loads all tensors of the all-MiniLM-L6-v2 model from a safetensors file.
     *
     * <p>Supports both PyTorch-style naming ({@code bert.embeddings.*}) and
     * HuggingFace safetensors naming ({@code embeddings.*}). The prefix is
     * auto-detected from the first tensor.
     *
     * @param reader an open {@link SafetensorsReader} positioned on the model file
     * @throws IOException if any tensor cannot be read from the file
     */
    public BertWeights(@NotNull SafetensorsReader reader) throws IOException {
        String prefix = detectPrefix(reader);

        // ---- Embeddings ---------------------------------------------------------
        wordEmbeddings = reader.loadTensor(prefix + "embeddings.word_embeddings.weight");
        positionEmbeddings = reader.loadTensor(prefix + "embeddings.position_embeddings.weight");
        tokenTypeEmbeddings = reader.loadTensor(prefix + "embeddings.token_type_embeddings.weight");
        embeddingLayerNormWeight = reader.loadTensor(prefix + "embeddings.LayerNorm.weight");
        embeddingLayerNormBias = reader.loadTensor(prefix + "embeddings.LayerNorm.bias");

        // ---- Encoder layers -----------------------------------------------------
        layers = new LayerWeights[NUM_LAYERS];
        for (int i = 0; i < NUM_LAYERS; i++) {
            String layerPrefix = prefix + "encoder.layer." + i + ".";
            layers[i] = new LayerWeights(
                reader.loadTensor(layerPrefix + "attention.self.query.weight"),
                reader.loadTensor(layerPrefix + "attention.self.query.bias"),
                reader.loadTensor(layerPrefix + "attention.self.key.weight"),
                reader.loadTensor(layerPrefix + "attention.self.key.bias"),
                reader.loadTensor(layerPrefix + "attention.self.value.weight"),
                reader.loadTensor(layerPrefix + "attention.self.value.bias"),
                reader.loadTensor(layerPrefix + "attention.output.dense.weight"),
                reader.loadTensor(layerPrefix + "attention.output.dense.bias"),
                reader.loadTensor(layerPrefix + "attention.output.LayerNorm.weight"),
                reader.loadTensor(layerPrefix + "attention.output.LayerNorm.bias"),
                reader.loadTensor(layerPrefix + "intermediate.dense.weight"),
                reader.loadTensor(layerPrefix + "intermediate.dense.bias"),
                reader.loadTensor(layerPrefix + "output.dense.weight"),
                reader.loadTensor(layerPrefix + "output.dense.bias"),
                reader.loadTensor(layerPrefix + "output.LayerNorm.weight"),
                reader.loadTensor(layerPrefix + "output.LayerNorm.bias")
            );
        }

        // ---- Log total parameter count ------------------------------------------
        long totalParams = (long) wordEmbeddings.length
            + positionEmbeddings.length
            + tokenTypeEmbeddings.length
            + embeddingLayerNormWeight.length
            + embeddingLayerNormBias.length;
        for (LayerWeights layer : layers) {
            totalParams += layer.parameterCount();
        }
        int tensorCount = 5 + NUM_LAYERS * 16;
        LOG.info("Loaded BertWeights: " + totalParams + " parameters from " + tensorCount
            + " tensors (prefix: " + (prefix.isEmpty() ? "<none>" : "'" + prefix + "'") + ")");
    }
}
