package com.github.catatafishen.agentbridge.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ToolOptions} — per-tool configuration POJO.
 */
@DisplayName("ToolOptions")
class ToolOptionsTest {

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        @DisplayName("default constructor creates empty template")
        void defaultConstructor() {
            ToolOptions opts = new ToolOptions();
            assertEquals("", opts.getOutputTemplate());
            assertTrue(opts.getCustomOptions().isEmpty());
        }

        @Test
        @DisplayName("single-arg constructor sets template")
        void singleArgConstructor() {
            ToolOptions opts = new ToolOptions("Always use conventional commits");
            assertEquals("Always use conventional commits", opts.getOutputTemplate());
        }

        @Test
        @DisplayName("single-arg constructor with null defaults to empty")
        void nullConstructorDefaultsEmpty() {
            ToolOptions opts = new ToolOptions(null);
            assertEquals("", opts.getOutputTemplate());
        }
    }

    @Nested
    @DisplayName("isEmpty")
    class IsEmpty {

        @Test
        @DisplayName("empty by default")
        void defaultIsEmpty() {
            assertTrue(new ToolOptions().isEmpty());
        }

        @Test
        @DisplayName("not empty when template is set")
        void notEmptyWithTemplate() {
            ToolOptions opts = new ToolOptions("Check tests after editing");
            assertFalse(opts.isEmpty());
        }

        @Test
        @DisplayName("not empty when hook command is set")
        void notEmptyWithHookCommand() {
            ToolOptions opts = new ToolOptions();
            opts.setOutputHookCommand("python hooks/tool.py");
            assertFalse(opts.isEmpty());
        }

        @Test
        @DisplayName("not empty when custom options are set")
        void notEmptyWithCustomOptions() {
            ToolOptions opts = new ToolOptions();
            opts.getCustomOptions().put("timeout", "30");
            assertFalse(opts.isEmpty());
        }

        @Test
        @DisplayName("empty after clearing template")
        void emptyAfterClearingTemplate() {
            ToolOptions opts = new ToolOptions("something");
            opts.setOutputTemplate("");
            assertTrue(opts.isEmpty());
        }
    }

    @Nested
    @DisplayName("outputTemplate")
    class OutputTemplate {

        @Test
        @DisplayName("round-trips correctly")
        void roundTrip() {
            ToolOptions opts = new ToolOptions();
            opts.setOutputTemplate("Remember to run tests");
            assertEquals("Remember to run tests", opts.getOutputTemplate());
        }

        @Test
        @DisplayName("hook command round-trips correctly")
        void hookCommandRoundTrip() {
            ToolOptions opts = new ToolOptions();
            opts.setOutputHookCommand("python hooks/tool.py");
            assertEquals("python hooks/tool.py", opts.getOutputHookCommand());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("null/empty hook command normalizes to empty string")
        void hookCommandNullNormalizesToEmpty(String value) {
            ToolOptions opts = new ToolOptions();
            opts.setOutputHookCommand(value);
            assertEquals("", opts.getOutputHookCommand());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("null/empty normalizes to empty string")
        void nullNormalizesToEmpty(String value) {
            ToolOptions opts = new ToolOptions("initial");
            opts.setOutputTemplate(value);
            assertEquals("", opts.getOutputTemplate());
        }
    }

    @Nested
    @DisplayName("customOptions")
    class CustomOptions {

        @Test
        @DisplayName("round-trips correctly")
        void roundTrip() {
            ToolOptions opts = new ToolOptions();
            Map<String, String> map = new LinkedHashMap<>();
            map.put("timeout", "60");
            map.put("rateLimit", "10/min");
            opts.setCustomOptions(map);
            assertEquals(Map.of("timeout", "60", "rateLimit", "10/min"), opts.getCustomOptions());
        }

        @Test
        @DisplayName("null normalizes to empty map")
        void nullNormalizesToEmptyMap() {
            ToolOptions opts = new ToolOptions();
            opts.getCustomOptions().put("key", "value");
            opts.setCustomOptions(null);
            assertTrue(opts.getCustomOptions().isEmpty());
        }
    }

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsHashCode {

        @Test
        @DisplayName("equal when same template and options")
        void equalWhenSame() {
            ToolOptions a = new ToolOptions("template");
            ToolOptions b = new ToolOptions("template");
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("not equal with different templates")
        void notEqualDifferentTemplates() {
            ToolOptions a = new ToolOptions("template1");
            ToolOptions b = new ToolOptions("template2");
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("not equal with different hook commands")
        void notEqualDifferentHookCommands() {
            ToolOptions a = new ToolOptions();
            ToolOptions b = new ToolOptions();
            a.setOutputHookCommand("python hook-a.py");
            b.setOutputHookCommand("python hook-b.py");
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("not equal with different custom options")
        void notEqualDifferentOptions() {
            ToolOptions a = new ToolOptions();
            a.getCustomOptions().put("key", "value1");
            ToolOptions b = new ToolOptions();
            b.getCustomOptions().put("key", "value2");
            assertNotEquals(a, b);
        }
    }
}
