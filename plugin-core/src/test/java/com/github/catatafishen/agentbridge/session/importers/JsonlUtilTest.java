package com.github.catatafishen.agentbridge.session.importers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonlUtilTest {

    @Test
    void parseJsonlSingleLine() {
        List<JsonObject> result = JsonlUtil.parseJsonl("{\"key\":\"value\"}");
        assertEquals(1, result.size());
        assertEquals("value", result.get(0).get("key").getAsString());
    }

    @Test
    void parseJsonlMultipleLines() {
        String input = "{\"a\":1}\n{\"b\":2}\n{\"c\":3}";
        List<JsonObject> result = JsonlUtil.parseJsonl(input);
        assertEquals(3, result.size());
        assertEquals(1, result.get(0).get("a").getAsInt());
        assertEquals(2, result.get(1).get("b").getAsInt());
        assertEquals(3, result.get(2).get("c").getAsInt());
    }

    @Test
    void parseJsonlSkipsBlankLines() {
        String input = "{\"a\":1}\n\n\n{\"b\":2}\n";
        List<JsonObject> result = JsonlUtil.parseJsonl(input);
        assertEquals(2, result.size());
    }

    @Test
    void parseJsonlSkipsMalformedLines() {
        String input = "{\"a\":1}\nnot json\n{\"b\":2}";
        List<JsonObject> result = JsonlUtil.parseJsonl(input);
        assertEquals(2, result.size());
        assertEquals(1, result.get(0).get("a").getAsInt());
        assertEquals(2, result.get(1).get("b").getAsInt());
    }

    @Test
    void parseJsonlSkipsNonObjectJson() {
        String input = "{\"a\":1}\n[1,2,3]\n\"plain string\"\n{\"b\":2}";
        List<JsonObject> result = JsonlUtil.parseJsonl(input);
        assertEquals(2, result.size());
    }

    @Test
    void parseJsonlEmptyInputReturnsEmptyList() {
        assertEquals(0, JsonlUtil.parseJsonl("").size());
        assertEquals(0, JsonlUtil.parseJsonl("  \n  \n").size());
    }

    @Test
    void getStrReturnsStringValue() {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", "hello");
        assertEquals("hello", JsonlUtil.getStr(obj, "name"));
    }

    @Test
    void getStrReturnsNullForMissingKey() {
        assertNull(JsonlUtil.getStr(new JsonObject(), "missing"));
    }

    @Test
    void getStrReturnsNullForNonPrimitive() {
        JsonObject obj = new JsonObject();
        obj.add("nested", new JsonObject());
        assertNull(JsonlUtil.getStr(obj, "nested"));
    }

    @Test
    void getArrayReturnsJsonArray() {
        JsonObject obj = new JsonObject();
        JsonArray arr = new JsonArray();
        arr.add("item");
        obj.add("list", arr);
        JsonArray result = JsonlUtil.getArray(obj, "list");
        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void getArrayReturnsNullForNonArray() {
        JsonObject obj = new JsonObject();
        obj.addProperty("key", "value");
        assertNull(JsonlUtil.getArray(obj, "key"));
    }

    @Test
    void getObjectReturnsJsonObject() {
        JsonObject obj = new JsonObject();
        JsonObject nested = new JsonObject();
        nested.addProperty("inner", "value");
        obj.add("child", nested);
        JsonObject result = JsonlUtil.getObject(obj, "child");
        assertNotNull(result);
        assertEquals("value", result.get("inner").getAsString());
    }

    @Test
    void getObjectReturnsNullForNonObject() {
        JsonObject obj = new JsonObject();
        obj.addProperty("key", "value");
        assertNull(JsonlUtil.getObject(obj, "key"));
    }

    @Test
    void getObjectReturnsNullForMissingKey() {
        assertNull(JsonlUtil.getObject(new JsonObject(), "missing"));
    }
}
