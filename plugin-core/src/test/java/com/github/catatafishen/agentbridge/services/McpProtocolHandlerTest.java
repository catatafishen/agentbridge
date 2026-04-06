package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for the MCP protocol handler, including the resources surface.
 */
class McpProtocolHandlerTest {

    private McpProtocolHandler handler;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        Project project = (Project) Proxy.newProxyInstance(
            Project.class.getClassLoader(),
            new Class<?>[]{Project.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getBasePath" -> tempDir.toString();
                case "isDisposed" -> false;
                case "getName" -> "test-project";
                default -> defaultValue(method.getReturnType());
            }
        );
        handler = new McpProtocolHandler(project);
    }

    @Test
    void testInitializeAdvertisesResourcesCapability() {
        JsonObject response = parseResponse(sendRequest("initialize", new JsonObject()));
        JsonObject capabilities = response.getAsJsonObject("result").getAsJsonObject("capabilities");

        assertNotNull(capabilities.getAsJsonObject("resources"));
        assertFalse(capabilities.getAsJsonObject("resources").get("listChanged").getAsBoolean());
    }

    @Test
    void testResourcesListIncludesOnlyStartupInstructions() {
        Path file = tempDir.resolve("README.md");
        writeTextFile(file, "# hello");

        JsonObject response = parseResponse(sendRequest("resources/list", new JsonObject()));
        JsonArray resources = response.getAsJsonObject("result").getAsJsonArray("resources");

        assertEquals(1, resources.size());
        JsonObject resource = resources.get(0).getAsJsonObject();
        assertEquals("resource://default-startup-instructions.md", resource.get("uri").getAsString());
        assertEquals("default-startup-instructions", resource.get("name").getAsString());
        assertEquals("Default Startup Instructions", resource.get("title").getAsString());
        assertEquals("text/markdown", resource.get("mimeType").getAsString());
    }

    @Test
    void testResourceTemplatesListIncludesProjectFilesTemplate() {
        JsonObject response = parseResponse(sendRequest("resources/templates/list", new JsonObject()));
        JsonArray templates = response.getAsJsonObject("result").getAsJsonArray("resourceTemplates");

        assertEquals(1, templates.size());
        JsonObject template = templates.get(0).getAsJsonObject();
        assertEquals("file:///{path}", template.get("uriTemplate").getAsString());
        assertEquals("Project Files", template.get("name").getAsString());
        assertEquals("Project Files", template.get("title").getAsString());
    }

    @Test
    void testResourcesListRejectsInvalidCursor() {
        JsonObject params = new JsonObject();
        params.addProperty("cursor", "bad-cursor");

        JsonObject response = parseResponse(sendRequest("resources/list", params));
        JsonObject error = response.getAsJsonObject("error");

        assertEquals(-32602, error.get("code").getAsInt());
        assertEquals("Invalid cursor", error.get("message").getAsString());
    }

    @Test
    void testResourceTemplatesListRejectsInvalidCursor() {
        JsonObject params = new JsonObject();
        params.addProperty("cursor", "bad-cursor");

        JsonObject response = parseResponse(sendRequest("resources/templates/list", params));
        JsonObject error = response.getAsJsonObject("error");

        assertEquals(-32602, error.get("code").getAsInt());
        assertEquals("Invalid cursor", error.get("message").getAsString());
    }

    @Test
    void testResourcesReadReturnsStartupInstructionsText() {
        JsonObject params = new JsonObject();
        params.addProperty("uri", "resource://default-startup-instructions.md");

        JsonObject response = parseResponse(sendRequest("resources/read", params));
        JsonArray contents = response.getAsJsonObject("result").getAsJsonArray("contents");

        assertEquals(1, contents.size());
        JsonObject content = contents.get(0).getAsJsonObject();
        assertEquals("resource://default-startup-instructions.md", content.get("uri").getAsString());
        assertEquals("text/markdown", content.get("mimeType").getAsString());
        assertNotNull(content.get("text").getAsString());
        assertFalse(content.get("text").getAsString().isEmpty());
    }

    @Test
    void testResourcesReadReturnsProjectFileContents() {
        Path file = tempDir.resolve("src/test.txt");
        writeTextFile(file, "hello resource");

        JsonObject params = new JsonObject();
        params.addProperty("uri", file.toUri().toString());

        JsonObject response = parseResponse(sendRequest("resources/read", params));
        JsonArray contents = response.getAsJsonObject("result").getAsJsonArray("contents");

        assertEquals(1, contents.size());
        JsonObject content = contents.get(0).getAsJsonObject();
        assertEquals(file.toUri().toString(), content.get("uri").getAsString());
        assertEquals("hello resource", content.get("text").getAsString());
    }

    @Test
    void testResourcesReadReturnsBinaryFileAsBlob() {
        Path file = tempDir.resolve("bin/data.bin");
        writeBinaryFile(file, new byte[]{0x00, 0x01, 0x02, 0x03});

        JsonObject params = new JsonObject();
        params.addProperty("uri", file.toUri().toString());

        JsonObject response = parseResponse(sendRequest("resources/read", params));
        JsonArray contents = response.getAsJsonObject("result").getAsJsonArray("contents");

        assertEquals(1, contents.size());
        JsonObject content = contents.get(0).getAsJsonObject();
        assertEquals(file.toUri().toString(), content.get("uri").getAsString());
        assertEquals("application/octet-stream", content.get("mimeType").getAsString());
        assertEquals("AAECAw==", content.get("blob").getAsString());
    }

    @Test
    void testResourcesReadRejectsInvalidFileUri() {
        JsonObject params = new JsonObject();
        params.addProperty("uri", "file://bad host/path");

        JsonObject response = parseResponse(sendRequest("resources/read", params));
        JsonObject error = response.getAsJsonObject("error");

        assertEquals(-32602, error.get("code").getAsInt());
        assertEquals("Invalid resource URI: file://bad host/path", error.get("message").getAsString());
    }

    @Test
    void testResourcesReadRejectsUnknownFileOutsideProject() throws Exception {
        Path outside = Files.createTempFile("mcp-outside", ".txt");
        Files.writeString(outside, "outside", StandardCharsets.UTF_8);
        try {
            JsonObject params = new JsonObject();
            params.addProperty("uri", outside.toUri().toString());

            JsonObject response = parseResponse(sendRequest("resources/read", params));
            JsonObject error = response.getAsJsonObject("error");
            assertEquals(-32002, error.get("code").getAsInt());
            assertEquals(outside.toUri().toString(), error.getAsJsonObject("data").get("uri").getAsString());
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    private static void writeBinaryFile(Path path, byte[] content) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, content);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void writeTextFile(Path path, String content) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0f;
        }
        if (returnType == double.class) {
            return 0d;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }

    private String sendRequest(String method, JsonObject params) {
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", 1);
        request.addProperty("method", method);
        request.add("params", params);
        return handler.handleMessage(request.toString());
    }

    private JsonObject parseResponse(String json) {
        assertNotNull(json);
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
