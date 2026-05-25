package com.github.catatafishen.agentbridge.sandbox;

import com.github.catatafishen.agentbridge.settings.ShellEnvironment;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.SystemProperties;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class BwrapSandbox {

    private static final Logger LOG = Logger.getInstance(BwrapSandbox.class);

    @VisibleForTesting
    static final String BWRAP_BINARY = "bwrap";

    private static final String TMPFS = "--tmpfs";

    /**
     * Interpreter resolution result: the absolute interpreter path and whether bwrap must
     * invoke it explicitly (true for {@code #!/usr/bin/env} shebangs, where {@code /usr/bin/env}
     * itself is absent from the sandbox and cannot be relied on).
     */
    @VisibleForTesting
    record InterpreterResolution(String interpreterPath, boolean requiresExplicitCall) {
    }

    /**
     * Cached availability check; null = not yet checked.
     */
    private static volatile Boolean available;

    private BwrapSandbox() {
    }

    /**
     * Returns true if bwrap is installed and the system is Linux.
     * Result is cached after the first call.
     */
    public static boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) return cached;
        boolean result = SystemInfo.isLinux && detectBwrap();
        available = result;
        LOG.info("bwrap sandbox availability: " + result);
        return result;
    }

    /**
     * Clears the cached availability result so the next {@link #isAvailable()} call performs a fresh check.
     * Use from production code when system state may have changed (e.g., bwrap was installed or removed).
     */
    public static void forceRecheck() {
        available = null;
    }

    /**
     * Resets the cached availability result, forcing a re-check on the next {@link #isAvailable()} call.
     * Only needed in tests.
     */
    @VisibleForTesting
    public static void resetDetectionCache() {
        available = null;
    }

    /**
     * Wraps a ProcessBuilder command to execute inside a bwrap sandbox.
     * The command list of {@code pb} is modified in-place: bwrap and its arguments are
     * prepended, with the original command following after {@code --}.
     *
     * <p>Call {@link #isAvailable()} before invoking this method. If bwrap is unavailable
     * this method throws rather than silently falling back, so callers can decide whether
     * to abort or proceed unsandboxed.
     *
     * @param pb              the ProcessBuilder whose command will be wrapped
     * @param agentBinaryPath absolute path to the agent binary
     * @param configBinds     paths to bind read-only into the sandbox (e.g., agent auth config dirs)
     * @throws IllegalStateException if bwrap is not available on this system
     */
    public static void wrap(
        @NotNull ProcessBuilder pb,
        @NotNull String agentBinaryPath,
        @NotNull List<Path> configBinds
    ) {
        if (!isAvailable()) {
            throw new IllegalStateException(
                "bwrap sandbox requested but bwrap is not available on this system. " +
                    "Install bubblewrap: https://github.com/containers/bubblewrap");
        }

        // Resolve symlinks so the command and mounts use the real path. Script runtimes
        // (e.g. Node.js) determine module type (ESM vs CJS) by walking up from the script
        // file's directory to find package.json. If we pass the symlink path, Node looks
        // in the symlink's parent instead of the real package directory and cannot find it.
        String realBinaryPath = resolveSymlink(agentBinaryPath);
        List<String> pbCommand = pb.command();
        List<String> commandForSandbox;
        if (!realBinaryPath.equals(agentBinaryPath) && !pbCommand.isEmpty()) {
            commandForSandbox = new ArrayList<>(pbCommand);
            commandForSandbox.set(0, realBinaryPath);
        } else {
            commandForSandbox = pbCommand;
        }

        InterpreterResolution resolution = detectInterpreterResolution(realBinaryPath);
        pb.command(buildWrappedCommandWithResolution(realBinaryPath, configBinds, commandForSandbox, resolution));
        LOG.info("Agent sandboxed with bwrap: " + agentBinaryPath
            + (realBinaryPath.equals(agentBinaryPath) ? "" : " → " + realBinaryPath)
            + " | configBinds=" + configBinds.size()
            + " | interpreter=" + (resolution != null ? resolution.interpreterPath() : null)
            + " | explicitCall=" + (resolution != null && resolution.requiresExplicitCall()));
    }

    /**
     * Returns a new command list with bwrap prepended, or throws if bwrap is unavailable.
     * Convenience overload for callers that work with command lists rather than ProcessBuilders.
     *
     * @see #wrap(ProcessBuilder, String, List)
     */
    public static List<String> wrapCommand(
        @NotNull List<String> command,
        @NotNull String agentBinaryPath,
        @NotNull List<Path> configBinds
    ) {
        if (!isAvailable()) {
            throw new IllegalStateException(
                "bwrap sandbox requested but bwrap is not available on this system. " +
                    "Install bubblewrap: https://github.com/containers/bubblewrap");
        }
        String realBinaryPath = resolveSymlink(agentBinaryPath);
        List<String> resolvedCommand;
        if (!realBinaryPath.equals(agentBinaryPath) && !command.isEmpty()) {
            resolvedCommand = new ArrayList<>(command);
            resolvedCommand.set(0, realBinaryPath);
        } else {
            resolvedCommand = command;
        }
        return buildWrappedCommand(realBinaryPath, configBinds, resolvedCommand);
    }

    @VisibleForTesting
    static List<String> buildWrappedCommand(
        @NotNull String agentBinaryPath,
        @NotNull List<Path> configBinds,
        @NotNull List<String> originalCommand
    ) {
        return buildWrappedCommandWithResolution(
            agentBinaryPath, configBinds, originalCommand,
            detectInterpreterResolution(agentBinaryPath));
    }

    @VisibleForTesting
    static List<String> buildWrappedCommandWithResolution(
        @NotNull String agentBinaryPath,
        @NotNull List<Path> configBinds,
        @NotNull List<String> originalCommand,
        @Nullable InterpreterResolution resolution
    ) {
        // When the script shebang uses #!/usr/bin/env, /usr/bin/env is absent from the sandbox.
        // Linux reports this as ENOENT on the script itself (not on env), producing a misleading
        // "No such file or directory" error on the agent binary. Fix: explicitly prepend the
        // resolved interpreter so the sandbox runs `node /path/to/script [args]` directly,
        // bypassing the /usr/bin/env mechanism entirely.
        List<String> effectiveCommand = originalCommand;
        if (resolution != null && resolution.requiresExplicitCall()) {
            effectiveCommand = new ArrayList<>(originalCommand);
            effectiveCommand.addFirst(resolution.interpreterPath());
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(BWRAP_BINARY);
        cmd.addAll(buildBwrapArgs(agentBinaryPath, configBinds, resolution));
        cmd.add("--");
        cmd.addAll(effectiveCommand);
        return cmd;
    }

    private static List<String> buildBwrapArgs(
        @NotNull String agentBinaryPath,
        @NotNull List<Path> configBinds,
        @Nullable InterpreterResolution resolution
    ) {
        List<String> args = new ArrayList<>();

        // ── Pseudo-filesystems ────────────────────────────────────────────────
        args.addAll(List.of("--proc", "/proc"));
        args.addAll(List.of("--dev", "/dev"));

        // ── Shared libraries (needed for the agent binary to load its dependencies) ──
        // We bind /usr/lib and /lib for .so resolution.
        // We deliberately do NOT bind /usr/bin or /bin so system executables are absent.
        roBindTry(args, "/usr/lib");
        roBindTry(args, "/usr/lib64");
        roBindTry(args, "/usr/lib32");
        roBindTry(args, "/lib");
        roBindTry(args, "/lib64");
        roBindTry(args, "/lib32");

        // ── Dynamic linker configuration ──────────────────────────────────────
        roBindTry(args, "/etc/ld.so.cache");
        roBindTry(args, "/etc/ld.so.conf");
        roBindTry(args, "/etc/ld.so.conf.d");

        // ── Network resolution and TLS certs (needed for ACP communication) ──
        roBindTry(args, "/etc/resolv.conf");
        roBindTry(args, "/etc/hosts");
        roBindTry(args, "/etc/nsswitch.conf");
        roBindTry(args, "/etc/ssl/certs");
        roBindTry(args, "/usr/share/ca-certificates");
        roBindTry(args, "/etc/ca-certificates");
        roBindTry(args, "/etc/pki/tls/certs");

        // ── Writable temporary space ──────────────────────────────────────────
        args.addAll(List.of(TMPFS, "/tmp"));

        // ── Block user home directories with empty tmpfs ──────────────────────
        // This prevents the agent from reading SSH keys, cloud credentials, or any
        // other personal data. The agent's own config dirs are selectively re-added
        // via --ro-bind mounts BELOW, so auth tokens remain accessible.
        //
        // ORDERING IS CRITICAL: these tmpfs mounts MUST come before any --ro-bind
        // paths that live under /home or /root (e.g. ~/.nvm/node, ~/.copilot).
        // bwrap processes arguments sequentially — a --tmpfs on a parent path hides
        // all earlier binds beneath it. Binds placed after the tmpfs layer on top
        // instead, and bwrap creates any missing intermediate directories as needed.
        args.addAll(List.of(TMPFS, "/home"));
        args.addAll(List.of(TMPFS, "/root"));
        String userHome = SystemProperties.getUserHome();
        if (!userHome.startsWith("/home/") && !userHome.equals("/root")) {
            args.addAll(List.of(TMPFS, userHome));
        }

        // ── Agent binary (mounted read-only) ─────────────────────────────────
        // Must come AFTER --tmpfs /home and --tmpfs /root so the bind is visible
        // even when the binary lives under a user home directory (e.g. ~/.nvm/...).
        //
        // When the binary is part of an npm package (package.json in the same directory),
        // bind the entire package directory. Node.js resolves module type (ESM vs CommonJS)
        // by searching for package.json from the script's directory — binding only the binary
        // file would leave that search with nothing to find.
        Path binaryParent = Path.of(agentBinaryPath).getParent();
        if (binaryParent != null && Files.exists(binaryParent.resolve("package.json"))) {
            roBind(args, binaryParent.toString());
        } else {
            roBind(args, agentBinaryPath);
        }

        // ── Runtime interpreter (e.g., Node.js for CLI agents) ────────────────
        // Must also come after the tmpfs mounts for the same reason.
        // Note: /usr/bin/env is intentionally NOT bound. When the shebang uses
        // #!/usr/bin/env, we instead modify the command to explicitly invoke the
        // interpreter directly (see buildWrappedCommand), so /usr/bin/env is never
        // needed in the sandbox.
        if (resolution != null) {
            roBind(args, resolution.interpreterPath());
        }

        // ── Agent config directories (auth tokens, cached credentials) ────────
        // Also after the tmpfs mounts — these dirs live under /home/... too.
        // WRITABLE bind mounts (not read-only): the agent needs to write auth tokens
        // when authenticating for the first time or refreshing credentials. With
        // --ro-bind the CLI sees EACCES and silently writes to the ephemeral tmpfs
        // instead, which is discarded when bwrap exits — causing a re-auth prompt
        // on every launch. We use --bind-try (writable) and pre-create the directory
        // on the host so the bind succeeds even before the first authentication.
        for (Path bind : configBinds) {
            try {
                Files.createDirectories(bind);
            } catch (IOException e) {
                LOG.warn("Could not pre-create config bind dir: " + bind + " — auth may not persist", e);
            }
            rwBindTry(args, bind.toString());
        }

        // ── D-Bus session socket (for system keychain / secret service access) ──
        // The Copilot CLI uses keytar (libsecret) to read its stored OAuth token
        // from the system keychain (GNOME Keyring, KWallet, etc.). libsecret
        // communicates with the keyring daemon via the D-Bus session bus.
        //
        // Without this socket the CLI cannot read its stored token and falls back
        // to prompting for re-authentication on every launch.
        //
        // Security trade-off: mounting the D-Bus session socket allows the sandboxed
        // process to call any session-bus D-Bus service, including the Secret Service
        // API (org.freedesktop.secrets). This is narrower than exposing ~/.ssh or
        // ~/.aws directly but does grant access to all secrets stored in the keychain.
        // The trade-off is intentional — the agent needs its own stored credentials.
        String dbusSocket = resolveDbusSessionSocket();
        if (dbusSocket != null) {
            roBindTry(args, dbusSocket);
            args.addAll(List.of("--setenv", "DBUS_SESSION_BUS_ADDRESS", "unix:path=" + dbusSocket));
        } else {
            LOG.warn("Could not resolve D-Bus session socket; agent may prompt for re-authentication");
        }

        // ── Working directory ─────────────────────────────────────────────────
        // The parent process CWD is typically the project base path, which is not mounted
        // in the sandbox. If we don't set an explicit --chdir, bwrap tries to inherit the
        // CWD — and if that path doesn't exist in the new mount namespace, bwrap fails with
        // ENOENT. Set CWD to /tmp, which is always present (mounted as tmpfs above).
        args.addAll(List.of("--chdir", "/tmp"));

        // ── Process and session isolation ──────────────────────────────────────
        args.add("--unshare-pid");      // new PID namespace: agent cannot see other processes
        args.add("--new-session");      // detach from IDE's controlling terminal
        args.add("--die-with-parent");  // container cleaned up when the plugin exits

        return args;
    }

    @Nullable
    @VisibleForTesting
    static String resolveDbusSessionSocket() {
        return resolveDbusSessionSocket(
            System.getenv("DBUS_SESSION_BUS_ADDRESS"),
            System.getenv("XDG_RUNTIME_DIR")
        );
    }

    @Nullable
    @VisibleForTesting
    static String resolveDbusSessionSocket(@Nullable String dbusAddr, @Nullable String xdgRuntimeDir) {
        if (dbusAddr != null) {
            String prefix = "unix:path=";
            if (dbusAddr.startsWith(prefix)) {
                String rawPath = dbusAddr.substring(prefix.length());
                int comma = rawPath.indexOf(',');
                String socketPath = comma >= 0 ? rawPath.substring(0, comma) : rawPath;
                if (!socketPath.isBlank()) return socketPath;
            }
        }
        if (xdgRuntimeDir != null && !xdgRuntimeDir.isBlank()) {
            return xdgRuntimeDir + "/bus";
        }
        return null;
    }

    /**
     * Adds {@code --ro-bind SRC DEST} where SRC == DEST. Fails at bwrap runtime if SRC is absent.
     */
    private static void roBind(List<String> args, String path) {
        args.add("--ro-bind");
        args.add(path);
        args.add(path);
    }

    /**
     * Adds {@code --ro-bind-try SRC DEST} where SRC == DEST. Silently skipped by bwrap if SRC is absent.
     */
    private static void roBindTry(List<String> args, String path) {
        args.add("--ro-bind-try");
        args.add(path);
        args.add(path);
    }

    /**
     * Adds {@code --bind-try SRC DEST} where SRC == DEST (writable). Silently skipped by bwrap if SRC is absent.
     */
    private static void rwBindTry(List<String> args, String path) {
        args.add("--bind-try");
        args.add(path);
        args.add(path);
    }

    /**
     * Reads the shebang line of the binary and returns an {@link InterpreterResolution}
     * describing the interpreter and how it must be invoked in the sandbox.
     *
     * <p>Returns {@code null} if the binary is a native ELF, the shebang is absent,
     * or the interpreter cannot be resolved.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code #!/usr/bin/env node} &rarr; resolves "node" via shell PATH; {@code requiresExplicitCall=true}
     *       because {@code /usr/bin/env} is absent from the sandbox</li>
     *   <li>{@code #!/usr/local/bin/node} &rarr; returns "/usr/local/bin/node"; {@code requiresExplicitCall=false}
     *       because the kernel can follow the shebang directly once the interpreter is bound</li>
     *   <li>ELF binary (no shebang) &rarr; returns null</li>
     * </ul>
     */
    @Nullable
    @VisibleForTesting
    static InterpreterResolution detectInterpreterResolution(@NotNull String binaryPath) {
        try {
            byte[] header = new byte[256];
            int read;
            try (RandomAccessFile raf = new RandomAccessFile(binaryPath, "r")) {
                read = raf.read(header);
            }
            if (read < 2 || header[0] != '#' || header[1] != '!') return null;

            String shebang = new String(header, 2, read - 2, StandardCharsets.UTF_8);
            int newline = shebang.indexOf('\n');
            String line = (newline >= 0 ? shebang.substring(0, newline) : shebang).trim();

            // line is e.g. "/usr/bin/env node" or "/usr/local/bin/node"
            String[] parts = line.split("\\s+", 2);
            String interpreterExe = parts[0];

            if (interpreterExe.endsWith("/env") && parts.length > 1) {
                // #!/usr/bin/env PROGRAM [args] or #!/usr/bin/env -S PROGRAM [args]
                // Skip any option flags (e.g. -S) before the program name.
                // /usr/bin/env is absent from the sandbox, so we must invoke the interpreter
                // explicitly rather than relying on env resolution.
                String programName = null;
                for (String arg : parts[1].split("\\s+")) {
                    if (!arg.startsWith("-")) {
                        programName = arg;
                        break;
                    }
                }
                if (programName == null) return null;
                String resolved = resolveOnShellPath(programName);
                return resolved != null ? new InterpreterResolution(resolved, true) : null;
            }

            return Files.exists(Path.of(interpreterExe))
                ? new InterpreterResolution(interpreterExe, false)
                : null;

        } catch (IOException e) {
            LOG.debug("Could not read shebang from " + binaryPath + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Resolves a bare binary name (e.g., "node") to an absolute path using the shell environment PATH.
     */
    @Nullable
    private static String resolveOnShellPath(@NotNull String binaryName) {
        Map<String, String> env = ShellEnvironment.getEnvironment();
        String pathStr = env.getOrDefault("PATH", System.getenv("PATH"));
        if (pathStr == null || pathStr.isBlank()) return null;
        for (String dir : pathStr.split(":")) {
            Path candidate = Path.of(dir, binaryName);
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    /**
     * Resolves symlinks in the given binary path to its canonical real path.
     * Returns the input unchanged if resolution fails.
     */
    @VisibleForTesting
    static String resolveSymlink(@NotNull String binaryPath) {
        try {
            return Path.of(binaryPath).toRealPath().toString();
        } catch (IOException e) {
            LOG.warn("Could not resolve symlink for " + binaryPath + ": " + e.getMessage());
            return binaryPath;
        }
    }

    private static boolean detectBwrap() {
        try {
            Process proc = new ProcessBuilder(BWRAP_BINARY, "--version")
                .redirectErrorStream(true)
                .start();
            try (OutputStream sink = OutputStream.nullOutputStream()) {
                proc.getInputStream().transferTo(sink);
            }
            return proc.waitFor() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}
