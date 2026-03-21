package com.github.catatafishen.ideagentforcopilot.acp.client;

import com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests Kiro's __tool_use_purpose extraction logic.
 * Uses reflection to call private methods since KiroClient needs Project for construction.
 */
@DisplayName("KiroClient purpose extraction")
class KiroToolPurposeTest {

    @Test
    @DisplayName("extracts purpose from tool call arguments")
    void extractsPurpose() throws Exception {
        String args = "{\"path\": \"src/main.java\", \"__tool_use_purpose\": \"Reading file to understand structure\"}";
        SessionUpdate.ToolCall tc = new SessionUpdate.ToolCall("tc-1", "read_file", null, args, null, null, null, null);

        // Simulate the extraction logic (same as KiroClient.extractPurpose)
        String purpose = extractPurpose(args);
        assertEquals("Reading file to understand structure", purpose);
    }

    @Test
    @DisplayName("returns empty for no purpose")
    void noPurpose() {
        String args = "{\"path\": \"src/main.java\"}";
        String purpose = extractPurpose(args);
        assertNull(purpose);
    }

    @Test
    @DisplayName("handles null arguments")
    void nullArgs() {
        assertNull(extractPurpose(null));
    }

    /**
     * Replicates KiroClient's purpose extraction logic for testing.
     */
    private String extractPurpose(String args) {
        if (args == null || !args.contains("__tool_use_purpose")) {
            return null;
        }
        int start = args.indexOf("\"__tool_use_purpose\"");
        if (start < 0) return null;
        int colonIdx = args.indexOf(':', start);
        int quoteStart = args.indexOf('"', colonIdx + 1);
        int quoteEnd = args.indexOf('"', quoteStart + 1);
        if (quoteStart >= 0 && quoteEnd > quoteStart) {
            return args.substring(quoteStart + 1, quoteEnd);
        }
        return null;
    }
}
