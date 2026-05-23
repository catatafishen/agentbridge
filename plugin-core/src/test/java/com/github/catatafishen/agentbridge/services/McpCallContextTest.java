package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpCallContextTest {

    @AfterEach
    void cleanup() {
        McpCallContext.clear();
    }

    @Nested
    class Current {
        @Test
        void nullWhenNotSet() {
            assertNull(McpCallContext.current());
        }

        @Test
        void returnsSetValue() {
            McpCallContext.setCurrent("req-123");
            assertEquals("req-123", McpCallContext.current());
        }
    }

    @Nested
    class CurrentOrFallback {
        @Test
        void returnsBoundValue() {
            McpCallContext.setCurrent("req-abc");
            assertEquals("req-abc", McpCallContext.currentOrFallback());
        }

        @Test
        void returnsFallbackWhenUnbound() {
            String fallback = McpCallContext.currentOrFallback();
            assertNotNull(fallback);
            assertTrue(fallback.startsWith("test:"));
        }
    }

    @Nested
    class Clear {
        @Test
        void removesCurrentValue() {
            McpCallContext.setCurrent("req-xyz");
            McpCallContext.clear();
            assertNull(McpCallContext.current());
        }

        @Test
        void clearWhenNothingSetDoesNotThrow() {
            assertDoesNotThrow(McpCallContext::clear);
        }
    }

    @Nested
    class SetCurrent {
        @Test
        void overwritesPreviousValue() {
            McpCallContext.setCurrent("first");
            McpCallContext.setCurrent("second");
            assertEquals("second", McpCallContext.current());
        }
    }
}
