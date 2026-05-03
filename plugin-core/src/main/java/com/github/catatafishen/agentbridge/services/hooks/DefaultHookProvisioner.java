package com.github.catatafishen.agentbridge.services.hooks;

import com.github.catatafishen.agentbridge.settings.AgentBridgeStorageSettings;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Provisions default hook configs and scripts from bundled plugin resources.
 *
 * <p>On first project open (when no hook JSON configs exist), copies the bundled
 * defaults to {@code <storage-dir>/hooks/}. Users can then customize the files,
 * and use {@link #restoreDefaults(Project)} to reset them to the bundled originals.</p>
 */
public final class DefaultHookProvisioner {

    private static final Logger LOG = Logger.getInstance(DefaultHookProvisioner.class);
    private static final String RESOURCE_BASE = "/default-hooks/";
    private static final String MANIFEST_RESOURCE = RESOURCE_BASE + "manifest.txt";

    private DefaultHookProvisioner() {
    }

    /**
     * Provisions default hooks if the hooks directory is empty or doesn't exist.
     * Called from {@code HookRegistry.ensureLoaded()} on first load.
     *
     * @return true if defaults were provisioned, false if hooks already exist
     */
    public static boolean provisionIfEmpty(@NotNull Project project) {
        Path hooksDir = resolveHooksDir(project);
        if (hasExistingJsonConfigs(hooksDir)) {
            return false;
        }

        LOG.info("No hook configs found — provisioning defaults to " + hooksDir);
        return copyBundledResources(hooksDir);
    }

    /**
     * Restores all hook configs and scripts to their bundled defaults.
     * Overwrites any user customizations.
     *
     * @return true if defaults were restored successfully
     */
    public static boolean restoreDefaults(@NotNull Project project) {
        Path hooksDir = resolveHooksDir(project);
        LOG.info("Restoring default hooks to " + hooksDir);
        return copyBundledResources(hooksDir);
    }

    private static boolean copyBundledResources(@NotNull Path hooksDir) {
        List<String> entries = readManifest();
        if (entries.isEmpty()) {
            LOG.warn("Default hooks manifest is empty or missing");
            return false;
        }

        try {
            Files.createDirectories(hooksDir.resolve("scripts"));
        } catch (IOException e) {
            LOG.warn("Failed to create hooks directory: " + hooksDir, e);
            return false;
        }

        boolean allCopied = true;
        for (String entry : entries) {
            String resourcePath = RESOURCE_BASE + entry;
            Path targetPath = hooksDir.resolve(entry);

            try (InputStream is = DefaultHookProvisioner.class.getResourceAsStream(resourcePath)) {
                if (is == null) {
                    LOG.warn("Bundled resource not found: " + resourcePath);
                    allCopied = false;
                    continue;
                }
                Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);

                if (entry.endsWith(".sh") && !targetPath.toFile().setExecutable(true)) {
                    LOG.warn("Failed to set executable permission on: " + entry);
                }
            } catch (IOException e) {
                LOG.warn("Failed to copy default hook resource: " + entry, e);
                allCopied = false;
            }
        }

        if (allCopied) {
            LOG.info("Provisioned " + entries.size() + " default hook resources");
        }
        return allCopied;
    }

    private static @NotNull List<String> readManifest() {
        List<String> entries = new ArrayList<>();
        try (InputStream is = DefaultHookProvisioner.class.getResourceAsStream(MANIFEST_RESOURCE)) {
            if (is == null) {
                LOG.warn("Default hooks manifest resource not found: " + MANIFEST_RESOURCE);
                return entries;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        entries.add(line);
                    }
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to read default hooks manifest", e);
        }
        return entries;
    }

    private static boolean hasExistingJsonConfigs(@NotNull Path hooksDir) {
        if (!Files.isDirectory(hooksDir)) return false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(hooksDir,
            p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json"))) {
            return stream.iterator().hasNext();
        } catch (IOException e) {
            LOG.warn("Failed to check hooks directory: " + hooksDir, e);
            return false;
        }
    }

    @NotNull
    private static Path resolveHooksDir(@NotNull Project project) {
        return AgentBridgeStorageSettings.getInstance()
            .getProjectStorageDir(project)
            .resolve("hooks");
    }
}
