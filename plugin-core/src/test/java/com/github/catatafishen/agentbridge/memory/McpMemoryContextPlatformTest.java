package com.github.catatafishen.agentbridge.memory;

import com.github.catatafishen.agentbridge.memory.store.DrawerDocument;
import com.github.catatafishen.agentbridge.memory.store.MemoryStore;
import com.github.catatafishen.agentbridge.services.McpProtocolHandler;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Instant;
import java.util.Arrays;

/**
 * Platform tests verifying that {@link McpProtocolHandler#handleMessage(String)} injects
 * semantic memory context into the {@code initialize} response when drawers exist.
 *
 * <p>Regression guard: {@code buildMemoryContext()} previously checked
 * {@link MemoryService#isActive()} before {@link MemoryService#getStore()}, which meant
 * the lazy-init flag was still {@code false} on first call and memory context was never
 * injected.
 */
public class McpMemoryContextPlatformTest extends MemoryPlatformTestCase {

    /**
     * When memory is enabled and the store has drawers, the MCP initialize response
     * must include the SEMANTIC MEMORY block and Essential Story layer.
     */
    public void testInitializeIncludesMemoryContextWhenDrawersExist() throws Exception {
        enableMemory();
        memorySettings().setPalaceWing("test-project");

        MemoryStore store = replaceMemoryServiceWithTestComponents();

        DrawerDocument drawer = DrawerDocument.builder()
            .id("test-drawer-1")
            .wing("test-project")
            .room("technical")
            .content("We implemented semantic memory using Lucene for fast retrieval")
            .memoryType(DrawerDocument.TYPE_CONTEXT)
            .filedAt(Instant.now())
            .addedBy(DrawerDocument.ADDED_BY_MINER)
            .build();
        float[] embedding = new float[384];
        Arrays.fill(embedding, 1.0f / (float) Math.sqrt(384));
        store.addDrawer(drawer, embedding);

        McpProtocolHandler handler = new McpProtocolHandler(getProject());

        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", 1);
        request.addProperty("method", "initialize");
        request.add("params", new JsonObject());

        String responseJson = handler.handleMessage(request.toString());
        assertNotNull("initialize must return a response", responseJson);

        JsonObject response = JsonParser.parseString(responseJson).getAsJsonObject();
        assertTrue("response must have result", response.has("result"));
        JsonObject result = response.getAsJsonObject("result");
        assertTrue("result must have instructions", result.has("instructions"));

        String instructions = result.get("instructions").getAsString();
        assertTrue("instructions should contain auto-injected SEMANTIC MEMORY header",
            instructions.contains("SEMANTIC MEMORY (auto-injected at session start)"));
        assertTrue("instructions should contain Essential Story layer header",
            instructions.contains("Essential Story"));
    }

    /**
     * When memory is disabled, the MCP initialize response must NOT include
     * the SEMANTIC MEMORY block.
     */
    public void testInitializeOmitsMemoryContextWhenDisabled() {
        disableMemory();

        McpProtocolHandler handler = new McpProtocolHandler(getProject());

        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", 1);
        request.addProperty("method", "initialize");
        request.add("params", new JsonObject());

        String responseJson = handler.handleMessage(request.toString());
        assertNotNull("initialize must return a response", responseJson);

        JsonObject response = JsonParser.parseString(responseJson).getAsJsonObject();
        String instructions = response.getAsJsonObject("result")
            .get("instructions").getAsString();

        assertFalse("instructions should NOT contain auto-injected SEMANTIC MEMORY when disabled",
            instructions.contains("SEMANTIC MEMORY (auto-injected at session start)"));
    }

    /**
     * When memory is enabled but the store is empty (no drawers), the MCP initialize
     * response must NOT include the SEMANTIC MEMORY block.
     */
    public void testInitializeOmitsMemoryContextWhenStoreIsEmpty() throws Exception {
        enableMemory();
        replaceMemoryServiceWithTestComponents();

        McpProtocolHandler handler = new McpProtocolHandler(getProject());

        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", 1);
        request.addProperty("method", "initialize");
        request.add("params", new JsonObject());

        String responseJson = handler.handleMessage(request.toString());
        assertNotNull("initialize must return a response", responseJson);

        JsonObject response = JsonParser.parseString(responseJson).getAsJsonObject();
        String instructions = response.getAsJsonObject("result")
            .get("instructions").getAsString();

        assertFalse("instructions should NOT contain auto-injected SEMANTIC MEMORY when store is empty",
            instructions.contains("SEMANTIC MEMORY (auto-injected at session start)"));
    }
}
