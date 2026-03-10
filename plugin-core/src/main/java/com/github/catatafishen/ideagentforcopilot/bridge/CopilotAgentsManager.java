package com.github.catatafishen.ideagentforcopilot.bridge;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Deploys bundled agent definitions to the project's {@code .github/agents/} directory.
 *
 * <p>The plugin bundles agent {@code .md} files (e.g., an IntelliJ-aware explore agent)
 * as classpath resources under {@code /agents/}. On startup, this manager copies them
 * into the project so the Copilot CLI discovers them as custom agents.</p>
 *
 * <p>Each deployed file uses a sentinel comment to detect prior deployment. Files are
 * only written once — manual edits by the user are preserved on subsequent runs.</p>
 *
 * <p>Thread-safe: uses a class-level lock to prevent races between startup hooks.</p>
 */
public final class CopilotAgentsManager {
    private static final Logger LOG = Logger.getInstance(CopilotAgentsManager.class);

    private static final String SENTINEL =
            "<!-- Deployed by AgentBridge — edits are preserved, delete to stop auto-deploy -->";

    private static final String[] BUNDLED_AGENTS = {"ide-explore.md"};

    private static final Object LOCK = new Object();

    private CopilotAgentsManager() {
    }

    /**
     * Ensures all bundled agent definitions exist in the project's {@code .github/agents/} directory.
     * Safe to call multiple times — uses a sentinel to skip already-deployed files.
     *
     * @param projectBasePath the project root directory, or null to skip
     */
    public static void ensureAgents(@Nullable String projectBasePath) {
        if (projectBasePath == null) return;

        synchronized (LOCK) {
            Path agentsDir = Path.of(projectBasePath, ".github", "agents");
            for (String agentFile : BUNDLED_AGENTS) {
                deployAgent(agentsDir, agentFile);
            }
        }
    }

    private static void deployAgent(@NotNull Path agentsDir, @NotNull String filename) {
        Path targetFile = agentsDir.resolve(filename);

        if (Files.isRegularFile(targetFile)) {
            try {
                String existing = Files.readString(targetFile, StandardCharsets.UTF_8);
                if (existing.contains(SENTINEL)) {
                    return; // already deployed by us — don't overwrite user edits
                }
                // File exists but wasn't deployed by us — don't overwrite
                LOG.info("Skipping " + targetFile + " — exists and not managed by plugin");
                return;
            } catch (IOException e) {
                LOG.warn("Failed to read existing agent file: " + targetFile, e);
                return;
            }
        }

        String content = loadBundledAgent(filename);
        if (content == null) return;

        try {
            Files.createDirectories(agentsDir);
            String deployed = SENTINEL + "\n" + content;
            Files.writeString(targetFile, deployed, StandardCharsets.UTF_8);
            LOG.info("Deployed bundled agent: " + targetFile);
        } catch (IOException e) {
            LOG.warn("Failed to deploy agent: " + targetFile, e);
        }
    }

    @Nullable
    private static String loadBundledAgent(@NotNull String filename) {
        String resourcePath = "/agents/" + filename;
        try (InputStream is = CopilotAgentsManager.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                LOG.warn("Bundled agent resource not found: " + resourcePath);
                return null;
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Failed to load bundled agent resource: " + resourcePath, e);
            return null;
        }
    }
}
