package com.github.catatafishen.agentbridge.services.hooks;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Reads and writes the {@code .provision-hashes} file in the hooks directory.
 *
 * <p>The file maps each hook file's relative path to the SHA-256 hash of the
 * <em>bundled</em> version that was last written by the plugin. On the next
 * plugin startup, {@link DefaultHookProvisioner} compares the on-disk content's
 * hash against this stored value:
 * <ul>
 *   <li>Match → file is unchanged since the plugin wrote it → safe to overwrite with the new version.</li>
 *   <li>Mismatch → the user has edited the file → do not overwrite; notify the user instead.</li>
 * </ul>
 *
 * <p>File format: one {@code filename=sha256hex} entry per line. Lines starting
 * with {@code #} are comments and are ignored.
 */
public final class HookHashRegistry {

    private static final Logger LOG = Logger.getInstance(HookHashRegistry.class);
    static final String HASH_FILE = ".provision-hashes";

    private HookHashRegistry() {
    }

    /**
     * Returns true if the hash registry file exists in the given hooks directory.
     * Used to distinguish a fresh install from an old install that predates hash tracking.
     */
    public static boolean exists(@NotNull Path hooksDir) {
        return Files.isRegularFile(hooksDir.resolve(HASH_FILE));
    }

    /**
     * Loads stored hashes from the hooks directory.
     *
     * @return map from relative filename to SHA-256 hex string; empty if file missing
     */
    public static @NotNull Map<String, String> load(@NotNull Path hooksDir) {
        Path hashFile = hooksDir.resolve(HASH_FILE);
        if (!Files.isRegularFile(hashFile)) return new HashMap<>();
        try {
            return parseProperties(Files.readString(hashFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.warn("Failed to read hook hash registry: " + e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Saves hashes to the hooks directory, replacing any existing file.
     */
    public static void save(@NotNull Path hooksDir, @NotNull Map<String, String> hashes) {
        Path hashFile = hooksDir.resolve(HASH_FILE);
        StringBuilder sb = new StringBuilder("# AgentBridge hook provision hashes — do not edit\n");
        hashes.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> sb.append(e.getKey()).append('=').append(e.getValue()).append('\n'));
        try {
            Files.writeString(hashFile, sb, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Failed to save hook hash registry: " + e.getMessage());
        }
    }

    /**
     * Parses a simple {@code key=value} properties text into a map, skipping blank lines and comments.
     */
    private static @NotNull Map<String, String> parseProperties(@NotNull String text) {
        Map<String, String> result = new HashMap<>();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq > 0) {
                result.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        return result;
    }

    /**
     * Loads the pre-computed bundled hashes from {@code bundled-hashes.properties} in the JAR.
     *
     * <p>The file is generated at build time by the {@code generateHookHashes} Gradle task.
     * It maps each hook filename to its current SHA-256 hash, and stores optional historical
     * hashes under {@code <filename>.history} as a comma-separated list.</p>
     *
     * <p>Using pre-computed hashes avoids re-reading every bundled resource at runtime, and
     * lets us detect official-version files across multiple plugin upgrades (including skipped
     * versions).</p>
     *
     * @return map keyed by filename; values include the current hash entry and {@code .history}
     * entries exactly as read from the file
     */
    public static @NotNull Map<String, String> loadBundledHashes() {
        String resource = "/default-hooks/bundled-hashes.properties";
        try (InputStream in = HookHashRegistry.class.getResourceAsStream(resource)) {
            if (in == null) {
                LOG.warn("bundled-hashes.properties not found in JAR — generateHookHashes may not have run");
                return new HashMap<>();
            }
            return parseProperties(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.warn("Failed to load bundled-hashes.properties: " + e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Returns true if {@code diskHash} matches an officially-shipped version of {@code filename}.
     *
     * <p>Checks both the current bundled hash and the historical hashes accumulated across
     * previous plugin releases. A match means the file was never edited by the user — it's
     * safe to overwrite even if the plugin skipped a version.
     *
     * @param filename    relative filename (e.g. {@code scripts/run-command-abuse.sh})
     * @param diskHash    SHA-256 of the file currently on disk
     * @param bundledData map returned by {@link #loadBundledHashes()}
     */
    public static boolean isOfficialHash(@NotNull String filename,
                                         @NotNull String diskHash,
                                         @NotNull Map<String, String> bundledData) {
        String current = bundledData.get(filename);
        if (diskHash.equals(current)) return true;

        String historyEntry = bundledData.get(filename + ".history");
        if (historyEntry == null) return false;
        for (String h : historyEntry.split(",")) {
            if (diskHash.equals(h.trim())) return true;
        }
        return false;
    }

    /**
     * Computes the SHA-256 hash of the given input stream.
     *
     * @return lowercase hex string, or {@code null} if the stream cannot be read
     */
    public static @Nullable String computeHash(@NotNull InputStream in) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        } catch (IOException e) {
            LOG.warn("Failed to compute hash: " + e.getMessage());
            return null;
        }
    }

    /**
     * Computes the SHA-256 hash of the file at the given path.
     *
     * @return lowercase hex string, or {@code null} if the file cannot be read
     */
    public static @Nullable String computeFileHash(@NotNull Path path) {
        if (!Files.isRegularFile(path)) return null;
        try (InputStream in = Files.newInputStream(path)) {
            return computeHash(in);
        } catch (IOException e) {
            LOG.warn("Failed to compute file hash for " + path + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Computes the SHA-256 hash of a string (used for generated JSON config content).
     */
    public static @NotNull String computeStringHash(@NotNull String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
