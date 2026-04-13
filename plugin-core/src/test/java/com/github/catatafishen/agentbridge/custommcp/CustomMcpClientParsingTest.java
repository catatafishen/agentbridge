package com.github.catatafishen.agentbridge.custommcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the extracted JSON parsing methods in {@link CustomMcpClient}:
 * {@link CustomMcpClient#parseToolList(JsonObject)} and
 * {@link CustomMcpClient#parseInitializeResult(JsonObject)}.
 */
class CustomMcpClientParsingTest {

    // ── parseToolList ───────────────────────────────────────────────────

    @Nested
    class ParseToolListTests {

        @Test
        void singleTool_allFields() throws IOException {
            JsonObject response = toolsListResponse(
                tool("read_file", "Read a file from disk", objectSchema("path", "string"))
            );

            List<CustomMcpClient.ToolInfo> tools = CustomMcpClient.parseToolList(response);

            assertEquals(1, tools.size());
            CustomMcpClient.ToolInfo t = tools.getFirst();
            assertEquals("read_file", t.name());
            assertEquals("Read a file from disk", t.description());
            assertNotNull(t.inputSchema());
            assertEquals("object", t.inputSchema().get("type").getAsString());
        }

        @Test
        void multipleTools() throws IOException {
            JsonObject response = toolsListResponse(
                tool("read_file", "Read a file", objectSchema("path", "string")),
                tool("write_file", "Write a file", objectSchema("path", "string")),
                tool("delete_file", "Delete a file", null)
            );

            List<CustomMcpClient.ToolInfo> tools = CustomMcpClient.parseToolList(response);

            assertEquals(3, tools.size());
            assertEquals("read_file", tools.get(0).name());
            assertEquals("write_file", tools.get(1).name());
            assertEquals("delete_file", tools.get(2).name());
        }

        @Test
        void emptyToolsArray_returnsEmptyList() throws IOException {
            JsonObject response = toolsListResponse();

            List<CustomMcpClient.ToolInfo> tools = CustomMcpClient.parseToolList(response);

            assertTrue(tools.isEmpty());
        }

        @Test
        void missingResultField_returnsEmptyList() throws IOException {
            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.addProperty("id", 1);

            List<CustomMcpClient.ToolInfo> tools = CustomMcpClient.parseToolList(response);

            assertTrue(tools.isEmpty());
        }

        @Test
        void missingToolsFieldInResult_returnsEmptyList() throws IOException {
            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.addProperty("id", 1);
            response.add("result", new JsonObject());

            List<CustomMcpClient.ToolInfo> tools = CustomMcpClient.parseToolList(response);

            assertTrue(tools.isEmpty());
        }

        @Test
        void toolWithMissingName_isSkipped() throws IOException {
            JsonObject toolObj = new JsonObject();
            toolObj.addProperty("description", "No name tool");
            // no "name" field

            JsonObject response = toolsListResponse(toolObj);

            List<CustomMcpClient.ToolInfo> tools = CustomMcpClient.parseToolList(response);

            assertTrue(tools.isEmpty(), "Tool without a name should be skipped");
        }

        @Test
        void toolWithEmptyName_isSkipped() throws IOException {
            JsonObject toolObj = new JsonObject();
            toolObj.addProperty("name", "");
            toolObj.addProperty("description", "Empty name");

            JsonObject response = toolsListResponse(toolObj);

            List<CustomMcpClient.ToolInfo> tools = CustomMcpClient.parseToolList(response);

            assertTrue(tools.isEmpty(), "Tool with empty name should be skipped");
        }

        @Test
        void toolWithMissingDescription_defaultsToEmptyString() throws IOException {
            JsonObject toolObj = new JsonObject();
            toolObj.addProperty("name", "no_desc");
            // no "description" field

            JsonObject response = toolsListResponse(toolObj);

            List<CustomMcpClient.ToolInfo> tools = CustomMcpClient.parseToolList(response);

            assertEquals(1, tools.size());
            assertEquals("no_desc", tools.getFirst().name());
            assertEquals("", tools.getFirst().description());
        }

        @Test
        void toolWithMissingInputSchema_defaultsToNull() throws IOException {
            JsonObject toolObj = new JsonObject();
            toolObj.addProperty("name", "no_schema");
            toolObj.addProperty("description", "Tool without schema");
            // no "inputSchema" field

            JsonObject response = toolsListResponse(toolObj);

            List<CustomMcpClient.ToolInfo> tools = CustomMcpClient.parseToolList(response);

            assertEquals(1, tools.size());
            assertNull(tools.getFirst().inputSchema());
        }

        @Test
        void complexInputSchema_isPreserved() throws IOException {
            JsonObject schema = JsonParser.parseString("""
                {
                  "type": "object",
                  "properties": {
                    "path": {"type": "string", "description": "File path"},
                    "content": {"type": "string"}
                  },
                  "required": ["path", "content"]
                }
                """).getAsJsonObject();

            JsonObject toolObj = new JsonObject();
            toolObj.addProperty("name", "write_file");
            toolObj.addProperty("description", "Write content to a file");
            toolObj.add("inputSchema", schema);

            JsonObject response = toolsListResponse(toolObj);
            List<CustomMcpClient.ToolInfo> tools = CustomMcpClient.parseToolList(response);

            assertEquals(1, tools.size());
            JsonObject parsed = tools.getFirst().inputSchema();
            assertNotNull(parsed);
            assertTrue(parsed.has("properties"));
            assertTrue(parsed.getAsJsonObject("properties").has("path"));
            assertTrue(parsed.has("required"));
            assertEquals(2, parsed.getAsJsonArray("required").size());
        }

        @Test
        void mixOfValidAndSkippedTools() throws IOException {
            JsonObject valid = tool("valid_tool", "Works fine", null);
            JsonObject noName = new JsonObject();
            noName.addProperty("description", "I have no name");
            JsonObject emptyName = new JsonObject();
            emptyName.addProperty("name", "");
            JsonObject anotherValid = tool("another", "Also works", null);

            JsonObject response = toolsListResponse(valid, noName, emptyName, anotherValid);

            List<CustomMcpClient.ToolInfo> tools = CustomMcpClient.parseToolList(response);

            assertEquals(2, tools.size());
            assertEquals("valid_tool", tools.get(0).name());
            assertEquals("another", tools.get(1).name());
        }

        @Test
        void errorResponse_throwsIOException() {
            JsonObject response = errorResponse(-32601, "Method not found");

            IOException ex = assertThrows(IOException.class,
                () -> CustomMcpClient.parseToolList(response));
            assertTrue(ex.getMessage().contains("Method not found"));
            assertTrue(ex.getMessage().contains("tools/list"));
        }
    }

    // ── parseInitializeResult ───────────────────────────────────────────

    @Nested
    class ParseInitializeResultTests {

        @Test
        void validResponse_parsesAllFields() throws IOException {
            JsonObject response = initializeResponse("2025-11-25", "my-server", "2.0");

            CustomMcpClient.InitializeInfo info = CustomMcpClient.parseInitializeResult(response);

            assertEquals("2025-11-25", info.protocolVersion());
            assertEquals("my-server", info.serverName());
            assertEquals("2.0", info.serverVersion());
        }

        @Test
        void missingServerInfo_defaultsToEmptyStrings() throws IOException {
            JsonObject result = new JsonObject();
            result.addProperty("protocolVersion", "2025-11-25");
            result.add("capabilities", new JsonObject());
            // no "serverInfo"

            JsonObject response = wrapResult(result);

            CustomMcpClient.InitializeInfo info = CustomMcpClient.parseInitializeResult(response);

            assertEquals("2025-11-25", info.protocolVersion());
            assertEquals("", info.serverName());
            assertEquals("", info.serverVersion());
        }

        @Test
        void missingProtocolVersion_defaultsToEmptyString() throws IOException {
            JsonObject result = new JsonObject();
            result.add("capabilities", new JsonObject());
            JsonObject serverInfo = new JsonObject();
            serverInfo.addProperty("name", "test");
            serverInfo.addProperty("version", "1.0");
            result.add("serverInfo", serverInfo);

            JsonObject response = wrapResult(result);

            CustomMcpClient.InitializeInfo info = CustomMcpClient.parseInitializeResult(response);

            assertEquals("", info.protocolVersion());
            assertEquals("test", info.serverName());
            assertEquals("1.0", info.serverVersion());
        }

        @Test
        void missingResultField_returnsAllDefaults() throws IOException {
            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.addProperty("id", 1);

            CustomMcpClient.InitializeInfo info = CustomMcpClient.parseInitializeResult(response);

            assertEquals("", info.protocolVersion());
            assertEquals("", info.serverName());
            assertEquals("", info.serverVersion());
        }

        @Test
        void serverInfoWithNameOnly() throws IOException {
            JsonObject result = new JsonObject();
            result.addProperty("protocolVersion", "2025-11-25");
            JsonObject serverInfo = new JsonObject();
            serverInfo.addProperty("name", "partial-server");
            // no "version"
            result.add("serverInfo", serverInfo);

            JsonObject response = wrapResult(result);

            CustomMcpClient.InitializeInfo info = CustomMcpClient.parseInitializeResult(response);

            assertEquals("partial-server", info.serverName());
            assertEquals("", info.serverVersion());
        }

        @Test
        void serverInfoWithVersionOnly() throws IOException {
            JsonObject result = new JsonObject();
            result.addProperty("protocolVersion", "2025-11-25");
            JsonObject serverInfo = new JsonObject();
            serverInfo.addProperty("version", "3.0");
            // no "name"
            result.add("serverInfo", serverInfo);

            JsonObject response = wrapResult(result);

            CustomMcpClient.InitializeInfo info = CustomMcpClient.parseInitializeResult(response);

            assertEquals("", info.serverName());
            assertEquals("3.0", info.serverVersion());
        }

        @Test
        void serverInfoAsNonObject_treatedAsMissing() throws IOException {
            JsonObject result = new JsonObject();
            result.addProperty("protocolVersion", "2025-11-25");
            result.addProperty("serverInfo", "not-an-object");

            JsonObject response = wrapResult(result);

            CustomMcpClient.InitializeInfo info = CustomMcpClient.parseInitializeResult(response);

            assertEquals("", info.serverName());
            assertEquals("", info.serverVersion());
        }

        @Test
        void errorResponse_throwsIOException() {
            JsonObject response = errorResponse(-32600, "Invalid request");

            IOException ex = assertThrows(IOException.class,
                () -> CustomMcpClient.parseInitializeResult(response));
            assertTrue(ex.getMessage().contains("Invalid request"));
            assertTrue(ex.getMessage().contains("initialize"));
        }

        @Test
        void errorResponseWithoutMessage_includesErrorToString() {
            JsonObject response = new JsonObject();
            JsonObject error = new JsonObject();
            error.addProperty("code", -32700);
            response.add("error", error);

            IOException ex = assertThrows(IOException.class,
                () -> CustomMcpClient.parseInitializeResult(response));
            assertTrue(ex.getMessage().contains("-32700"));
        }
    }

    // ── Test helpers ────────────────────────────────────────────────────

    private static JsonObject tool(String name, String description, JsonObject inputSchema) {
        JsonObject t = new JsonObject();
        t.addProperty("name", name);
        t.addProperty("description", description);
        if (inputSchema != null) {
            t.add("inputSchema", inputSchema);
        }
        return t;
    }

    private static JsonObject objectSchema(String propertyName, String propertyType) {
        JsonObject prop = new JsonObject();
        prop.addProperty("type", propertyType);
        JsonObject properties = new JsonObject();
        properties.add(propertyName, prop);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        return schema;
    }

    private static JsonObject toolsListResponse(JsonObject... tools) {
        JsonArray arr = new JsonArray();
        for (JsonObject t : tools) {
            arr.add(t);
        }
        JsonObject result = new JsonObject();
        result.add("tools", arr);
        return wrapResult(result);
    }

    private static JsonObject initializeResponse(String protocolVersion, String serverName, String serverVersion) {
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", protocolVersion);
        result.add("capabilities", new JsonObject());
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", serverName);
        serverInfo.addProperty("version", serverVersion);
        result.add("serverInfo", serverInfo);
        return wrapResult(result);
    }

    private static JsonObject wrapResult(JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.addProperty("id", 1);
        response.add("result", result);
        return response;
    }

    private static JsonObject errorResponse(int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.addProperty("id", 1);
        response.add("error", error);
        return response;
    }
}
