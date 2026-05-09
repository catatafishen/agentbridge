package com.github.catatafishen.agentbridge.psi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for private static methods in {@link RunConfigurationService} via reflection.
 */
class RunConfigurationServiceStaticMethodsTest {

    private static Method parseTaskNames;
    private static Method checkProgramArgsAbuse;

    @BeforeAll
    static void setup() throws Exception {
        parseTaskNames = RunConfigurationService.class.getDeclaredMethod(
            "parseTaskNames", com.google.gson.JsonElement.class);
        parseTaskNames.setAccessible(true);

        checkProgramArgsAbuse = RunConfigurationService.class.getDeclaredMethod(
            "checkProgramArgsAbuse", JsonObject.class, String.class);
        checkProgramArgsAbuse.setAccessible(true);
    }

    @Nested
    class ParseTaskNames {

        @SuppressWarnings("unchecked")
        private List<String> call(com.google.gson.JsonElement elem) throws Exception {
            return (List<String>) parseTaskNames.invoke(null, elem);
        }

        @Test
        void parsesJsonArray() throws Exception {
            JsonArray arr = new JsonArray();
            arr.add(":core:build");
            arr.add(":app:test");
            List<String> result = call(arr);
            assertEquals(List.of(":core:build", ":app:test"), result);
        }

        @Test
        void parsesSpaceSeparatedString() throws Exception {
            List<String> result = call(new JsonPrimitive("clean build test"));
            assertEquals(List.of("clean", "build", "test"), result);
        }

        @Test
        void singleTaskString() throws Exception {
            List<String> result = call(new JsonPrimitive("build"));
            assertEquals(List.of("build"), result);
        }

        @Test
        void emptyArrayReturnsEmptyList() throws Exception {
            List<String> result = call(new JsonArray());
            assertTrue(result.isEmpty());
        }

        @Test
        void emptyStringReturnsEmptyList() throws Exception {
            List<String> result = call(new JsonPrimitive(""));
            assertTrue(result.isEmpty());
        }

        @Test
        void multipleSpacesHandledCorrectly() throws Exception {
            List<String> result = call(new JsonPrimitive("clean   build"));
            assertEquals(List.of("clean", "build"), result);
        }
    }

    @Nested
    class CheckProgramArgsAbuse {

        private String call(JsonObject args, String configType) throws Exception {
            return (String) checkProgramArgsAbuse.invoke(null, args, configType);
        }

        @Test
        void noProgramArgsReturnsNull() throws Exception {
            JsonObject args = new JsonObject();
            assertNull(call(args, "application"));
        }

        static Stream<Arguments> allowedProgramArgs() {
            return Stream.of(
                Arguments.of("--verbose --port 8080", "application"),
                // "test" alone doesn't match general abuse patterns, only gradle-specific check
                Arguments.of("test --info", "application"),
                // null configType means we only run general abuse detection (test alone won't match)
                Arguments.of("test --info", null)
            );
        }

        @ParameterizedTest
        @MethodSource("allowedProgramArgs")
        void allowedArgsReturnsNull(String programArgs, String configType) throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("program_args", programArgs);
            assertNull(call(args, configType));
        }

        static Stream<Arguments> blockedCommands() {
            return Stream.of(
                Arguments.of("git push origin main", "application", "Error:"),
                Arguments.of("test --info", "gradle", "run_tests"),
                Arguments.of("sed -i 's/foo/bar/g' file.txt", "application", "edit_text")
            );
        }

        @ParameterizedTest
        @MethodSource("blockedCommands")
        void blockedCommandDetected(String programArgs, String configType, String expectedSubstring) throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("program_args", programArgs);
            String result = call(args, configType);
            assertNotNull(result);
            assertTrue(result.contains(expectedSubstring),
                "Expected result to contain '" + expectedSubstring + "' but was: " + result);
        }
    }
}
