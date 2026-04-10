package com.github.catatafishen.agentbridge.memory.layers;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * L0 — Identity layer. Reads static identity facts from
 * {@code .agent-work/memory/identity.txt} in the project root.
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

    private final Path basePath;

    public IdentityLayer(@NotNull Project project) {
        this(project.getBasePath() != null ? Path.of(project.getBasePath()) : null);
    }

    /**
     * Package-private constructor for testing — accepts a base path directly.
     */
    IdentityLayer(@Nullable Path basePath) {
        this.basePath = basePath;
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
        if (basePath == null) return null;
        return basePath.resolve(".agent-work").resolve("memory").resolve(IDENTITY_FILE);
    }
}
