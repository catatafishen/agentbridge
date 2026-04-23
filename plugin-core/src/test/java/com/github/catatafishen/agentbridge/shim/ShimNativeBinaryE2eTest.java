package com.github.catatafishen.agentbridge.shim;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E test for the native Go shim binary, parallel to {@link ShimScriptE2eTest}.
 *
 * <p>Same three scenarios as the bash version (HTTP 200 redirect, missing port
 * fall-through, HTTP 204 fall-through), but exercises the cross-platform
 * {@code agentbridge-shim} binary instead of the POSIX script. Limited to
 * Linux x86_64 in CI because that is the only platform on which the binary
 * runs natively in this repository's GitHub runners; cross-platform builds
 * are validated by Go-side unit tests under {@code plugin-core/shim-src/}.
 *
 * <p>Skipped silently when the bundled binary is missing (developer worktree
 * without {@code scripts/build-shims.sh} executed).
 */
@EnabledOnOs(value = OS.LINUX, architectures = "amd64")
class ShimNativeBinaryE2eTest {

    private HttpServer server;
    private int port;
    private Path workDir;
    private Path shimDir;
    private Path fakeBinDir;
    private final ConcurrentLinkedQueue<List<String>> recordedArgvs = new ConcurrentLinkedQueue<>();
    private volatile int responseStatus = 200;
    private volatile String responseBody = "EXIT 0\nhello from MCP";
    private boolean binaryAvailable;

    @BeforeEach
    void setUp() throws IOException {
        workDir = Files.createTempDirectory("shim-native-e2e");
        shimDir = workDir.resolve("shims");
        fakeBinDir = workDir.resolve("fakebin");
        Files.createDirectories(shimDir);
        Files.createDirectories(fakeBinDir);

        binaryAvailable = copyNativeBinaryAsCat();
        if (!binaryAvailable) return;

        // Fake "cat" used to observe shim fall-through.
        Path realCat = fakeBinDir.resolve("cat");
        Files.writeString(realCat, "#!/usr/bin/env bash\necho fallthrough-cat-saw \"$@\"\n");
        markExecutable(realCat);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/shim-exec", exchange -> {
            try (exchange) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                recordedArgvs.add(ShimController.parseArgv(new String(body, StandardCharsets.UTF_8)));
                if (responseStatus == 204) {
                    exchange.sendResponseHeaders(204, -1);
                } else {
                    byte[] resp = responseBody.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(responseStatus, resp.length);
                    exchange.getResponseBody().write(resp);
                }
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) server.stop(0);
        deleteRecursively(workDir);
    }

    @Test
    void redirectedResponsePrintsStdoutAndExitsWithCode() throws Exception {
        if (!binaryAvailable) return; // bundle missing — skip silently
        responseStatus = 200;
        responseBody = "EXIT 7\nrouted-output";

        ProcessResult r = runShim(List.of("foo.txt"), true, "tok");

        assertEquals(7, r.exitCode, "shim must propagate EXIT code");
        assertEquals("routed-output", r.stdout);
        assertEquals(1, recordedArgvs.size());
        assertEquals(List.of("cat", "foo.txt"), recordedArgvs.peek(),
            "argv must include $0 basename then user args");
    }

    @Test
    void noPortFallsThroughWithoutHttp() throws Exception {
        if (!binaryAvailable) return;
        ProcessResult r = runShim(List.of("hello.txt"), false, null);

        assertEquals(0, r.exitCode);
        assertTrue(r.stdout.contains("fallthrough-cat-saw hello.txt"),
            "shim must exec the fake cat when AGENTBRIDGE_SHIM_PORT is unset; stdout=" + r.stdout);
        assertTrue(recordedArgvs.isEmpty(), "no HTTP call expected when port is unset");
    }

    @Test
    void serverReturns204CausesFallThrough() throws Exception {
        if (!binaryAvailable) return;
        responseStatus = 204;
        responseBody = "";

        ProcessResult r = runShim(List.of("payload.txt"), true, "tok");

        assertEquals(0, r.exitCode);
        assertTrue(r.stdout.contains("fallthrough-cat-saw payload.txt"),
            "204 response must cause fall-through; stdout=" + r.stdout);
        assertEquals(1, recordedArgvs.size(), "HTTP call still happens, then fall-through");
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }

    private ProcessResult runShim(List<String> args, boolean withPort, String token) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(shimDir.resolve("cat").toString());
        cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        // PATH order: shim dir first so the shim's own selfDir-strip logic kicks in
        // and falls through to the fake cat in fakeBinDir.
        String pathPrefix = shimDir + ":" + fakeBinDir + ":";
        String currentPath = System.getenv("PATH");
        pb.environment().put("PATH", pathPrefix + (currentPath == null ? "" : currentPath));
        if (withPort) pb.environment().put("AGENTBRIDGE_SHIM_PORT", Integer.toString(port));
        if (token != null) pb.environment().put("AGENTBRIDGE_SHIM_TOKEN", token);
        pb.redirectErrorStream(false);

        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!p.waitFor(15, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new AssertionError("shim timed out; stdout=" + out + " stderr=" + err);
        }
        return new ProcessResult(p.exitValue(), out, err);
    }

    private boolean copyNativeBinaryAsCat() throws IOException {
        String resource = "/agentbridge/shim/bin/" + linuxKey() + "/agentbridge-shim";
        try (InputStream in = ShimManager.class.getResourceAsStream(resource)) {
            if (in == null) return false;
            Path target = shimDir.resolve("cat");
            Files.write(target, in.readAllBytes());
            markExecutable(target);
            assertNotNull(target);
            return true;
        }
    }

    private static String linuxKey() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "aarch64", "arm64" -> "linux-arm64";
            default -> "linux-amd64";
        };
    }

    private static void markExecutable(Path p) throws IOException {
        Files.setPosixFilePermissions(p, EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE
        ));
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var s = Files.walk(root)) {
            s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // best-effort cleanup
                }
            });
        }
    }
}
