package com.github.catatafishen.agentbridge.psi.tools;

import com.github.catatafishen.agentbridge.psi.tools.git.GitStatusTool;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Tool}'s static schema builder ({@code schema()},
 * {@code addArrayItems()}, and {@code addDictProperty()}).
 *
 * <p>Uses {@link GitStatusTool} as a concrete driver because its {@code inputSchema()} exercises
 * the full builder surface. Direct static calls are not possible (package-private), so we drive
 * through real tool instances.
 */
@DisplayName("Tool schema builder")
class ToolSchemaBuilderTest {

    /**
     * Helper: build a concrete tool schema to introspect. We use concrete tools
     * that exercise the feature we want to test.
     */
    private static JsonObject schemaFor(Tool tool) {
        return tool.inputSchema();
    }

    // ── Root structure ────────────────────────────────────────────────────────

    @Test
    @DisplayName("schema root always has type=object, properties, and required")
    void rootStructure() {
        JsonObject schema = schemaFor(new GitStatusTool(null));
        assertEquals("object", schema.get("type").getAsString());
        assertTrue(schema.has("properties"));
        assertTrue(schema.get("properties").isJsonObject());
        assertTrue(schema.has("required"));
        assertTrue(schema.get("required").isJsonArray());
    }

    @Test
    @DisplayName("schema with zero params has empty properties and required")
    void noParamsIsEmptySchema() {
        // GetActiveFileTool has no parameters
        var tool = new com.github.catatafishen.agentbridge.psi.tools.editor.GetActiveFileTool(null);
        JsonObject schema = schemaFor(tool);
        assertEquals("object", schema.get("type").getAsString());
        assertEquals(0, schema.getAsJsonObject("properties").size());
        assertEquals(0, schema.getAsJsonArray("required").size());
    }

    // ── Required params ──────────────────────────────────────────────────────

    @Test
    @DisplayName("required param appears in 'required' array and has type + description")
    void requiredParamInRequiredArray() {
        // GitCommitTool has 'message' as a required param
        var tool = new com.github.catatafishen.agentbridge.psi.tools.git.GitCommitTool(null);
        JsonObject schema = schemaFor(tool);
        JsonArray required = schema.getAsJsonArray("required");

        boolean foundMessage = false;
        for (var el : required) {
            if ("message".equals(el.getAsString())) {
                foundMessage = true;
                break;
            }
        }
        assertTrue(foundMessage, "'message' must be in the required array");

        JsonObject prop = schema.getAsJsonObject("properties").getAsJsonObject("message");
        assertNotNull(prop, "'message' must appear in properties");
        assertTrue(prop.has("type"), "required property must have 'type'");
        assertTrue(prop.has("description"), "required property must have 'description'");
    }

    // ── Optional params ──────────────────────────────────────────────────────

    @Test
    @DisplayName("optional param appears in properties but not in required array")
    void optionalParamNotInRequired() {
        // GitStatusTool has 'verbose' as an optional boolean param
        JsonObject schema = schemaFor(new GitStatusTool(null));
        JsonObject props = schema.getAsJsonObject("properties");
        assertTrue(props.has("verbose"), "'verbose' must appear in properties");

        JsonArray required = schema.getAsJsonArray("required");
        for (var el : required) {
            assertNotEquals("verbose", el.getAsString(),
                "'verbose' must NOT be in the required array");
        }
    }

    // ── Default value ────────────────────────────────────────────────────────

    @Test
    @DisplayName("optional param with default value includes 'default' in its property")
    void optionalParamWithDefault() {
        // GetCoverageTool has 'path' as Param.optional(..., "") — a concrete default value
        var tool = new com.github.catatafishen.agentbridge.psi.tools.testing.GetCoverageTool(null);
        JsonObject schema = schemaFor(tool);
        JsonObject props = schema.getAsJsonObject("properties");
        assertTrue(props.has("path"), "'path' must be in properties");
        JsonObject fileProp = props.getAsJsonObject("path");
        assertTrue(fileProp.has("default"),
            "'path' optional param with default must include 'default' field");
    }

    // ── addArrayItems ────────────────────────────────────────────────────────

    @Test
    @DisplayName("array param with items has items.type=string")
    void arrayParamHasItems() {
        // GitStageTool has a 'paths' array param with addArrayItems applied
        var tool = new com.github.catatafishen.agentbridge.psi.tools.git.GitStageTool(null);
        JsonObject schema = schemaFor(tool);
        JsonObject props = schema.getAsJsonObject("properties");
        assertTrue(props.has("paths"), "'paths' array param must be in properties");
        JsonObject pathsProp = props.getAsJsonObject("paths");
        assertEquals("array", pathsProp.get("type").getAsString());
        assertTrue(pathsProp.has("items"), "'paths' must have 'items' after addArrayItems");
        assertEquals("string", pathsProp.getAsJsonObject("items").get("type").getAsString());
    }

    // ── addDictProperty ──────────────────────────────────────────────────────

    @Test
    @DisplayName("dict property has type=object with additionalProperties.type=string")
    void dictPropertyStructure() {
        // HttpRequestTool has an 'headers' dict property
        var tool = new com.github.catatafishen.agentbridge.psi.tools.infrastructure.HttpRequestTool(null);
        JsonObject schema = schemaFor(tool);
        JsonObject props = schema.getAsJsonObject("properties");
        assertTrue(props.has("headers"), "'headers' dict param must be in properties");
        JsonObject headersProp = props.getAsJsonObject("headers");
        assertEquals("object", headersProp.get("type").getAsString());
        assertTrue(headersProp.has("additionalProperties"),
            "dict property must have 'additionalProperties'");
        assertEquals("string",
            headersProp.getAsJsonObject("additionalProperties").get("type").getAsString());
    }

    // ── Type integrity across all property types ──────────────────────────────

    @Test
    @DisplayName("all properties have non-empty type and description")
    void allPropertiesHaveTypeAndDescription() {
        // Check a complex tool with many different param types
        var tool = new com.github.catatafishen.agentbridge.psi.tools.git.GitLogTool(null);
        JsonObject schema = schemaFor(tool);
        for (var entry : schema.getAsJsonObject("properties").entrySet()) {
            JsonObject prop = entry.getValue().getAsJsonObject();
            assertTrue(prop.has("type"),
                "Property '" + entry.getKey() + "' must have 'type'");
            assertFalse(prop.get("type").getAsString().isBlank(),
                "Property '" + entry.getKey() + "' type must not be blank");
            assertTrue(prop.has("description"),
                "Property '" + entry.getKey() + "' must have 'description'");
            assertFalse(prop.get("description").getAsString().isBlank(),
                "Property '" + entry.getKey() + "' description must not be blank");
        }
    }
}
