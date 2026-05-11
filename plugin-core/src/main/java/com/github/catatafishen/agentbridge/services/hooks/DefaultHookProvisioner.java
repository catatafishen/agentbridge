package com.github.catatafishen.agentbridge.services.hooks;

import com.github.catatafishen.agentbridge.settings.AgentBridgeStorageSettings;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
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
 * Provisions the default hook directory structure on plugin startup.
 *
 * <p>There are two distinct update policies, reflecting two distinct purposes:
 * <ul>
 *   <li><b>Script files</b> ({@code scripts/*.sh} and {@code scripts/*.ps1}) — internal
 *       implementation. Always overwritten on plugin startup so that bug fixes
 *       reach all existing users without requiring them to manually restore defaults.</li>
 *   <li><b>JSON config files</b> ({@code *.json}) — user-configurable (which hooks run,
 *       timeouts, env vars, failSilently, etc.). Only provisioned when no JSON files exist
 *       yet, preserving any user customizations made after first install.</li>
 * </ul>
 *
 * <p>This separation means a plugin upgrade will silently patch buggy scripts while
 * keeping user-edited hook configs intact.
 *
 * <p>Script variants:
 * <ul>
 *   <li>{@code scripts/*.sh} — POSIX shell, used on Unix/Mac.</li>
 *   <li>{@code scripts/*.ps1} — PowerShell, used on Windows.</li>
 * </ul>
 * Both variants are always copied so users can read/reference either format.
 * The active JSON configs reference the platform-appropriate extension.</p>
 */
public final class DefaultHookProvisioner {

    private static final Logger LOG = Logger.getInstance(DefaultHookProvisioner.class);
    private static final String RESOURCE_BASE = "/default-hooks/";
    private static final String MANIFEST_RESOURCE = RESOURCE_BASE + "manifest.txt";

    /**
     * Script extension for the current platform. Used when generating JSON configs
     * that reference the platform-appropriate variant of each hook script.
     */
    static final String SCRIPT_EXT = SystemInfo.isWindows ? ".ps1" : ".sh";

    private DefaultHookProvisioner() {
    }

    /**
     * Provisions default hooks on plugin startup.
     *
     * <p>Scripts are <em>always</em> overwritten so that bug fixes in plugin updates
     * automatically reach existing users. JSON configs are only generated when none
     * exist yet, preserving user customizations.
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

        // Always overwrite all script files (bug fixes must reach existing users)
        for (String entry : entries) {
            copyEntry(entry, hooksDir);
        }

        // JSON configs are generated dynamically (not in manifest) — only on first install
        if (!hasExistingJsonConfigs(hooksDir)) {
            writeJsonConfigs(hooksDir);
            LOG.info("No hook configs found — provisioned defaults to " + hooksDir);
        }
    }

    /**
     * Restores all hook configs and scripts to their bundled defaults, unconditionally.
     * Called only on explicit user action ("Restore Default Hooks").
     *
     * @return true if all files were restored successfully
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

        writeJsonConfigs(hooksDir);

        if (allCopied) {
            LOG.info("Restored " + entries.size() + " default hook resources");
        }
        return allCopied;
    }

    /**
     * Writes JSON hook configs that reference the platform-appropriate script extension.
     * Using dynamically-generated configs avoids the need for separate JSON templates
     * per platform while keeping the script references explicit and editable.
     */
    private static void writeJsonConfigs(@NotNull Path hooksDir) {
        writeJsonConfig(hooksDir, "run_command.json",
            "{\"permission\":[{\"script\":\"scripts/run-command-abuse" + SCRIPT_EXT + "\",\"rejectOnFailure\":true,\"timeout\":10}]}");

        writeJsonConfig(hooksDir, "run_in_terminal.json",
            "{\"permission\":[{\"script\":\"scripts/run-in-terminal-abort" + SCRIPT_EXT + "\",\"rejectOnFailure\":true,\"timeout\":10}],"
                + "\"success\":[{\"script\":\"scripts/run-in-terminal-reprimand" + SCRIPT_EXT + "\",\"timeout\":10,\"failSilently\":true}]}");

        writeJsonConfig(hooksDir, "write_file.json",
            "{\"success\":[{\"script\":\"scripts/check-stale-naming" + SCRIPT_EXT + "\",\"timeout\":10,\"failSilently\":true}]}");
    }

    private static void writeJsonConfig(@NotNull Path hooksDir,
                                        @NotNull String filename,
                                        @NotNull String content) {
        Path target = hooksDir.resolve(filename);
        try {
            Files.writeString(target, content, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            LOG.warn("Failed to write hook config: " + filename, e);
        }
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
    static Path resolveHooksDir(@NotNull Project project) {
        return AgentBridgeStorageSettings.getInstance()
            .getProjectStorageDir(project)
            .resolve("hooks");
    }
}
