package com.github.catatafishen.agentbridge.acp.model;

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

    private static final String META_KEY = "_meta";
    private static final String AVAILABLE_MODES_KEY = "availableModes";
    private static final String COMMANDS_KEY = "commands";
    private static final String CONFIG_OPTIONS_KEY = "configOptions";
    private static final String AVAILABLE_MODELS_KEY = "availableModels";
    private static final String DESCRIPTION_KEY = "description";

    @Override
    public NewSessionResponse deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext ctx)
        throws JsonParseException {
        JsonObject obj = json.getAsJsonObject();

        String sessionId = getString(obj, "sessionId");

        ModelsResult modelsResult = parseModelsContainer(obj.get("models"));
        ModesResult modesResult = parseModesContainer(obj.get("modes"));

        List<NewSessionResponse.AvailableCommand> commands = null;
        if (obj.has(COMMANDS_KEY) && obj.get(COMMANDS_KEY).isJsonArray()) {
            commands = new ArrayList<>();
            for (JsonElement e : obj.getAsJsonArray(COMMANDS_KEY)) {
                commands.add(ctx.deserialize(e, NewSessionResponse.AvailableCommand.class));
            }
        }

        List<NewSessionResponse.SessionConfigOption> configOptions = null;
        if (obj.has(CONFIG_OPTIONS_KEY) && obj.get(CONFIG_OPTIONS_KEY).isJsonArray()) {
            configOptions = new ArrayList<>();
            for (JsonElement e : obj.getAsJsonArray(CONFIG_OPTIONS_KEY)) {
                if (e.isJsonObject()) {
                    configOptions.add(parseConfigOption(e.getAsJsonObject()));
                }
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

    private record ModelsResult(@Nullable String currentId, @Nullable List<Model> models) {
    }

    private record ModesResult(@Nullable String currentId, @Nullable List<NewSessionResponse.AvailableMode> modes) {
    }

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

        // Standard ACP format: object with availableModels array and optional currentModelId string
        if (container.has(AVAILABLE_MODELS_KEY) && container.get(AVAILABLE_MODELS_KEY).isJsonArray()) {
            List<Model> models = parseModelArray(container.getAsJsonArray(AVAILABLE_MODELS_KEY));
            String currentId = getString(container, "currentModelId");
            return new ModelsResult(currentId, models.isEmpty() ? null : models);
        }

        // Legacy map format: object mapping model-id keys to model descriptor objects
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
        String description = getString(obj, DESCRIPTION_KEY);
        JsonObject meta = obj.has(META_KEY) && obj.get(META_KEY).isJsonObject()
            ? obj.getAsJsonObject(META_KEY) : null;
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

        // Standard ACP format: object with availableModes array and optional currentModeId string
        if (container.has(AVAILABLE_MODES_KEY) && container.get(AVAILABLE_MODES_KEY).isJsonArray()) {
            List<NewSessionResponse.AvailableMode> modes = parseModeArray(container.getAsJsonArray(AVAILABLE_MODES_KEY));
            String currentId = getString(container, "currentModeId");
            return new ModesResult(currentId, modes.isEmpty() ? null : modes);
        }

        // Legacy map format: object mapping slug keys to display name strings or mode descriptor objects
        List<NewSessionResponse.AvailableMode> modes = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : container.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                modes.add(new NewSessionResponse.AvailableMode(key, value.getAsString(), null));
            } else if (value.isJsonObject()) {
                JsonObject modeObj = value.getAsJsonObject();
                String name = getString(modeObj, "name");
                String desc = getString(modeObj, DESCRIPTION_KEY);
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
                String desc = getString(obj, DESCRIPTION_KEY);
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

    /**
     * Parses a configOption entry, handling Copilot's wire format which differs from the ACP spec:
     * <ul>
     *   <li>{@code name} → label (Copilot uses "name", spec says "label")</li>
     *   <li>{@code options} → values (Copilot uses "options", spec says "values")</li>
     *   <li>Each option: {@code value} → id, {@code name} → label</li>
     *   <li>{@code currentValue} → selectedValueId</li>
     * </ul>
     */
    private static NewSessionResponse.SessionConfigOption parseConfigOption(JsonObject obj) {
        String id = getString(obj, "id");
        String label = getString(obj, "label");
        if (label == null) label = getString(obj, "name");
        if (label == null) label = id != null ? id : "";
        String description = getString(obj, DESCRIPTION_KEY);
        String selectedValueId = getString(obj, "selectedValueId");
        if (selectedValueId == null) selectedValueId = getString(obj, "currentValue");

        List<NewSessionResponse.SessionConfigOptionValue> values = new ArrayList<>();
        JsonElement valuesEl = null;
        if (obj.has("values")) {
            valuesEl = obj.get("values");
        } else if (obj.has("options")) {
            valuesEl = obj.get("options");
        }
        if (valuesEl != null && valuesEl.isJsonArray()) {
            for (JsonElement e : valuesEl.getAsJsonArray()) {
                if (e.isJsonObject()) {
                    JsonObject vo = e.getAsJsonObject();
                    String valueId = getString(vo, "id");
                    if (valueId == null) valueId = getString(vo, "value");
                    if (valueId == null) continue;
                    String valueLabel = getString(vo, "label");
                    if (valueLabel == null) valueLabel = getString(vo, "name");
                    if (valueLabel == null) valueLabel = valueId;
                    values.add(new NewSessionResponse.SessionConfigOptionValue(valueId, valueLabel));
                }
            }
        }
        return new NewSessionResponse.SessionConfigOption(id, label, description, values, selectedValueId);
    }
}
