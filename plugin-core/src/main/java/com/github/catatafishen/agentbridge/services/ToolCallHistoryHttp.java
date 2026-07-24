package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Pure HTTP routing and JSON selection for the live tool-call history endpoint.
 */
final class ToolCallHistoryHttp {

    private static final String LIST_PATH = "/tool-calls";
    private static final String DETAIL_PATH_PREFIX = LIST_PATH + "/";

    private ToolCallHistoryHttp() {
    }

    static @NotNull Response resolve(@Nullable String method,
                                     @Nullable String path,
                                     @NotNull LiveToolCallService service) {
        if (!"GET".equals(method)) {
            return Response.error(405, "Method not allowed");
        }
        if (LIST_PATH.equals(path)) {
            return Response.ok(listJson(service.getEntries()));
        }
        if (path == null || !path.matches("/tool-calls/[1-9][0-9]*")) {
            return Response.error(400, "Malformed tool call id");
        }

        long callId;
        try {
            callId = Long.parseLong(path.substring(DETAIL_PATH_PREFIX.length()));
        } catch (NumberFormatException e) {
            return Response.error(400, "Malformed tool call id");
        }

        LiveToolCallEntry entry = service.findById(callId).orElse(null);
        if (entry == null) {
            return Response.error(404, "Tool call not found");
        }
        if (!entry.fullPayloadAvailable()) {
            return Response.error(410, "Full tool call payload unavailable");
        }
        return Response.ok(ToolCallHistoryJson.detail(entry));
    }

    private static @NotNull JsonObject listJson(@NotNull List<LiveToolCallEntry> entries) {
        JsonArray items = new JsonArray();
        for (LiveToolCallEntry entry : entries) {
            items.add(ToolCallHistoryJson.summary(entry));
        }
        JsonObject json = new JsonObject();
        json.add("items", items);
        return json;
    }

    record Response(int status, @Nullable JsonObject body, @Nullable String error) {
        static @NotNull Response ok(@NotNull JsonObject body) {
            return new Response(200, body, null);
        }

        static @NotNull Response error(int status, @NotNull String error) {
            return new Response(status, null, error);
        }
    }
}
