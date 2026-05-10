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
 * <p>There are two distinct update policies, reflecting two distinct purposes:
 * <ul>
 *   <li><b>Script files</b> ({@code scripts/*.sh}) — internal implementation. Always
 *       overwritten on plugin startup so that bug fixes (e.g. Windows path normalization)
 *       reach all existing users without requiring them to manually restore defaults.</li>
 *   <li><b>JSON config files</b> ({@code *.json}) — user-configurable (which hooks run,
 *       timeouts, env vars, failSilently, etc.). Only provisioned when no JSON files exist
 *       yet, preserving any user customizations made after first install.</li>
 * </ul>
 *
 * <p>This separation means a plugin upgrade will silently patch buggy scripts while
 * keeping user-edited hook configs intact.
 *
 * <p><b>Distribution boundary:</b> Only scripts listed in {@code manifest.txt} are
 * distributed to end users. The manifest references resources from
 * {@code plugin-core/src/main/resources/default-hooks/}, which is the only
 * path compiled into the plugin JAR. Scripts under {@code .agentbridge/hooks/scripts/}
 * at the project root are development-only artifacts — because that directory is
 * <em>not</em> a Gradle source set, it is never included in the plugin ZIP and
 * never copied here. If you add new end-user hooks, they must be placed under
 * {@code plugin-core/src/main/resources/default-hooks/} and listed in the manifest.</p>
 */
public final class DefaultHookProvisioner {

    private static final Logger LOG = Logger.getInstance(DefaultHookProvisioner.class);
    private static final String RESOURCE_BASE = "/default-hooks/";
    private static final String MANIFEST_RESOURCE = RESOURCE_BASE + "manifest.txt";

    private DefaultHookProvisioner() {
    }

    /**
     * Provisions default hooks on plugin startup.
     *
     * <p>Scripts are <em>always</em> overwritten so that bug fixes in plugin updates
     * (e.g. Windows path normalization) automatically reach existing users.
     * JSON configs are only provisioned when none exist yet, preserving user customizations.
     *
     * <p>Called from {@link HookRegistry} on first access after each IDE startup.
     */
    public static void provisionDefaults(@NotNull Project project) {
        Path hooksDir = resolveHooksDir(project);
        ensureScriptsDir(hooksDir);

        List<String> entries = readManifest();
        if (entries.isEmpty()) {
            LOG.warn("Default hooks manifest is empty or missing");
            return;
        }

        boolean hasJsonConfigs = hasExistingJsonConfigs(hooksDir);

        for (String entry : entries) {
            boolean isScript = entry.startsWith("scripts/");
            if (!isScript && hasJsonConfigs) {
                // JSON config exists — skip to preserve user customizations
                continue;
            }
            copyEntry(entry, hooksDir);
        }

        if (!hasJsonConfigs) {
            LOG.info("No hook configs found — provisioned defaults to " + hooksDir);
        }
    }

    /**
     * Restores all hook configs and scripts to their bundled defaults.
     * Overwrites any user customizations — call only when the user explicitly requests a reset.
     *
     * @return true if defaults were restored successfully
     */
    public static boolean restoreDefaults(@NotNull Project project) {
        Path hooksDir = resolveHooksDir(project);
        LOG.info("Restoring default hooks to " + hooksDir);

        ensureScriptsDir(hooksDir);

        List<String> entries = readManifest();
        if (entries.isEmpty()) {
            LOG.warn("Default hooks manifest is empty or missing");
            return false;
        }

        boolean allCopied = true;
        for (String entry : entries) {
            if (!copyEntry(entry, hooksDir)) {
                allCopied = false;
            }
        }

        if (allCopied) {
            LOG.info("Restored " + entries.size() + " default hook resources");
        }
        return allCopied;
    }

    private static boolean copyEntry(@NotNull String entry, @NotNull Path hooksDir) {
        String resourcePath = RESOURCE_BASE + entry;
        Path targetPath = hooksDir.resolve(entry);

        try (InputStream is = DefaultHookProvisioner.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                LOG.warn("Bundled resource not found: " + resourcePath);
                return false;
            }
            Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);

            if (entry.endsWith(".sh") && !targetPath.toFile().setExecutable(true)) {
                LOG.warn("Failed to set executable permission on: " + entry);
            }
            return true;
        } catch (IOException e) {
            LOG.warn("Failed to copy default hook resource: " + entry, e);
            return false;
        }
    }

    private static void ensureScriptsDir(@NotNull Path hooksDir) {
        try {
            Files.createDirectories(hooksDir.resolve("scripts"));
        } catch (IOException e) {
            LOG.warn("Failed to create hooks/scripts directory: " + hooksDir, e);
        }
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
