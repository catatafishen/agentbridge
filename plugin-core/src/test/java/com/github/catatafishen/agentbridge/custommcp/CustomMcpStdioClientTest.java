package com.github.catatafishen.agentbridge.custommcp;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that responses are matched to requests by JSON-RPC id, using a fake stdio server
 * (a bash script) that emits noise before each real response.
 */
@DisabledOnOs(OS.WINDOWS)
class CustomMcpStdioClientTest {

    /**
     * For every request line: echo a notification, a stale response with a foreign id, and a
     * server-initiated request, then the real response whose text is the request's own id.
     */
    private static final String NOISY_SERVER = """
        while IFS= read -r line; do
          id=$(printf '%s' "$line" | sed -n 's/.*"id":\\([0-9]*\\).*/\\1/p')
          [ -z "$id" ] && continue
          case "$line" in
            *'"method":"initialize"'*)
              printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2025-03-26","capabilities":{},"serverInfo":{"name":"fake","version":"1"}}}\\n' "$id";;
            *)
              printf '{"jsonrpc":"2.0","method":"notifications/message","params":{"data":"hi"}}\\n'
              printf '{"jsonrpc":"2.0","id":9999,"result":{"content":[{"type":"text","text":"STALE"}]}}\\n'
              printf '{"jsonrpc":"2.0","id":777,"method":"ping"}\\n'
              printf '{"jsonrpc":"2.0","id":%s,"result":{"content":[{"type":"text","text":"reply-%s"}]}}\\n' "$id" "$id";;
          esac
        done
        """;

    @Test
    @DisplayName("callTool ignores notifications, stale replies and server requests, returning its own response")
    void callToolReturnsResponseMatchingItsOwnId() throws Exception {
        try (CustomMcpStdioClient client = new CustomMcpStdioClient(
            "bash", List.of("-c", NOISY_SERVER), Map.of())) {
            client.initialize();

            // initialize used id 1, so the first tool call is id 2, the next id 3
            assertEquals("reply-2", client.callTool("t", new JsonObject()));
            assertEquals("reply-3", client.callTool("t", new JsonObject()));
        }
    }
}
