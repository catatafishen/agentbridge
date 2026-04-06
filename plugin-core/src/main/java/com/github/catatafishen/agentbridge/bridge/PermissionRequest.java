package com.github.catatafishen.agentbridge.bridge;

public class PermissionRequest {
    public final String reqId;
    public final String toolId;
    public final String displayName;
    public final String description;
    private final java.util.function.Consumer<PermissionResponse> respondFn;

    public PermissionRequest(String reqId, String toolId, String displayName, String description,
                             java.util.function.Consumer<PermissionResponse> respondFn) {
        this.reqId = reqId;
        this.toolId = toolId;
        this.displayName = displayName;
        this.description = description;
        this.respondFn = respondFn;
    }

    public void respond(PermissionResponse response) {
        respondFn.accept(response);
    }
}
