package com.github.catatafishen.agentbridge.shim;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Owns the on-disk command shim directory and the per-process auth token used
 * by the shim ↔ {@code /shim-exec} round-trip.
 *
 * <p>On first call to {@link #install()}, copies the bundled shim payload (a
 * native Go binary on supported OS+arch combinations, or the POSIX bash
 * fallback) into a per-project directory under IntelliJ's system path, once
 * per command name we want to intercept. The directory is then returned and
 * prepended to {@code PATH} for every ACP agent subprocess (Copilot, Junie,
 * OpenCode, Kiro).
 *
 * <p>Each installed file is an independent copy of the same payload — using
 * {@link Files#createSymbolicLink} fails on Windows without elevated
 * permissions and a one-shot ~5MB copy is negligible. The shim reads its own
 * basename ({@code argv[0]}) to decide which command name was invoked.
 *
 * <p>The token defends the {@code /shim-exec} endpoint against unrelated local
 * processes that stumble upon the bound port: only a process spawned by us
 * inherits {@code AGENTBRIDGE_SHIM_TOKEN} and can authenticate.
 *
 * <p><b>Native vs script payload</b> — Windows has no POSIX shell available
 * by default, so we ship a tiny stripped Go binary cross-compiled for every
 * (os, arch) we support. On Linux/macOS the same Go binary is preferred (no
 * dependency on /bin/bash availability or curl on PATH), with the legacy
 * bash script as a development fallback when the Go binary isn't bundled
 * (e.g. local IDE-from-source runs that didn't run {@code build-shims.sh}).
 */
@Service(Service.Level.PROJECT)
public final class ShimManager implements Disposable {

    private static final Logger LOG = Logger.getInstance(ShimManager.class);

    /**
     * Names that get a copy of the shim. Phase A: tools whose redirects exist
     * in {@code ShellRedirectPlanner}. Extend in Phase D.
     */
    public static final List<String> SHIMMED_COMMANDS =
        List.of("cat", "head", "grep", "egrep", "fgrep", "rg", "git");

    /**
     * Directory inside resources that holds per-(os, arch) Go binaries.
     */
    private static final String NATIVE_BIN_ROOT = "/agentbridge/shim/bin";

    /**
     * Bundled POSIX bash fallback used when no native binary is available.
     */
    private static final String SCRIPT_FALLBACK = "/agentbridge/shim/agentbridge-shim.sh";

    private final @NotNull Project project;
    private final @NotNull String token = UUID.randomUUID().toString().replace("-", "");

    private volatile Path shimDir;

    public ShimManager(@NotNull Project project) {
        this.project = project;
    }

    public static @NotNull ShimManager getInstance(@NotNull Project project) {
        return project.getService(ShimManager.class);
    }

    /**
     * Lazily extract the shim payload under one path per shimmed command name and
     * return the directory. Subsequent calls return the cached path.
     *
     * <p>Returns {@code null} when extraction fails — the caller (env injection
     * site in {@code AcpClient}) treats this as "skip the shim" so launches
     * never break because of a shim install failure.
     */
    public synchronized Path install() {
        if (shimDir != null) return shimDir;
        try {
            Payload payload = resolvePayload();
            if (payload == null) {
                LOG.warn("No shim payload available for "
                    + currentPlatformKey() + " — agent shells will run unintercepted");
                return null;
            }

            Path dir = computeShimDir();
            Files.createDirectories(dir);

            for (String name : SHIMMED_COMMANDS) {
                String filename = payload.executable() ? name + payload.suffix() : name;
                Path target = dir.resolve(filename);
                Files.write(target, payload.bytes());
                if (payload.executable()) makeExecutable(target);
            }

            shimDir = dir;
            LOG.info("Installed " + SHIMMED_COMMANDS.size() + " command shims under "
                + dir + " using " + payload.kind());
            return dir;
        } catch (IOException e) {
            LOG.warn("Failed to install command shims for project " + project.getName()
                + " — agent shells will run unintercepted", e);
            return null;
        }
    }

    public @NotNull String getToken() {
        return token;
    }

    private Path computeShimDir() {
        // Per-project subdir keeps multiple open projects from clobbering each
        // other's shim files (tokens differ between projects).
        String projectKey = projectFingerprint();
        return Path.of(PathManager.getSystemPath(), "agentbridge", "shims", projectKey);
    }

    private String projectFingerprint() {
        String basePath = project.getBasePath();
        if (basePath == null) basePath = project.getName();
        return Integer.toHexString(basePath.hashCode());
    }

    /**
     * Pick the best available payload for the current platform. Tries the native
     * Go binary first (works everywhere including Windows); falls back to the
     * bash script on Unix when the binary isn't bundled (dev convenience).
     */
    private @Nullable Payload resolvePayload() throws IOException {
        String key = currentPlatformKey();
        String suffix = SystemInfo.isWindows ? ".exe" : "";
        String nativeRes = NATIVE_BIN_ROOT + "/" + key + "/agentbridge-shim" + suffix;
        byte[] nativeBytes = readResourceOrNull(nativeRes);
        if (nativeBytes != null) {
            return new Payload(nativeBytes, suffix, true, "native:" + key);
        }
        if (!SystemInfo.isWindows) {
            byte[] scriptBytes = readResourceOrNull(SCRIPT_FALLBACK);
            if (scriptBytes != null) {
                return new Payload(scriptBytes, "", true, "bash-script");
            }
        }
        return null;
    }

    /**
     * Returns a key like {@code "linux-amd64"} matching the layout produced by
     * {@code scripts/build-shims.sh}. Maps JVM arch names to Go arch names.
     */
    static @NotNull String currentPlatformKey() {
        String os;
        if (SystemInfo.isWindows) os = "windows";
        else if (SystemInfo.isMac) os = "darwin";
        else os = "linux";

        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String goArch = switch (arch) {
            case "amd64", "x86_64", "x64" -> "amd64";
            case "aarch64", "arm64" -> "arm64";
            default -> arch; // best-effort, will simply miss the resource and fall through
        };
        return os + "-" + goArch;
    }

    private static byte @Nullable [] readResourceOrNull(@NotNull String resourcePath) throws IOException {
        try (InputStream in = ShimManager.class.getResourceAsStream(resourcePath)) {
            if (in == null) return null;
            return in.readAllBytes();
        }
    }

    private static void makeExecutable(@NotNull Path path) throws IOException {
        // Windows: PosixFileAttributeView is unsupported. Files written via the
        // standard Java APIs are launchable as long as the .exe extension is
        // present — which Payload.suffix() ensures on Windows.
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE
            ));
        } catch (UnsupportedOperationException ignore) {
            // Non-POSIX FS (Windows). The .exe extension is enough on NTFS/ReFS.
        }
    }

    @Override
    public void dispose() {
        Path dir = shimDir;
        if (dir == null) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            // Delete bottom-up so the directory itself can be removed.
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) { /* best-effort */ }
            });
        } catch (IOException ignore) {
            // best-effort cleanup
        }
    }

    /**
     * Returned by {@link #snapshot()} — convenience for the launch-time env injection
     * to avoid two getters.
     */
    public record EnvSnapshot(@NotNull Path shimDir, @NotNull String token, @NotNull Set<String> commands) {
    }

    public EnvSnapshot snapshot() {
        Path dir = install();
        if (dir == null) return null;
        return new EnvSnapshot(dir, token, Set.copyOf(SHIMMED_COMMANDS));
    }

    /**
     * One installed shim image — the bytes plus the filename suffix the OS
     * needs ({@code ".exe"} on Windows, empty elsewhere).
     */
    private record Payload(byte @NotNull [] bytes, @NotNull String suffix,
                           boolean executable, @NotNull String kind) {
    }
}
