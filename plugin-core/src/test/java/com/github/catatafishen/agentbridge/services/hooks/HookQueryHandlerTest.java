package com.github.catatafishen.agentbridge.services.hooks;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for static utility methods in {@link HookQueryHandler}.
 */
class HookQueryHandlerTest {

    @Nested
    class ErrorJson {
        @Test
        void containsErrorMessage() {
            String json = HookQueryHandler.errorJson("bad request");
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            assertEquals("bad request", obj.get("error").getAsString());
        }

        @Test
        void emptyMessage() {
            String json = HookQueryHandler.errorJson("");
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            assertEquals("", obj.get("error").getAsString());
        }

        @Test
        void validJsonStructure() {
            String json = HookQueryHandler.errorJson("test");
            assertDoesNotThrow(() -> JsonParser.parseString(json));
        }
    }
}
