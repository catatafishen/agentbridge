package com.github.catatafishen.agentbridge.services.hooks;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class RepositoryBotIdentityHookTest {

    private static final String HOOK_PATH =
        ".agentbridge/hooks/scripts/enforce-agentbridge-gh-bot-identity.js";
    private static final String LIB_PATH =
        "plugin-core/src/main/resources/default-hooks/scripts/_lib.js";
    private static final String TEST_TOKEN = "ghs_test_token";

    @ParameterizedTest
    @ValueSource(strings = {
        "bash .agents/skills/pr-review/pr-ci.sh 1084",
        "sh ./.agents/skills/pr-review/pr-threads.sh 1084",
        ".agents/skills/pr-review/pr-issues.sh view 1085",
        "gh issue view 1085",
        "gh issue comment 1085 --body \"GH_TOKEN=example\"",
        "gh issue comment 1085 --body \"env -u GH_TOKEN\""
    })
    void githubCommandsReceiveBotToken(String command, @TempDir Path dir) throws IOException {
        String json = runHook(dir, "run_command", command);

        assertTrue(json.contains("\"_env.GH_TOKEN\":\"" + TEST_TOKEN + "\""), json);
    }

    @Test
    void untrustedShellScriptDoesNotReceiveBotToken(@TempDir Path dir) throws IOException {
        assertEquals("", runHook(dir, "run_command", "bash scripts/local.sh"));
    }

    @Test
    void terminalInvocationWrapsTrustedHelperWithBotToken(@TempDir Path dir) throws IOException {
        String json = runHook(dir, "run_in_terminal",
            "bash .agents/skills/pr-review/pr-ci.sh 1084");

        assertTrue(json.contains("export GH_TOKEN='" + TEST_TOKEN + "'"), json);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "env GH_TOKEN=personal bash .agents/skills/pr-review/pr-ci.sh 1084",
        "FOO=1 GH_TOKEN=personal gh issue view 1085",
        "FOO=1 GH_TOKEN=personal bash .agents/skills/pr-review/pr-ci.sh 1084",
        "FOO=1 GH_TOKEN=personal .agents/skills/pr-review/pr-threads.sh 1084",
        "GH_TOKEN=personal; gh issue view 1085",
        "export GH_TOKEN=personal; gh issue view 1085",
        "env -u GH_TOKEN gh issue view 1085",
        "env -uGH_TOKEN gh issue view 1085"
    })
    void githubCommandsCannotOverrideOrRemoveBotToken(String command, @TempDir Path dir) throws IOException {
        String json = runHook(dir, "run_command", command);

        assertTrue(json.contains("GH_TOKEN must not be overridden"), json);
    }

    private static String runHook(Path dir, String tool, String command) throws IOException {
        Path scriptDir = dir.resolve("hook-scripts");
        Files.createDirectories(scriptDir);
        Path hook = copyRepositoryFile(HOOK_PATH, scriptDir.resolve("hook.js"));
        copyRepositoryFile(LIB_PATH, scriptDir.resolve("_lib.js"));

        Path fakeHome = dir.resolve("home");
        Path tokenFile = fakeHome.resolve(".agentbridge/bot-token");
        Files.createDirectories(tokenFile.getParent());
        Files.writeString(tokenFile, TEST_TOKEN + "\n");

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());
        try {
            Project project = Mockito.mock(Project.class);
            Mockito.when(project.getBasePath()).thenReturn(dir.toString());
            Mockito.when(project.getName()).thenReturn("test");

            JsonObject args = new JsonObject();
            args.addProperty("command", command);
            HookPayload payload = HookPayload.forPreExecution(tool, args, "test", "ts");
            HookEntryConfig entry = new HookEntryConfig(
                "scripts/hook.js", 10, false, false, Map.of(), null, null, false,
                Set.of(HookCapability.FILESYSTEM, HookCapability.SUBPROCESS));
            return JsHookEngine.evaluate(project, hook, entry, payload);
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    private static Path copyRepositoryFile(String relativePath, Path target) throws IOException {
        Path source = findRepositoryFile(relativePath);
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private static Path findRepositoryFile(String relativePath) throws IOException {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(relativePath);
            if (Files.isRegularFile(candidate)) return candidate;
            directory = directory.getParent();
        }
        throw new IOException("Repository file not found: " + relativePath);
    }
}
