package com.github.catatafishen.agentbridge.services.hooks;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link JsHookEngine} against the bundled {@code .js} hooks, covering the policy
 * decisions that do not require the IntelliJ project model (git/gradle denial, soft nudges,
 * stale-naming). The source-root write-deny path ({@code Hook.isSourceOrTest}) needs a live
 * project model and is covered by integration tests, not here.
 */
class JsHookEngineTest {

    private static final String RESOURCE_DIR = "/default-hooks/scripts/";

    private static HookEntryConfig entry() {
        return entry(HookCapability.none());
    }

    private static HookEntryConfig entry(java.util.Set<HookCapability> capabilities) {
        return new HookEntryConfig("scripts/hook.js", 10, true, false, Map.of(), null, null, false, capabilities);
    }

    private static Project mockProject(Path baseDir) {
        Project project = Mockito.mock(Project.class);
        Mockito.lenient().when(project.getBasePath()).thenReturn(baseDir.toString());
        Mockito.lenient().when(project.getName()).thenReturn("test");
        return project;
    }

    /**
     * Copies a bundled script (and the shared {@code _lib.js}) into {@code dir}.
     */
    private static Path copy(Path dir, String name) throws IOException {
        copyResource(dir, "_lib.js");
        return copyResource(dir, name);
    }

