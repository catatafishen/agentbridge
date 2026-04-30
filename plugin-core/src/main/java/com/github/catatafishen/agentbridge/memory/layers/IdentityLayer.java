package com.github.catatafishen.agentbridge.memory.layers;

import com.github.catatafishen.agentbridge.settings.AgentBridgeStorageSettings;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * L0 — Identity layer. Reads static identity facts from {@code identity.txt}
 * in the project's configured semantic-memory directory.
 *
 * <p>This file is user-managed and contains free-form identity statements
 * such as "This project is an IntelliJ plugin written in Java 21" or
 * "The team prefers conventional commits".
 *
 * <p><b>Attribution:</b> identity layer concept from MemPalace's layers.py (MIT License).
 */
public final class IdentityLayer implements MemoryStack {

    private static final Logger LOG = Logger.getInstance(IdentityLayer.class);
    private static final String IDENTITY_FILE = "identity.txt";

    private final Path memoryDir;

    public IdentityLayer(@NotNull Project project) {
        this(AgentBridgeStorageSettings.getInstance().getProjectMemoryDir(project));
    }

    /**
     * Package-private constructor for testing — accepts the resolved memory
     * directory directly.
     */
    IdentityLayer(@Nullable Path memoryDir) {
        this.memoryDir = memoryDir;
    }

    @Override
    public @NotNull String layerId() {
        return "L0-identity";
    }

    @Override
    public @NotNull String displayName() {
        return "Identity";
    }

    @Override
    public @NotNull String render(@NotNull String wing, @Nullable String query) {
        Path identityPath = getIdentityPath();
        if (identityPath == null || !Files.exists(identityPath)) {
            return "";
        }
        try {
            String content = Files.readString(identityPath, StandardCharsets.UTF_8).strip();
            if (content.isEmpty()) return "";
            return "## Identity\n\n" + content;
        } catch (IOException e) {
            LOG.warn("Failed to read identity.txt", e);
            return "";
        }
    }

    private @Nullable Path getIdentityPath() {
        if (memoryDir == null) return null;
        return memoryDir.resolve(IDENTITY_FILE);
    }
}
