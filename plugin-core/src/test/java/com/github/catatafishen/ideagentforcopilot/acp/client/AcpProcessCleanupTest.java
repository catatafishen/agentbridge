package com.github.catatafishen.ideagentforcopilot.acp.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

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
            for (ProcessHandle descendant : descendants) {
                assertFalse(descendant.isAlive(), "Child process " + descendant.pid() + " should be terminated");
            }
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
        List<ProcessHandle> descendants = handle.descendants().toList();
        while (descendants.isEmpty() && System.currentTimeMillis() < deadline && process.isAlive()) {
            Thread.sleep(100);
            descendants = handle.descendants().toList();
        }
        if (descendants.isEmpty()) {
            fail("Process did not spawn descendants before timeout");
        }
        return descendants;
    }
}
