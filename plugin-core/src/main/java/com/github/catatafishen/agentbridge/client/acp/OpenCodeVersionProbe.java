package com.github.catatafishen.agentbridge.client.acp;

import com.github.catatafishen.agentbridge.settings.ShellEnvironment;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds out which OpenCode version is installed before the agent is launched.
 * <p>
 * The version OpenCode reports in the ACP {@code initialize} response arrives too late for decisions
 * about the launch command itself, such as whether {@code opencode acp} accepts {@code --port}
 * (1.x does, 2.x exits on it).
 */
final class OpenCodeVersionProbe {

    private static final Logger LOG = Logger.getInstance(OpenCodeVersionProbe.class);
    private static final int TIMEOUT_MS = 5_000;
    // Bounded repetition: a version has a few short numeric segments, and unbounded ones would
    // backtrack badly on pathological output.
    private static final Pattern VERSION = Pattern.compile("\\d{1,9}(?:\\.\\d{1,9}){1,3}");

    private OpenCodeVersionProbe() {
    }

    /**
     * Runs {@code <binary> --version} with the same shell environment the agent is launched with.
     * Blocking, at most a few seconds; call from a background thread.
     *
     * @return the version such as {@code "1.19.0"}, or {@code null} (after logging why) when it
     * could not be determined
     */
    static @Nullable String probe(@NotNull String binary) {
        try {
            GeneralCommandLine commandLine = new GeneralCommandLine(binary, "--version")
                .withEnvironment(ShellEnvironment.getEnvironment());
            ProcessOutput output = new CapturingProcessHandler(commandLine).runProcess(TIMEOUT_MS);
            if (output.isTimeout() || output.getExitCode() != 0) {
                LOG.warn("`" + binary + " --version` " + (output.isTimeout() ? "timed out" : "exited with " + output.getExitCode())
                    + ": " + output.getStderr().trim());
                return null;
            }
            String version = parseVersionOutput(output.getStdout());
            if (version == null) {
                LOG.warn("Could not find a version in the output of `" + binary + " --version`: " + output.getStdout().trim());
            }
            return version;
        } catch (ExecutionException | RuntimeException e) {
            LOG.warn("Could not run `" + binary + " --version`", e);
            return null;
        }
    }

    /**
     * Extracts the first dotted number from the output of {@code --version}. Tolerates a prefix
     * such as {@code "v"} or {@code "opencode "} that {@code KiroClient.parseVersion} would reject.
     *
     * @return the version, or {@code null} when the output contains none
     */
    static @Nullable String parseVersionOutput(@Nullable String output) {
        if (output == null) {
            return null;
        }
        Matcher matcher = VERSION.matcher(output);
        return matcher.find() ? matcher.group() : null;
    }
}
