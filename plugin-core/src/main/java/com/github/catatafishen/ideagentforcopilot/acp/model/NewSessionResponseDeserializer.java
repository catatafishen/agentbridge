package com.github.catatafishen.ideagentforcopilot.acp.model;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Normalises the various {@code session/new} response wire formats from different ACP agents.
 * <p>
 * All agents (Copilot, Junie, Kiro, OpenCode) wrap models and modes in a container object:
 * <pre>
 *   "models": { "availableModels": [{modelId, name, description, _meta}, ...], "currentModelId": "..." }
 *   "modes":  { "availableModes": [{id, name, description}, ...], "currentModeId": "..." }
 * </pre>
 * Additionally each model item uses {@code modelId} (not {@code id}) as the identifier field.
 * This deserializer handles all known format variations.
 */
public class NewSessionResponseDeserializer implements JsonDeserializer<NewSessionResponse> {

    @Override
    public NewSessionResponse deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext ctx)
            throws JsonParseException {
        JsonObject obj = json.getAsJsonObject();

        String sessionId = getString(obj, "sessionId");

        ModelsResult modelsResult = parseModelsContainer(obj.get("models"));
        ModesResult modesResult = parseModesContainer(obj.get("modes"));

        List<NewSessionResponse.AvailableCommand> commands = null;
        if (obj.has("commands") && obj.get("commands").isJsonArray()) {
            commands = new ArrayList<>();
            for (JsonElement e : obj.getAsJsonArray("commands")) {
                commands.add(ctx.deserialize(e, NewSessionResponse.AvailableCommand.class));
            }
        }

        List<NewSessionResponse.SessionConfigOption> configOptions = null;
        if (obj.has("configOptions") && obj.get("configOptions").isJsonArray()) {
            configOptions = new ArrayList<>();
            for (JsonElement e : obj.getAsJsonArray("configOptions")) {
                configOptions.add(ctx.deserialize(e, NewSessionResponse.SessionConfigOption.class));
            }
        }

        return new NewSessionResponse(
            sessionId,
            modelsResult != null ? modelsResult.currentId : null,
            modesResult != null ? modesResult.currentId : null,
            modelsResult != null ? modelsResult.models : null,
            modesResult != null ? modesResult.modes : null,
            commands,
            configOptions
        );
    }

    private record ModelsResult(@Nullable String currentId, @Nullable List<Model> models) {}
    private record ModesResult(@Nullable String currentId, @Nullable List<NewSessionResponse.AvailableMode> modes) {}

    @Nullable
    private static ModelsResult parseModelsContainer(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) return null;

        if (element.isJsonArray()) {
            // Direct array — parse each item
            List<Model> models = parseModelArray(element.getAsJsonArray());
            return new ModelsResult(null, models.isEmpty() ? null : models);
        }

        if (!element.isJsonObject()) return null;
        JsonObject container = element.getAsJsonObject();

        // Standard ACP format: { availableModels: [...], currentModelId: "..." }
        if (container.has("availableModels") && container.get("availableModels").isJsonArray()) {
            List<Model> models = parseModelArray(container.getAsJsonArray("availableModels"));
            String currentId = getString(container, "currentModelId");
            return new ModelsResult(currentId, models.isEmpty() ? null : models);
        }

        // Legacy map format: { "model-id": {name, ...}, ... }
        List<Model> models = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : container.entrySet()) {
            if (entry.getValue().isJsonObject()) {
                models.add(parseModelObject(entry.getKey(), entry.getValue().getAsJsonObject()));
            }
        }
        return new ModelsResult(null, models.isEmpty() ? null : models);
    }

    private static List<Model> parseModelArray(com.google.gson.JsonArray array) {
        List<Model> models = new ArrayList<>();
        for (JsonElement e : array) {
            if (e.isJsonObject()) {
                models.add(parseModelObject(null, e.getAsJsonObject()));
            }
        }
        return models;
    }

    private static Model parseModelObject(@Nullable String keyFallbackId, JsonObject obj) {
        // Agents use "modelId" — the ACP spec says "id" but no agent actually sends "id"
        String id = getString(obj, "modelId");
        if (id == null) id = getString(obj, "id");
        if (id == null) id = keyFallbackId;
        String name = getString(obj, "name");
        String description = getString(obj, "description");
        JsonObject meta = obj.has("_meta") && obj.get("_meta").isJsonObject()
            ? obj.getAsJsonObject("_meta") : null;
        return new Model(id, name, description, meta);
    }

    @Nullable
    private static ModesResult parseModesContainer(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) return null;

        if (element.isJsonArray()) {
            List<NewSessionResponse.AvailableMode> modes = parseModeArray(element.getAsJsonArray());
            return new ModesResult(null, modes.isEmpty() ? null : modes);
        }

        if (!element.isJsonObject()) return null;
        JsonObject container = element.getAsJsonObject();

        // Standard ACP format: { availableModes: [...], currentModeId: "..." }
        if (container.has("availableModes") && container.get("availableModes").isJsonArray()) {
            List<NewSessionResponse.AvailableMode> modes = parseModeArray(container.getAsJsonArray("availableModes"));
            String currentId = getString(container, "currentModeId");
            return new ModesResult(currentId, modes.isEmpty() ? null : modes);
        }

        // Legacy map format: { "slug": "Display Name" } or { "slug": {name, description} }
        List<NewSessionResponse.AvailableMode> modes = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : container.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                modes.add(new NewSessionResponse.AvailableMode(key, value.getAsString(), null));
            } else if (value.isJsonObject()) {
                JsonObject modeObj = value.getAsJsonObject();
                String name = getString(modeObj, "name");
                String desc = getString(modeObj, "description");
                String slug = getString(modeObj, "id");
                if (slug == null) slug = key;
                modes.add(new NewSessionResponse.AvailableMode(slug, name != null ? name : key, desc));
            }
        }
        return new ModesResult(null, modes.isEmpty() ? null : modes);
    }

    private static List<NewSessionResponse.AvailableMode> parseModeArray(com.google.gson.JsonArray array) {
        List<NewSessionResponse.AvailableMode> modes = new ArrayList<>();
        for (JsonElement e : array) {
            if (e.isJsonObject()) {
                JsonObject obj = e.getAsJsonObject();
                String slug = getString(obj, "id");
                String name = getString(obj, "name");
                String desc = getString(obj, "description");
                modes.add(new NewSessionResponse.AvailableMode(slug, name != null ? name : slug, desc));
            }
        }
        return modes;
    }

    @Nullable
    private static String getString(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : null;
    }
}
