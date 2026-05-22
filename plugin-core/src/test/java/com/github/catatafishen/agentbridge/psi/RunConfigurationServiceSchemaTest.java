package com.github.catatafishen.agentbridge.psi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jdom.Element;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the JSON schema generation, validation, and XML merge helpers in
 * {@link RunConfigurationService}. These methods are package-private pure-logic static helpers
 * with no IntelliJ platform dependencies, so they can run without a sandbox.
 */
class RunConfigurationServiceSchemaTest {

    // ── xmlElementToJsonSchema ───────────────────────────────────────────────

    @Nested
    class XmlElementToJsonSchema {

        @Test
        void flatStringOption() {
            var root = new Element("configuration");
            root.addContent(optionElement("SCRIPT_NAME", "app.py"));

            JsonObject schema = RunConfigurationService.xmlElementToJsonSchema(root);

            assertTrue(schema.has("properties"));
            JsonObject props = schema.getAsJsonObject("properties");
            assertTrue(props.has("SCRIPT_NAME"));
            assertEquals("string", props.getAsJsonObject("SCRIPT_NAME").get("type").getAsString());
            assertEquals("app.py", props.getAsJsonObject("SCRIPT_NAME").get("default").getAsString());
        }

        @Test
        void booleanOptionInferredFromTrueDefault() {
            var root = new Element("configuration");
            root.addContent(optionElement("ALLOW_PARALLEL_RUN", "true"));

            JsonObject schema = RunConfigurationService.xmlElementToJsonSchema(root);

            JsonObject prop = schema.getAsJsonObject("properties").getAsJsonObject("ALLOW_PARALLEL_RUN");
            assertEquals("boolean", prop.get("type").getAsString());
            assertTrue(prop.get("default").getAsBoolean());
        }

        @Test
        void booleanOptionInferredFromFalseDefault() {
            var root = new Element("configuration");
            root.addContent(optionElement("ALLOW_PARALLEL_RUN", "false"));

            JsonObject schema = RunConfigurationService.xmlElementToJsonSchema(root);

            JsonObject prop = schema.getAsJsonObject("properties").getAsJsonObject("ALLOW_PARALLEL_RUN");
            assertEquals("boolean", prop.get("type").getAsString());
            assertFalse(prop.get("default").getAsBoolean());
        }

        @Test
        void listOptionMapsToArraySchema() {
            var root = new Element("configuration");
            var optElem = new Element("option");
            optElem.setAttribute("name", "VM_PARAMETERS");
            var list = new Element("list");
            var item = new Element("option");
            item.setAttribute("value", "-Xmx512m");
            list.addContent(item);
            optElem.addContent(list);
            root.addContent(optElem);

            JsonObject schema = RunConfigurationService.xmlElementToJsonSchema(root);

            JsonObject prop = schema.getAsJsonObject("properties").getAsJsonObject("VM_PARAMETERS");
            assertEquals("array", prop.get("type").getAsString());
            assertTrue(prop.has("items"));
        }

        @Test
        void nestedObjectOption() {
            var root = new Element("configuration");
            var nested = new Element("envs");
            root.addContent(nested);

            JsonObject schema = RunConfigurationService.xmlElementToJsonSchema(root);

            JsonObject props = schema.getAsJsonObject("properties");
            assertTrue(props.has("envs"));
            // "envs" element gets dict schema
            JsonObject envSchema = props.getAsJsonObject("envs");
            assertEquals("object", envSchema.get("type").getAsString());
        }

        @Test
        void emptyRootProducesEmptyProperties() {
            var root = new Element("configuration");
            JsonObject schema = RunConfigurationService.xmlElementToJsonSchema(root);
            assertEquals(0, schema.getAsJsonObject("properties").size());
        }
    }

    // ── validateJsonAgainstSchema ────────────────────────────────────────────

    @Nested
    class ValidateJsonAgainstSchema {

        @Test
        void validConfigReturnsNull() {
            JsonObject schema = schemaWith("name", "string", "", "enabled", "boolean", "false");
            JsonObject config = new JsonObject();
            config.addProperty("name", "MyApp");
            config.addProperty("enabled", true);

            assertNull(RunConfigurationService.validateJsonAgainstSchema(config, schema));
        }

        @Test
        void unknownKeyReturnsError() {
            JsonObject schema = schemaWith("name", "string", "");
            JsonObject config = new JsonObject();
            config.addProperty("unknown_key", "value");

            String error = RunConfigurationService.validateJsonAgainstSchema(config, schema);
            assertNotNull(error);
            assertTrue(error.contains("unknown_key"));
        }

        @Test
        void arrayForStringFieldReturnsError() {
            JsonObject schema = schemaWith("name", "string", "");
            JsonObject config = new JsonObject();
            config.add("name", new JsonArray());

            String error = RunConfigurationService.validateJsonAgainstSchema(config, schema);
            assertNotNull(error);
            assertTrue(error.contains("'name'"));
            assertTrue(error.contains("string"));
        }

        @Test
        void objectForStringFieldReturnsError() {
            JsonObject schema = schemaWith("flag", "string", "");
            JsonObject config = new JsonObject();
            config.add("flag", new JsonObject());

            String error = RunConfigurationService.validateJsonAgainstSchema(config, schema);
            assertNotNull(error);
            assertTrue(error.contains("'flag'"));
        }

