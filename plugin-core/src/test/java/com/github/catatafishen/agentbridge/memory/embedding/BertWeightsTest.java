package com.github.catatafishen.agentbridge.memory.embedding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for {@link BertWeights}: constructor via SafetensorsReader, and parameterCount()
 * on LayerWeights with single-element arrays.
 */
class BertWeightsTest {

    @TempDir
    Path tempDir;

    @Test
    void constructorLoadsAllTensorsFromSafetensorsFile() throws IOException {
        Path modelFile = buildMinimalModelFile(tempDir);
        try (SafetensorsReader reader = new SafetensorsReader(modelFile)) {
            BertWeights weights = new BertWeights(reader);
            assertNotNull(weights.layers, "layers must not be null");
            assertEquals(6, weights.layers.length, "model must have 6 encoder layers");
        }
    }

    @Test
    void constructorLoadsNoPrefixTensors() throws IOException {
        Path modelFile = buildMinimalModelFileNoPrefix(tempDir);
        try (SafetensorsReader reader = new SafetensorsReader(modelFile)) {
            BertWeights weights = new BertWeights(reader);
            assertNotNull(weights.layers, "layers must not be null");
            assertEquals(6, weights.layers.length, "model must have 6 encoder layers");
        }
    }

    @Test
    void layerWeightsParameterCountWithSingleFloatArrays() {
        float[] one = {1.0f};
        BertWeights.LayerWeights layer = new BertWeights.LayerWeights(
            one, one, // query W, B
            one, one, // key W, B
            one, one, // value W, B
            one, one, // attention output W, B
            one, one, // attention LN gamma, beta
            one, one, // intermediate W, B
            one, one, // output W, B
            one, one  // output LN gamma, beta
        );
        // 16 slots, each backed by a 1-float array → 16 total floats
        assertEquals(16L, layer.parameterCount());
    }

    // ---- Helpers ----------------------------------------------------------------

    /**
     * Writes a minimal safetensors file containing all 101 tensors required by
     * {@link BertWeights}: 5 embedding tensors + 6 layers × 16 per-layer tensors.
     * Every tensor holds exactly one {@code float32} value (4 bytes of data), so
     * the file is small but structurally valid for the constructor to read.
     */
    private static Path buildMinimalModelFile(Path dir) throws IOException {
        List<String> names = buildTensorNames();

        // Build the safetensors JSON header
        StringBuilder json = new StringBuilder("{");
        int dataOffset = 0;
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) json.append(",");
            json.append("\"").append(names.get(i)).append("\":");
            json.append("{\"dtype\":\"F32\",\"shape\":[1],");
            json.append("\"data_offsets\":[").append(dataOffset).append(",").append(dataOffset + 4).append("]}");
            dataOffset += 4;
        }
        json.append("}");

        byte[] headerBytes = json.toString().getBytes(StandardCharsets.UTF_8);
        long headerLen = headerBytes.length;
        int totalData = names.size() * 4; // 4 bytes per float32

        ByteBuffer buf = ByteBuffer.allocate(8 + headerBytes.length + totalData).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(headerLen);
        buf.put(headerBytes);
        // Write one float (1.0f) per tensor as the data section
        for (int i = 0; i < names.size(); i++) {
            buf.putFloat(1.0f);
        }

        Path file = dir.resolve("model.safetensors");
        Files.write(file, buf.array());
        return file;
    }

    private static List<String> buildTensorNames() {
        List<String> names = new ArrayList<>(101);
        // sentence-transformers/all-MiniLM-L6-v2 safetensors omit the "bert." prefix
        names.add("embeddings.word_embeddings.weight");
        names.add("embeddings.position_embeddings.weight");
        names.add("embeddings.token_type_embeddings.weight");
        names.add("embeddings.LayerNorm.weight");
        names.add("embeddings.LayerNorm.bias");
        for (int i = 0; i < 6; i++) {
            String p = "encoder.layer." + i + ".";
            names.add(p + "attention.self.query.weight");
            names.add(p + "attention.self.query.bias");
            names.add(p + "attention.self.key.weight");
            names.add(p + "attention.self.key.bias");
            names.add(p + "attention.self.value.weight");
            names.add(p + "attention.self.value.bias");
            names.add(p + "attention.output.dense.weight");
            names.add(p + "attention.output.dense.bias");
            names.add(p + "attention.output.LayerNorm.weight");
            names.add(p + "attention.output.LayerNorm.bias");
            names.add(p + "intermediate.dense.weight");
            names.add(p + "intermediate.dense.bias");
            names.add(p + "output.dense.weight");
            names.add(p + "output.dense.bias");
            names.add(p + "output.LayerNorm.weight");
            names.add(p + "output.LayerNorm.bias");
        }
        return names;
    }

    /**
     * Like {@link #buildMinimalModelFile(Path)} but without the "bert." prefix,
     * matching the HuggingFace safetensors export format.
     */
    private static Path buildMinimalModelFileNoPrefix(Path dir) throws IOException {
        List<String> names = buildTensorNamesNoPrefix();

        StringBuilder json = new StringBuilder("{");
        int dataOffset = 0;
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) json.append(",");
            json.append("\"").append(names.get(i)).append("\":");
            json.append("{\"dtype\":\"F32\",\"shape\":[1],");
            json.append("\"data_offsets\":[").append(dataOffset).append(",").append(dataOffset + 4).append("]}");
            dataOffset += 4;
        }
        json.append("}");

        byte[] headerBytes = json.toString().getBytes(StandardCharsets.UTF_8);
        long headerLen = headerBytes.length;
        int totalData = names.size() * 4;

        ByteBuffer buf = ByteBuffer.allocate(8 + headerBytes.length + totalData).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(headerLen);
        buf.put(headerBytes);
        for (int i = 0; i < names.size(); i++) {
            buf.putFloat(1.0f);
        }

        Path file = dir.resolve("model-noprefix.safetensors");
        Files.write(file, buf.array());
        return file;
    }

    private static List<String> buildTensorNamesNoPrefix() {
        List<String> names = new ArrayList<>(101);
        names.add("embeddings.word_embeddings.weight");
        names.add("embeddings.position_embeddings.weight");
        names.add("embeddings.token_type_embeddings.weight");
        names.add("embeddings.LayerNorm.weight");
        names.add("embeddings.LayerNorm.bias");
        for (int i = 0; i < 6; i++) {
            String p = "encoder.layer." + i + ".";
            names.add(p + "attention.self.query.weight");
            names.add(p + "attention.self.query.bias");
            names.add(p + "attention.self.key.weight");
            names.add(p + "attention.self.key.bias");
            names.add(p + "attention.self.value.weight");
            names.add(p + "attention.self.value.bias");
            names.add(p + "attention.output.dense.weight");
            names.add(p + "attention.output.dense.bias");
            names.add(p + "attention.output.LayerNorm.weight");
            names.add(p + "attention.output.LayerNorm.bias");
            names.add(p + "intermediate.dense.weight");
            names.add(p + "intermediate.dense.bias");
            names.add(p + "output.dense.weight");
            names.add(p + "output.dense.bias");
            names.add(p + "output.LayerNorm.weight");
            names.add(p + "output.LayerNorm.bias");
        }
        return names;
    }
}
