package com.github.catatafishen.agentbridge.psi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jdom.Element;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RunConfigurationServiceSchemaInferenceTest {

    @Nested
    class BuildConfigElement {
        @Test
        void setsNameAttribute() {
            Element el = RunConfigurationService.buildConfigElement("My App", "Application", "Application");
            assertEquals("My App", el.getAttributeValue("name"));
        }

        @Test
        void setsTypeAttribute() {
            Element el = RunConfigurationService.buildConfigElement("Test", "JUnit", "JUnit");
            assertEquals("JUnit", el.getAttributeValue("type"));
        }

        @Test
        void setsFactoryNameAttribute() {
            Element el = RunConfigurationService.buildConfigElement("Test", "JUnit", "TestNG");
            assertEquals("TestNG", el.getAttributeValue("factoryName"));
        }

        @Test
        void createsConfigurationElement() {
            Element el = RunConfigurationService.buildConfigElement("n", "t", "f");
            assertEquals("configuration", el.getName());
        }

        @Test
        void setsDefaultToFalse() {
            Element el = RunConfigurationService.buildConfigElement("n", "t", "f");
            assertEquals("false", el.getAttributeValue("default"));
        }
    }

    @Nested
    class InferOptionSchema {
        @Test
        void returnsStringSchemaForValueAttribute() {
            Element option = new Element("option");
            option.setAttribute("value", "hello");
            JsonObject schema = RunConfigurationService.inferOptionSchema(option);
            assertEquals("string", schema.get("type").getAsString());
            assertEquals("hello", schema.get("default").getAsString());
        }

        @Test
        void returnsBooleanSchemaForBooleanValue() {
            Element option = new Element("option");
            option.setAttribute("value", "true");
            JsonObject schema = RunConfigurationService.inferOptionSchema(option);
            assertEquals("boolean", schema.get("type").getAsString());
        }

        @Test
        void returnsArraySchemaForListChild() {
            Element option = new Element("option");
            Element list = new Element("list");
            option.addContent(list);
            JsonObject schema = RunConfigurationService.inferOptionSchema(option);
            assertEquals("array", schema.get("type").getAsString());
        }

        @Test
        void returnsSchemaForNoValue() {
            Element option = new Element("option");
            JsonObject schema = RunConfigurationService.inferOptionSchema(option);
            assertNotNull(schema);
            assertTrue(schema.has("type"));
        }
    }

    @Nested
    class InferElementSchema {
        @Test
        void returnsDictSchemaForEnvs() {
            Element envs = new Element("envs");
            JsonObject schema = RunConfigurationService.inferElementSchema(envs);
            assertEquals("object", schema.get("type").getAsString());
        }

        @Test
        void returnsPrimitiveSchemaForValueAttribute() {
            Element el = new Element("myElement");
            el.setAttribute("value", "someValue");
            JsonObject schema = RunConfigurationService.inferElementSchema(el);
            assertEquals("string", schema.get("type").getAsString());
        }

        @Test
        void returnsPrimitiveSchemaForTextContent() {
            Element el = new Element("myElement");
            el.setText("some text");
            JsonObject schema = RunConfigurationService.inferElementSchema(el);
            assertEquals("string", schema.get("type").getAsString());
        }

        @Test
        void returnsArraySchemaForListChild() {
            Element el = new Element("myElement");
            Element list = new Element("list");
            el.addContent(list);
            JsonObject schema = RunConfigurationService.inferElementSchema(el);
            assertEquals("array", schema.get("type").getAsString());
        }
    }

    @Nested
    class CollectTypeErrors {
        @Test
        void acceptsStringPrimitive() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "string");
            List<String> errors = new ArrayList<>();
            RunConfigurationService.collectTypeErrors("key", new JsonPrimitive("value"), schema, errors);
            assertTrue(errors.isEmpty());
        }

        @Test
        void rejectsArrayWhenStringExpected() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "string");
            List<String> errors = new ArrayList<>();
            RunConfigurationService.collectTypeErrors("key", new JsonArray(), schema, errors);
            assertEquals(1, errors.size());
            assertTrue(errors.getFirst().contains("must be a string"));
        }

        @Test
        void acceptsArrayWhenArrayExpected() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "array");
            List<String> errors = new ArrayList<>();
            RunConfigurationService.collectTypeErrors("key", new JsonArray(), schema, errors);
            assertTrue(errors.isEmpty());
        }

        @Test
        void rejectsPrimitiveWhenArrayExpected() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "array");
            List<String> errors = new ArrayList<>();
            RunConfigurationService.collectTypeErrors("key", new JsonPrimitive("text"), schema, errors);
            assertEquals(1, errors.size());
            assertTrue(errors.getFirst().contains("must be an array"));
        }

        @Test
        void acceptsObjectWhenObjectExpected() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            List<String> errors = new ArrayList<>();
            RunConfigurationService.collectTypeErrors("key", new JsonObject(), schema, errors);
            assertTrue(errors.isEmpty());
        }

        @Test
        void rejectsPrimitiveWhenObjectExpected() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            List<String> errors = new ArrayList<>();
            RunConfigurationService.collectTypeErrors("key", new JsonPrimitive("text"), schema, errors);
            assertEquals(1, errors.size());
            assertTrue(errors.getFirst().contains("must be an object"));
        }

        @Test
        void acceptsBooleanPrimitive() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "boolean");
            List<String> errors = new ArrayList<>();
            RunConfigurationService.collectTypeErrors("key", new JsonPrimitive(true), schema, errors);
            assertTrue(errors.isEmpty());
        }

        @Test
        void rejectsArrayWhenBooleanExpected() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "boolean");
            List<String> errors = new ArrayList<>();
            RunConfigurationService.collectTypeErrors("key", new JsonArray(), schema, errors);
            assertEquals(1, errors.size());
            assertTrue(errors.getFirst().contains("must be a boolean"));
        }

        @Test
        void defaultsToStringType() {
            JsonObject schema = new JsonObject();
            List<String> errors = new ArrayList<>();
            RunConfigurationService.collectTypeErrors("key", new JsonPrimitive("value"), schema, errors);
            assertTrue(errors.isEmpty());
        }
    }
}
