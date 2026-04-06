package com.github.catatafishen.agentbridge.acp.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

/**
 * JSON-RPC 2.0 message types used by the ACP transport layer.
 */
public final class JsonRpcMessage {

    private JsonRpcMessage() {}

    public record Request(
            String jsonrpc,
            long id,
            String method,
            @Nullable JsonObject params
    ) {
        public Request(long id, String method, @Nullable JsonObject params) {
            this("2.0", id, method, params);
        }
    }

    public record Response(
            String jsonrpc,
            long id,
            @Nullable JsonElement result,
            @Nullable Error error
    ) {
        public static Response success(long id, JsonElement result) {
            return new Response("2.0", id, result, null);
        }

        public static Response error(long id, int code, String message) {
            return new Response("2.0", id, null, new Error(code, message, null));
        }
    }

    public record Notification(
            String jsonrpc,
            String method,
            @Nullable JsonObject params
    ) {
        public Notification(String method, @Nullable JsonObject params) {
            this("2.0", method, params);
        }
    }

    public record Error(int code, String message, @Nullable JsonElement data) {}
}
