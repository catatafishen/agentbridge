package com.github.catatafishen.agentbridge.memory.embedding;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Reads tensors from a
 * <a href="https://huggingface.co/docs/safetensors/index">safetensors</a>
 * binary file using memory-mapped I/O.
 *
 * <p>The safetensors format is:
 * <pre>
 * [8 bytes : little-endian uint64 header_length]
 * [header_length bytes : UTF-8 JSON metadata]
 * [data section : raw packed tensors at byte offsets declared in JSON]
 * </pre>
 *
 * <p>Each JSON entry maps a tensor name to its {@code dtype}, {@code shape},
 * and {@code data_offsets} (start/end relative to the data section).
 *
 * <p>This reader only supports {@code F32} tensors. It memory-maps the data
 * section via {@link FileChannel#map(FileChannel.MapMode, long, long)} for
 * efficient random access without copying entire files into heap memory.
 *
 * <p>Implements {@link Closeable} — use in a try-with-resources block.
 */
public final class SafetensorsReader implements Closeable {

    private static final Logger LOG = Logger.getInstance(SafetensorsReader.class);

    /**
     * Safety limit: reject header lengths >= 100 MB to prevent OOM on corrupt files.
     */
    private static final long MAX_HEADER_LENGTH = 100L * 1024 * 1024;

    private static final String DTYPE_KEY = "dtype";

    private final RandomAccessFile randomAccessFile;
    private final JsonObject metadata;
    private final MappedByteBuffer dataBuffer;

    /**
     * Opens a safetensors file and parses its metadata header.
     *
     * @param path path to the {@code .safetensors} file
     * @throws IOException if the file cannot be read or has an invalid header
     */
    public SafetensorsReader(@NotNull Path path) throws IOException {
        this.randomAccessFile = new RandomAccessFile(path.toFile(), "r");
        try {
            FileChannel channel = randomAccessFile.getChannel();

            // --- 1. Read the 8-byte little-endian header length ---
            ByteBuffer lengthBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            readFully(channel, lengthBuf, 0);
            lengthBuf.flip();
            long headerLength = lengthBuf.getLong();

            if (headerLength <= 0 || headerLength >= MAX_HEADER_LENGTH) {
                throw new IOException(
                    "Invalid safetensors header length: " + headerLength
                        + " (must be > 0 and < " + MAX_HEADER_LENGTH + ")"
                );
            }
            LOG.debug("Safetensors header length: " + headerLength + " bytes");

            // --- 2. Read the JSON header ---
            byte[] headerBytes = new byte[(int) headerLength];
            ByteBuffer headerBuf = ByteBuffer.wrap(headerBytes);
            readFully(channel, headerBuf, 8);
            String headerJson = new String(headerBytes, StandardCharsets.UTF_8);
            this.metadata = JsonParser.parseString(headerJson).getAsJsonObject();

            // --- 3. Memory-map the data section ---
            long dataStart = 8 + headerLength;
            long dataLength = channel.size() - dataStart;
            if (dataLength < 0) {
                throw new IOException("Safetensors file shorter than declared header");
            }
            this.dataBuffer = channel.map(FileChannel.MapMode.READ_ONLY, dataStart, dataLength);
            this.dataBuffer.order(ByteOrder.LITTLE_ENDIAN);

            LOG.info("Opened safetensors file: " + path + " (data section: " + dataLength + " bytes)");
        } catch (RuntimeException e) {
            randomAccessFile.close();
            throw new IOException("Invalid safetensors metadata header in file: " + path, e);
        } catch (IOException e) {
            randomAccessFile.close();
            throw e;
        }
    }

    /**
     * Loads a tensor by name as a flat {@code float[]} array.
     *
     * @param name the tensor name as it appears in the safetensors metadata
     * @return the tensor data as a float array
     * @throws IOException              if the tensor is not found
     * @throws IllegalArgumentException if the dtype is not {@code F32}
     */
    public float[] loadTensor(@NotNull String name) throws IOException {
        JsonObject tensor = getTensorMetadata(name);

        String dtype = tensor.get(DTYPE_KEY).getAsString();
        if (!"F32".equals(dtype)) {
            throw new IllegalArgumentException(
                "Unsupported dtype '" + dtype + "' for tensor '" + name + "': only F32 is supported"
            );
        }

        JsonArray offsets = tensor.getAsJsonArray("data_offsets");
        long start = offsets.get(0).getAsLong();
        long end = offsets.get(1).getAsLong();

        if (start < 0 || end < start || end > dataBuffer.capacity()) {
            throw new IOException(
                "Tensor '" + name + "' has invalid data offsets [" + start + ", " + end
                    + "): data section capacity is " + dataBuffer.capacity() + " bytes"
            );
        }

        long byteLength = end - start;

        if (byteLength % Float.BYTES != 0) {
            throw new IOException(
                "Tensor '" + name + "' byte range [" + start + ", " + end
                    + ") is not a multiple of 4 (float size)"
            );
        }

        int floatCount = (int) (byteLength / Float.BYTES);
        float[] result = new float[floatCount];

        // Create a duplicate so concurrent reads don't interfere with position state
        ByteBuffer slice = dataBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        slice.position((int) start);
        slice.limit((int) end);
        FloatBuffer floatBuffer = slice.asFloatBuffer();
        floatBuffer.get(result);

        LOG.debug("Loaded tensor '" + name + "': " + floatCount + " floats");
        return result;
    }

    /**
     * Returns the shape of a tensor as an {@code int[]} array.
     *
     * @param name the tensor name
     * @return the shape dimensions (e.g. {@code [384, 384]} for a 2-D weight matrix)
     * @throws IOException if the tensor is not found
     */
    public int[] getShape(@NotNull String name) throws IOException {
        JsonObject tensor = getTensorMetadata(name);
        JsonArray shapeArray = tensor.getAsJsonArray("shape");
        int[] shape = new int[shapeArray.size()];
        for (int i = 0; i < shapeArray.size(); i++) {
            shape[i] = shapeArray.get(i).getAsInt();
        }
        return shape;
    }

    /**
     * Checks whether a tensor with the given name exists in the file.
     *
     * @param name the tensor name to look up
     * @return {@code true} if the tensor exists, {@code false} otherwise
     */
    public boolean hasTensor(@NotNull String name) {
        if (!metadata.has(name)) {
            return false;
        }
        JsonElement element = metadata.get(name);
        // The metadata may contain a top-level "__metadata__" key that is not a tensor
        return element.isJsonObject() && element.getAsJsonObject().has(DTYPE_KEY);
    }

    /**
     * Closes the underlying {@link RandomAccessFile} and releases resources.
     */
    @Override
    public void close() throws IOException {
        randomAccessFile.close();
        LOG.debug("Closed SafetensorsReader");
    }

    // ---- private helpers --------------------------------------------------------

    /**
     * Reads exactly {@code buf.remaining()} bytes from the channel at the given position.
     * Unlike {@link FileChannel#read(ByteBuffer, long)}, this loops until the buffer is full
     * or EOF is reached.
     */
    private static void readFully(FileChannel channel, ByteBuffer buf, long position) throws IOException {
        long pos = position;
        while (buf.hasRemaining()) {
            int read = channel.read(buf, pos);
            if (read < 0) {
                throw new IOException(
                    "Safetensors file too short: expected " + buf.capacity()
                        + " bytes at offset " + position + ", got " + (buf.position())
                );
            }
            pos += read;
        }
    }

    /**
     * Retrieves and validates the JSON metadata entry for a tensor.
     */
    @NotNull
    private JsonObject getTensorMetadata(@NotNull String name) throws IOException {
        if (!metadata.has(name) || !metadata.get(name).isJsonObject()) {
            throw new IOException("Tensor not found in safetensors metadata: '" + name + "'");
        }
        JsonObject tensor = metadata.getAsJsonObject(name);
        if (!tensor.has(DTYPE_KEY) || !tensor.has("data_offsets") || !tensor.has("shape")) {
            throw new IOException(
                "Tensor '" + name + "' metadata is missing required fields (dtype, shape, data_offsets)"
            );
        }
        return tensor;
    }
}
