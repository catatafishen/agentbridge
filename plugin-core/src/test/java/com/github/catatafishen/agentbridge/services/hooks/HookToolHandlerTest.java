package com.github.catatafishen.agentbridge.services.hooks;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for static utility methods in {@link HookToolHandler}.
 */
class HookToolHandlerTest {

    @Nested
    class SuccessResponse {
        @Test
        void normalResult() {
            String json = HookToolHandler.successResponse("hello");
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            assertEquals("hello", obj.get("result").getAsString());
            assertFalse(obj.get("truncated").getAsBoolean());
            assertFalse(obj.get("error").getAsBoolean());
        }

        @Test
        void nullResult() {
            String json = HookToolHandler.successResponse(null);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            assertEquals("", obj.get("result").getAsString());
            assertFalse(obj.get("truncated").getAsBoolean());
        }

        @Test
        void emptyResult() {
            String json = HookToolHandler.successResponse("");
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            assertEquals("", obj.get("result").getAsString());
            assertFalse(obj.get("truncated").getAsBoolean());
        }

        @Test
        void truncatesLongResult() {
            // MAX_RESULT_CHARS is 100_000
            String longText = "x".repeat(120_000);
            String json = HookToolHandler.successResponse(longText);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            assertTrue(obj.get("truncated").getAsBoolean());
            assertTrue(obj.get("result").getAsString().length() <= 100_000);
        }

        @Test
        void exactLimitNotTruncated() {
            // MAX_RESULT_CHARS is 100_000
            String exact = "y".repeat(100_000);
            String json = HookToolHandler.successResponse(exact);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            assertFalse(obj.get("truncated").getAsBoolean());
            assertEquals(100_000, obj.get("result").getAsString().length());
        }
    }

    @Nested
    class ErrorResponse {
        @Test
        void containsErrorFlag() {
            String json = HookToolHandler.errorResponse("something broke");
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            assertTrue(obj.get("error").getAsBoolean());
            assertEquals("something broke", obj.get("message").getAsString());
        }

        @Test
        void emptyMessage() {
            String json = HookToolHandler.errorResponse("");
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            assertTrue(obj.get("error").getAsBoolean());
            assertEquals("", obj.get("message").getAsString());
        }
    }
}
