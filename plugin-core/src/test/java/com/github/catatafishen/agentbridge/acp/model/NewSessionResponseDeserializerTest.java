package com.github.catatafishen.agentbridge.acp.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NewSessionResponseDeserializer} — verifies that every known
 * wire format variation for models, modes, commands, and configOptions is
 * normalised into the canonical {@link NewSessionResponse} structure.
 */
class NewSessionResponseDeserializerTest {

    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(NewSessionResponse.class, new NewSessionResponseDeserializer())
            .create();

    private NewSessionResponse deserialize(String json) {
        return gson.fromJson(json, NewSessionResponse.class);
    }

    // ── Minimal / Edge cases ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Minimal and edge cases")
    class MinimalTests {

        @Test
        @DisplayName("minimal response — only sessionId")
        void minimalSessionIdOnly() {
            NewSessionResponse r = deserialize("""
                    {"sessionId":"test-123"}""");

            assertEquals("test-123", r.sessionId());
            assertNull(r.currentModelId());
            assertNull(r.currentModeId());
            assertNull(r.models());
            assertNull(r.modes());
            assertNull(r.commands());
            assertNull(r.configOptions());
        }

        @Test
        @DisplayName("empty object — sessionId is null, all nullable fields null")
        void emptyObject() {
            NewSessionResponse r = deserialize("{}");

            assertNull(r.sessionId());
            assertNull(r.currentModelId());
            assertNull(r.currentModeId());
            assertNull(r.models());
            assertNull(r.modes());
            assertNull(r.commands());
            assertNull(r.configOptions());
        }

        @Test
        @DisplayName("null models field → null models in response")
        void nullModels() {
            NewSessionResponse r = deserialize("""
                    {"sessionId":"s1","models":null}""");

            assertEquals("s1", r.sessionId());
            assertNull(r.models());
            assertNull(r.currentModelId());
        }

        @Test
        @DisplayName("null modes field → null modes in response")
        void nullModes() {
            NewSessionResponse r = deserialize("""
                    {"sessionId":"s1","modes":null}""");

            assertEquals("s1", r.sessionId());
            assertNull(r.modes());
            assertNull(r.currentModeId());
        }

        @Test
        @DisplayName("empty models array → null (empty list normalised to null)")
        void emptyModelsArray() {
            NewSessionResponse r = deserialize("""
                    {"sessionId":"s1","models":[]}""");

            assertNull(r.models());
        }

        @Test
        @DisplayName("empty modes array → null (empty list normalised to null)")
        void emptyModesArray() {
            NewSessionResponse r = deserialize("""
                    {"sessionId":"s1","modes":[]}""");

            assertNull(r.modes());
        }

        @Test
        @DisplayName("non-object, non-array models element → null")
        void modelsAsPrimitive() {
            NewSessionResponse r = deserialize("""
                    {"sessionId":"s1","models":"unexpected-string"}""");

            assertNull(r.models());
            assertNull(r.currentModelId());
        }

        @Test
        @DisplayName("non-object, non-array modes element → null")
        void modesAsPrimitive() {
            NewSessionResponse r = deserialize("""
                    {"sessionId":"s1","modes":42}""");

            assertNull(r.modes());
            assertNull(r.currentModeId());
        }

        @Test
        @DisplayName("empty availableModels container → null models")
        void emptyAvailableModelsContainer() {
            NewSessionResponse r = deserialize("""
                    {"sessionId":"s1","models":{"availableModels":[],"currentModelId":"gpt-4"}}""");

            assertNull(r.models());
            assertEquals("gpt-4", r.currentModelId());
        }

        @Test
        @DisplayName("empty availableModes container → null modes")
        void emptyAvailableModesContainer() {
            NewSessionResponse r = deserialize("""
                    {"sessionId":"s1","modes":{"availableModes":[],"currentModeId":"agent"}}""");

            assertNull(r.modes());
            assertEquals("agent", r.currentModeId());
        }
    }

    // ── Models ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Models container formats")
    class ModelsTests {

