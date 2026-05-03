package com.github.catatafishen.agentbridge.services;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Shared async I/O utilities for reading process streams.
 * Used by both {@link ToolOutputHookRunner} and
 * {@link com.github.catatafishen.agentbridge.services.hooks.HookExecutor}.
 */
public final class ProcessStreamUtils {

    private ProcessStreamUtils() {
    }

    /**
     * Reads an input stream asynchronously, returning the content as a string.
     * Optionally truncates to {@code maxChars} characters.
     *
     * @param stream   the input stream to read
     * @param maxChars maximum characters to keep (use {@link Integer#MAX_VALUE} for no limit)
     */
    public static CompletableFuture<String> readAsync(@NotNull InputStream stream, int maxChars) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                return text.length() > maxChars
                    ? text.substring(0, maxChars)
                    : text;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * Waits for a stream-reading future to complete and returns the result.
     *
     * @param future     the future from {@link #readAsync}
     * @param streamName label used in error messages (e.g., "stdout", "stderr")
     */
    public static @NotNull String await(@NotNull CompletableFuture<String> future,
                                        @NotNull String streamName) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted reading " + streamName, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UncheckedIOException unchecked) {
                throw unchecked.getCause();
            }
            throw new IOException("Failed reading " + streamName, cause);
        }
    }
}
