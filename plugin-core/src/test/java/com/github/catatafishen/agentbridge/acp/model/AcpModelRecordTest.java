package com.github.catatafishen.agentbridge.acp.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ACP Model Records")
class AcpModelRecordTest {

    // ── ContentBlock ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ContentBlock")
    class ContentBlockTest {

        @Test
        @DisplayName("Text construction and text() accessor")
        void textConstruction() {
            var block = new ContentBlock.Text("hello world");
            assertEquals("hello world", block.text());
        }

        @Test
        @DisplayName("Thinking construction and thinking() accessor")
        void thinkingConstruction() {
            var block = new ContentBlock.Thinking("reasoning step");
            assertEquals("reasoning step", block.thinking());
        }

        @Test
        @DisplayName("Image construction with data and mimeType")
        void imageConstruction() {
            var block = new ContentBlock.Image("base64data==", "image/png");
            assertEquals("base64data==", block.data());
            assertEquals("image/png", block.mimeType());
        }

        @Test
        @DisplayName("Resource construction with ResourceLink")
        void resourceConstruction() {
            var link = new ContentBlock.ResourceLink(
                    "file:///tmp/test.txt", "test.txt", "text/plain", "contents", null);
            var block = new ContentBlock.Resource(link);

            assertEquals(link, block.resource());
            assertEquals("file:///tmp/test.txt", block.resource().uri());
            assertEquals("test.txt", block.resource().name());
            assertEquals("text/plain", block.resource().mimeType());
            assertEquals("contents", block.resource().text());
            assertNull(block.resource().blob());
        }

        @Test
        @DisplayName("Pattern matching with instanceof for each variant")
        void patternMatching() {
            ContentBlock text = new ContentBlock.Text("t");
            ContentBlock thinking = new ContentBlock.Thinking("th");
            ContentBlock image = new ContentBlock.Image("d", "image/jpeg");
            ContentBlock audio = new ContentBlock.Audio("a", "audio/wav");
            ContentBlock resource = new ContentBlock.Resource(
                    new ContentBlock.ResourceLink("uri", null, null, null, null));

            assertInstanceOf(ContentBlock.Text.class, text);
            assertInstanceOf(ContentBlock.Thinking.class, thinking);
            assertInstanceOf(ContentBlock.Image.class, image);
            assertInstanceOf(ContentBlock.Audio.class, audio);
            assertInstanceOf(ContentBlock.Resource.class, resource);

            // cross-checks
            assertFalse(text instanceof ContentBlock.Thinking);
            assertFalse(image instanceof ContentBlock.Text);
        }
    }

    // ── NewSessionRequest.McpServerConfig ────────────────────────────────────

    @Nested
    @DisplayName("NewSessionRequest.McpServerConfig")
    class McpServerConfigTest {

        @Test
        @DisplayName("stdio() factory sets transport to 'stdio', url is null")
        void stdioFactory() {
            var env = Map.of("PATH", "/usr/bin");
            var cfg = NewSessionRequest.McpServerConfig.stdio(
                    "my-server", List.of("node", "index.js"), env);

            assertEquals("my-server", cfg.name());
            assertEquals("stdio", cfg.transport());
            assertEquals(List.of("node", "index.js"), cfg.command());
            assertNull(cfg.url());
            assertEquals(env, cfg.env());
        }

        @Test
        @DisplayName("http() factory sets transport to 'http', command and env are null")
        void httpFactory() {
            var cfg = NewSessionRequest.McpServerConfig.http(
                    "remote-server", "https://example.com/mcp");

            assertEquals("remote-server", cfg.name());
            assertEquals("http", cfg.transport());
            assertNull(cfg.command());
            assertEquals("https://example.com/mcp", cfg.url());
            assertNull(cfg.env());
        }

        @Test
        @DisplayName("sse() factory sets transport to 'sse', command and env are null")
        void sseFactory() {
            var cfg = NewSessionRequest.McpServerConfig.sse(
                    "sse-server", "https://example.com/sse");

            assertEquals("sse-server", cfg.name());
            assertEquals("sse", cfg.transport());
            assertNull(cfg.command());
            assertEquals("https://example.com/sse", cfg.url());
            assertNull(cfg.env());
        }
    }

    // ── NewSessionResponse ───────────────────────────────────────────────────

    @Nested
    @DisplayName("NewSessionResponse")
    class NewSessionResponseTest {

