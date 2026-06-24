package com.github.catatafishen.agentbridge.services.hooks;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Opt-in capabilities an embedded JavaScript hook may request in its JSON config.
 *
 * <p>By default an embedded {@code .js} hook runs in a strict sandbox (see {@link JsHookEngine}):
 * no filesystem, no subprocess, no host access beyond the {@link HookHostApi} payload/project
 * accessors. A hook that genuinely needs more declares it explicitly:
 * <pre>{@code
 * { "script": "scripts/clear-cache.js", "capabilities": ["filesystem"] }
 * }</pre>
 *
 * <p>The capability list is the gate: {@link HookHostApi} exposes the matching methods
 * ({@code readFile}, {@code exec}, …) but they throw when the capability was not granted, so a
 * hook can only touch the disk or spawn a process if its config says so. Because hook files are
 * author-trusted (they live in the project's own {@code hooks/} directory), this is primarily an
 * <em>auditability</em> and <em>least-privilege wiring</em> mechanism rather than a hard security
 * boundary against a malicious hook author — a glance at the config shows exactly what each hook
 * can reach.
 */
public enum HookCapability {

    /**
     * Read and write files on disk via {@code Hook.readFile/writeFile/deletePath/listDir/...}.
     */
    FILESYSTEM("filesystem"),

    /**
     * Spawn external processes via {@code Hook.exec(...)}.
     */
    SUBPROCESS("subprocess");

    private final String jsonValue;

    HookCapability(@NotNull String jsonValue) {
        this.jsonValue = jsonValue;
    }

    /**
     * The lowercase token used in JSON config {@code "capabilities"} arrays.
     */
    public @NotNull String jsonValue() {
        return jsonValue;
    }

    /**
     * Parses a JSON capability token (case-insensitive), or {@code null} if it matches no capability.
     */
    public static @Nullable HookCapability fromJson(@NotNull String token) {
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        for (HookCapability c : values()) {
            if (c.jsonValue.equals(normalized)) return c;
        }
        return null;
    }

    /**
     * An immutable empty capability set — the default (strict-sandbox) grant.
     */
    public static @NotNull Set<HookCapability> none() {
        return EnumSet.noneOf(HookCapability.class);
    }
}
