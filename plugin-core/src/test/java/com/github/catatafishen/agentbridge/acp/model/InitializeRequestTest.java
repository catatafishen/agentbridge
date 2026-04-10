package com.github.catatafishen.agentbridge.acp.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("InitializeRequest and McpServerConfig")
class InitializeRequestTest {

    @Nested
    @DisplayName("ClientCapabilities.standard()")
    class ClientCapabilitiesStandard {

        private final InitializeRequest.ClientCapabilities caps = InitializeRequest.ClientCapabilities.standard();

        @Test
        @DisplayName("fs is not null")
        void fsIsNotNull() {
            assertNotNull(caps.fs());
        }

        @Test
        @DisplayName("fs.readTextFile() is true")
        void fsReadTextFileIsTrue() {
            assertNotNull(caps.fs());
            assertEquals(true, caps.fs().readTextFile());
        }

        @Test
        @DisplayName("fs.writeTextFile() is true")
        void fsWriteTextFileIsTrue() {
            assertNotNull(caps.fs());
            assertEquals(true, caps.fs().writeTextFile());
        }

        @Test
        @DisplayName("terminal() is true")
        void terminalIsTrue() {
            assertEquals(true, caps.terminal());
        }
    }

    @Nested
    @DisplayName("ClientCapabilities.empty()")
    class ClientCapabilitiesEmpty {

        private final InitializeRequest.ClientCapabilities caps = InitializeRequest.ClientCapabilities.empty();

        @Test
        @DisplayName("fs is null")
        void fsIsNull() {
            assertNull(caps.fs());
        }

        @Test
        @DisplayName("terminal is null")
        void terminalIsNull() {
            assertNull(caps.terminal());
        }
    }

    @Nested
    @DisplayName("ClientInfo record")
    class ClientInfoRecord {

        @Test
        @DisplayName("stores name, title, version correctly")
        void storesFieldsCorrectly() {
            var info = new InitializeRequest.ClientInfo("myName", "myTitle", "1.0.0");
            assertAll(
                () -> assertEquals("myName", info.name()),
                () -> assertEquals("myTitle", info.title()),
                () -> assertEquals("1.0.0", info.version())
            );
        }
    }

    @Nested
    @DisplayName("FsCapabilities record")
    class FsCapabilitiesRecord {

        @Test
        @DisplayName("stores readTextFile and writeTextFile")
        void storesFieldsCorrectly() {
            var fs = new InitializeRequest.FsCapabilities(true, false);
            assertAll(
                () -> assertEquals(true, fs.readTextFile()),
                () -> assertEquals(false, fs.writeTextFile())
            );
        }
    }

    @Nested
    @DisplayName("InitializeRequest record")
    class InitializeRequestRecord {

        @Test
        @DisplayName("stores protocolVersion, clientInfo, clientCapabilities")
        void storesFieldsCorrectly() {
            var info = new InitializeRequest.ClientInfo("name", "title", "2.0");
            var caps = InitializeRequest.ClientCapabilities.standard();
            var request = new InitializeRequest(1, info, caps);
            assertAll(
                () -> assertEquals(1, request.protocolVersion()),
                () -> assertSame(info, request.clientInfo()),
                () -> assertSame(caps, request.clientCapabilities())
            );
        }
    }

    @Nested
    @DisplayName("McpServerConfig factories")
    class McpServerConfigFactories {

        @Nested
        @DisplayName("stdio factory")
        class StdioFactory {

            @Test
            @DisplayName("sets transport to 'stdio'")
            void transportIsStdio() {
                var config = NewSessionRequest.McpServerConfig.stdio(
                    "myServer", List.of("node", "index.js"), Map.of("KEY", "VAL"));
                assertEquals("stdio", config.transport());
            }

            @Test
            @DisplayName("stores the provided command list")
            void commandIsProvided() {
                var cmd = List.of("node", "index.js");
                var config = NewSessionRequest.McpServerConfig.stdio("myServer", cmd, null);
                assertEquals(cmd, config.command());
            }

            @Test
            @DisplayName("url is null")
            void urlIsNull() {
                var config = NewSessionRequest.McpServerConfig.stdio(
                    "myServer", List.of("node"), null);
                assertNull(config.url());
            }

            @Test
            @DisplayName("stores the provided env map")
            void envIsProvided() {
                var env = Map.of("A", "1");
                var config = NewSessionRequest.McpServerConfig.stdio("myServer", List.of("cmd"), env);
                assertEquals(env, config.env());
            }

            @Test
            @DisplayName("stores the provided name")
            void nameIsProvided() {
                var config = NewSessionRequest.McpServerConfig.stdio(
                    "myServer", List.of("cmd"), null);
                assertEquals("myServer", config.name());
            }
        }

        @Nested
        @DisplayName("http factory")
        class HttpFactory {

            @Test
            @DisplayName("transport is 'http'")
            void transportIsHttp() {
                var config = NewSessionRequest.McpServerConfig.http("srv", "http://localhost:8080");
                assertEquals("http", config.transport());
            }

            @Test
            @DisplayName("command is null")
            void commandIsNull() {
                var config = NewSessionRequest.McpServerConfig.http("srv", "http://localhost:8080");
                assertNull(config.command());
            }

            @Test
            @DisplayName("url is the provided url")
            void urlIsProvided() {
                var config = NewSessionRequest.McpServerConfig.http("srv", "http://localhost:8080");
                assertEquals("http://localhost:8080", config.url());
            }

            @Test
            @DisplayName("env is null")
            void envIsNull() {
                var config = NewSessionRequest.McpServerConfig.http("srv", "http://localhost:8080");
                assertNull(config.env());
            }
        }

        @Nested
        @DisplayName("sse factory")
        class SseFactory {

            @Test
            @DisplayName("transport is 'sse'")
            void transportIsSse() {
                var config = NewSessionRequest.McpServerConfig.sse("srv", "http://localhost:9090/sse");
                assertEquals("sse", config.transport());
            }

            @Test
            @DisplayName("command is null")
            void commandIsNull() {
                var config = NewSessionRequest.McpServerConfig.sse("srv", "http://localhost:9090/sse");
                assertNull(config.command());
            }

            @Test
            @DisplayName("url is the provided url")
            void urlIsProvided() {
                var config = NewSessionRequest.McpServerConfig.sse("srv", "http://localhost:9090/sse");
                assertEquals("http://localhost:9090/sse", config.url());
            }

            @Test
            @DisplayName("env is null")
            void envIsNull() {
                var config = NewSessionRequest.McpServerConfig.sse("srv", "http://localhost:9090/sse");
                assertNull(config.env());
            }
        }
    }
}
