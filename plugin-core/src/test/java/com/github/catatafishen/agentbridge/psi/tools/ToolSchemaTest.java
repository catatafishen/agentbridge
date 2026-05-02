package com.github.catatafishen.agentbridge.psi.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the protected static schema builder methods in {@link Tool}.
 * These build MCP JSON Schema objects from Param definitions.
 */
class ToolSchemaTest {

    private static Method schemaMethod;
    private static Method addArrayItemsMethod;
    private static Method addDictPropertyMethod;

    @BeforeAll
    static void setup() throws Exception {
        schemaMethod = Tool.class.getDeclaredMethod("schema", Tool.Param[].class);
        schemaMethod.setAccessible(true);

        addArrayItemsMethod = Tool.class.getDeclaredMethod("addArrayItems", JsonObject.class, String.class);
        addArrayItemsMethod.setAccessible(true);

        addDictPropertyMethod = Tool.class.getDeclaredMethod(
            "addDictProperty", JsonObject.class, String.class, String.class);
        addDictPropertyMethod.setAccessible(true);
    }

    private JsonObject callSchema(Tool.Param... params) throws Exception {
        return (JsonObject) schemaMethod.invoke(null, (Object) params);
    }

    private void callAddArrayItems(JsonObject schema, String propName) throws Exception {
        addArrayItemsMethod.invoke(null, schema, propName);
    }

    private void callAddDictProperty(JsonObject schema, String name, String desc) throws Exception {
        addDictPropertyMethod.invoke(null, schema, name, desc);
    }

    @Nested
    class Schema {

        @Test
        void emptyParamsCreatesEmptySchema() throws Exception {
            JsonObject result = callSchema();
            assertEquals("object", result.get("type").getAsString());
            assertTrue(result.getAsJsonObject("properties").isEmpty());
            assertEquals(0, result.getAsJsonArray("required").size());
        }

        @Test
        void requiredParamAddsToRequiredArray() throws Exception {
            JsonObject result = callSchema(
                Tool.Param.required("name", "string", "The name")
            );
            JsonArray required = result.getAsJsonArray("required");
            assertEquals(1, required.size());
            assertEquals("name", required.get(0).getAsString());
        }

        @Test
        void optionalParamNotInRequired() throws Exception {
            JsonObject result = callSchema(
                Tool.Param.optional("limit", "integer", "Max results")
            );
            assertEquals(0, result.getAsJsonArray("required").size());
            assertTrue(result.getAsJsonObject("properties").has("limit"));
        }

        @Test
        void paramTypeAndDescriptionSet() throws Exception {
            JsonObject result = callSchema(
                Tool.Param.required("path", "string", "File path")
            );
            JsonObject prop = result.getAsJsonObject("properties").getAsJsonObject("path");
            assertEquals("string", prop.get("type").getAsString());
            assertEquals("File path", prop.get("description").getAsString());
        }

        @Test
        void stringDefaultValue() throws Exception {
            JsonObject result = callSchema(
                Tool.Param.optional("mode", "string", "The mode", "auto")
            );
            JsonObject prop = result.getAsJsonObject("properties").getAsJsonObject("mode");
            assertEquals("auto", prop.get("default").getAsString());
        }

        @Test
        void numericDefaultValue() throws Exception {
            JsonObject result = callSchema(
                Tool.Param.optional("limit", "integer", "Max count", 100)
            );
            JsonObject prop = result.getAsJsonObject("properties").getAsJsonObject("limit");
            assertEquals(100, prop.get("default").getAsInt());
        }

        @Test
        void booleanDefaultValue() throws Exception {
            JsonObject result = callSchema(
                Tool.Param.optional("verbose", "boolean", "Verbose mode", true)
            );
            JsonObject prop = result.getAsJsonObject("properties").getAsJsonObject("verbose");
            assertTrue(prop.get("default").getAsBoolean());
        }

        @Test
        void noDefaultValueOmitsDefaultKey() throws Exception {
            JsonObject result = callSchema(
                Tool.Param.optional("path", "string", "File path")
            );
            JsonObject prop = result.getAsJsonObject("properties").getAsJsonObject("path");
            assertFalse(prop.has("default"));
        }

        @Test
        void multipleParams() throws Exception {
            JsonObject result = callSchema(
                Tool.Param.required("file", "string", "File path"),
                Tool.Param.required("line", "integer", "Line number"),
                Tool.Param.optional("column", "integer", "Column number")
            );
            assertEquals(3, result.getAsJsonObject("properties").size());
            JsonArray required = result.getAsJsonArray("required");
            assertEquals(2, required.size());
        }

        @Test
        void arrayParamGetsAutoItems() throws Exception {
            JsonObject result = callSchema(
                Tool.Param.optional("paths", "array", "List of paths")
            );
            JsonObject prop = result.getAsJsonObject("properties").getAsJsonObject("paths");
            assertTrue(prop.has("items"), "Array params must auto-include 'items'");
            assertEquals("string", prop.getAsJsonObject("items").get("type").getAsString());
        }

        @Test
        void nonArrayParamDoesNotGetItems() throws Exception {
            JsonObject result = callSchema(
                Tool.Param.required("name", "string", "The name")
            );
            JsonObject prop = result.getAsJsonObject("properties").getAsJsonObject("name");
            assertFalse(prop.has("items"), "Non-array params must not have 'items'");
        }
    }

    @Nested
    class AddArrayItems {

        @Test
        void addsStringItemsToArrayProperty() throws Exception {
            JsonObject schema = callSchema(
                Tool.Param.optional("paths", "array", "List of paths")
            );
            callAddArrayItems(schema, "paths");

            JsonObject prop = schema.getAsJsonObject("properties").getAsJsonObject("paths");
            assertTrue(prop.has("items"));
            assertEquals("string", prop.getAsJsonObject("items").get("type").getAsString());
        }
    }

    @Nested
    class AddDictProperty {

        @Test
        void addsDictWithAdditionalProperties() throws Exception {
            JsonObject schema = callSchema();
            callAddDictProperty(schema, "env", "Environment variables");

            JsonObject prop = schema.getAsJsonObject("properties").getAsJsonObject("env");
            assertEquals("object", prop.get("type").getAsString());
            assertEquals("Environment variables", prop.get("description").getAsString());
            assertTrue(prop.has("additionalProperties"));
            assertEquals("string",
                prop.getAsJsonObject("additionalProperties").get("type").getAsString());
        }

        @Test
        void dictPropertyHasEmptyProperties() throws Exception {
            JsonObject schema = callSchema();
            callAddDictProperty(schema, "headers", "HTTP headers");

            JsonObject prop = schema.getAsJsonObject("properties").getAsJsonObject("headers");
            assertTrue(prop.getAsJsonObject("properties").isEmpty());
        }
    }
}
