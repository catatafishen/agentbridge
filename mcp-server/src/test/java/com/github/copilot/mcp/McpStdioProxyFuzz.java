package com.github.copilot.mcp;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;

/**
 * Jazzer fuzz target for {@link McpStdioProxy#extractJsonRpcId(String)} and
 * {@link McpStdioProxy#buildErrorResponse(String, String)}.
 *
 * <p>These are hand-rolled JSON parsers on the critical path — every MCP message
 * from an agent flows through them. Malformed JSON (unclosed strings, nested "id"
 * keys, UTF-8 edge cases) could cause index-out-of-bounds or response injection.
 *
 * <p>To run: {@code java -jar jazzer.jar --cp=<test-classpath>
 * --target_class=com.github.copilot.mcp.fuzz.McpStdioProxyFuzz}
 */
public class McpStdioProxyFuzz {

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        String message = data.consumeString(data.remainingBytes() / 2);
        String errorMessage = data.consumeRemainingAsString();

        // extractJsonRpcId must never throw — it returns "null" for unparseable input
        String id = McpStdioProxy.extractJsonRpcId(message);
        if (id == null) {
            throw new AssertionError("extractJsonRpcId returned null (should return \"null\" string)");
        }

        // buildErrorResponse must produce valid-ish JSON for any input
        String response = McpStdioProxy.buildErrorResponse(message, errorMessage);
        if (response == null || response.isEmpty()) {
            throw new AssertionError("buildErrorResponse returned null/empty");
        }
    }
}
