package com.github.catatafishen.agentbridge.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PermissionRequest")
class PermissionRequestTest {

    @Nested
    @DisplayName("constructor")
    class ConstructorTest {

        @Test
        @DisplayName("stores all fields")
        void storesAllFields() {
            PermissionRequest request = new PermissionRequest(
                    "req-1", "tool-read", "Read File", "Reads a file from disk",
                    response -> {}
            );

            assertEquals("req-1", request.reqId);
            assertEquals("tool-read", request.toolId);
            assertEquals("Read File", request.displayName);
            assertEquals("Reads a file from disk", request.description);
        }
    }

    @Nested
    @DisplayName("respond")
    class RespondTest {

        @Test
        @DisplayName("invokes consumer with the exact response")
        void invokesConsumerWithExactResponse() {
            List<PermissionResponse> captured = new ArrayList<>();

            PermissionRequest request = new PermissionRequest(
                    "req-2", "tool-write", "Write File", "Writes a file",
                    captured::add
            );

            request.respond(PermissionResponse.ALLOW_ONCE);

            assertEquals(1, captured.size());
            assertEquals(PermissionResponse.ALLOW_ONCE, captured.get(0));
        }

        @Test
        @DisplayName("invokes consumer with DENY")
        void invokesConsumerWithDeny() {
            List<PermissionResponse> captured = new ArrayList<>();

            PermissionRequest request = new PermissionRequest(
                    "req-3", "tool-exec", "Execute", "Runs a command",
                    captured::add
            );

            request.respond(PermissionResponse.DENY);

            assertEquals(1, captured.size());
            assertEquals(PermissionResponse.DENY, captured.get(0));
        }

        @Test
        @DisplayName("invokes consumer with ALLOW_SESSION")
        void invokesConsumerWithAllowSession() {
            List<PermissionResponse> captured = new ArrayList<>();

            PermissionRequest request = new PermissionRequest(
                    "req-4", "tool-git", "Git Status", "Shows git status",
                    captured::add
            );

            request.respond(PermissionResponse.ALLOW_SESSION);

            assertEquals(1, captured.size());
            assertEquals(PermissionResponse.ALLOW_SESSION, captured.get(0));
        }

        @Test
        @DisplayName("invokes consumer with ALLOW_ALWAYS")
        void invokesConsumerWithAllowAlways() {
            List<PermissionResponse> captured = new ArrayList<>();

            PermissionRequest request = new PermissionRequest(
                    "req-5", "tool-build", "Build", "Builds the project",
                    captured::add
            );

            request.respond(PermissionResponse.ALLOW_ALWAYS);

            assertEquals(1, captured.size());
            assertEquals(PermissionResponse.ALLOW_ALWAYS, captured.get(0));
        }

        @Test
        @DisplayName("multiple respond calls invoke consumer each time")
        void multipleRespondCallsInvokeConsumerEachTime() {
            List<PermissionResponse> captured = new ArrayList<>();

            PermissionRequest request = new PermissionRequest(
                    "req-6", "tool-multi", "Multi", "Multiple calls",
                    captured::add
            );

            request.respond(PermissionResponse.DENY);
            request.respond(PermissionResponse.ALLOW_ONCE);
            request.respond(PermissionResponse.ALLOW_ALWAYS);

            assertEquals(3, captured.size());
            assertEquals(PermissionResponse.DENY, captured.get(0));
            assertEquals(PermissionResponse.ALLOW_ONCE, captured.get(1));
            assertEquals(PermissionResponse.ALLOW_ALWAYS, captured.get(2));
        }
    }
}