        @Test
        void arrayForBooleanFieldReturnsError() {
            JsonObject schema = schemaWith("enabled", "boolean", "false");
            JsonObject config = new JsonObject();
            config.add("enabled", new JsonArray());

            String error = RunConfigurationService.validateJsonAgainstSchema(config, schema);
            assertNotNull(error);
            assertTrue(error.contains("'enabled'"));
            assertTrue(error.contains("boolean"));
        }

        @Test
        void stringForArrayFieldReturnsError() {
            JsonObject schema = arraySchema("tasks");
            JsonObject config = new JsonObject();
            config.addProperty("tasks", "not-an-array");

            String error = RunConfigurationService.validateJsonAgainstSchema(config, schema);
            assertNotNull(error);
            assertTrue(error.contains("'tasks'"));
        }

        @Test
        void emptyConfigAlwaysValid() {
            JsonObject schema = schemaWith("name", "string", "");
            assertNull(RunConfigurationService.validateJsonAgainstSchema(new JsonObject(), schema));
        }

        @Test
        void schemaWithNoPropertiesKeyIsAlwaysValid() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            JsonObject config = new JsonObject();
            config.addProperty("anything", "value");
            assertNull(RunConfigurationService.validateJsonAgainstSchema(config, schema));
        }
    }

    // ── mergeJsonConfigIntoXml ───────────────────────────────────────────────

    @Nested
    class MergeJsonConfigIntoXml {

        @Test
        void updatesExistingStringOption() {
            var root = new Element("configuration");
            root.addContent(optionElement("SCRIPT_NAME", "old.py"));

            JsonObject config = new JsonObject();
            config.addProperty("SCRIPT_NAME", "new.py");
            RunConfigurationService.mergeJsonConfigIntoXml(root, config);

            assertEquals("new.py", findOption(root, "SCRIPT_NAME"));
        }

        @Test
        void addsNewStringOption() {
            var root = new Element("configuration");
            JsonObject config = new JsonObject();
            config.addProperty("NEW_OPTION", "hello");

            RunConfigurationService.mergeJsonConfigIntoXml(root, config);

            assertEquals("hello", findOption(root, "NEW_OPTION"));
        }

        @Test
        void setsArrayOption() {
            var root = new Element("configuration");
            var config = new JsonObject();
            var arr = new JsonArray();
            arr.add("task1");
            arr.add("task2");
            config.add("TASKS", arr);

            RunConfigurationService.mergeJsonConfigIntoXml(root, config);

            Element optElem = findOptionElement(root, "TASKS");
            assertNotNull(optElem);
            Element list = optElem.getChild("list");
            assertNotNull(list);
            assertEquals(2, list.getChildren("option").size());
            assertEquals("task1", list.getChildren("option").get(0).getAttributeValue("value"));
        }

        @Test
        void envsObjectCreatesEnvChildElements() {
            var root = new Element("configuration");
            var envsElem = new Element("envs");
            root.addContent(envsElem);

            var config = new JsonObject();
            var envs = new JsonObject();
            envs.addProperty("MY_VAR", "my_val");
            config.add("envs", envs);

            RunConfigurationService.mergeJsonConfigIntoXml(root, config);

            Element envs2 = root.getChild("envs");
            assertNotNull(envs2);
            assertFalse(envs2.getChildren().isEmpty());
            assertEquals("MY_VAR", envs2.getChildren().getFirst().getAttributeValue("name"));
            assertEquals("my_val", envs2.getChildren().getFirst().getAttributeValue("value"));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Element optionElement(String name, String value) {
        var e = new Element("option");
        e.setAttribute("name", name);
        e.setAttribute("value", value);
        return e;
    }

    private static String findOption(Element root, String name) {
        for (var child : root.getChildren("option")) {
            if (name.equals(child.getAttributeValue("name")))
                return child.getAttributeValue("value");
        }
        return null;
    }

    private static Element findOptionElement(Element root, String name) {
        for (var child : root.getChildren("option")) {
            if (name.equals(child.getAttributeValue("name"))) return child;
        }
        return null;
    }

    /**
     * Builds a minimal JSON schema with one or two properties (name→type→default pairs).
     */
    private static JsonObject schemaWith(String... triples) {
        var schema = new JsonObject();
        schema.addProperty("type", "object");
        var props = new JsonObject();
        for (int i = 0; i + 2 < triples.length; i += 3) {
            var prop = new JsonObject();
            prop.addProperty("type", triples[i + 1]);
            if (!triples[i + 2].isEmpty()) prop.addProperty("default", triples[i + 2]);
            props.add(triples[i], prop);
        }
        schema.add("properties", props);
        return schema;
    }

    private static JsonObject arraySchema(String propName) {
        var schema = new JsonObject();
        schema.addProperty("type", "object");
        var props = new JsonObject();
        var prop = new JsonObject();
        prop.addProperty("type", "array");
        var items = new JsonObject();
        items.addProperty("type", "string");
        prop.add("items", items);
        props.add(propName, prop);
        schema.add("properties", props);
        return schema;
    }
}
