package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RunCommandTool#detectPermissionAbuse(JsonObject)} —
 * verifies JSON extraction from various permission request shapes and
 * delegation to {@link com.github.catatafishen.agentbridge.psi.ToolUtils#detectCommandAbuseType}.
 */
class RunCommandToolAbuseDetectionTest {

    private final RunCommandTool tool = new RunCommandTool(null);

    private static JsonObject withParameters(String command) {
        JsonObject params = new JsonObject();
        params.addProperty("command", command);
        JsonObject toolCall = new JsonObject();
        toolCall.add("parameters", params);
        return toolCall;
    }

    private static JsonObject withArguments(String command) {
        JsonObject args = new JsonObject();
        args.addProperty("command", command);
        JsonObject toolCall = new JsonObject();
        toolCall.add("arguments", args);
        return toolCall;
    }

    private static JsonObject withInput(String command) {
        JsonObject input = new JsonObject();
        input.addProperty("command", command);
        JsonObject toolCall = new JsonObject();
        toolCall.add("input", input);
        return toolCall;
    }

    @Nested
    @DisplayName("JSON extraction from different shapes")
    class JsonExtraction {

        @Test
        void extractsFromParameters() {
            String abuse = tool.detectPermissionAbuse(withParameters("git status"));
            assertEquals("git", abuse);
        }

        @Test
        void extractsFromArguments() {
            String abuse = tool.detectPermissionAbuse(withArguments("git commit -m 'msg'"));
            assertEquals("git", abuse);
        }

        @Test
        void extractsFromInput() {
            String abuse = tool.detectPermissionAbuse(withInput("cat README.md"));
            assertEquals("cat", abuse);
        }

        @Test
        void prioritizesParametersOverArguments() {
            JsonObject toolCall = new JsonObject();
            JsonObject params = new JsonObject();
            params.addProperty("command", "git push");
            toolCall.add("parameters", params);
            JsonObject args = new JsonObject();
            args.addProperty("command", "echo safe");
            toolCall.add("arguments", args);

            assertEquals("git", tool.detectPermissionAbuse(toolCall));
        }
    }

    @Nested
    @DisplayName("abuse detection via tool definition")
    class AbuseDetection {

        @ParameterizedTest
        @ValueSource(strings = {
            "git status",
            "git diff HEAD",
            "git push origin main",
        })
        void detectsGitAbuse(String command) {
            assertEquals("git", tool.detectPermissionAbuse(withParameters(command)));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "cat file.txt",
            "head -n 10 README.md",
            "tail log.txt",
        })
        void detectsCatAbuse(String command) {
            assertEquals("cat", tool.detectPermissionAbuse(withParameters(command)));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "sed -i 's/foo/bar/' file.txt",
            "sed 's/old/new/g' input.txt",
        })
        void detectsSedAbuse(String command) {
            assertEquals("sed", tool.detectPermissionAbuse(withParameters(command)));
        }
    }

    @Nested
    @DisplayName("clean commands pass through")
    class CleanCommands {

        @ParameterizedTest
        @ValueSource(strings = {
            "./gradlew clean",
            "npm start",
            "ls -la",
            "echo hello",
            "mkdir new_dir",
        })
        void allowsSafeCommands(String command) {
            assertNull(tool.detectPermissionAbuse(withParameters(command)));
        }
    }

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        void nullToolCallReturnsNull() {
            assertNull(tool.detectPermissionAbuse(null));
        }

        @Test
        void emptyObjectReturnsNull() {
            assertNull(tool.detectPermissionAbuse(new JsonObject()));
        }

        @Test
        void missingCommandFieldReturnsNull() {
            JsonObject params = new JsonObject();
            params.addProperty("timeout", 30);
            JsonObject toolCall = new JsonObject();
            toolCall.add("parameters", params);
            assertNull(tool.detectPermissionAbuse(toolCall));
        }

        @Test
        void nonObjectParametersIgnored() {
            JsonObject toolCall = new JsonObject();
            toolCall.addProperty("parameters", "not-an-object");
            assertNull(tool.detectPermissionAbuse(toolCall));
        }
    }
}
