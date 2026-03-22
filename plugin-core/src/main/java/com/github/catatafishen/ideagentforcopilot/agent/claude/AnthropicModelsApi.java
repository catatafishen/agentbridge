package com.github.catatafishen.ideagentforcopilot.agent.claude;

import com.github.catatafishen.ideagentforcopilot.acp.model.Model;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Utility for fetching Claude model list from Anthropic API.
 *
 * <p>Shared by both {@link ClaudeCliClient} and {@link AnthropicDirectClient}
 * to provide dynamic model discovery when an API key is available.</p>
 */
public final class AnthropicModelsApi {

    private static final Logger LOG = Logger.getInstance(AnthropicModelsApi.class);

    private static final String API_BASE = "https://api.anthropic.com";
    private static final String MODELS_PATH = "/v1/models";
    private static final String API_VERSION_HEADER = "anthropic-version";
    private static final String API_VERSION = "2023-06-01";
    private static final String API_KEY_HEADER = "x-api-key";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private AnthropicModelsApi() {
        // Utility class
    }

    /**
     * Fetches the list of available Claude models from Anthropic API.
     *
     * @param apiKey Anthropic API key (required for authentication)
     * @return List of models, or empty list if the request fails
     */
    @NotNull
    public static List<Model> fetchModels(@Nullable String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            LOG.debug("No API key provided, cannot fetch models");
            return List.of();
        }

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + MODELS_PATH))
                    .header(API_KEY_HEADER, apiKey)
                    .header(API_VERSION_HEADER, API_VERSION)
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOG.warn("Failed to list models from Anthropic API: HTTP " + resp.statusCode() + 
                        " — " + resp.body());
                return List.of();
            }
            return parseModelsFromResponse(resp.body());
        } catch (IOException e) {
            LOG.warn("Failed to contact Anthropic API: " + e.getMessage());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    @NotNull
    private static List<Model> parseModelsFromResponse(@NotNull String responseBody) {
        try {
            JsonObject body = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray data = body.getAsJsonArray("data");
            if (data == null) return List.of();

            // Use a LinkedHashMap keyed by display_name to deduplicate: the Anthropic API returns
            // both undated aliases (e.g. "claude-opus-4-6") and dated snapshots
            // (e.g. "claude-opus-4-6-20251101") with identical display names. Keep the first
            // occurrence, which is the alias and is the more stable identifier to send.
            LinkedHashMap<String, Model> seen = new LinkedHashMap<>();
            for (var elem : data) {
                JsonObject m = elem.getAsJsonObject();
                String id = m.has("id") ? m.get("id").getAsString() : "";
                if (id.isEmpty()) continue;
                String displayName = m.has("display_name") ? m.get("display_name").getAsString() : id;
                seen.computeIfAbsent(displayName, k -> new Model(id, k, null, null));
            }
            return new ArrayList<>(seen.values());
        } catch (Exception e) {
            LOG.warn("Failed to parse models response: " + e.getMessage());
            return List.of();
        }
    }
}
