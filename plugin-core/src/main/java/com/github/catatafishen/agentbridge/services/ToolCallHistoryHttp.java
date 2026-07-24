package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.Supplier;

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
        return resolve(method, path, service::getEntries, service::findById, ignored -> { });
    }

    static @NotNull Response resolve(@Nullable String method,
                                     @Nullable String path,
                                     @NotNull Supplier<List<LiveToolCallEntry>> entriesSupplier,
                                     @NotNull LongFunction<Optional<LiveToolCallEntry>> entryFinder) {
        return resolve(method, path, entriesSupplier, entryFinder, ignored -> { });
    }

    static @NotNull Response resolve(@Nullable String method,
                                     @Nullable String path,
                                     @NotNull Supplier<List<LiveToolCallEntry>> entriesSupplier,
                                     @NotNull LongFunction<Optional<LiveToolCallEntry>> entryFinder,
                                     @NotNull Consumer<SerializationFailure> failureLogger) {
        if (!"GET".equals(method)) {
            return Response.error(405, "Method not allowed");
        }
        if (LIST_PATH.equals(path)) {
            try {
                return Response.ok(listJson(entriesSupplier.get()));
            } catch (RuntimeException e) {
                failureLogger.accept(SerializationFailure.of("list", path, null, e));
                return Response.ok(listJson(List.of()));
            }
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

        LiveToolCallEntry entry = entryFinder.apply(callId).orElse(null);
        if (entry == null) {
            return Response.error(404, "Tool call not found");
        }
        if (!entry.fullPayloadAvailable()) {
            return Response.error(410, "Full tool call payload unavailable");
        }
        try {
            return Response.ok(ToolCallHistoryJson.detail(entry));
        } catch (RuntimeException e) {
            failureLogger.accept(SerializationFailure.of("detail", path, callId, e));
            throw e;
        }
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

    record SerializationFailure(@NotNull String stage,
                                @NotNull String path,
                                @Nullable Long callId,
                                @NotNull String exceptionType) {
        static @NotNull SerializationFailure of(@NotNull String stage,
                                                @NotNull String path,
                                                @Nullable Long callId,
                                                @NotNull RuntimeException exception) {
            return new SerializationFailure(stage, path, callId, exception.getClass().getName());
        }
    }
}
