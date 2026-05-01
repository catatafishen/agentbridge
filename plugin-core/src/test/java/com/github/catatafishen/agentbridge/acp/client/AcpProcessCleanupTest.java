package com.github.catatafishen.agentbridge.acp.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

@DisplayName("ACP process cleanup")
class AcpProcessCleanupTest {

    @Test
    void destroyProcessTreeTerminatesChildren() throws Exception {
        Process process = startProcessTree();
        try {
            List<ProcessHandle> descendants = awaitDescendants(process, 5_000L);
            assertFalse(descendants.isEmpty(), "Expected the test process to spawn at least one child");

            AcpClient.destroyProcessTree(process);

            assertFalse(process.isAlive(), "Parent process should be terminated");
            awaitAllTerminated(descendants, 5_000L);
        } finally {
            AcpClient.destroyProcessTree(process);
        }
    }

    private static Process startProcessTree() throws IOException {
        ProcessBuilder builder = new ProcessBuilder("sh", "-c", "sleep 60 & sleep 60");
        builder.redirectErrorStream(true);
        return builder.start();
    }

    private static List<ProcessHandle> awaitDescendants(Process process, long timeoutMs) throws InterruptedException {
        ProcessHandle handle = process.toHandle();
        long deadline = System.currentTimeMillis() + timeoutMs;
        waitUntil(() -> !handle.descendants().toList().isEmpty() || !process.isAlive(), deadline);
        List<ProcessHandle> descendants = handle.descendants().toList();
        if (descendants.isEmpty()) {
            fail("Process did not spawn descendants before timeout");
        }
        return descendants;
    }

    private static void waitUntil(BooleanSupplier condition, long deadline) throws InterruptedException {
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            new CountDownLatch(1).await(100, TimeUnit.MILLISECONDS);
        }
    }

    private static void awaitAllTerminated(List<ProcessHandle> handles, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        for (ProcessHandle h : handles) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining > 0) {
                try {
                    h.onExit().orTimeout(remaining, java.util.concurrent.TimeUnit.MILLISECONDS).join();
                } catch (java.util.concurrent.CompletionException ignored) {
                    // timeout — fall through to assertion
                }
            }
            assertFalse(h.isAlive(), "Child process " + h.pid() + " should be terminated");
        }
    }
}
