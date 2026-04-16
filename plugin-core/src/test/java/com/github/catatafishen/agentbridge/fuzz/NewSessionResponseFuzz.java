package com.github.catatafishen.agentbridge.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.github.catatafishen.agentbridge.acp.model.NewSessionResponse;
import com.github.catatafishen.agentbridge.acp.model.NewSessionResponseDeserializer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/**
 * Jazzer fuzz target for {@link NewSessionResponseDeserializer}.
 *
 * <p>This deserializer normalises 3+ wire formats for models, modes, and configOptions
 * from different ACP agents. Type confusion between arrays, objects, primitives, and null
 * values at every level could cause ClassCastException or NullPointerException.
 *
 * <p>To run: {@code java -jar jazzer.jar --cp=<test-classpath>
 * --target_class=com.github.catatafishen.agentbridge.fuzz.NewSessionResponseFuzz}
 */
public class NewSessionResponseFuzz {

    private static final Gson GSON = new GsonBuilder()
        .registerTypeAdapter(NewSessionResponse.class, new NewSessionResponseDeserializer())
        .create();

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        String json = data.consumeRemainingAsString();
        try {
            GSON.fromJson(json, NewSessionResponse.class);
        } catch (JsonParseException ignored) {
            // Expected for malformed JSON — Gson's contract
        }
        // Any other exception type (NPE, ClassCastException, etc.) is a bug
    }
}