        @Test
        @DisplayName("standard ACP format with availableModels and currentModelId")
        void standardAcpFormat() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "s1",
                      "models": {
                        "availableModels": [
                          {"modelId": "gpt-4", "name": "GPT-4"},
                          {"modelId": "gpt-3.5", "name": "GPT-3.5", "description": "Fast"}
                        ],
                        "currentModelId": "gpt-4"
                      }
                    }""");

            assertEquals("gpt-4", r.currentModelId());
            assertNotNull(r.models());
            assertEquals(2, r.models().size());

            Model first = r.models().get(0);
            assertEquals("gpt-4", first.id());
            assertEquals("GPT-4", first.name());
            assertNull(first.description());

            Model second = r.models().get(1);
            assertEquals("gpt-3.5", second.id());
            assertEquals("GPT-3.5", second.name());
            assertEquals("Fast", second.description());
        }

        @Test
        @DisplayName("direct array of models — no container")
        void directArray() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "s1",
                      "models": [
                        {"modelId": "gpt-4", "name": "GPT-4"}
                      ]
                    }""");

            assertNull(r.currentModelId(), "no container → no currentModelId");
            assertNotNull(r.models());
            assertEquals(1, r.models().size());
            assertEquals("gpt-4", r.models().get(0).id());
            assertEquals("GPT-4", r.models().get(0).name());
        }

        @Test
        @DisplayName("legacy map format — model id from map key")
        void legacyMapFormat() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "s1",
                      "models": {
                        "gpt-4": {"name": "GPT-4", "description": "Fast"},
                        "claude-3": {"name": "Claude 3", "description": "Smart"}
                      }
                    }""");

            assertNull(r.currentModelId());
            assertNotNull(r.models());
            assertEquals(2, r.models().size());

            // Map iteration order in Gson's JsonObject is insertion order
            Model first = r.models().get(0);
            assertEquals("gpt-4", first.id(), "id should come from map key");
            assertEquals("GPT-4", first.name());
            assertEquals("Fast", first.description());

            Model second = r.models().get(1);
            assertEquals("claude-3", second.id());
            assertEquals("Claude 3", second.name());
        }

        @Test
        @DisplayName("model with 'id' field instead of 'modelId'")
        void idFieldFallback() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "s1",
                      "models": {
                        "availableModels": [
                          {"id": "gpt-4", "name": "GPT-4"}
                        ]
                      }
                    }""");

            assertNotNull(r.models());
            assertEquals(1, r.models().size());
            assertEquals("gpt-4", r.models().get(0).id(), "should fall back to 'id' when 'modelId' is absent");
        }

        @Test
        @DisplayName("modelId takes priority over id when both present")
        void modelIdPriority() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "s1",
                      "models": {
                        "availableModels": [
                          {"modelId": "model-id", "id": "other-id", "name": "M"}
                        ]
                      }
                    }""");

            assertNotNull(r.models());
            assertEquals("model-id", r.models().get(0).id(), "modelId should take priority over id");
        }

        @Test
        @DisplayName("model with _meta field")
        void modelWithMeta() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "s1",
                      "models": {
                        "availableModels": [
                          {"modelId": "m1", "name": "M1", "_meta": {"premium": true, "tier": "enterprise"}}
                        ]
                      }
                    }""");

            assertNotNull(r.models());
            assertEquals(1, r.models().size());

            Model model = r.models().get(0);
            assertEquals("m1", model.id());
            assertNotNull(model._meta(), "_meta should be preserved");
            assertTrue(model._meta().get("premium").getAsBoolean());
            assertEquals("enterprise", model._meta().get("tier").getAsString());
        }

        @Test
        @DisplayName("model without _meta → null _meta")
        void modelWithoutMeta() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "s1",
                      "models": {
                        "availableModels": [
                          {"modelId": "m1", "name": "M1"}
                        ]
                      }
                    }""");

            assertNotNull(r.models());
            assertNull(r.models().get(0)._meta());
        }

        @Test
        @DisplayName("legacy map — modelId in value object overrides map key")
        void legacyMapWithModelId() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "s1",
                      "models": {
                        "key-id": {"modelId": "real-id", "name": "M"}
                      }
                    }""");

            assertNotNull(r.models());
            assertEquals("real-id", r.models().get(0).id(), "modelId in value should override the map key");
        }

        @Test
        @DisplayName("legacy map — no modelId/id in value, falls back to map key")
        void legacyMapKeyFallback() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "s1",
                      "models": {
                        "fallback-key": {"name": "Some Model"}
                      }
                    }""");

            assertNotNull(r.models());
            assertEquals("fallback-key", r.models().get(0).id());
        }
    }

    // ── Modes ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Modes container formats")
    class ModesTests {

        @Test
        @DisplayName("standard ACP format with availableModes and currentModeId")
        void standardAcpFormat() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "s1",
                      "modes": {
                        "availableModes": [
                          {"id": "agent", "name": "Agent", "description": "Full agent"},
                          {"id": "ask", "name": "Ask", "description": "Read-only"}
                        ],
                        "currentModeId": "agent"
                      }
                    }""");

            assertEquals("agent", r.currentModeId());
            assertNotNull(r.modes());
            assertEquals(2, r.modes().size());

            NewSessionResponse.AvailableMode first = r.modes().get(0);
            assertEquals("agent", first.slug());
            assertEquals("Agent", first.name());
            assertEquals("Full agent", first.description());

            NewSessionResponse.AvailableMode second = r.modes().get(1);
            assertEquals("ask", second.slug());
            assertEquals("Ask", second.name());
            assertEquals("Read-only", second.description());
        }

        @Test
        @DisplayName("direct array of modes")
        void directArray() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "s1",
                      "modes": [
                        {"id": "agent", "name": "Agent"}
                      ]
                    }""");

            assertNull(r.currentModeId());
            assertNotNull(r.modes());
            assertEquals(1, r.modes().size());
            assertEquals("agent", r.modes().get(0).slug());
            assertEquals("Agent", r.modes().get(0).name());
            assertNull(r.modes().get(0).description());
        }

        @Test
        @DisplayName("legacy string map — key is slug, value is display name")
        void legacyStringMap() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "s1",
                      "modes": {
                        "agent": "Agent Mode",
                        "ask": "Ask Mode"
                      }
                    }""");

            assertNull(r.currentModeId());
            assertNotNull(r.modes());
            assertEquals(2, r.modes().size());

            NewSessionResponse.AvailableMode first = r.modes().get(0);
            assertEquals("agent", first.slug());
            assertEquals("Agent Mode", first.name());
            assertNull(first.description());

            NewSessionResponse.AvailableMode second = r.modes().get(1);
            assertEquals("ask", second.slug());
            assertEquals("Ask Mode", second.name());
        }

        @Test
        @DisplayName("legacy object map — key is slug, value has name and description")
        void legacyObjectMap() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "s1",
                      "modes": {
                        "agent": {"name": "Agent", "description": "Full agent"}
                      }
                    }""");

            assertNotNull(r.modes());
            assertEquals(1, r.modes().size());

            NewSessionResponse.AvailableMode mode = r.modes().get(0);
            assertEquals("agent", mode.slug(), "slug should come from map key when no id field");
            assertEquals("Agent", mode.name());
            assertEquals("Full agent", mode.description());
        }

        @Test
        @DisplayName("legacy object map with id field — id overrides map key as slug")
        void legacyObjectMapWithIdField() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "s1",
                      "modes": {
                        "agent": {"id": "custom-slug", "name": "Agent"}
                      }
                    }""");

            assertNotNull(r.modes());
            assertEquals(1, r.modes().size());
            assertEquals("custom-slug", r.modes().get(0).slug(), "id field should override map key");
            assertEquals("Agent", r.modes().get(0).name());
        }

        @Test
        @DisplayName("legacy object map with no name — falls back to map key")
        void legacyObjectMapNoName() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "s1",
                      "modes": {
                        "agent": {"description": "Full agent"}
                      }
                    }""");

            assertNotNull(r.modes());
            assertEquals(1, r.modes().size());
            assertEquals("agent", r.modes().get(0).slug());
            assertEquals("agent", r.modes().get(0).name(), "name should fall back to key when missing");
        }

        @Test
        @DisplayName("mode array entry with no name — falls back to slug")
        void modeArrayNoName() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "s1",
                      "modes": [
                        {"id": "agent"}
                      ]
                    }""");

            assertNotNull(r.modes());
            assertEquals("agent", r.modes().get(0).slug());
            assertEquals("agent", r.modes().get(0).name(), "name should fall back to slug");
        }
    }

    // ── Commands ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Commands")
    class CommandsTests {

        @Test
        @DisplayName("array of command objects")
        void commandArray() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "s1",
                      "commands": [
                        {"name": "/help", "description": "Show help"},
                        {"name": "/clear", "description": "Clear history"}
                      ]
                    }""");

            assertNotNull(r.commands());
            assertEquals(2, r.commands().size());

            assertEquals("/help", r.commands().get(0).name());
            assertEquals("Show help", r.commands().get(0).description());
            assertNull(r.commands().get(0).input());

            assertEquals("/clear", r.commands().get(1).name());
            assertEquals("Clear history", r.commands().get(1).description());
        }

        @Test
        @DisplayName("command with input")
        void commandWithInput() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "s1",
                      "commands": [
                        {
                          "name": "/file",
                          "description": "Add file",
                          "input": {"type": "file", "placeholder": "Enter path..."}
                        }
                      ]
                    }""");

            assertNotNull(r.commands());
            assertEquals(1, r.commands().size());

            NewSessionResponse.AvailableCommand cmd = r.commands().get(0);
            assertNotNull(cmd.input());
            assertEquals("file", cmd.input().type());
            assertEquals("Enter path...", cmd.input().placeholder());
        }

        @Test
        @DisplayName("null commands → null")
        void nullCommands() {
            NewSessionResponse r = deserialize("""
                    {"sessionId":"s1","commands":null}""");

            assertNull(r.commands());
        }

        @Test
        @DisplayName("missing commands field → null")
        void missingCommands() {
            NewSessionResponse r = deserialize("""
                    {"sessionId":"s1"}""");

            assertNull(r.commands());
        }

        @Test
        @DisplayName("empty commands array → empty list (not null)")
        void emptyCommandsArray() {
            NewSessionResponse r = deserialize("""
                    {"sessionId":"s1","commands":[]}""");

            // Empty array is kept as-is (unlike models/modes which normalise to null)
            assertNotNull(r.commands());
            assertTrue(r.commands().isEmpty());
        }
    }

    // ── ConfigOptions ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ConfigOptions")
    class ConfigOptionsTests {

        @Test
        @DisplayName("ACP standard format — label, values, selectedValueId")
        void acpStandardFormat() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "s1",
                      "configOptions": [
                        {
                          "id": "effort",
                          "label": "Effort",
                          "description": "Thinking effort",
                          "values": [
                            {"id": "low", "label": "Low"},
                            {"id": "high", "label": "High"}
                          ],
                          "selectedValueId": "low"
                        }
                      ]
                    }""");

            assertNotNull(r.configOptions());
            assertEquals(1, r.configOptions().size());

            NewSessionResponse.SessionConfigOption opt = r.configOptions().get(0);
            assertEquals("effort", opt.id());
            assertEquals("Effort", opt.label());
            assertEquals("Thinking effort", opt.description());
            assertEquals("low", opt.selectedValueId());

            assertEquals(2, opt.values().size());
            assertEquals("low", opt.values().get(0).id());
            assertEquals("Low", opt.values().get(0).label());
            assertEquals("high", opt.values().get(1).id());
            assertEquals("High", opt.values().get(1).label());
        }

        @Test
        @DisplayName("Copilot format — name→label, options→values, currentValue→selectedValueId, value→id")
        void copilotFormat() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "s1",
                      "configOptions": [
                        {
                          "id": "effort",
                          "name": "Effort",
                          "options": [
                            {"value": "low", "name": "Low"},
                            {"value": "high", "name": "High"}
                          ],
                          "currentValue": "low"
                        }
                      ]
                    }""");

            assertNotNull(r.configOptions());
            assertEquals(1, r.configOptions().size());

            NewSessionResponse.SessionConfigOption opt = r.configOptions().get(0);
            assertEquals("effort", opt.id());
            assertEquals("Effort", opt.label(), "'name' should be normalised to 'label'");
            assertEquals("low", opt.selectedValueId(), "'currentValue' should be normalised to 'selectedValueId'");

            assertEquals(2, opt.values().size());
            assertEquals("low", opt.values().get(0).id(), "'value' should be normalised to 'id'");
            assertEquals("Low", opt.values().get(0).label(), "'name' should be normalised to 'label'");
            assertEquals("high", opt.values().get(1).id());
            assertEquals("High", opt.values().get(1).label());
        }

        @Test
        @DisplayName("ACP format takes priority over Copilot fallbacks when both present")
        void acpPriorityOverCopilot() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "s1",
                      "configOptions": [
                        {
                          "id": "effort",
                          "label": "ACP Label",
                          "name": "Copilot Name",
                          "values": [
                            {"id": "v1", "label": "V1 Label", "value": "alt-v1", "name": "Alt Name"}
                          ],
                          "selectedValueId": "v1",
                          "currentValue": "alt-v1"
                        }
                      ]
                    }""");

            assertNotNull(r.configOptions());
            NewSessionResponse.SessionConfigOption opt = r.configOptions().get(0);
            assertEquals("ACP Label", opt.label(), "label should take priority over name");
            assertEquals("v1", opt.selectedValueId(), "selectedValueId should take priority over currentValue");
            assertEquals("v1", opt.values().get(0).id(), "id should take priority over value");
            assertEquals("V1 Label", opt.values().get(0).label(), "label should take priority over name");
        }

        @Test
        @DisplayName("null configOptions → null")
        void nullConfigOptions() {
            NewSessionResponse r = deserialize("""
                    {"sessionId":"s1","configOptions":null}""");

            assertNull(r.configOptions());
        }

        @Test
        @DisplayName("missing configOptions field → null")
        void missingConfigOptions() {
            NewSessionResponse r = deserialize("""
                    {"sessionId":"s1"}""");

            assertNull(r.configOptions());
        }

        @Test
        @DisplayName("configOption with no label and no name — falls back to id")
        void labelFallbackToId() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "s1",
                      "configOptions": [
                        {
                          "id": "effort",
                          "values": []
                        }
                      ]
                    }""");

            assertNotNull(r.configOptions());
            assertEquals("effort", r.configOptions().get(0).label(), "label should fall back to id");
        }

        @Test
        @DisplayName("configOption with no label, no name, no id — falls back to empty string")
        void labelFallbackToEmpty() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "s1",
                      "configOptions": [
                        {
                          "values": []
                        }
                      ]
                    }""");

            assertNotNull(r.configOptions());
            assertEquals("", r.configOptions().get(0).label(), "label should fall back to empty string");
        }

        @Test
        @DisplayName("configOption value with no label/name — falls back to id")
        void valueLabelFallback() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "s1",
                      "configOptions": [
                        {
                          "id": "opt",
                          "label": "Opt",
                          "values": [
                            {"id": "v1"}
                          ]
                        }
                      ]
                    }""");

            assertNotNull(r.configOptions());
            assertEquals("v1", r.configOptions().get(0).values().get(0).label(), "value label should fall back to id");
        }

        @Test
        @DisplayName("configOption value with no id and no value — skipped")
        void valueWithNoIdSkipped() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "s1",
                      "configOptions": [
                        {
                          "id": "opt",
                          "label": "Opt",
                          "values": [
                            {"label": "orphan with no id"},
                            {"id": "valid", "label": "Valid"}
                          ]
                        }
                      ]
                    }""");

            assertNotNull(r.configOptions());
            assertEquals(1, r.configOptions().get(0).values().size(), "value with no id should be skipped");
            assertEquals("valid", r.configOptions().get(0).values().get(0).id());
        }

        @Test
        @DisplayName("multiple configOptions")
        void multipleConfigOptions() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "s1",
                      "configOptions": [
                        {"id": "effort", "label": "Effort", "values": [{"id": "low", "label": "Low"}]},
                        {"id": "lang", "label": "Language", "values": [{"id": "en", "label": "English"}], "selectedValueId": "en"}
                      ]
                    }""");

            assertNotNull(r.configOptions());
            assertEquals(2, r.configOptions().size());
            assertEquals("effort", r.configOptions().get(0).id());
            assertNull(r.configOptions().get(0).selectedValueId());
            assertEquals("lang", r.configOptions().get(1).id());
            assertEquals("en", r.configOptions().get(1).selectedValueId());
        }
    }

    // ── Full response ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Full response with all fields")
    class FullResponseTests {

        @Test
        @DisplayName("complete response with models, modes, commands, and configOptions")
        void completeResponse() {
            NewSessionResponse r = deserialize("""
                    {
                      "sessionId": "session-42",
                      "models": {
                        "availableModels": [
                          {"modelId": "gpt-4", "name": "GPT-4", "description": "Flagship"},
                          {"modelId": "gpt-3.5", "name": "GPT-3.5"}
                        ],
                        "currentModelId": "gpt-4"
                      },
                      "modes": {
                        "availableModes": [
                          {"id": "agent", "name": "Agent", "description": "Full agent"},
                          {"id": "ask", "name": "Ask"}
                        ],
                        "currentModeId": "agent"
                      },
                      "commands": [
                        {"name": "/help", "description": "Show help"}
                      ],
                      "configOptions": [
                        {
                          "id": "effort",
                          "label": "Effort",
                          "values": [{"id": "low", "label": "Low"}, {"id": "high", "label": "High"}],
                          "selectedValueId": "high"
                        }
                      ]
                    }""");

            assertEquals("session-42", r.sessionId());

            // Models
            assertEquals("gpt-4", r.currentModelId());
            assertNotNull(r.models());
            assertEquals(2, r.models().size());
            assertEquals("Flagship", r.models().get(0).description());

            // Modes
            assertEquals("agent", r.currentModeId());
            assertNotNull(r.modes());
            assertEquals(2, r.modes().size());
            assertNull(r.modes().get(1).description());

            // Commands
            assertNotNull(r.commands());
            assertEquals(1, r.commands().size());

            // ConfigOptions
            assertNotNull(r.configOptions());
            assertEquals(1, r.configOptions().size());
            assertEquals("high", r.configOptions().get(0).selectedValueId());
        }
    }
}
