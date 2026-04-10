package com.github.catatafishen.agentbridge.acp.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JsonRpcException")
class JsonRpcExceptionTest {

    @Nested
    @DisplayName("constructor")
    class ConstructorTest {

        @Test
        @DisplayName("stores code and message")
        void storesCodeAndMessage() {
            JsonRpcException ex = new JsonRpcException(-32603, "Internal error");

            assertEquals(-32603, ex.getCode());
            assertEquals("Internal error", ex.getMessage());
        }
    }

    @Nested
    @DisplayName("getCode")
    class GetCodeTest {

        @Test
        @DisplayName("returns the code passed to constructor")
        void returnsCode() {
            JsonRpcException ex = new JsonRpcException(-32600, "Invalid Request");

            assertEquals(-32600, ex.getCode());
        }
    }

    @Nested
    @DisplayName("getMessage")
    class GetMessageTest {

        @Test
        @DisplayName("returns the message passed to constructor")
        void returnsMessage() {
            JsonRpcException ex = new JsonRpcException(-32700, "Parse error");

            assertEquals("Parse error", ex.getMessage());
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTest {

        @Test
        @DisplayName("formats as JsonRpcException{code=..., message='...'}")
        void formatsCorrectly() {
            JsonRpcException ex = new JsonRpcException(-32603, "Internal error");

            assertEquals("JsonRpcException{code=-32603, message='Internal error'}", ex.toString());
        }
    }

    @Nested
    @DisplayName("inheritance")
    class InheritanceTest {

        @Test
        @DisplayName("extends Exception")
        void extendsException() {
            JsonRpcException ex = new JsonRpcException(-32000, "Server error");

            assertInstanceOf(Exception.class, ex);
        }
    }
}
