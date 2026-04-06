package com.github.catatafishen.agentbridge.acp.transport;

/**
 * Standard JSON-RPC 2.0 error codes as defined by the spec, plus ACP-specific additions.
 *
 * @see <a href="https://www.jsonrpc.org/specification#error_object">JSON-RPC 2.0 — Error object</a>
 */
public final class JsonRpcErrorCodes {

    /** Invalid JSON was received by the server. */
    public static final int PARSE_ERROR = -32700;

    /** The JSON sent is not a valid Request object. */
    public static final int INVALID_REQUEST = -32600;

    /** The method does not exist or is not available. */
    public static final int METHOD_NOT_FOUND = -32601;

    /** Invalid method parameters. */
    public static final int INVALID_PARAMS = -32602;

    /** Internal JSON-RPC error. */
    public static final int INTERNAL_ERROR = -32603;

    private JsonRpcErrorCodes() {}
}
