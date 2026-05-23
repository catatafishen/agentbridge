package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class ProcessStreamUtilsTest {

    @Nested
    class ReadAsync {
        @Test
        void readsFullStream() throws Exception {
            var input = new ByteArrayInputStream("hello world".getBytes(StandardCharsets.UTF_8));
            var future = ProcessStreamUtils.readAsync(input, 1024);
            assertEquals("hello world", future.get(5, TimeUnit.SECONDS));
        }

        @Test
        void truncatesAtMaxChars() throws Exception {
            var longText = "x".repeat(200);
            var input = new ByteArrayInputStream(longText.getBytes(StandardCharsets.UTF_8));
            var future = ProcessStreamUtils.readAsync(input, 50);
            var result = future.get(5, TimeUnit.SECONDS);
            assertTrue(result.length() <= 50, "Result should be truncated to maxChars");
        }

        @Test
        void emptyStream() throws Exception {
            var input = new ByteArrayInputStream(new byte[0]);
            var future = ProcessStreamUtils.readAsync(input, 1024);
            assertEquals("", future.get(5, TimeUnit.SECONDS));
        }
    }

    @Nested
    class Await {
        @Test
        void returnsCompletedFutureResult() throws Exception {
            var future = CompletableFuture.completedFuture("result");
            assertEquals("result", ProcessStreamUtils.await(future, "test"));
        }

        @Test
        void throwsOnFailedFuture() {
            var future = new CompletableFuture<String>();
            future.completeExceptionally(new UncheckedIOException(new IOException("fail")));
            assertThrows(IOException.class, () -> ProcessStreamUtils.await(future, "test"));
        }
    }
}
