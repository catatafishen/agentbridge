package com.github.catatafishen.agentbridge.services.hooks;

import com.github.catatafishen.agentbridge.settings.AgentBridgeStorageSettings;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Provisions the default hook directory structure on plugin startup.
 *
 * <p><b>Update policy:</b> each hook file's SHA-256 is stored in {@code .provision-hashes}
 * when the plugin writes it. On the next IDE startup:
 * <ul>
 *   <li>If the file on disk matches the stored hash → user never edited it → safe to overwrite
 *       with the new bundled version.</li>
 *   <li>If the file on disk differs from the stored hash → user has edited it → do not overwrite;
 *       show a balloon notification so the user can choose.</li>
 *   <li>If no hash file exists (an installation predating this feature) → reset only files in
 *       the current manifest. Retired and unknown files are preserved because ownership cannot
 *       be established safely.</li>
 * </ul>
 *
 * <p><b>Hook scripts</b> are JavaScript ({@code scripts/*.js}) executed in-process via the
 * embedded Rhino engine ({@link JsHookEngine}), so a single file runs on every OS and JetBrains
 * IDE — no paired {@code .sh}/{@code .ps1} variants and no external shell, PowerShell, or Node.</p>
 */
public final class DefaultHookProvisioner {

    private static final Logger LOG = Logger.getInstance(DefaultHookProvisioner.class);
    private static final String RESOURCE_BASE = "/default-hooks/";
    private static final String MANIFEST_RESOURCE = RESOURCE_BASE + "manifest.txt";
    private static final String RETIRED_RESOURCE = RESOURCE_BASE + "retired.txt";

    private DefaultHookProvisioner() {
    }

    /**
     * Provisions default hooks on plugin startup using hash-based change detection.
     *
     * <p>If no hash registry exists (old install), wipes the hooks directory and starts
     * fresh so we have a clean known state. On subsequent startups, each file is only
     * overwritten when the user has not modified it (disk hash matches stored hash).
     *
     * <p>Called from {@link HookRegistry} on first access after each IDE startup.
     */
    public static void provisionDefaults(@NotNull Project project) {
        Path hooksDir = resolveHooksDir(project);
        List<String> manifestEntries = requireManifest();
        if (manifestEntries == null) return;

        if (!HookHashRegistry.exists(hooksDir)) {
            // Ownership is unknown on old installs, so only reset files still in the current manifest.
            wipeThenProvision(hooksDir, manifestEntries);
            return;
        }

        provisionWithHashCheck(project, hooksDir, manifestEntries, readRetiredEntries());
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

        List<String> manifestEntries = requireManifest();
        if (manifestEntries == null) return false;

        deleteEntries(hooksDir, readRetiredEntries());
        return wipeThenProvision(hooksDir, manifestEntries);
    }

    private static @Nullable List<String> requireManifest() {
        List<String> entries = readManifest();
        if (entries.isEmpty()) {
            LOG.warn("Default hooks manifest is empty or missing — cannot provision");
            return null;
        }
        return entries;
    }

    static boolean wipeThenProvision(@NotNull Path hooksDir,
                                     @NotNull List<String> manifestEntries) {
        deleteEntries(hooksDir, manifestEntries);
        ensureScriptsDir(hooksDir);

        Map<String, String> newHashes = new HashMap<>();
        boolean allOk = true;

        for (String entry : manifestEntries) {
            String hash = copyAndHash(entry, hooksDir);
            if (hash != null) {
                newHashes.put(entry, hash);
            } else {
                allOk = false;
            }
        }

        HookHashRegistry.save(hooksDir, newHashes);

        if (allOk) {
            LOG.info("Provisioned " + manifestEntries.size() + " hook files to " + hooksDir);
        }
        return allOk;
    }

    /**
     * Compares each file against its stored hash and only overwrites files the user has not edited.
     * Collects conflicts and notifies the user if any exist.
     */
    private static void provisionWithHashCheck(@NotNull Project project,
                                               @NotNull Path hooksDir,
                                               @NotNull List<String> manifestEntries,
                                               @NotNull List<String> retiredEntries) {
        Map<String, String> storedHashes = HookHashRegistry.load(hooksDir);
        Map<String, String> bundledHashes = HookHashRegistry.loadBundledHashes();
        Map<String, String> updatedHashes = new HashMap<>(storedHashes);
        List<HookUpdateNotifier.Conflict> conflicts = new ArrayList<>();

        ensureScriptsDir(hooksDir);
        removeRetiredEntries(hooksDir, retiredEntries, storedHashes, updatedHashes);

        for (String entry : manifestEntries) {
            processScriptEntry(entry, hooksDir, storedHashes, bundledHashes, updatedHashes, conflicts);
        }

        HookHashRegistry.save(hooksDir, updatedHashes);

        if (!conflicts.isEmpty()) {
            HookUpdateNotifier.notify(project, conflicts, updatedHashes, hooksDir);
        }
    }

    private static void processScriptEntry(@NotNull String entry,
                                           @NotNull Path hooksDir,
                                           @NotNull Map<String, String> storedHashes,
                                           @NotNull Map<String, String> bundledHashes,
                                           @NotNull Map<String, String> updatedHashes,
                                           @NotNull List<HookUpdateNotifier.Conflict> conflicts) {
        String bundledHash = resolveBundledHash(entry, bundledHashes);
        if (bundledHash == null) return;

        Path diskPath = hooksDir.resolve(entry);
        String storedHash = storedHashes.get(entry);
        String diskHash = HookHashRegistry.computeFileHash(diskPath);

        if (diskHash == null) {
            String written = copyAndHash(entry, hooksDir);
            if (written != null) updatedHashes.put(entry, written);
        } else if (diskHash.equals(storedHash) || HookHashRegistry.isOfficialHash(entry, diskHash, bundledHashes)) {
            if (!bundledHash.equals(diskHash)) {
                String written = copyAndHash(entry, hooksDir);
                if (written != null) updatedHashes.put(entry, written);
            }
        } else if (!bundledHash.equals(storedHash)) {
            String content = readBundledResourceAsString(RESOURCE_BASE + entry);
            if (content != null) {
                conflicts.add(new HookUpdateNotifier.Conflict(entry, content, diskPath, bundledHash));
            }
        }
        // else: bundledHash == storedHash but disk differs → user edited, no new version to offer.
    }

    /**
     * Removes files that were provisioned by an older plugin version but are no longer bundled.
     * Customized copies are preserved and released from provisioner ownership.
     */
    static void removeRetiredEntries(@NotNull Path hooksDir,
                                     @NotNull List<String> retiredEntries,
                                     @NotNull Map<String, String> storedHashes,
                                     @NotNull Map<String, String> updatedHashes) {
        for (String entry : retiredEntries) {
            Path diskPath = hooksDir.resolve(entry);
            String diskHash = HookHashRegistry.computeFileHash(diskPath);
            String storedHash = storedHashes.get(entry);

            if (diskHash == null || !diskHash.equals(storedHash)) {
                updatedHashes.remove(entry);
            } else {
                try {
                    Files.delete(diskPath);
                    updatedHashes.remove(entry);
                } catch (IOException e) {
                    LOG.warn("Failed to remove retired hook file: " + entry, e);
                }
            }
        }
    }

    /**
     * Returns the expected SHA-256 for {@code entry} from the pre-computed bundled hashes.
     * Falls back to computing it at runtime if the pre-computed file is unavailable.
     *
     * @return hash, or {@code null} if the resource cannot be read
     */
    private static @Nullable String resolveBundledHash(@NotNull String entry,
                                                       @NotNull Map<String, String> bundledHashes) {
        String hash = bundledHashes.get(entry);
        if (hash != null) return hash;
        String content = readBundledResourceAsString(RESOURCE_BASE + entry);
        return content != null ? HookHashRegistry.computeStringHash(content) : null;
    }

    // -------------------------------------------------------------------------
    // File I/O helpers
    // -------------------------------------------------------------------------

    /**
     * Copies a bundled resource to the hooks directory and returns the SHA-256 of the written bytes.
     *
     * @return SHA-256 hex of the written content, or {@code null} on failure
     */
    private static @Nullable String copyAndHash(@NotNull String entry, @NotNull Path hooksDir) {
        String resourcePath = RESOURCE_BASE + entry;
        String content = readBundledResourceAsString(resourcePath);
        if (content == null) return null;
        return copyStringAndHash(entry, content, hooksDir);
    }

    /**
     * Writes the given string content to {@code entry} inside the hooks directory and returns its SHA-256.
     *
     * @return SHA-256 hex of the written content, or {@code null} on failure
     */
    private static @Nullable String copyStringAndHash(@NotNull String entry,
                                                      @NotNull String content,
                                                      @NotNull Path hooksDir) {
        Path targetPath = hooksDir.resolve(entry);
        try {
            Files.writeString(targetPath, content, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            return HookHashRegistry.computeStringHash(content);
        } catch (IOException e) {
            LOG.warn("Failed to write hook file: " + entry, e);
            return null;
        }
    }

    /**
     * Reads a classpath resource as a UTF-8 string.
     *
     * @return the resource content, or {@code null} if the resource is not found or cannot be read
     */
    private static @Nullable String readBundledResourceAsString(@NotNull String resourcePath) {
        try (InputStream is = DefaultHookProvisioner.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                LOG.warn("Bundled resource not found: " + resourcePath);
                return null;
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Failed to read bundled resource: " + resourcePath, e);
            return null;
        }
    }

    /**
     * Deletes only entries explicitly managed by the supplied resource list.
     * Files outside that list remain untouched.
     */
    private static void deleteEntries(@NotNull Path hooksDir, @NotNull List<String> entries) {
        for (String entry : entries) {
            Path file = hooksDir.resolve(entry);
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LOG.warn("Failed to delete hook file: " + entry + ": " + e.getMessage());
            }
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
        return readResourceEntries(MANIFEST_RESOURCE, "default hooks manifest");
    }

    private static @NotNull List<String> readRetiredEntries() {
        return readResourceEntries(RETIRED_RESOURCE, "retired hooks list");
    }

    private static @NotNull List<String> readResourceEntries(@NotNull String resourcePath,
                                                             @NotNull String description) {
        List<String> entries = new ArrayList<>();
        try (InputStream is = DefaultHookProvisioner.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                LOG.warn("Bundled " + description + " resource not found: " + resourcePath);
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
            LOG.warn("Failed to read " + description, e);
        }
        return entries;
    }

    /**
     * Reverts a single hook file to its bundled default and updates the stored hash.
     *
     * <p>Works for both scripts and JSON config files (all read from bundled classpath
     * resources). After reverting, the file will be recognized as official on the next startup.
     *
     * @param project  the current project (used to resolve the hooks directory)
     * @param filename relative path within the hooks directory (e.g. {@code scripts/run-command-abuse.js})
     * @return true if the file was successfully reverted
     */
    public static boolean revertFile(@NotNull Project project, @NotNull String filename) {
        Path hooksDir = resolveHooksDir(project);
        String content = readBundledResourceAsString(RESOURCE_BASE + filename);
        if (content == null) {
            LOG.warn("Cannot revert: no bundled content found for " + filename);
            return false;
        }
        String hash = copyStringAndHash(filename, content, hooksDir);
        if (hash == null) return false;
        Map<String, String> hashes = HookHashRegistry.load(hooksDir);
        hashes.put(filename, hash);
        HookHashRegistry.save(hooksDir, hashes);
        return true;
    }

    @NotNull
    public static Path resolveHooksDir(@NotNull Project project) {
        return AgentBridgeStorageSettings.getInstance()
            .getProjectStorageDir(project)
            .resolve("hooks");
    }
}
