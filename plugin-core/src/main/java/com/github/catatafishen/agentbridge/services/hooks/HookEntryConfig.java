package com.github.catatafishen.agentbridge.services.hooks;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Configuration for one entry within a trigger array of a per-tool hook file.
 * Multiple entries can be chained under the same trigger (they run sequentially).
 *
 * <p>An entry can be script-based, text-only, or both. At least one of {@code script},
 * {@code prependString}, or {@code appendString} must be non-empty — an entry with none
 * of these would have no effect.
 *
 * <p>Maps to one object in a trigger array (e.g. one entry in the {@code "success"} array):
 * <pre>{@code
 * {
 *   "script": "scripts/remind-bot-identity.sh",   // optional
 *   "prependString": "Context note",               // optional; not for permission hooks
 *   "appendString": "Footer note",                 // optional; not for permission hooks
 *   "timeout": 10,
 *   "failSilently": true,
 *   "async": false,
 *   "env": { "LOG_LEVEL": "INFO" }
 * }
 * }</pre>
 *
 * @param script        path to script file (relative to hooks directory), or null for text-only entries
 * @param timeout       max execution time in seconds before force-kill (default 10, only for script entries)
 * @param failSilently  if true, script errors are silently ignored; if false, they propagate.
 *                      Permission hooks use {@code rejectOnFailure} in JSON which maps to
 *                      {@code failSilently = !rejectOnFailure}.
 * @param async         if true, the script is fire-and-forget. Only meaningful for success/failure hooks.
 * @param env           extra environment variables merged into the script process
 * @param prependString optional static text prepended to tool output (null for permission hooks)
 * @param appendString  optional static text appended to tool output (null for permission hooks)
 */
public record HookEntryConfig(
    @Nullable String script,
    int timeout,
    boolean failSilently,
    boolean async,
    @NotNull Map<String, String> env,
    @Nullable String prependString,
    @Nullable String appendString
) {

    private static final int DEFAULT_TIMEOUT = 10;

    public HookEntryConfig {
        if (timeout <= 0) timeout = DEFAULT_TIMEOUT;
        boolean hasScript = script != null && !script.isBlank();
        boolean hasPrepend = prependString != null && !prependString.isEmpty();
        boolean hasAppend = appendString != null && !appendString.isEmpty();
        if (!hasScript && !hasPrepend && !hasAppend) {
            throw new IllegalArgumentException("HookEntryConfig requires at least one of: script, prependString, appendString");
        }
    }
}
