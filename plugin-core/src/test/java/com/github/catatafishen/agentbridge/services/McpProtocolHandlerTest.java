package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void testPingReturnsEmptyResult() {
        JsonObject response = parseResponse(sendRequest("ping", new JsonObject()));
        JsonObject result = response.getAsJsonObject("result");

        assertNotNull(result);
        assertEquals(0, result.size()); // empty object
    }

    @Test
    void testUnknownMethodReturnsMethodNotFound() {
        JsonObject response = parseResponse(sendRequest("unknown/method", new JsonObject()));
        JsonObject error = response.getAsJsonObject("error");

        assertEquals(-32601, error.get("code").getAsInt());
        assertEquals("Method not found: unknown/method", error.get("message").getAsString());
    }

    @Test
    void testNotificationWithoutIdReturnsNull() {
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("method", "ping");
        // no "id" field → notification

        assertNull(handler.handleMessage(request.toString()));
    }

    @Test
    void testMalformedJsonReturnsInternalError() {
        // The handler logs an ERROR for malformed JSON (intentional — surfacing parse failures).
        // The IntelliJ TestLoggerFactory converts any ERROR log into a test assertion error, so
        // we verify the returned JSON is a parse error without triggering the logger check.
        // Root cause: IntelliJ's test infra installs a log listener even for plain JUnit 5 tests.
        String response = handler.handleMessage("{not valid json");
        // Should not throw — handler must always return a valid JSON response
        assertNotNull(response);
        JsonObject parsed = JsonParser.parseString(response).getAsJsonObject();
        // Parse errors produce a response with an "error" field
        assertTrue(parsed.has("error"));
    }

    @Test
    void testInitializeAdvertisesToolsCapability() {
        JsonObject response = parseResponse(sendRequest("initialize", new JsonObject()));
        JsonObject capabilities = response.getAsJsonObject("result").getAsJsonObject("capabilities");

        assertNotNull(capabilities.getAsJsonObject("tools"));
        assertFalse(capabilities.getAsJsonObject("tools").get("listChanged").getAsBoolean());
    }

    @Test
    void testInitializeIncludesServerInfo() {
        JsonObject response = parseResponse(sendRequest("initialize", new JsonObject()));
        JsonObject result = response.getAsJsonObject("result");

        assertNotNull(result.getAsJsonObject("serverInfo"));
        assertNotNull(result.get("serverInfo").getAsJsonObject().get("name"));
        assertNotNull(result.get("serverInfo").getAsJsonObject().get("version"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "README.md,   '# readme',                  text/markdown",
        "index.html,  '<html></html>',              text/html",
        "config.json, '{\"k\":\"v\"}',              application/json",
    })
    void testResourcesReadReturnsCorrectMimeType(String filename, String content, String expectedMime) {
        Path file = tempDir.resolve(filename);
        writeTextFile(file, content);

        JsonObject params = new JsonObject();
        params.addProperty("uri", file.toUri().toString());

        JsonObject response = parseResponse(sendRequest("resources/read", params));
        JsonObject item = response.getAsJsonObject("result").getAsJsonArray("contents").get(0).getAsJsonObject();

        assertEquals(expectedMime.trim(), item.get("mimeType").getAsString());
    }

    @Test
    void testResourcesReadReturnsNotFoundForMissingFile() {
        JsonObject params = new JsonObject();
        params.addProperty("uri", tempDir.resolve("does-not-exist.txt").toUri().toString());

        JsonObject response = parseResponse(sendRequest("resources/read", params));
        JsonObject error = response.getAsJsonObject("error");

        assertEquals(-32002, error.get("code").getAsInt());
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

    // ── parseCursorOffset ─────────────────────────────────────────────────────

    @Test
    void parseCursorOffset_returnsZeroForNull() throws Exception {
        assertEquals(0, invokeParseCursorOffset(null, "res:"));
    }

    @Test
    void parseCursorOffset_parsesPrefixedOffset() throws Exception {
        var cursorElement = com.google.gson.JsonParser.parseString("\"res:5\"");
        assertEquals(5, invokeParseCursorOffset(cursorElement, "res:"));
    }

    @Test
    void parseCursorOffset_negativeClampedToZero() throws Exception {
        var cursorElement = com.google.gson.JsonParser.parseString("\"res:-3\"");
        assertEquals(0, invokeParseCursorOffset(cursorElement, "res:"));
    }

    @Test
    void parseCursorOffset_wrongPrefixThrows() {
        var cursorElement = com.google.gson.JsonParser.parseString("\"wrong:5\"");
        var ex = assertThrows(java.lang.reflect.InvocationTargetException.class,
            () -> invokeParseCursorOffset(cursorElement, "res:"));
        assertEquals("InvalidCursorException", ex.getCause().getClass().getSimpleName());
    }

    @Test
    void parseCursorOffset_nonNumericThrows() {
        var cursorElement = com.google.gson.JsonParser.parseString("\"res:abc\"");
        var ex = assertThrows(java.lang.reflect.InvocationTargetException.class,
            () -> invokeParseCursorOffset(cursorElement, "res:"));
        assertEquals("InvalidCursorException", ex.getCause().getClass().getSimpleName());
    }

    // ── encodeCursor ─────────────────────────────────────────────────────────

    @Test
    void encodeCursor_concatenatesPrefixAndOffset() throws Exception {
        assertEquals("res:10", invokeEncodeCursor("res:", 10));
    }

    @Test
    void encodeCursor_zeroOffset() throws Exception {
        assertEquals("tmpl:0", invokeEncodeCursor("tmpl:", 0));
    }

    // ── guessMimeType ────────────────────────────────────────────────────────

    @Test
    void guessMimeType_java() throws Exception {
        assertEquals("text/x-java", invokeGuessMimeType(Path.of("Foo.java")));
    }

    @Test
    void guessMimeType_kotlin() throws Exception {
        assertEquals("text/x-kotlin", invokeGuessMimeType(Path.of("Foo.kt")));
    }

    @Test
    void guessMimeType_json() throws Exception {
        assertEquals("application/json", invokeGuessMimeType(Path.of("data.json")));
    }

    @Test
    void guessMimeType_markdown() throws Exception {
        assertEquals("text/markdown", invokeGuessMimeType(Path.of("README.md")));
    }

    @Test
    void guessMimeType_yaml() throws Exception {
        assertEquals("application/yaml", invokeGuessMimeType(Path.of("config.yaml")));
    }

    @Test
    void guessMimeType_yml() throws Exception {
        assertEquals("application/yaml", invokeGuessMimeType(Path.of("config.yml")));
    }

    @Test
    void guessMimeType_xml() throws Exception {
        assertEquals("application/xml", invokeGuessMimeType(Path.of("pom.xml")));
    }

    @Test
    void guessMimeType_txt() throws Exception {
        assertEquals("text/plain", invokeGuessMimeType(Path.of("notes.txt")));
    }

    @Test
    void guessMimeType_unknownExtension() throws Exception {
        assertEquals("application/octet-stream", invokeGuessMimeType(Path.of("data.qzx")));
    }

    // ── isTextResource ───────────────────────────────────────────────────────

    @Test
    void isTextResource_trueForJava() throws Exception {
        assertTrue(invokeIsTextResource(Path.of("Foo.java")));
    }

    @Test
    void isTextResource_trueForJson() throws Exception {
        assertTrue(invokeIsTextResource(Path.of("data.json")));
    }

    @Test
    void isTextResource_trueForXml() throws Exception {
        assertTrue(invokeIsTextResource(Path.of("pom.xml")));
    }

    @Test
    void isTextResource_trueForYaml() throws Exception {
        assertTrue(invokeIsTextResource(Path.of("config.yaml")));
    }

    @Test
    void isTextResource_falseForBinary() throws Exception {
        assertFalse(invokeIsTextResource(Path.of("image.png")));
    }

    // ── extractMeta ──────────────────────────────────────────────────────────

    @Test
    void extractMeta_returnsNullsWhenNoMeta() throws Exception {
        JsonObject params = new JsonObject();
        Object meta = invokeExtractMeta(params);
        assertNull(getRecordField(meta, "progressToken"));
        assertNull(getRecordField(meta, "toolUseId"));
    }

    @Test
    void extractMeta_extractsProgressTokenAndToolUseId() throws Exception {
        JsonObject metaObj = new JsonObject();
        metaObj.addProperty("progressToken", "42");
        metaObj.addProperty("claudecode/toolUseId", "tu_abc123");
        JsonObject params = new JsonObject();
        params.add("_meta", metaObj);

        Object meta = invokeExtractMeta(params);
        assertEquals("42", getRecordField(meta, "progressToken"));
        assertEquals("tu_abc123", getRecordField(meta, "toolUseId"));
    }

    // ── getOptionalMetaString ────────────────────────────────────────────────

    @Test
    void getOptionalMetaString_returnsNullForMissingKey() throws Exception {
        JsonObject meta = new JsonObject();
        assertNull(invokeGetOptionalMetaString(meta, "missing"));
    }

    @Test
    void getOptionalMetaString_returnsStringValue() throws Exception {
        JsonObject meta = new JsonObject();
        meta.addProperty("key", "value");
        assertEquals("value", invokeGetOptionalMetaString(meta, "key"));
    }

    @Test
    void getOptionalMetaString_returnsNumberAsString() throws Exception {
        JsonObject meta = new JsonObject();
        meta.addProperty("key", 42);
        assertEquals("42", invokeGetOptionalMetaString(meta, "key"));
    }

    @Test
    void getOptionalMetaString_returnsNullForJsonNull() throws Exception {
        JsonObject meta = new JsonObject();
        meta.add("key", com.google.gson.JsonNull.INSTANCE);
        assertNull(invokeGetOptionalMetaString(meta, "key"));
    }

    @Test
    void getOptionalMetaString_returnsNullForJsonObject() throws Exception {
        JsonObject meta = new JsonObject();
        meta.add("key", new JsonObject());
        assertNull(invokeGetOptionalMetaString(meta, "key"));
    }

    // ── truncateIfNeeded ─────────────────────────────────────────────────────

    @Test
    void truncateIfNeeded_returnsNullForNull() throws Exception {
        assertNull(invokeTruncateIfNeeded(null));
    }

    @Test
    void truncateIfNeeded_returnsShortTextUnchanged() throws Exception {
        assertEquals("hello", invokeTruncateIfNeeded("hello"));
    }

    @Test
    void truncateIfNeeded_truncatesLongText() throws Exception {
        String longText = "x".repeat(100_000);
        String result = invokeTruncateIfNeeded(longText);
        assertNotNull(result);
        assertTrue(result.length() < longText.length());
        assertTrue(result.contains("[Output truncated:"));
        assertTrue(result.contains("characters omitted"));
    }

    // ── respondResult ────────────────────────────────────────────────────────

    @Test
    void respondResult_includesJsonRpcAndId() throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("id", 42);
        JsonObject resultObj = new JsonObject();
        resultObj.addProperty("data", "test");

        JsonObject response = invokeRespondResult(request, resultObj);
        assertEquals("2.0", response.get("jsonrpc").getAsString());
        assertEquals(42, response.get("id").getAsInt());
        assertTrue(response.has("result"));
        assertEquals("test", response.getAsJsonObject("result").get("data").getAsString());
    }

    @Test
    void respondResult_omitsIdWhenMissing() throws Exception {
        JsonObject request = new JsonObject();
        JsonObject resultObj = new JsonObject();
        JsonObject response = invokeRespondResult(request, resultObj);
        assertEquals("2.0", response.get("jsonrpc").getAsString());
        assertFalse(response.has("id"));
    }

    // ── respondError ─────────────────────────────────────────────────────────

    @Test
    void respondError_includesCodeAndMessage() throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("id", 7);
        JsonObject response = invokeRespondError(request, -32600, "Invalid Request");
        assertEquals("2.0", response.get("jsonrpc").getAsString());
        assertEquals(7, response.get("id").getAsInt());
        JsonObject error = response.getAsJsonObject("error");
        assertEquals(-32600, error.get("code").getAsInt());
        assertEquals("Invalid Request", error.get("message").getAsString());
    }

    // ── makeErrorResponse ────────────────────────────────────────────────────

    @Test
    void makeErrorResponse_withId() throws Exception {
        com.google.gson.JsonElement idElement = com.google.gson.JsonParser.parseString("99");
        JsonObject response = invokeMakeErrorResponse(idElement, -32601, "Method not found");
        assertEquals(99, response.get("id").getAsInt());
        assertEquals(-32601, response.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void makeErrorResponse_withNullId() throws Exception {
        JsonObject response = invokeMakeErrorResponse(null, -32700, "Parse error");
        assertFalse(response.has("id"));
        assertEquals(-32700, response.getAsJsonObject("error").get("code").getAsInt());
        assertEquals("Parse error", response.getAsJsonObject("error").get("message").getAsString());
    }

    // ── buildToolResult tests ────────────────────────────────────────────────

    @Test
    void buildToolResult_normalText_isErrorFalse() throws Exception {
        JsonObject msg = makeJsonRpcRequest(42);
        JsonObject response = invokeBuildToolResult(msg, "hello world", false);

        assertEquals("2.0", response.get("jsonrpc").getAsString());
        assertEquals(42, response.get("id").getAsInt());
        JsonObject result = response.getAsJsonObject("result");
        JsonArray content = result.getAsJsonArray("content");
        assertEquals(1, content.size());
        JsonObject entry = content.get(0).getAsJsonObject();
        assertEquals("text", entry.get("type").getAsString());
        assertEquals("hello world", entry.get("text").getAsString());
        assertFalse(result.get("isError").getAsBoolean());
    }

    @Test
    void buildToolResult_nullText_replacedWithEmptyString() throws Exception {
        JsonObject msg = makeJsonRpcRequest(1);
        JsonObject response = invokeBuildToolResult(msg, null, false);

        JsonObject result = response.getAsJsonObject("result");
        JsonArray content = result.getAsJsonArray("content");
        assertEquals("", content.get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void buildToolResult_isErrorTrue() throws Exception {
        JsonObject msg = makeJsonRpcRequest(7);
        JsonObject response = invokeBuildToolResult(msg, "something failed", true);

        JsonObject result = response.getAsJsonObject("result");
        assertTrue(result.get("isError").getAsBoolean());
    }

    @Test
    void buildToolResult_preservesJsonRpcId() throws Exception {
        JsonObject msg = makeJsonRpcRequest(99);
        JsonObject response = invokeBuildToolResult(msg, "text", false);

        assertEquals(99, response.get("id").getAsInt());
        assertEquals("2.0", response.get("jsonrpc").getAsString());
    }

    // ── respondResourceNotFound tests ────────────────────────────────────────

    @Test
    void respondResourceNotFound_returnsErrorWithCode32002() throws Exception {
        JsonObject request = makeJsonRpcRequest(5);
        JsonObject response = invokeRespondResourceNotFound(request, "file:///missing.txt", "Resource not found");

        JsonObject error = response.getAsJsonObject("error");
        assertEquals(-32002, error.get("code").getAsInt());
    }

    @Test
    void respondResourceNotFound_hasDataUriField() throws Exception {
        JsonObject request = makeJsonRpcRequest(10);
        JsonObject response = invokeRespondResourceNotFound(request, "file:///some/path", "Resource not found");

        JsonObject error = response.getAsJsonObject("error");
        JsonObject data = error.getAsJsonObject("data");
        assertNotNull(data);
        assertEquals("file:///some/path", data.get("uri").getAsString());
    }

    @Test
    void respondResourceNotFound_errorMessageIsResourceNotFound() throws Exception {
        JsonObject request = makeJsonRpcRequest(3);
        JsonObject response = invokeRespondResourceNotFound(request, "file:///x", "Resource not found");

        JsonObject error = response.getAsJsonObject("error");
        assertEquals("Resource not found", error.get("message").getAsString());
    }

    @Test
    void respondResourceNotFound_includesJsonRpcId() throws Exception {
        JsonObject request = makeJsonRpcRequest(77);
        JsonObject response = invokeRespondResourceNotFound(request, "file:///y", "Resource not found");

        assertEquals("2.0", response.get("jsonrpc").getAsString());
        assertEquals(77, response.get("id").getAsInt());
    }

    // ── buildPagedResourceResponse tests ─────────────────────────────────────

    @Test
    void buildPagedResourceResponse_itemsWithinPageSize_allReturned_noNextCursor() throws Exception {
        JsonObject msg = makeJsonRpcRequest(1);
        List<JsonObject> items = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            JsonObject item = new JsonObject();
            item.addProperty("name", "item" + i);
            items.add(item);
        }

        JsonObject response = invokeBuildPagedResourceResponse(msg, "res:", items, "resources");

        JsonObject result = response.getAsJsonObject("result");
        JsonArray resources = result.getAsJsonArray("resources");
        assertEquals(5, resources.size());
        assertFalse(result.has("nextCursor"));
    }

    @Test
    void buildPagedResourceResponse_moreItemsThanPageSize_pageReturned_withNextCursor() throws Exception {
        JsonObject msg = makeJsonRpcRequest(2);
        int pageSize = 200; // RESOURCE_PAGE_SIZE
        List<JsonObject> items = new ArrayList<>();
        for (int i = 0; i < pageSize + 50; i++) {
            JsonObject item = new JsonObject();
            item.addProperty("name", "item" + i);
            items.add(item);
        }

        JsonObject response = invokeBuildPagedResourceResponse(msg, "res:", items, "resources");

        JsonObject result = response.getAsJsonObject("result");
        JsonArray resources = result.getAsJsonArray("resources");
        assertEquals(pageSize, resources.size());
        assertEquals("item0", resources.get(0).getAsJsonObject().get("name").getAsString());
        assertTrue(result.has("nextCursor"));
        assertEquals("res:" + pageSize, result.get("nextCursor").getAsString());
    }

    @Test
    void buildPagedResourceResponse_cursorInRequest_offsetApplied() throws Exception {
        int pageSize = 200; // RESOURCE_PAGE_SIZE
        int totalItems = pageSize + 50;
        int cursorOffset = pageSize;

        JsonObject params = new JsonObject();
        params.addProperty("cursor", "res:" + cursorOffset);
        JsonObject msg = makeJsonRpcRequest(3);
        msg.add("params", params);

        List<JsonObject> items = new ArrayList<>();
        for (int i = 0; i < totalItems; i++) {
            JsonObject item = new JsonObject();
            item.addProperty("name", "item" + i);
            items.add(item);
        }

        JsonObject response = invokeBuildPagedResourceResponse(msg, "res:", items, "resources");

        JsonObject result = response.getAsJsonObject("result");
        JsonArray resources = result.getAsJsonArray("resources");
        assertEquals(50, resources.size());
        assertEquals("item" + cursorOffset, resources.get(0).getAsJsonObject().get("name").getAsString());
        assertFalse(result.has("nextCursor"));
    }

    @Test
    void buildPagedResourceResponse_emptyItems_emptyResultArray() throws Exception {
        JsonObject msg = makeJsonRpcRequest(4);
        List<JsonObject> items = new ArrayList<>();

        JsonObject response = invokeBuildPagedResourceResponse(msg, "res:", items, "resources");

        JsonObject result = response.getAsJsonObject("result");
        JsonArray resources = result.getAsJsonArray("resources");
        assertEquals(0, resources.size());
        assertFalse(result.has("nextCursor"));
    }

    // ── Reflection helpers ───────────────────────────────────────────────────

    private static int invokeParseCursorOffset(com.google.gson.JsonElement cursor, String prefix) throws Exception {
        var m = McpProtocolHandler.class.getDeclaredMethod("parseCursorOffset",
            com.google.gson.JsonElement.class, String.class);
        m.setAccessible(true);
        return (int) m.invoke(null, cursor, prefix);
    }

    private static String invokeEncodeCursor(String prefix, int offset) throws Exception {
        var m = McpProtocolHandler.class.getDeclaredMethod("encodeCursor", String.class, int.class);
        m.setAccessible(true);
        return (String) m.invoke(null, prefix, offset);
    }

    private static String invokeGuessMimeType(Path path) throws Exception {
        var m = McpProtocolHandler.class.getDeclaredMethod("guessMimeType", Path.class);
        m.setAccessible(true);
        return (String) m.invoke(null, path);
    }

    private static boolean invokeIsTextResource(Path path) throws Exception {
        var m = McpProtocolHandler.class.getDeclaredMethod("isTextResource", Path.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, path);
    }

    private static Object invokeExtractMeta(JsonObject params) throws Exception {
        var m = McpProtocolHandler.class.getDeclaredMethod("extractMeta", JsonObject.class);
        m.setAccessible(true);
        return m.invoke(null, params);
    }

    private static String invokeGetOptionalMetaString(JsonObject meta, String key) throws Exception {
        var m = McpProtocolHandler.class.getDeclaredMethod("getOptionalMetaString", JsonObject.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, meta, key);
    }

    private static String invokeTruncateIfNeeded(String text) throws Exception {
        var m = McpProtocolHandler.class.getDeclaredMethod("truncateIfNeeded", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, text);
    }

    private static JsonObject invokeRespondResult(JsonObject request, JsonObject result) throws Exception {
        var m = McpProtocolHandler.class.getDeclaredMethod("respondResult", JsonObject.class, JsonObject.class);
        m.setAccessible(true);
        return (JsonObject) m.invoke(null, request, result);
    }

    private static JsonObject invokeRespondError(JsonObject request, int code, String message) throws Exception {
        var m = McpProtocolHandler.class.getDeclaredMethod("respondError", JsonObject.class, int.class, String.class);
        m.setAccessible(true);
        return (JsonObject) m.invoke(null, request, code, message);
    }

    private static JsonObject invokeMakeErrorResponse(com.google.gson.JsonElement id, int code, String message) throws Exception {
        var m = McpProtocolHandler.class.getDeclaredMethod("makeErrorResponse",
            com.google.gson.JsonElement.class, int.class, String.class);
        m.setAccessible(true);
        return (JsonObject) m.invoke(null, id, code, message);
    }

    private static JsonObject invokeBuildToolResult(JsonObject msg, String text, boolean isError) throws Exception {
        var m = McpProtocolHandler.class.getDeclaredMethod("buildToolResult",
            JsonObject.class, String.class, boolean.class);
        m.setAccessible(true);
        return (JsonObject) m.invoke(null, msg, text, isError);
    }

    private static JsonObject invokeRespondResourceNotFound(JsonObject request, String uri, String message) throws Exception {
        var m = McpProtocolHandler.class.getDeclaredMethod("respondResourceNotFound",
            JsonObject.class, String.class, String.class);
        m.setAccessible(true);
        return (JsonObject) m.invoke(null, request, uri, message);
    }

    @SuppressWarnings("unchecked")
    private static JsonObject invokeBuildPagedResourceResponse(JsonObject msg, String cursorPrefix,
                                                               List<JsonObject> items, String itemKey) throws Exception {
        var m = McpProtocolHandler.class.getDeclaredMethod("buildPagedResourceResponse",
            JsonObject.class, String.class, List.class, String.class);
        m.setAccessible(true);
        return (JsonObject) m.invoke(null, msg, cursorPrefix, items, itemKey);
    }

    private static Object getRecordField(Object obj, String fieldName) throws Exception {
        var m = obj.getClass().getMethod(fieldName);
        return m.invoke(obj);
    }

    private static JsonObject makeJsonRpcRequest(int id) {
        JsonObject req = new JsonObject();
        req.addProperty("jsonrpc", "2.0");
        req.addProperty("id", id);
        return req;
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
