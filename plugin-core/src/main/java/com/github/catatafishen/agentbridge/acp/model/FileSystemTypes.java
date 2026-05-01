package com.github.catatafishen.agentbridge.acp.model;

/**
 * File-system related requests from agent to client.
 *
 * @see <a href="https://agentclientprotocol.com/protocol/file-system">ACP File System</a>
 */
public final class FileSystemTypes {

    private FileSystemTypes() {}

    public record ReadTextFileRequest(String path) {}

    public record ReadTextFileResponse(String text) {}

    public record WriteTextFileRequest(String path, String text) {}

    public record WriteTextFileResponse() {
        public static final boolean SUCCESS = true;
    }
}
