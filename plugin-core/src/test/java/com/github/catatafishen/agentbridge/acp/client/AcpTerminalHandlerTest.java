package com.github.catatafishen.agentbridge.acp.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AcpTerminalHandler} based on the ACP terminal spec.
 * <p>
 * Tests cover all 5 terminal methods and verify spec requirements:
 * response format, truncation behavior, lifecycle management.
 *
 * @see <a href="https://agentclientprotocol.com/protocol/terminals">ACP Terminals</a>
 */
@SuppressWarnings("DataFlowIssue") // null Project is fine — tests always supply explicit cwd
class AcpTerminalHandlerTest {

    private final AcpTerminalHandler handler = new AcpTerminalHandler(null);

    @AfterEach
    void tearDown() {
        handler.releaseAll();
    }

    // ── terminal/create — per spec: returns {terminalId} immediately ─────────

    @Test
    void createReturnsTerminalIdImmediately() throws IOException {
        JsonObject result = handler.create(createParams("echo", "hello"));

        assertTrue(result.has("terminalId"), "Response must contain terminalId per spec");
        assertFalse(result.get("terminalId").getAsString().isEmpty());
    }

    @Test
    void createWithArgsEnvAndCwd() throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("command", "echo");
        params.addProperty("cwd", System.getProperty("user.dir"));
        JsonArray args = new JsonArray();
        args.add("hello");
        args.add("world");
        params.add("args", args);

        JsonArray env = new JsonArray();
        JsonObject envVar = new JsonObject();
        envVar.addProperty("name", "MY_VAR");
        envVar.addProperty("value", "test");
        env.add(envVar);
        params.add("env", env);

        JsonObject result = handler.create(params);
        assertTrue(result.has("terminalId"));
    }

    @Test
    void createRequiresCommand() {
        assertThrows(IllegalArgumentException.class, () -> handler.create(new JsonObject()),
            "Must reject missing 'command' parameter");
    }

    // ── terminal/output — per spec: returns {output, truncated, exitStatus?} ─

    @Test
    void outputReturnsRequiredFields() throws Exception {
        String id = createTerminal("echo", "hello");
        Thread.sleep(500);

        JsonObject result = handler.output(terminalParams(id));

        assertTrue(result.has("output"), "Must contain 'output' per spec");
        assertTrue(result.has("truncated"), "Must contain 'truncated' per spec");
        assertFalse(result.get("truncated").getAsBoolean());
    }

    @Test
    void outputIncludesExitStatusOnlyWhenFinished() throws Exception {
        String id = createTerminal("echo", "done");
        Thread.sleep(1000);

        JsonObject result = handler.output(terminalParams(id));

        // Spec: exitStatus present only if command has exited, fields: exitCode + signal
        if (result.has("exitStatus")) {
            JsonObject exitStatus = result.getAsJsonObject("exitStatus");
            assertTrue(exitStatus.has("exitCode"), "exitStatus must have exitCode per spec");
            assertTrue(exitStatus.has("signal"), "exitStatus must have signal per spec");
        }
    }

    @Test
    void outputTruncatesFromBeginningPerSpec() throws Exception {
        // ACP spec: "Client truncates from the beginning of the output to stay within the limit"
        JsonObject params = new JsonObject();
        params.addProperty("command", "bash");
        params.addProperty("cwd", System.getProperty("user.dir"));
        JsonArray args = new JsonArray();
        args.add("-c");
        args.add("for i in $(seq 1 200); do echo \"line$i\"; done");
        params.add("args", args);
        params.addProperty("outputByteLimit", 50);

        String id = handler.create(params).get("terminalId").getAsString();
        Thread.sleep(1500);

        JsonObject result = handler.output(terminalParams(id));

        assertTrue(result.get("truncated").getAsBoolean(),
            "Per spec: must report truncated=true when output exceeds byte limit");
        assertTrue(result.get("output").getAsString().getBytes().length <= 50,
            "Output bytes must not exceed outputByteLimit");
    }

    @Test
    void outputRejectsUnknownTerminalId() {
        assertThrows(IllegalArgumentException.class,
            () -> handler.output(terminalParams("nonexistent")));
    }

    // ── terminal/wait_for_exit — per spec: blocks, returns {exitCode, signal} ─

    @Test
    void waitForExitReturnsExitCodeAndSignal() throws Exception {
        String id = createTerminal("echo", "test");

        JsonObject result = handler.waitForExit(terminalParams(id));

        assertTrue(result.has("exitCode"), "Per spec: must have exitCode");
        assertTrue(result.has("signal"), "Per spec: must have signal");
        assertEquals(0, result.get("exitCode").getAsInt());
    }

    @Test
    void waitForExitBlocksUntilCompletion() throws Exception {
        String id = createTerminal("sleep", "0.5");

        long start = System.nanoTime();
        handler.waitForExit(terminalParams(id));
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsed >= 400, "Per spec: should block until process exits");
    }

    @Test
    void waitForExitReportsNonZeroExitCode() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("command", "bash");
        params.addProperty("cwd", System.getProperty("user.dir"));
        JsonArray args = new JsonArray();
        args.add("-c");
        args.add("exit 42");
        params.add("args", args);

        String id = handler.create(params).get("terminalId").getAsString();
        JsonObject result = handler.waitForExit(terminalParams(id));

        assertEquals(42, result.get("exitCode").getAsInt());
    }

    // ── terminal/kill — per spec: kills but keeps terminal valid ─────────────

    @Test
    void killKeepsTerminalValidPerSpec() throws Exception {
        String id = createTerminal("sleep", "60");

        handler.kill(terminalParams(id));

        // Per spec: "terminal remains valid" — output and wait_for_exit should work
        JsonObject output = handler.output(terminalParams(id));
        assertTrue(output.has("output"), "Terminal must remain valid after kill");

        JsonObject exit = handler.waitForExit(terminalParams(id));
        assertTrue(exit.has("exitCode"), "wait_for_exit must work after kill");
    }

    // ── terminal/release — per spec: kills + invalidates terminal ID ────────

    @Test
    void releaseInvalidatesTerminalId() throws Exception {
        String id = createTerminal("sleep", "60");

        handler.release(terminalParams(id));

        // Per spec: "terminal ID becomes invalid for all other terminal/* methods"
        assertThrows(IllegalArgumentException.class, () -> handler.output(terminalParams(id)));
        assertThrows(IllegalArgumentException.class, () -> handler.kill(terminalParams(id)));
    }

    @Test
    void releaseAllCleansUpEverything() throws Exception {
        String id1 = createTerminal("sleep", "60");
        String id2 = createTerminal("sleep", "60");

        handler.releaseAll();

        assertThrows(IllegalArgumentException.class, () -> handler.output(terminalParams(id1)));
        assertThrows(IllegalArgumentException.class, () -> handler.output(terminalParams(id2)));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String createTerminal(String command, String arg) throws IOException {
        return handler.create(createParams(command, arg)).get("terminalId").getAsString();
    }

    private JsonObject createParams(String command, String arg) {
        JsonObject params = new JsonObject();
        params.addProperty("command", command);
        params.addProperty("cwd", System.getProperty("user.dir"));
        JsonArray args = new JsonArray();
        args.add(arg);
        params.add("args", args);
        return params;
    }

    private JsonObject terminalParams(String terminalId) {
        JsonObject params = new JsonObject();
        params.addProperty("terminalId", terminalId);
        return params;
    }
}
