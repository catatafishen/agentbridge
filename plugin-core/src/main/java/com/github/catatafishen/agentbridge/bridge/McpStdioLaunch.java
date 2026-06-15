package com.github.catatafishen.agentbridge.bridge;

import com.intellij.openapi.util.SystemInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;

/**
 * Resolves the command needed to launch the bundled MCP server as a stdio proxy
 * ({@code java -jar mcp-server.jar --port <N>}).
 *
 * <p>The stdio proxy ({@code McpStdioProxy}) is the transport every agent should use to reach
 * the in-IDE {@code McpHttpServer}: it opens a fresh, short-lived HTTP connection per message,
 * so there is never an idle keep-alive socket for the JDK {@code HttpServer} idle-reaper to
 * close. Wiring an agent <em>directly</em> over HTTP (a persistent keep-alive pool) is what
 * produced spurious "MCP disconnected" errors for clients such as Claude Code, whose undici
 * client leaves an idle socket lingering between calls. Routing through this stdio command
 * makes every agent use the same robust path the ACP agents already use.</p>
 *
 * <p>Centralized here so the Java-binary resolution and jar lookup live in one place, shared by
 * {@code AcpClient} (ACP {@code mcpServers} array form) and {@code ClaudeClient} (Claude
 * {@code .mcp.json} keyed-object form).</p>
 */
public final class McpStdioLaunch {

    private McpStdioLaunch() {
    }

    /**
     * Resolves the path to a usable {@code java} binary. Tries {@code java.home/bin/java} first
     * (the JVM running this IDE), then falls back to {@code JAVA_HOME/bin/java}.
     *
     * @return absolute path to an existing java executable, or {@code null} if none found
     */
    @Nullable
    public static String resolveJavaBinaryPath() {
        String javaExe = SystemInfo.isWindows ? "java.exe" : "java";

        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isEmpty()) {
            File candidate = new File(javaHome + File.separator + "bin" + File.separator + javaExe);
            if (candidate.exists()) return candidate.getAbsolutePath();
        }

        String envJavaHome = System.getenv("JAVA_HOME");
        if (envJavaHome != null && !envJavaHome.isEmpty()) {
            File candidate = new File(envJavaHome + File.separator + "bin" + File.separator + javaExe);
            if (candidate.exists()) return candidate.getAbsolutePath();
        }

        return null;
    }

    /**
     * Builds the full launch command for the stdio proxy, or {@code null} if either the Java
     * binary or the bundled {@code mcp-server.jar} cannot be located. The first element is the
     * command (the java executable); the remaining elements are its arguments.
     */
    @Nullable
    public static List<String> buildCommand(int mcpPort) {
        String javaPath = resolveJavaBinaryPath();
        if (javaPath == null) return null;
        String jarPath = McpServerJarLocator.findMcpServerJar();
        if (jarPath == null) return null;
        return commandFor(javaPath, jarPath, mcpPort);
    }

    /**
     * Pure builder: assembles the argument list from already-resolved paths. Exposed for testing.
     */
    @NotNull
    static List<String> commandFor(@NotNull String javaPath, @NotNull String jarPath, int mcpPort) {
        return List.of(javaPath, "-jar", jarPath, "--port", String.valueOf(mcpPort));
    }

    /**
     * Human-readable diagnostic describing which launch component is missing. Intended for
     * inclusion in exception messages when {@link #buildCommand} returns {@code null}, so users
     * see which dependency is unavailable instead of a generic failure.
     */
    @NotNull
    public static String describeUnavailable() {
        StringBuilder sb = new StringBuilder();
        String javaPath = resolveJavaBinaryPath();
        if (javaPath == null) {
            sb.append("Java binary not found (checked java.home=")
                .append(System.getProperty("java.home"))
                .append(" and JAVA_HOME=")
                .append(System.getenv("JAVA_HOME"))
                .append(")");
        } else {
            sb.append("Java binary ok at ").append(javaPath);
        }
        sb.append("; ");
        String jarPath = McpServerJarLocator.findMcpServerJar();
        if (jarPath == null) {
            sb.append("mcp-server.jar not found in plugin lib directory "
                + "(plugin may not be fully installed; try rebuilding or reinstalling)");
        } else {
            sb.append("mcp-server.jar ok at ").append(jarPath);
        }
        return sb.toString();
    }
}
