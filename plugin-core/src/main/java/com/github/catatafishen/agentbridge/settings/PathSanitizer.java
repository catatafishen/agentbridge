package com.github.catatafishen.agentbridge.settings;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.VisibleForTesting;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Filters PATH entries to prevent agent CLIs from detecting and advertising
 * tools that should be accessed exclusively through MCP.
 *
 * <h3>Why this exists</h3>
 * Agent CLIs (e.g. Copilot) inspect the process {@code PATH} at startup to
 * detect available tools ({@code git}, {@code gh}, {@code curl}, etc.) and
 * inject an {@code <environment_context>} block into the system prompt listing
 * them as "Available tools". This conflicts with the plugin's instruction that
 * agents should use AgentBridge MCP tools instead, because:
 * <ul>
 *     <li><b>Tool hooks</b> (bot identity tokens, audit trails) are bypassed
 *         when the agent uses native CLI tools directly.</li>
 *     <li><b>Follow-agent mode</b> (making terminal output visible to the user)
 *         does not work with native tools.</li>
 *     <li><b>IDE buffer sync</b> is lost — native git/file tools modify disk
 *         files behind the IDE's back, desynchronizing editor buffers.</li>
 * </ul>
 *
 * <h3>How it works</h3>
 * Given the original {@code PATH} and the resolved CLI binary path, this class
 * keeps only directories that are strictly necessary for the CLI to function:
 * <ol>
 *     <li>The directory containing the resolved CLI binary itself</li>
 *     <li>Directories containing {@code node}, {@code npm}, or {@code npx}
 *         (most agent CLIs are Node.js applications)</li>
 *     <li>Essential system directories ({@code /usr/bin}, {@code /bin},
 *         {@code /usr/sbin}, {@code /sbin}) — required for basic process
 *         execution</li>
 * </ol>
 * All other directories are stripped. This effectively hides tools installed
 * via Homebrew, Snap, Cargo, pip, custom locations, etc.
 *
 * <h3>Limitations</h3>
 * Tools installed in shared system directories (e.g. {@code git} in
 * {@code /usr/bin}) cannot be hidden via PATH stripping alone. The plugin's
 * startup instructions provide an additional layer of enforcement for these
 * cases. If the upstream {@code --excluded-tools} CLI flag is fixed
 * (bug #556), that will be the definitive solution.
 *
 * @see ShellEnvironment
 */
public final class PathSanitizer {

    private static final Logger LOG = Logger.getInstance(PathSanitizer.class);

    private static final Set<String> ESSENTIAL_SYSTEM_DIRS = Set.of(
        "/usr/bin", "/bin", "/usr/sbin", "/sbin"
    );

    private static final Set<String> NODE_BINARIES = Set.of("node", "npm", "npx");

    private PathSanitizer() {
    }

    /**
     * Filters PATH to keep only directories essential for the agent CLI.
     *
     * @param originalPath      the full PATH string (colon- or semicolon-separated)
     * @param resolvedBinaryDir absolute path to the directory containing the resolved
     *                          CLI binary (e.g. {@code /usr/local/bin} if the binary
     *                          is {@code /usr/local/bin/copilot})
     * @return the sanitized PATH string with non-essential directories removed
     */
    @NotNull
    public static String sanitize(@NotNull String originalPath, @NotNull String resolvedBinaryDir) {
        String[] dirs = originalPath.split(File.pathSeparator);
        List<String> kept = new ArrayList<>();
        List<String> stripped = new ArrayList<>();

        for (String dir : dirs) {
            if (dir.isBlank()) continue;

            if (shouldKeep(dir, resolvedBinaryDir)) {
                kept.add(dir);
            } else {
                stripped.add(dir);
            }
        }

        if (!stripped.isEmpty()) {
            LOG.info("PATH sanitizer stripped " + stripped.size() + " directory(ies): " + stripped);
            LOG.info("PATH sanitizer kept " + kept.size() + " directory(ies): " + kept);
        }

        return String.join(File.pathSeparator, kept);
    }

    @VisibleForTesting
    static boolean shouldKeep(@NotNull String dir, @NotNull String resolvedBinaryDir) {
        // Always keep the directory containing the resolved CLI binary
        if (dir.equals(resolvedBinaryDir)) {
            return true;
        }

        // Keep essential system directories
        if (ESSENTIAL_SYSTEM_DIRS.contains(dir)) {
            return true;
        }

        // Keep directories containing Node.js (most agent CLIs are Node apps)
        Path dirPath = Path.of(dir);
        if (Files.isDirectory(dirPath)) {
            for (String nodeBin : NODE_BINARIES) {
                if (Files.exists(dirPath.resolve(nodeBin))) {
                    return true;
                }
            }
        }

        return false;
    }
}
