package com.github.catatafishen.agentbridge.acp.transport;

/**
 * Exception wrapping a JSON-RPC error response.
 */
public class JsonRpcException extends Exception {

    private final int code;

    public JsonRpcException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    @Override
    public String toString() {
        return "JsonRpcException{code=" + code + ", message='" + getMessage() + "'}";
    }
}
