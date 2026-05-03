package com.github.catatafishen.agentbridge.services.hooks;

import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Configuration for one script entry within a trigger array of a per-tool hook file.
 * Multiple entries can be chained under the same trigger (they run sequentially).
 *
 * <p>Maps to one object in a trigger array (e.g. one entry in the {@code "success"} array)
 * inside a {@code <tool-id>.json} file:
 * <pre>{@code
 * {
 *   "script": "scripts/remind-bot-identity.sh",
 *   "timeout": 10,
 *   "failSilently": true,
 *   "async": false,
 *   "env": { "LOG_LEVEL": "INFO" }
 * }
 * }</pre>
 *
 * @param script       path to script file (relative to the hooks directory)
 * @param timeout      max execution time in seconds before force-kill (default 10)
 * @param failSilently if true, script errors are silently ignored; if false, they propagate
 *                     and either fail the tool call (pre/success/failure) or reject it (permission).
 *                     Permission hooks use {@code rejectOnFailure} in JSON which maps to
 *                     {@code failSilently = !rejectOnFailure}.
 * @param async        if true, the script is fire-and-forget (no waiting for result).
 *                     Only meaningful for success and failure hooks — permission and pre hooks
 *                     always wait for results.
 * @param env          extra environment variables merged into the script process
 */
public record HookEntryConfig(
    @NotNull String script,
    int timeout,
    boolean failSilently,
    boolean async,
    @NotNull Map<String, String> env
) {

    private static final int DEFAULT_TIMEOUT = 10;

    public HookEntryConfig {
        if (timeout <= 0) timeout = DEFAULT_TIMEOUT;
        if (env == null) env = Map.of();
    }
}
