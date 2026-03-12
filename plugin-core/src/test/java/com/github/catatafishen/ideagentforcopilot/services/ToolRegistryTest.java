package com.github.catatafishen.ideagentforcopilot.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ToolRegistry} — built-in tool IDs, registration, and lookups.
 */
class ToolRegistryTest {

    @Nested
    @DisplayName("built-in tool IDs")
    class BuiltInToolIds {

        @Test
        void containsExpectedReadTools() {
            List<String> ids = ToolRegistry.getBuiltInToolIds();
            assertTrue(ids.contains("view"));
            assertTrue(ids.contains("read"));
            assertTrue(ids.contains("grep"));
            assertTrue(ids.contains("glob"));
            assertTrue(ids.contains("list"));
        }

        @Test
        void containsExpectedWriteTools() {
            List<String> ids = ToolRegistry.getBuiltInToolIds();
            assertTrue(ids.contains("bash"));
            assertTrue(ids.contains("edit"));
            assertTrue(ids.contains("write"));
            assertTrue(ids.contains("create"));
            assertTrue(ids.contains("execute"));
            assertTrue(ids.contains("runInTerminal"));
        }

        @Test
        void returnsImmutableList() {
            List<String> ids = ToolRegistry.getBuiltInToolIds();
            assertThrows(UnsupportedOperationException.class, () -> ids.add("hacked"));
        }

        @Test
        void isStatic() {
            // Same instance every call — no project dependency
            assertSame(ToolRegistry.getBuiltInToolIds(), ToolRegistry.getBuiltInToolIds());
        }
    }
}