    private static Path copyResource(Path dir, String name) throws IOException {
        Path target = dir.resolve(name);
        try (InputStream in = JsHookEngineTest.class.getResourceAsStream(RESOURCE_DIR + name)) {
            assertNotNull(in, "bundled resource missing: " + name);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private static HookPayload command(String tool, String command) {
        JsonObject args = new JsonObject();
        args.addProperty("command", command);
        return HookPayload.forPreExecution(tool, args, "test", "ts");
    }

    private static HookPayload commandResult(String command, String output, boolean error) {
        JsonObject args = new JsonObject();
        args.addProperty("command", command);
        return HookPayload.forPostExecution("run_command", args, output, error, "test", "ts", 0);
    }

    private static String run(Path dir, String script, HookPayload payload) throws IOException {
        Path path = copy(dir, script);
        return JsHookEngine.evaluate(mockProject(dir), path, entry(), payload);
    }

    // ---- run-command-abuse.js (permission) ----

    @Test
    void runCommandDeniesGit(@TempDir Path dir) throws IOException {
        String json = run(dir, "run-command-abuse.js", command("run_command", "git status"));
        assertTrue(json.contains("\"decision\":\"deny\""), json);
        assertTrue(json.contains("git commands are not allowed"), json);
    }

    @Test
    void runCommandDeniesGradleCompileOnly(@TempDir Path dir) throws IOException {
        String json = run(dir, "run-command-abuse.js", command("run_command", "./gradlew compileJava"));
        assertTrue(json.contains("\"decision\":\"deny\""), json);
        assertTrue(json.contains("Gradle compile tasks are not allowed"), json);
    }

    @Test
    void runCommandAllowsGradleTest(@TempDir Path dir) throws IOException {
        // compile + test together is a legitimate test run, not a compile-only task.
        assertEquals("", run(dir, "run-command-abuse.js", command("run_command", "./gradlew compileJava test")));
    }

    @Test
    void runCommandAllowsPlainCommand(@TempDir Path dir) throws IOException {
        assertEquals("", run(dir, "run-command-abuse.js", command("run_command", "echo hello")));
    }

    @Test
    void runCommandAllowsGrep(@TempDir Path dir) throws IOException {
        // grep is nudged by the success hook, never denied by the permission hook.
        assertEquals("", run(dir, "run-command-abuse.js", command("run_command", "grep -r foo .")));
    }

    // ---- run-in-terminal-abort.js (permission) ----

    @Test
    void terminalDeniesGit(@TempDir Path dir) throws IOException {
        String json = run(dir, "run-in-terminal-abort.js", command("run_in_terminal", "git commit -m x"));
        assertTrue(json.contains("\"decision\":\"deny\""), json);
        assertTrue(json.contains("git commands are not allowed"), json);
    }

    @Test
    void terminalAllowsListing(@TempDir Path dir) throws IOException {
        assertEquals("", run(dir, "run-in-terminal-abort.js", command("run_in_terminal", "ls -la")));
    }

    // ---- command-reprimand.js (success) ----

    @Test
    void reprimandNudgesGrep(@TempDir Path dir) throws IOException {
        String json = run(dir, "command-reprimand.js", commandResult("grep -r foo .", "out", false));
        assertTrue(json.contains("\"append\""), json);
        assertTrue(json.contains("search_text"), json);
    }

    @Test
    void reprimandNudgesCat(@TempDir Path dir) throws IOException {
        String json = run(dir, "command-reprimand.js", commandResult("cat file.txt", "out", false));
        assertTrue(json.contains("read_file"), json);
    }

    @Test
    void reprimandSkippedOnError(@TempDir Path dir) throws IOException {
        assertEquals("", run(dir, "command-reprimand.js", commandResult("grep -r foo .", "boom", true)));
    }

    @Test
    void reprimandSilentForPlainCommand(@TempDir Path dir) throws IOException {
        assertEquals("", run(dir, "command-reprimand.js", commandResult("echo hi", "hi", false)));
    }

    // ---- check-stale-naming.js (success) ----

    @Test
    void staleNamingFiresForLargeExistingFile(@TempDir Path dir) throws IOException {
        String content = "x\n".repeat(150);
        JsonObject args = new JsonObject();
        args.addProperty("content", content);
        HookPayload payload = HookPayload.forPostExecution(
            "write_file", args, "Written: Foo.java (200 lines)", false, "test", "ts", 0);
        String json = run(dir, "check-stale-naming.js", payload);
        assertTrue(json.contains("Stale naming check"), json);
    }

    @Test
    void staleNamingSkippedForNewFile(@TempDir Path dir) throws IOException {
        String content = "x\n".repeat(150);
        JsonObject args = new JsonObject();
        args.addProperty("content", content);
        HookPayload payload = HookPayload.forPostExecution(
            "write_file", args, "Created: Foo.java", false, "test", "ts", 0);
        assertEquals("", run(dir, "check-stale-naming.js", payload));
    }

    @Test
    void staleNamingSkippedForSmallFile(@TempDir Path dir) throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("content", "one\ntwo\n");
        HookPayload payload = HookPayload.forPostExecution(
            "write_file", args, "Written: Foo.java", false, "test", "ts", 0);
        assertEquals("", run(dir, "check-stale-naming.js", payload));
    }

    // ---- engine behaviour ----

    @Test
    void sandboxBlocksRuntimeEscape(@TempDir Path dir) throws IOException {
        Path script = dir.resolve("evil.js");
        Files.writeString(script, "java.lang.Runtime.getRuntime().exec('id');");
        IOException ex = assertThrows(IOException.class,
            () -> JsHookEngine.evaluate(mockProject(dir), script, entry(),
                command("run_command", "noop")));
        assertTrue(ex.getMessage().contains("Hook script error"), ex.getMessage());
    }

    @Test
    void infiniteLoopTimesOut(@TempDir Path dir) throws IOException {
        Path script = dir.resolve("loop.js");
        Files.writeString(script, "while (true) {}");
        HookEntryConfig oneSecond = new HookEntryConfig("scripts/loop.js", 1, true, false, Map.of(), null, null, false, HookCapability.none());
        IOException ex = assertThrows(IOException.class,
            () -> JsHookEngine.evaluate(mockProject(dir), script, oneSecond, command("run_command", "noop")));
        assertTrue(ex.getMessage().contains("timed out"), ex.getMessage());
    }

    @Test
    void isEmbeddedJsHookAcceptsPlainJs(@TempDir Path dir) throws IOException {
        Path script = dir.resolve("plain.js");
        Files.writeString(script, "// nothing\n");
        assertTrue(JsHookEngine.isEmbeddedJsHook(script));
    }

    @Test
    void isEmbeddedJsHookRejectsNodeShebang(@TempDir Path dir) throws IOException {
        Path script = dir.resolve("node.js");
        Files.writeString(script, "#!/usr/bin/env node\nconsole.log('hi');\n");
        assertFalse(JsHookEngine.isEmbeddedJsHook(script));
    }

    @Test
    void isEmbeddedJsHookRejectsNonJs(@TempDir Path dir) throws IOException {
        Path script = dir.resolve("hook.sh");
        Files.writeString(script, "echo hi\n");
        assertFalse(JsHookEngine.isEmbeddedJsHook(script));
    }

    // ---- capabilities ----

    private static String runInline(Path dir, String source, HookEntryConfig entry) throws IOException {
        Path path = dir.resolve("inline.js");
        Files.writeString(path, source);
        return JsHookEngine.evaluate(mockProject(dir), path, entry, command("run_command", "noop"));
    }

    @Test
    void filesystemReadDeniedWithoutCapability(@TempDir Path dir) {
        IOException ex = assertThrows(IOException.class,
            () -> runInline(dir, "Hook.readFile('x.txt');", entry()));
        assertTrue(ex.getMessage().contains("filesystem"), ex.getMessage());
    }

    @Test
    void filesystemRoundTripWithCapability(@TempDir Path dir) throws IOException {
        String json = runInline(dir,
            "Hook.writeFile('out.txt', 'hello world');\n"
                + "Hook.append(Hook.readFile('out.txt'));",
            entry(java.util.Set.of(HookCapability.FILESYSTEM)));
        assertTrue(json.contains("hello world"), json);
        assertTrue(Files.exists(dir.resolve("out.txt")));
    }

    @Test
    void filesystemListAndDeleteWithCapability(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.txt"), "1");
        Files.createDirectories(dir.resolve("sub"));
        String json = runInline(dir,
            "var names = JSON.parse(Hook.listDir('.'));\n"
                + "Hook.append('count=' + names.length + ' exists=' + Hook.exists('a.txt')"
                + " + ' dir=' + Hook.isDirectory('sub') + ' del=' + Hook.deletePath('a.txt'));",
            entry(java.util.Set.of(HookCapability.FILESYSTEM)));
        assertTrue(json.contains("exists=true"), json);
        assertTrue(json.contains("dir=true"), json);
        assertTrue(json.contains("del=true"), json);
        assertFalse(Files.exists(dir.resolve("a.txt")));
    }

    @Test
    void subprocessDeniedWithoutCapability(@TempDir Path dir) {
        IOException ex = assertThrows(IOException.class,
            () -> runInline(dir, "Hook.exec(JSON.stringify(['echo','hi']));", entry()));
        assertTrue(ex.getMessage().contains("subprocess"), ex.getMessage());
    }

    @Test
    void setArgumentAccumulatesIntoOneResult(@TempDir Path dir) throws IOException {
        String json = runInline(dir,
            "Hook.setArgument('author', 'Bot <bot@example.com>');\n"
                + "Hook.setArgument('message', 'msg');",
            entry());
        assertTrue(json.contains("\"arguments\""), json);
        assertTrue(json.contains("\"author\":\"Bot <bot@example.com>\""), json);
        assertTrue(json.contains("\"message\":\"msg\""), json);
    }

    @Test
    void setCommandIsShorthandForSetArgument(@TempDir Path dir) throws IOException {
        String json = runInline(dir, "Hook.setCommand('echo patched');", entry());
        assertTrue(json.contains("\"arguments\""), json);
        assertTrue(json.contains("\"command\":\"echo patched\""), json);
    }

    @Test
    void setEnvIsShorthandForSetArgumentWithEnvPrefix(@TempDir Path dir) throws IOException {
        String json = runInline(dir, "Hook.setEnv('GH_TOKEN', 'ghs_abc123');", entry());
        assertTrue(json.contains("\"arguments\""), json);
        assertTrue(json.contains("\"_env.GH_TOKEN\":\"ghs_abc123\""), json);
    }

    @Test
    void setEnvMultipleVarsAccumulate(@TempDir Path dir) throws IOException {
        String json = runInline(dir,
            "Hook.setEnv('FOO', 'bar');\nHook.setEnv('BAZ', 'qux');",
            entry());
        assertTrue(json.contains("\"_env.FOO\":\"bar\""), json);
        assertTrue(json.contains("\"_env.BAZ\":\"qux\""), json);
    }
}