        @Test
        @DisplayName("Full construction with all fields")
        void fullConstruction() {
            var mode = new NewSessionResponse.AvailableMode("code", "Code", "Coding mode");
            var commandInput = new NewSessionResponse.AvailableCommandInput("text", "Enter...");
            var command = new NewSessionResponse.AvailableCommand("run", "Run code", commandInput);
            var optionValue = new NewSessionResponse.SessionConfigOptionValue("v1", "Value 1");
            var configOption = new NewSessionResponse.SessionConfigOption(
                    "opt1", "Option 1", "desc", List.of(optionValue), "v1");

            var response = new NewSessionResponse(
                    "sess-123", "gpt-4", "code",
                    List.of(new Model("gpt-4", "GPT-4", null, null)),
                    List.of(mode), List.of(command), List.of(configOption));

            assertEquals("sess-123", response.sessionId());
            assertEquals("gpt-4", response.currentModelId());
            assertEquals("code", response.currentModeId());
            assertNotNull(response.models());
            assertEquals(1, response.models().size());
            assertEquals("gpt-4", response.models().get(0).id());
        }

        @Test
        @DisplayName("AvailableMode stores slug, name, and nullable description")
        void availableModeFields() {
            var mode = new NewSessionResponse.AvailableMode("agent", "Agent", null);
            assertEquals("agent", mode.slug());
            assertEquals("Agent", mode.name());
            assertNull(mode.description());
        }

        @Test
        @DisplayName("AvailableCommand stores name, description, and nullable input")
        void availableCommandFields() {
            var command = new NewSessionResponse.AvailableCommand("help", "Show help", null);
            assertEquals("help", command.name());
            assertEquals("Show help", command.description());
            assertNull(command.input());
        }

        @Test
        @DisplayName("SessionConfigOption stores all fields with selectedValueId")
        void sessionConfigOptionFields() {
            var val = new NewSessionResponse.SessionConfigOptionValue("dark", "Dark");
            var option = new NewSessionResponse.SessionConfigOption(
                    "theme", "Theme", "Color theme", List.of(val), "dark");

            assertEquals("theme", option.id());
            assertEquals("Theme", option.label());
            assertEquals("Color theme", option.description());
            assertEquals(1, option.values().size());
            assertEquals("dark", option.selectedValueId());
            assertEquals("Dark", option.values().get(0).label());
        }
    }

    // ── InitializeResponse ───────────────────────────────────────────────────

    @Nested
    @DisplayName("InitializeResponse")
    class InitializeResponseTest {

        @Test
        @DisplayName("Construction with nullable capabilities")
        void constructionWithNullCapabilities() {
            var info = new InitializeResponse.AgentInfo("agent", "Agent Title", "1.0");
            var caps = new InitializeResponse.AgentCapabilities(null, null, null, null);

            var response = new InitializeResponse(1, info, caps, null);

            assertEquals(1, response.protocolVersion());
            assertNotNull(response.agentInfo());
            assertNotNull(response.agentCapabilities());
            assertNull(response.authMethods());
            assertNull(caps.loadSession());
            assertNull(caps.mcpCapabilities());
            assertNull(caps.promptCapabilities());
            assertNull(caps.sessionCapabilities());
        }

        @Test
        @DisplayName("AgentInfo stores name, title, and version")
        void agentInfoFields() {
            var info = new InitializeResponse.AgentInfo("claude", "Claude", "3.5");
            assertEquals("claude", info.name());
            assertEquals("Claude", info.title());
            assertEquals("3.5", info.version());
        }

        @Test
        @DisplayName("McpCapabilities stores http and sse flags")
        void mcpCapabilitiesFields() {
            var mcp = new InitializeResponse.McpCapabilities(true, false);
            assertEquals(true, mcp.http());
            assertEquals(false, mcp.sse());
        }

        @Test
        @DisplayName("PromptCapabilities stores image, audio, and embeddedContext flags")
        void promptCapabilitiesFields() {
            var prompt = new InitializeResponse.PromptCapabilities(true, false, true);
            assertEquals(true, prompt.image());
            assertEquals(false, prompt.audio());
            assertEquals(true, prompt.embeddedContext());
        }
    }

    // ── PromptResponse ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("PromptResponse")
    class PromptResponseTest {

        @Test
        @DisplayName("Construction with TurnUsage")
        void constructionWithUsage() {
            var usage = new PromptResponse.TurnUsage(100L, 200L, 0.05);
            var response = new PromptResponse("end_turn", usage);

            assertEquals("end_turn", response.stopReason());
            assertNotNull(response.usage());
            assertEquals(100L, response.usage().inputTokens());
            assertEquals(200L, response.usage().outputTokens());
            assertEquals(0.05, response.usage().costUsd());
        }

