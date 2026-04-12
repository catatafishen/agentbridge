package com.github.catatafishen.agentbridge.custommcp;

import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CustomMcpToolProxy} — verifies ID namespacing, description
 * composition, schema pass-through, and execution delegation.
 */
class CustomMcpToolProxyTest {

    private static final String SERVER_PREFIX = "myServer";
    private static final String TOOL_NAME = "search_files";
    private static final String TOOL_DESC = "Search project files by pattern";
    private static final String INSTRUCTIONS = "Only search within the src/ directory";

    private static final McpToolCaller NOOP_CALLER = (name, args) -> "";

    // ── ID composition ─────────────────────────────────────

    @Test
    void id_isNamespacedWithServerPrefix() {
        var proxy = createProxy(TOOL_DESC, INSTRUCTIONS);
        assertEquals("myServer_search_files", proxy.id());
    }

    @Test
    void id_preservesSpecialCharactersInToolName() {
        var toolInfo = new CustomMcpClient.ToolInfo("read-file.v2", "Read a file", null);
        var proxy = new CustomMcpToolProxy("srv", NOOP_CALLER, toolInfo, "");
        assertEquals("srv_read-file.v2", proxy.id());
    }

    // ── Display name ───────────────────────────────────────

    @Test
    void displayName_isOriginalToolName() {
        var proxy = createProxy(TOOL_DESC, INSTRUCTIONS);
        assertEquals(TOOL_NAME, proxy.displayName());
    }

    // ── Description composition ────────────────────────────

    @Test
    void description_appendsInstructionsToToolDescription() {
        var proxy = createProxy(TOOL_DESC, INSTRUCTIONS);
        assertEquals("Search project files by pattern\n\nOnly search within the src/ directory",
            proxy.description());
    }

    @Test
    void description_usesOnlyToolDescriptionWhenInstructionsBlank() {
        var proxy = createProxy(TOOL_DESC, "");
        assertEquals(TOOL_DESC, proxy.description());
    }

    @Test
    void description_usesOnlyToolDescriptionWhenInstructionsWhitespaceOnly() {
        var proxy = createProxy(TOOL_DESC, "   ");
        assertEquals(TOOL_DESC, proxy.description());
    }

    @Test
    void description_usesOnlyInstructionsWhenToolDescriptionBlank() {
        var proxy = createProxy("", INSTRUCTIONS);
        assertEquals(INSTRUCTIONS, proxy.description());
    }

    @Test
    void description_isEmptyWhenBothBlank() {
        var proxy = createProxy("", "");
        assertEquals("", proxy.description());
    }

    @Test
    void description_escapesXmlInInstructions() {
        var proxy = createProxy(TOOL_DESC, "Use <tool> & \"double\"");
        String desc = proxy.description();
        assertTrue(desc.contains("&lt;tool&gt;"), "Should escape < and >");
        assertTrue(desc.contains("&amp;"), "Should escape &");
        assertTrue(desc.contains("&quot;"), "Should escape double quotes");
    }

    @Test
    void description_doesNotEscapeToolDescriptionFromServer() {
        var proxy = createProxy("Search <files> & more", "");
        assertEquals("Search <files> & more", proxy.description(),
            "Tool description from server should not be escaped");
    }

    // ── Schema pass-through ────────────────────────────────

    @Test
    void inputSchema_returnsToolInfoSchema() {
        var schema = new JsonObject();
        schema.addProperty("type", "object");
        var toolInfo = new CustomMcpClient.ToolInfo(TOOL_NAME, TOOL_DESC, schema);
        var proxy = new CustomMcpToolProxy(SERVER_PREFIX, NOOP_CALLER, toolInfo, "");
        assertSame(schema, proxy.inputSchema());
    }

    @Test
    void inputSchema_returnsNullWhenToolInfoHasNoSchema() {
        var toolInfo = new CustomMcpClient.ToolInfo(TOOL_NAME, TOOL_DESC, null);
        var proxy = new CustomMcpToolProxy(SERVER_PREFIX, NOOP_CALLER, toolInfo, "");
        assertNull(proxy.inputSchema());
    }

    // ── Kind, category, behavior flags ─────────────────────

    @Test
    void kind_isExecute() {
        assertEquals(ToolDefinition.Kind.EXECUTE, createProxy(TOOL_DESC, "").kind());
    }

    @Test
    void category_isCustomMcp() {
        assertEquals(ToolRegistry.Category.CUSTOM_MCP, createProxy(TOOL_DESC, "").category());
    }

    @Test
    void hasExecutionHandler_returnsTrue() {
        assertTrue(createProxy(TOOL_DESC, "").hasExecutionHandler());
    }

    @Test
    void isOpenWorld_returnsTrue() {
        assertTrue(createProxy(TOOL_DESC, "").isOpenWorld());
    }

    // ── Execute delegation ─────────────────────────────────

    @Test
    void execute_delegatesToCallerWithOriginalToolName() {
        var capturedName = new AtomicReference<String>();
        var capturedArgs = new AtomicReference<JsonObject>();
        McpToolCaller caller = (name, args) -> {
            capturedName.set(name);
            capturedArgs.set(args);
            return "tool result";
        };

        var toolInfo = new CustomMcpClient.ToolInfo(TOOL_NAME, TOOL_DESC, null);
        var proxy = new CustomMcpToolProxy(SERVER_PREFIX, caller, toolInfo, "");

        var args = new JsonObject();
        args.addProperty("pattern", "*.java");
        String result = proxy.execute(args);

        assertEquals("tool result", result);
        assertEquals(TOOL_NAME, capturedName.get());
        assertSame(args, capturedArgs.get());
    }

    // ── Helpers ────────────────────────────────────────────

    private CustomMcpToolProxy createProxy(String toolDescription, String serverInstructions) {
        var toolInfo = new CustomMcpClient.ToolInfo(TOOL_NAME, toolDescription, null);
        return new CustomMcpToolProxy(SERVER_PREFIX, NOOP_CALLER, toolInfo, serverInstructions);
    }
}
