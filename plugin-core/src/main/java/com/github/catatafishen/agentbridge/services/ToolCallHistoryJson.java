package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

/**
 * Serializes live tool-call history without retaining or exposing pre-hook arguments.
 */
final class ToolCallHistoryJson {

    private ToolCallHistoryJson() {
    }

    static @NotNull JsonObject summary(@NotNull LiveToolCallEntry entry) {
        JsonObject json = legacyFields(entry);
        addPayloadMetadata(json, "arguments", entry.inputPayload());
        addPayloadMetadata(json, "result", entry.outputPayload());
        json.addProperty("fullPayloadAvailable", entry.fullPayloadAvailable());
        if (entry.fullPayloadAvailable()) {
            json.addProperty("detailUrl", "/tool-calls/" + entry.callId());
        } else {
            json.addProperty("fullPayloadUnavailableReason", entry.fullPayloadUnavailableReason());
        }
        return json;
    }

    static @NotNull JsonObject detail(@NotNull LiveToolCallEntry entry) {
        String completeInput = entry.completeInputOrNull();
        String completeOutput = entry.completeOutputOrNull();
        if (completeInput == null || completeOutput == null) {
            throw new IllegalStateException("Complete tool-call payload is unavailable");
        }

        JsonObject json = legacyFields(entry);
        json.addProperty("arguments", completeInput);
        json.addProperty("result", completeOutput);
        addPayloadMetadata(json, "arguments", entry.inputPayload());
        addPayloadMetadata(json, "result", entry.outputPayload());
        return json;
    }

    private static void addPayloadMetadata(@NotNull JsonObject json,
                                           @NotNull String prefix,
                                           @NotNull ToolCallPayload payload) {
        json.addProperty(prefix + "Truncated", payload.truncated());
        json.addProperty(prefix + "Bytes", payload.byteLength());
        json.addProperty(prefix + "Sha256", payload.sha256());
    }

    private static @NotNull JsonObject legacyFields(@NotNull LiveToolCallEntry entry) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", entry.callId());
        obj.addProperty("title", entry.displayName());
        obj.addProperty("toolName", entry.toolName());
        if (entry.category() != null) {
            obj.addProperty("kind", entry.category());
        }
        String status;
        if (entry.isRunning()) {
            status = "running";
        } else if (Boolean.TRUE.equals(entry.success())) {
            status = "success";
        } else {
            status = "error";
        }
        obj.addProperty("status", status);
        obj.addProperty("timestamp", entry.timestamp().toString());
        obj.addProperty("arguments", entry.input());
        obj.addProperty("result", entry.output());
        obj.addProperty("durationMs", entry.durationMs());
        obj.addProperty("hasHooks", entry.hasHooks());

        if (!entry.hookStages().isEmpty()) {
            JsonArray stages = new JsonArray();
            for (var stage : entry.hookStages()) {
                JsonObject serializedStage = new JsonObject();
                serializedStage.addProperty("trigger", stage.trigger());
                serializedStage.addProperty("scriptName", stage.scriptName());
                serializedStage.addProperty("outcome", stage.outcome());
                serializedStage.addProperty("durationMs", stage.durationMs());
                if (stage.detail() != null) {
                    serializedStage.addProperty("detail", stage.detail());
                }
                stages.add(serializedStage);
            }
            obj.add("hookStages", stages);
        }
        return obj;
    }
}
