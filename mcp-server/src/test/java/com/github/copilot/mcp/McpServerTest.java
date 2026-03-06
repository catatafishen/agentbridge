package com.github.copilot.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class McpServerTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        // Create a small project structure for testing
        Path srcDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(srcDir);

        Files.writeString(srcDir.resolve("UserService.java"),
            """
                package com.example;

                public class UserService {
                    private final UserRepository userRepo;

                    public UserService(UserRepository userRepo) {
                        this.userRepo = userRepo;
                    }

                    public User findById(long id) {
                        return userRepo.findById(id);
                    }

                    public void deleteUser(long id) {
                        userRepo.delete(id);
                    }
                }
                """);

        Files.writeString(srcDir.resolve("UserRepository.java"),
            """
                package com.example;

                public interface UserRepository {
                    User findById(long id);
                    void delete(long id);
                    void save(User user);
                }
                """);

        Files.writeString(srcDir.resolve("User.java"),
            """
                package com.example;

                public class User {
                    private long id;
                    private String name;

                    public long getId() { return id; }
                    public String getName() { return name; }
                }
                """);

        // Set project root for tests
        try {
            var field = McpServer.class.getDeclaredField("projectRoot");
            field.setAccessible(true);
            field.set(null, tempDir.toString());
        } catch (Exception e) {
            fail("Could not set projectRoot: " + e);
        }
    }

    @Test
    void testInitialize() {
        JsonObject request = buildRequest("initialize", new JsonObject());
        JsonObject response = McpServer.handleMessage(request);

        assertNotNull(response);
        assertEquals(1, response.get("id").getAsLong());
        JsonObject result = response.getAsJsonObject("result");
        assertEquals("intellij-code-tools", result.getAsJsonObject("serverInfo").get("name").getAsString());
        assertTrue(result.getAsJsonObject("capabilities").has("tools"));
    }

    @Test
    void testToolsList() {
        JsonObject request = buildRequest("tools/list", new JsonObject());
        JsonObject response = McpServer.handleMessage(request);

        assertNotNull(response);
        JsonArray tools = response.getAsJsonObject("result").getAsJsonArray("tools");
        // Tool count is dynamic (PSI bridge filters unavailable tools at runtime).
        // In tests, the bridge is unavailable so all tools are advertised.
        assertFalse(tools.isEmpty(), "Should have at least one tool");
        assertTrue(tools.size() > 50, "Should have a large set of tools, got: " + tools.size());

        // Verify tool names
        var toolNames = new ArrayList<String>();
        tools.forEach(t -> toolNames.add(t.getAsJsonObject().get("name").getAsString()));
        assertTrue(toolNames.contains("search_symbols"));
        assertTrue(toolNames.contains("get_file_outline"));
        assertTrue(toolNames.contains("get_class_outline"));
        assertTrue(toolNames.contains("find_references"));
        assertTrue(toolNames.contains("list_project_files"));
        // Git tools
        assertTrue(toolNames.contains("git_status"));
        assertTrue(toolNames.contains("git_diff"));
        assertTrue(toolNames.contains("git_log"));
        assertTrue(toolNames.contains("git_blame"));
        assertTrue(toolNames.contains("git_commit"));
        assertTrue(toolNames.contains("git_stage"));
        assertTrue(toolNames.contains("git_unstage"));
        assertTrue(toolNames.contains("git_branch"));
        assertTrue(toolNames.contains("git_stash"));
        assertTrue(toolNames.contains("git_show"));
        assertTrue(toolNames.contains("git_push"));
        assertTrue(toolNames.contains("git_remote"));
        assertTrue(toolNames.contains("git_fetch"));
        assertTrue(toolNames.contains("git_pull"));
        assertTrue(toolNames.contains("git_merge"));
        assertTrue(toolNames.contains("git_rebase"));
        assertTrue(toolNames.contains("git_cherry_pick"));
        assertTrue(toolNames.contains("git_tag"));
        assertTrue(toolNames.contains("git_reset"));
        // Refactoring & code modification tools
        assertTrue(toolNames.contains("apply_quickfix"));
        assertTrue(toolNames.contains("refactor"));
        assertTrue(toolNames.contains("go_to_declaration"));
        assertTrue(toolNames.contains("get_type_hierarchy"));
        assertTrue(toolNames.contains("create_file"));
        assertTrue(toolNames.contains("delete_file"));
        assertTrue(toolNames.contains("build_project"));
    }

    @Test
    void testSearchSymbolsFindsClass() throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("query", "UserService");
        String result = McpServer.searchSymbols(args);

        assertTrue(result.contains("UserService"), "Should find UserService class");
        assertTrue(result.contains("[class]"), "Should identify as class");
    }

    @Test
    void testSearchSymbolsFindsInterface() throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("query", "UserRepository");
        String result = McpServer.searchSymbols(args);

        assertTrue(result.contains("UserRepository"), "Should find UserRepository");
        assertTrue(result.contains("[interface]"), "Should identify as interface");
    }

    @Test
    void testSearchSymbolsWithTypeFilter() throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("query", "User");
        args.addProperty("type", "class");
        String result = McpServer.searchSymbols(args);

        assertTrue(result.contains("[class]"), "Should only show classes");
        assertFalse(result.contains("[interface]"), "Should not show interfaces");
    }

    @Test
    void testGetFileOutline() throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("path", tempDir.resolve("src/main/java/com/example/UserService.java").toString());
        String result = McpServer.getFileOutline(args);

        assertTrue(result.contains("class UserService"), "Should show class");
        assertTrue(result.contains("Outline of"), "Should have outline header");
    }

    @Test
    void testGetFileOutlineNotFound() throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("path", "nonexistent.java");
        String result = McpServer.getFileOutline(args);

        assertTrue(result.contains("File not found"), "Should report file not found");
    }

    @Test
    void testFindReferences() throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("symbol", "userRepo");
        String result = McpServer.findReferences(args);

        assertTrue(result.contains("references found"), "Should find references");
        assertTrue(result.contains("UserService.java"), "Should find in UserService");
    }

    @Test
    void testFindReferencesWithFilePattern() throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("symbol", "findById");
        args.addProperty("file_pattern", "*.java");
        String result = McpServer.findReferences(args);

        assertTrue(result.contains("references found"), "Should find references");
    }

    @Test
    void testListProjectFiles() throws IOException {
        JsonObject args = new JsonObject();
        String result = McpServer.listProjectFiles(args);

        assertTrue(result.contains("files:"), "Should list files");
        assertTrue(result.contains("UserService.java"), "Should include UserService");
        assertTrue(result.contains("[Java]"), "Should identify Java files");
    }

    @Test
    void testListProjectFilesWithPattern() throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("pattern", "*.java");
        String result = McpServer.listProjectFiles(args);

        assertTrue(result.contains("[Java]"), "Should only show Java files");
        assertEquals(3, result.lines().filter(l -> l.contains("[Java]")).count(), "Should find 3 Java files");
    }

    @Test
    void testUnknownMethodReturnsError() {
        JsonObject request = buildRequest("unknown/method", new JsonObject());
        JsonObject response = McpServer.handleMessage(request);

        assertNotNull(response);
        assertTrue(response.has("error"), "Should return error for unknown method");
        assertEquals(-32601, response.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void testNotificationReturnsNull() {
        JsonObject msg = new JsonObject();
        msg.addProperty("jsonrpc", "2.0");
        msg.addProperty("method", "initialized");
        JsonObject response = McpServer.handleMessage(msg);

        assertNull(response, "Notification should not produce a response");
    }

    @Test
    void testPingReturnsEmptyResult() {
        JsonObject request = buildRequest("ping", new JsonObject());
        JsonObject response = McpServer.handleMessage(request);

        assertNotNull(response);
        assertNotNull(response.getAsJsonObject("result"));
    }

    @Test
    void testPathTraversalBlocked() {
        JsonObject args = new JsonObject();
        args.addProperty("path", "../../../etc/passwd");
        assertThrows(IOException.class, () -> McpServer.getFileOutline(args),
            "Should throw IOException for path traversal");
    }

    @Test
    void testAbsolutePathOutsideProjectBlocked() {
        JsonObject args = new JsonObject();
        args.addProperty("path", "/etc/passwd");
        assertThrows(IOException.class, () -> McpServer.getFileOutline(args),
            "Should throw IOException for absolute paths outside project");
    }

    private static JsonObject buildRequest(String method, JsonObject params) {
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", 1);
        request.addProperty("method", method);
        request.add("params", params);
        return request;
    }

    // ==================== New Tool Schema Tests ====================

    @Test
    void testApplyQuickfixToolSchema() {
        JsonObject tool = findToolByName("apply_quickfix");
        assertNotNull(tool, "apply_quickfix tool should exist");

        var schema = tool.getAsJsonObject("inputSchema");
        var props = schema.getAsJsonObject("properties");
        assertTrue(props.has("file"), "Should have 'file' parameter");
        assertTrue(props.has("line"), "Should have 'line' parameter");
        assertTrue(props.has("inspection_id"), "Should have 'inspection_id' parameter");
        assertTrue(props.has("fix_index"), "Should have 'fix_index' parameter");

        var required = schema.getAsJsonArray("required");
        assertTrue(required.toString().contains("file"));
        assertTrue(required.toString().contains("line"));
        assertTrue(required.toString().contains("inspection_id"));
    }

    @Test
    void testRefactorToolSchema() {
        JsonObject tool = findToolByName("refactor");
        assertNotNull(tool, "refactor tool should exist");

        var schema = tool.getAsJsonObject("inputSchema");
        var props = schema.getAsJsonObject("properties");
        assertTrue(props.has("operation"), "Should have 'operation' parameter");
        assertTrue(props.has("file"), "Should have 'file' parameter");
        assertTrue(props.has("symbol"), "Should have 'symbol' parameter");
        assertTrue(props.has("new_name"), "Should have 'new_name' parameter");
        assertTrue(props.has("line"), "Should have 'line' parameter");

        // Description should mention supported operations
        String desc = tool.get("description").getAsString();
        assertTrue(desc.contains("rename"), "Description should mention 'rename'");
        assertTrue(desc.contains("safe_delete"), "Description should mention 'safe_delete'");
    }

    @Test
    void testGoToDeclarationToolSchema() {
        JsonObject tool = findToolByName("go_to_declaration");
        assertNotNull(tool, "go_to_declaration tool should exist");

        var schema = tool.getAsJsonObject("inputSchema");
        var props = schema.getAsJsonObject("properties");
        assertTrue(props.has("file"), "Should have 'file' parameter");
        assertTrue(props.has("symbol"), "Should have 'symbol' parameter");
        assertTrue(props.has("line"), "Should have 'line' parameter");

        var required = schema.getAsJsonArray("required");
        assertEquals(3, required.size(), "All three parameters should be required");
    }

    @Test
    void testGetTypeHierarchyToolSchema() {
        JsonObject tool = findToolByName("get_type_hierarchy");
        assertNotNull(tool, "get_type_hierarchy tool should exist");

        var schema = tool.getAsJsonObject("inputSchema");
        var props = schema.getAsJsonObject("properties");
        assertTrue(props.has("symbol"), "Should have 'symbol' parameter");
        assertTrue(props.has("direction"), "Should have 'direction' parameter");

        // Description should mention hierarchy directions
        String desc = tool.get("description").getAsString();
        assertTrue(desc.contains("superclass") || desc.contains("supertypes"),
            "Description should mention supertypes/superclasses");
        assertTrue(desc.contains("subclass") || desc.contains("subtypes") || desc.contains("implementations"),
            "Description should mention subtypes/implementations");
    }

    @Test
    void testCreateFileToolSchema() {
        JsonObject tool = findToolByName("create_file");
        assertNotNull(tool, "create_file tool should exist");

        var schema = tool.getAsJsonObject("inputSchema");
        var props = schema.getAsJsonObject("properties");
        assertTrue(props.has("path"), "Should have 'path' parameter");
        assertTrue(props.has("content"), "Should have 'content' parameter");

        var required = schema.getAsJsonArray("required");
        assertEquals(2, required.size(), "Both path and content should be required");
    }

    @Test
    void testDeleteFileToolSchema() {
        JsonObject tool = findToolByName("delete_file");
        assertNotNull(tool, "delete_file tool should exist");

        var schema = tool.getAsJsonObject("inputSchema");
        var props = schema.getAsJsonObject("properties");
        assertTrue(props.has("path"), "Should have 'path' parameter");

        var required = schema.getAsJsonArray("required");
        assertEquals(1, required.size(), "path should be required");
    }

    @Test
    void testBuildProjectToolSchema() {
        JsonObject tool = findToolByName("build_project");
        assertNotNull(tool, "build_project tool should exist");

        var schema = tool.getAsJsonObject("inputSchema");
        var props = schema.getAsJsonObject("properties");
        assertTrue(props.has("module"), "Should have 'module' parameter");

        var required = schema.getAsJsonArray("required");
        assertEquals(0, required.size(), "No required parameters — module is optional");

        // Description should mention incremental compilation
        String desc = tool.get("description").getAsString();
        assertTrue(desc.contains("incremental"), "Description should mention 'incremental'");
    }

    @Test
    void testRunCommandDescriptionWarnsAgainstTests() {
        JsonObject tool = findToolByName("run_command");
        assertNotNull(tool, "run_command tool should exist");

        String desc = tool.get("description").getAsString();
        assertTrue(desc.contains("run_tests"), "run_command description should redirect to run_tests");
        assertTrue(desc.contains("search_symbols"), "run_command description should redirect to search_symbols");
    }

    /**
     * CAPI schema validation: every tool's inputSchema must be a valid JSON Schema
     * that CAPI will accept. Validates recursively — catches "object schema missing
     * properties", "array schema missing items", etc. at any nesting depth.
     */
    @Test
    void testAllToolSchemasAreValid() {
        JsonObject request = buildRequest("tools/list", new JsonObject());
        JsonObject response = McpServer.handleMessage(request);
        assertNotNull(response, "tools/list should return a response");
        JsonArray tools = response.getAsJsonObject("result").getAsJsonArray("tools");
        assertNotNull(tools, "tools/list result should contain 'tools' array");
        assertTrue(tools.size() > 50, "Expected 50+ tools, got " + tools.size());

        var errors = new ArrayList<String>();
        for (var toolElement : tools) {
            JsonObject tool = toolElement.getAsJsonObject();
            String toolName = tool.get("name").getAsString();

            if (!tool.has("inputSchema")) {
                errors.add(toolName + ": missing inputSchema");
                continue;
            }

            validateJsonSchema(tool.getAsJsonObject("inputSchema"), toolName, "", errors);
        }

        if (!errors.isEmpty()) {
            fail("CAPI schema validation errors:\n  " + String.join("\n  ", errors));
        }
    }

    /**
     * Recursively validates a JSON Schema node for CAPI compliance.
     * Rules: object types must have "properties", array types must have "items",
     * every schema node must have "type". Nested schemas are checked recursively.
     */
    private void validateJsonSchema(JsonObject schema, String toolName, String path, ArrayList<String> errors) {
        String context = toolName + (path.isEmpty() ? "" : "." + path);

        if (!schema.has("type")) {
            errors.add(context + ": missing 'type'");
            return;
        }

        String type = schema.get("type").getAsString();

        if ("object".equals(type)) {
            if (!schema.has("properties")) {
                errors.add(context + ": object schema missing 'properties'");
                return;
            }
            JsonObject properties = schema.getAsJsonObject("properties");
            for (String propName : properties.keySet()) {
                JsonObject propSchema = properties.getAsJsonObject(propName);
                if (propSchema == null) {
                    errors.add(context + "." + propName + ": property schema is null");
                    continue;
                }
                validateJsonSchema(propSchema, toolName, path.isEmpty() ? propName : path + "." + propName, errors);
            }
        } else if ("array".equals(type)) {
            if (!schema.has("items")) {
                errors.add(context + ": array schema missing 'items'");
                return;
            }
            JsonObject items = schema.getAsJsonObject("items");
            if (items != null) {
                validateJsonSchema(items, toolName, path.isEmpty() ? "items" : path + ".items", errors);
            }
        }
    }

    private JsonObject findToolByName(String name) {
        JsonObject request = buildRequest("tools/list", new JsonObject());
        JsonObject response = McpServer.handleMessage(request);
        if (response == null) return null;
        JsonObject result = response.getAsJsonObject("result");
        if (result == null) return null;
        JsonArray tools = result.getAsJsonArray("tools");
        if (tools == null) return null;
        for (var t : tools) {
            if (name.equals(t.getAsJsonObject().get("name").getAsString())) {
                return t.getAsJsonObject();
            }
        }
        return null;
    }
}
