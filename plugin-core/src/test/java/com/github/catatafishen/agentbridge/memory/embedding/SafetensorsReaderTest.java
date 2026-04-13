package com.github.catatafishen.agentbridge.memory.embedding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SafetensorsReader}. Creates synthetic safetensors binary
 * files in a temp directory — no real model files are needed.
 */
class SafetensorsReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsSimpleF32Tensor() throws IOException {
        float[] expected = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f};
        byte[] data = floatsToBytes(expected);
        String header = "{\"test_weight\":{\"dtype\":\"F32\",\"shape\":[2,3],\"data_offsets\":[0,24]}}";

        Path file = createSafetensorsFile(header, data);
        try (SafetensorsReader reader = new SafetensorsReader(file)) {
            float[] loaded = reader.loadTensor("test_weight");
            assertArrayEquals(expected, loaded);
            assertArrayEquals(new int[]{2, 3}, reader.getShape("test_weight"));
            assertTrue(reader.hasTensor("test_weight"));
        }
    }

    @Test
    void loadsMultipleTensors() throws IOException {
        float[] biasValues = {0.1f, 0.2f, 0.3f};
        float[] weightValues = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f};
        byte[] biasBytes = floatsToBytes(biasValues);
        byte[] weightBytes = floatsToBytes(weightValues);

        byte[] data = new byte[biasBytes.length + weightBytes.length];
        System.arraycopy(biasBytes, 0, data, 0, biasBytes.length);
        System.arraycopy(weightBytes, 0, data, biasBytes.length, weightBytes.length);

        String header = "{"
            + "\"bias\":{\"dtype\":\"F32\",\"shape\":[3],\"data_offsets\":[0,12]},"
            + "\"weight\":{\"dtype\":\"F32\",\"shape\":[2,3],\"data_offsets\":[12,36]}"
            + "}";

        Path file = createSafetensorsFile(header, data);
        try (SafetensorsReader reader = new SafetensorsReader(file)) {
            assertArrayEquals(biasValues, reader.loadTensor("bias"));
            assertArrayEquals(new int[]{3}, reader.getShape("bias"));

            assertArrayEquals(weightValues, reader.loadTensor("weight"));
            assertArrayEquals(new int[]{2, 3}, reader.getShape("weight"));
        }
    }

    @Test
    void throwsOnMissingTensor() throws IOException {
        byte[] data = floatsToBytes(1.0f);
        String header = "{\"present\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4]}}";

        Path file = createSafetensorsFile(header, data);
        try (SafetensorsReader reader = new SafetensorsReader(file)) {
            assertThrows(IOException.class, () -> reader.loadTensor("nonexistent"));
        }
    }

    @Test
    void throwsOnNonF32Dtype() throws IOException {
        byte[] data = new byte[4]; // dummy data
        String header = "{\"half_tensor\":{\"dtype\":\"F16\",\"shape\":[2],\"data_offsets\":[0,4]}}";

        Path file = createSafetensorsFile(header, data);
        try (SafetensorsReader reader = new SafetensorsReader(file)) {
            assertThrows(IllegalArgumentException.class, () -> reader.loadTensor("half_tensor"));
        }
    }

    @Test
    void hasTensorReturnsFalseForMissing() throws IOException {
        byte[] data = floatsToBytes(1.0f);
        String header = "{\"present\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4]}}";

        Path file = createSafetensorsFile(header, data);
        try (SafetensorsReader reader = new SafetensorsReader(file)) {
            assertFalse(reader.hasTensor("nonexistent"));
        }
    }

    @Test
    void hasTensorIgnoresMetadataKey() throws IOException {
        byte[] data = floatsToBytes(1.0f);
        String header = "{"
            + "\"__metadata__\":{\"format\":\"pt\"},"
            + "\"real_tensor\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4]}"
            + "}";

        Path file = createSafetensorsFile(header, data);
        try (SafetensorsReader reader = new SafetensorsReader(file)) {
            assertFalse(reader.hasTensor("__metadata__"));
            assertTrue(reader.hasTensor("real_tensor"));
        }
    }

    @Test
    void throwsOnTruncatedHeader() throws IOException {
        // Only 4 bytes — less than the 8-byte header length prefix
        Path file = tempDir.resolve("truncated.safetensors");
        Files.write(file, new byte[]{0x01, 0x02, 0x03, 0x04});

        assertThrows(IOException.class, () -> new SafetensorsReader(file));
    }

    @Test
    void throwsOnInvalidHeaderLength() throws IOException {
        // Write a negative header length (-1 as a signed long)
        Path file = tempDir.resolve("bad_length.safetensors");
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(-1L);
        Files.write(file, buf.array());

        assertThrows(IOException.class, () -> new SafetensorsReader(file));
    }

    @Test
    void closesResourcesOnError() throws IOException {
        byte[] data = floatsToBytes(1.0f, 2.0f);
        String header = "{\"vec\":{\"dtype\":\"F32\",\"shape\":[2],\"data_offsets\":[0,8]}}";

        Path file = createSafetensorsFile(header, data);
        SafetensorsReader reader = new SafetensorsReader(file);
        assertEquals(2, reader.loadTensor("vec").length);
        assertDoesNotThrow(reader::close);
    }

    // ---- helpers ----------------------------------------------------------------

    private Path createSafetensorsFile(String headerJson, byte[] dataSection) throws IOException {
        Path file = tempDir.resolve("test.safetensors");
        byte[] headerBytes = headerJson.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(8 + headerBytes.length + dataSection.length)
            .order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(headerBytes.length);
        buf.put(headerBytes);
        buf.put(dataSection);
        Files.write(file, buf.array());
        return file;
    }

    private byte[] floatsToBytes(float... values) {
        ByteBuffer buf = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) {
            buf.putFloat(v);
        }
        return buf.array();
    }
}