        @Test
        @DisplayName("Null usage field is allowed")
        void nullUsage() {
            var response = new PromptResponse("max_tokens", null);
            assertEquals("max_tokens", response.stopReason());
            assertNull(response.usage());
        }

        @Test
        @DisplayName("TurnUsage with all-null fields")
        void turnUsageAllNull() {
            var usage = new PromptResponse.TurnUsage(null, null, null);
            assertNull(usage.inputTokens());
            assertNull(usage.outputTokens());
            assertNull(usage.costUsd());
        }
    }

    // ── RequestPermissionRequest ─────────────────────────────────────────────

    @Nested
    @DisplayName("RequestPermissionRequest")
    class RequestPermissionRequestTest {

        @Test
        @DisplayName("ProtocolToolCall construction with all fields")
        void protocolToolCallConstruction() {
            var toolCall = new RequestPermissionRequest.ProtocolToolCall(
                    "tc-1", "Read File", SessionUpdate.ToolKind.READ, "{\"path\":\"/tmp\"}");

            assertEquals("tc-1", toolCall.toolCallId());
            assertEquals("Read File", toolCall.title());
            assertEquals(SessionUpdate.ToolKind.READ, toolCall.kind());
            assertEquals("{\"path\":\"/tmp\"}", toolCall.arguments());
        }

        @Test
        @DisplayName("ProtocolToolCall with nullable kind and arguments")
        void protocolToolCallNullableFields() {
            var toolCall = new RequestPermissionRequest.ProtocolToolCall(
                    "tc-2", "Custom Tool", null, null);

            assertNull(toolCall.kind());
            assertNull(toolCall.arguments());
        }

        @Test
        @DisplayName("PermissionOption construction")
        void permissionOptionConstruction() {
            var option = new RequestPermissionRequest.PermissionOption(
                    "opt-1", "allow_once", "Allow this tool call");

            assertEquals("opt-1", option.optionId());
            assertEquals("allow_once", option.kind());
            assertEquals("Allow this tool call", option.message());
        }

        @Test
        @DisplayName("PermissionOption with null message")
        void permissionOptionNullMessage() {
            var option = new RequestPermissionRequest.PermissionOption(
                    "opt-2", "deny", null);
            assertNull(option.message());
        }

        @Test
        @DisplayName("Full request construction with toolCall and options")
        void fullRequestConstruction() {
            var toolCall = new RequestPermissionRequest.ProtocolToolCall(
                    "tc-3", "Execute", SessionUpdate.ToolKind.EXECUTE, null);
            var options = List.of(
                    new RequestPermissionRequest.PermissionOption("allow", "allow_once", null),
                    new RequestPermissionRequest.PermissionOption("deny", "deny", null));
            var request = new RequestPermissionRequest(toolCall, options);

            assertEquals(toolCall, request.toolCall());
            assertEquals(2, request.options().size());
        }
    }

    // ── RequestPermissionResponse ────────────────────────────────────────────

    @Nested
    @DisplayName("RequestPermissionResponse")
    class RequestPermissionResponseTest {

        @Test
        @DisplayName("PermissionOutcome.Selected stores optionId")
        void selectedOutcome() {
            var outcome = new RequestPermissionResponse.PermissionOutcome.Selected("opt-1");
            assertEquals("opt-1", outcome.optionId());
            assertInstanceOf(RequestPermissionResponse.PermissionOutcome.Selected.class, outcome);
        }

        @Test
        @DisplayName("PermissionOutcome.Cancelled is a valid outcome")
        void cancelledOutcome() {
            var outcome = new RequestPermissionResponse.PermissionOutcome.Cancelled();
            assertInstanceOf(RequestPermissionResponse.PermissionOutcome.Cancelled.class, outcome);
            assertInstanceOf(RequestPermissionResponse.PermissionOutcome.class, outcome);
        }

        @Test
        @DisplayName("Response wraps Selected outcome")
        void responseWithSelectedOutcome() {
            var outcome = new RequestPermissionResponse.PermissionOutcome.Selected("allow-all");
            var response = new RequestPermissionResponse(outcome);

            assertInstanceOf(RequestPermissionResponse.PermissionOutcome.Selected.class, response.outcome());
            var selected = (RequestPermissionResponse.PermissionOutcome.Selected) response.outcome();
            assertEquals("allow-all", selected.optionId());
        }

        @Test
        @DisplayName("Response wraps Cancelled outcome")
        void responseWithCancelledOutcome() {
            var response = new RequestPermissionResponse(
                    new RequestPermissionResponse.PermissionOutcome.Cancelled());

            assertInstanceOf(RequestPermissionResponse.PermissionOutcome.Cancelled.class, response.outcome());
        }
    }
}
