package com.github.catatafishen.agentbridge.acp.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("JsonRpcMessage")
class JsonRpcMessageTest {

    @Nested
    @DisplayName("Request")
    class RequestTest {

        @Test
        @DisplayName("convenience constructor sets jsonrpc to 2.0")
        void convenienceConstructorSetsJsonrpc() {
            JsonObject params = new JsonObject();
            params.addProperty("key", "value");

            JsonRpcMessage.Request request = new JsonRpcMessage.Request(1L, "testMethod", params);

            assertEquals("2.0", request.jsonrpc());
            assertEquals(1L, request.id());
            assertEquals("testMethod", request.method());
            assertEquals(params, request.params());
        }

        @Test
        @DisplayName("convenience constructor with null params")
        void convenienceConstructorWithNullParams() {
            JsonRpcMessage.Request request = new JsonRpcMessage.Request(42L, "someMethod", null);

            assertEquals("2.0", request.jsonrpc());
            assertEquals(42L, request.id());
            assertEquals("someMethod", request.method());
            assertNull(request.params());
        }
    }

    @Nested
    @DisplayName("Response")
    class ResponseTest {

        @Test
        @DisplayName("success factory sets result and null error with jsonrpc 2.0")
        void successFactorySetsResultAndNullError() {
            JsonPrimitive result = new JsonPrimitive("ok");

            JsonRpcMessage.Response response = JsonRpcMessage.Response.success(7L, result);

            assertEquals("2.0", response.jsonrpc());
            assertEquals(7L, response.id());
            assertEquals(result, response.result());
            assertNull(response.error());
        }

        @Test
        @DisplayName("error factory sets error and null result with correct code and message")
        void errorFactorySetsErrorAndNullResult() {
            JsonRpcMessage.Response response = JsonRpcMessage.Response.error(9L, -32600, "Invalid Request");

            assertEquals("2.0", response.jsonrpc());
            assertEquals(9L, response.id());
            assertNull(response.result());
            assertNotNull(response.error());
            assertEquals(-32600, response.error().code());
            assertEquals("Invalid Request", response.error().message());
            assertNull(response.error().data());
        }
    }

    @Nested
    @DisplayName("Notification")
    class NotificationTest {

        @Test
        @DisplayName("convenience constructor sets jsonrpc to 2.0")
        void convenienceConstructorSetsJsonrpc() {
            JsonObject params = new JsonObject();
            params.addProperty("event", "update");

            JsonRpcMessage.Notification notification = new JsonRpcMessage.Notification("notify", params);

            assertEquals("2.0", notification.jsonrpc());
            assertEquals("notify", notification.method());
            assertEquals(params, notification.params());
        }

        @Test
        @DisplayName("convenience constructor with null params")
        void convenienceConstructorWithNullParams() {
            JsonRpcMessage.Notification notification = new JsonRpcMessage.Notification("ping", null);

            assertEquals("2.0", notification.jsonrpc());
            assertEquals("ping", notification.method());
            assertNull(notification.params());
        }
    }

    @Nested
    @DisplayName("Error")
    class ErrorTest {

        @Test
        @DisplayName("stores code, message, and data")
        void storesCodeMessageAndData() {
            JsonPrimitive data = new JsonPrimitive("extra info");

            JsonRpcMessage.Error error = new JsonRpcMessage.Error(-32603, "Internal error", data);

            assertEquals(-32603, error.code());
            assertEquals("Internal error", error.message());
            assertEquals(data, error.data());
        }

        @Test
        @DisplayName("stores null data")
        void storesNullData() {
            JsonRpcMessage.Error error = new JsonRpcMessage.Error(-32700, "Parse error", null);

            assertEquals(-32700, error.code());
            assertEquals("Parse error", error.message());
            assertNull(error.data());
        }
    }
}
