package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.services.hooks.HookStageResult;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatWebServerPureMethodsTest {

    @Nested
    class BuildSubjectAlternativeNames {
        @Test
        void includesDefaultEntries() {
            String san = ChatWebServer.buildSubjectAlternativeNames(List.of());
            assertTrue(san.contains("dns:localhost"));
            assertTrue(san.contains("dns:agentbridge.local"));
            assertTrue(san.contains("ip:127.0.0.1"));
            assertTrue(san.contains("ip:127.0.1.1"));
        }

        @Test
        void appendsCustomIps() {
            String san = ChatWebServer.buildSubjectAlternativeNames(List.of("192.168.1.5", "10.0.0.1"));
            assertTrue(san.contains("ip:192.168.1.5"));
            assertTrue(san.contains("ip:10.0.0.1"));
        }

        @Test
        void emptyListProducesOnlyDefaults() {
            String san = ChatWebServer.buildSubjectAlternativeNames(List.of());
            assertEquals("dns:localhost,dns:agentbridge.local,ip:127.0.0.1,ip:127.0.1.1", san);
        }

        @Test
        void singleIpAppended() {
            String san = ChatWebServer.buildSubjectAlternativeNames(List.of("10.0.0.2"));
            assertEquals("dns:localhost,dns:agentbridge.local,ip:127.0.0.1,ip:127.0.1.1,ip:10.0.0.2", san);
        }
    }

    @Nested
    class ParseSubscription {
        @Test
        void parsesValidSubscription() {
            String json = """
                {"endpoint":"https://push.example.com/abc","keys":{"p256dh":"key1","auth":"key2"}}
                """;
            var sub = ChatWebServer.parseSubscription(json);
            assertNotNull(sub);
            assertEquals("https://push.example.com/abc", sub.endpoint());
            assertEquals("key1", sub.p256dh());
            assertEquals("key2", sub.auth());
        }

        static Stream<String> incompleteSubscriptionJson() {
            return Stream.of(
                """
                    {"keys":{"p256dh":"key1","auth":"key2"}}
                    """,
                """
                    {"endpoint":"https://push.example.com/abc"}
                    """,
                """
                    {"endpoint":"https://push.example.com/abc","keys":{"p256dh":"key1"}}
                    """,
                """
                    {"endpoint":"https://push.example.com/abc","keys":{"auth":"key2"}}
                    """
            );
        }

        @ParameterizedTest
        @MethodSource("incompleteSubscriptionJson")
        void returnsNullForIncompleteSubscription(String json) {
            assertNull(ChatWebServer.parseSubscription(json));
        }
    }

    @Nested
    class PathQueryParameter {
        @Test
        void extractsPathFromQuery() {
            assertEquals("src/Main.java", ChatWebServer.pathQueryParameter("path=src/Main.java"));
        }

        @Test
        void returnsNullForNullQuery() {
            assertNull(ChatWebServer.pathQueryParameter(null));
        }

        @Test
        void returnsNullWhenNoPathParam() {
            assertNull(ChatWebServer.pathQueryParameter("foo=bar&baz=1"));
        }

        @Test
        void extractsPathAmongOtherParams() {
            assertEquals("file.txt", ChatWebServer.pathQueryParameter("foo=1&path=file.txt&bar=2"));
        }

        @Test
        void decodesUrlEncodedPath() {
            assertEquals("dir/file name.txt", ChatWebServer.pathQueryParameter("path=dir%2Ffile+name.txt"));
        }
    }

    @Nested
    class LiveEntryToJson {
        @Test
        void serializesBasicEntry() {
            var entry = new LiveToolCallEntry(
                1L, "read_file", "Read file.txt", "{\"path\":\"file.txt\"}", null,
                "file content", Instant.parse("2024-01-01T00:00:00Z"),
                100L, true, "file", false, Collections.emptyList(), false);

            JsonObject json = ChatWebServer.liveEntryToJson(entry);

            assertEquals(1L, json.get("id").getAsLong());
            assertEquals("Read file.txt", json.get("title").getAsString());
            assertEquals("read_file", json.get("toolName").getAsString());
            assertEquals("success", json.get("status").getAsString());
            assertEquals("file", json.get("kind").getAsString());
            assertEquals(100L, json.get("durationMs").getAsLong());
            assertFalse(json.get("hasHooks").getAsBoolean());
            assertFalse(json.has("hookStages"));
        }

        @Test
        void setsErrorStatusOnFailure() {
            var entry = new LiveToolCallEntry(
                2L, "write_file", "Write", "{}", null,
                "Error: failed", Instant.parse("2024-01-01T00:00:00Z"),
                50L, false, null, false, Collections.emptyList(), false);

            JsonObject json = ChatWebServer.liveEntryToJson(entry);
            assertEquals("error", json.get("status").getAsString());
            assertFalse(json.has("kind"));
        }

        @Test
        void setsRunningStatusWhenSuccessIsNull() {
            var entry = new LiveToolCallEntry(
                3L, "git_status", "Git status", "{}", null,
                "", Instant.parse("2024-01-01T00:00:00Z"),
                0L, null, null, false, Collections.emptyList(), false);

            JsonObject json = ChatWebServer.liveEntryToJson(entry);
            assertEquals("running", json.get("status").getAsString());
        }

        @Test
        void serializesHookStages() {
            var stage = new HookStageResult(
                "pre", "validate.sh", "success", 25L, "all checks passed");
            var entry = new LiveToolCallEntry(
                4L, "edit_text", "Edit", "{}", null,
                "done", Instant.parse("2024-01-01T00:00:00Z"),
                200L, true, null, true, List.of(stage), false);

            JsonObject json = ChatWebServer.liveEntryToJson(entry);
            assertTrue(json.has("hookStages"));
            var stages = json.getAsJsonArray("hookStages");
            assertEquals(1, stages.size());
            var s = stages.get(0).getAsJsonObject();
            assertEquals("pre", s.get("trigger").getAsString());
            assertEquals("validate.sh", s.get("scriptName").getAsString());
            assertEquals("success", s.get("outcome").getAsString());
            assertEquals(25L, s.get("durationMs").getAsLong());
            assertEquals("all checks passed", s.get("detail").getAsString());
        }

        @Test
        void omitsHookStageDetailWhenNull() {
            var stage = new HookStageResult(
                "success", "log.sh", "success", 10L, null);
            var entry = new LiveToolCallEntry(
                5L, "run_command", "Run", "{}", null,
                "ok", Instant.parse("2024-01-01T00:00:00Z"),
                50L, true, null, true, List.of(stage), false);

            JsonObject json = ChatWebServer.liveEntryToJson(entry);
            var s = json.getAsJsonArray("hookStages").get(0).getAsJsonObject();
            assertFalse(s.has("detail"));
        }
    }
}
