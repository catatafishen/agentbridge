package com.github.catatafishen.agentbridge.agent.codex;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonRpcTransportTest {

    @Nested
    class ClassifyMessageType {

        @Test
        void response_hasIdAndResult_noMethod() {
            JsonObject msg = new JsonObject();
            msg.addProperty("id", 1);
            msg.add("result", new JsonObject());
            assertEquals(JsonRpcTransport.MessageType.RESPONSE, JsonRpcTransport.classifyMessageType(msg));
        }

        @Test
        void response_hasIdAndError_noMethod() {
            JsonObject msg = new JsonObject();
            msg.addProperty("id", 2);
            msg.add("error", new JsonObject());
            assertEquals(JsonRpcTransport.MessageType.RESPONSE, JsonRpcTransport.classifyMessageType(msg));
        }

        @Test
        void serverRequest_hasIdAndMethod() {
            JsonObject msg = new JsonObject();
            msg.addProperty("id", 3);
            msg.addProperty("method", "item/tool/call");
            assertEquals(JsonRpcTransport.MessageType.SERVER_REQUEST, JsonRpcTransport.classifyMessageType(msg));
        }

        @Test
        void notification_hasMethod_noId() {
            JsonObject msg = new JsonObject();
            msg.addProperty("method", "turn/updated");
            assertEquals(JsonRpcTransport.MessageType.NOTIFICATION, JsonRpcTransport.classifyMessageType(msg));
        }

        @Test
        void notification_hasMethod_nullId() {
            JsonObject msg = new JsonObject();
            msg.add("id", JsonNull.INSTANCE);
            msg.addProperty("method", "turn/updated");
            assertEquals(JsonRpcTransport.MessageType.NOTIFICATION, JsonRpcTransport.classifyMessageType(msg));
        }

        @Test
        void unknown_emptyObject() {
            assertEquals(JsonRpcTransport.MessageType.UNKNOWN, JsonRpcTransport.classifyMessageType(new JsonObject()));
        }
    }

    @Nested
    class ExtractJsonRpcErrorMessage {

        @Test
        void hasMessageField_returnsIt() {
            JsonObject err = new JsonObject();
            err.addProperty("message", "rate limit exceeded");
            assertEquals("rate limit exceeded", JsonRpcTransport.extractJsonRpcErrorMessage(err));
        }

        @Test
        void noMessageField_returnsToString() {
            JsonObject err = new JsonObject();
            err.addProperty("code", -32600);
            assertEquals(err.toString(), JsonRpcTransport.extractJsonRpcErrorMessage(err));
        }
    }

    @Nested
    class IsCodexAuthError {

        @Test
        void nullInput_returnsFalse() {
            assertFalse(JsonRpcTransport.isCodexAuthError(null));
        }

        @Test
        void notAuthenticated_returnsTrue() {
            assertTrue(JsonRpcTransport.isCodexAuthError("Not authenticated with Codex"));
        }

        @Test
        void unauthorized_returnsTrue() {
            assertTrue(JsonRpcTransport.isCodexAuthError("Unauthorized request"));
        }

        @Test
        void unrelatedError_returnsFalse() {
            assertFalse(JsonRpcTransport.isCodexAuthError("Tool 'apply_patch' failed"));
        }
    }
}
