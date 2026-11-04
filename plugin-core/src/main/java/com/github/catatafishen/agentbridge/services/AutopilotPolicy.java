package com.github.catatafishen.agentbridge.services;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Autopilot / Supervised decision for plugin tool permissions.
 * <p>
 * In autopilot, a tool whose permission resolved to {@link ToolPermission#ASK} runs without
 * prompting the user. {@link ToolPermission#DENY} is never relaxed, so tools the user (or an
 * abuse check) blocked stay blocked. Note that this also covers tools the user explicitly set
 * to ASK: autopilot means "do not ask".
 * <p>
 * The user's choice is a per-agent session option stored under {@link #OPTION_KEY}.
 * No IDE dependencies — unit-testable in isolation.
 */
public final class AutopilotPolicy {

    /** Session option key under which the choice is stored. */
    public static final String OPTION_KEY = "agentbridge_autopilot";
    public static final String AUTOPILOT = "autopilot";
    public static final String SUPERVISED = "supervised";

    private AutopilotPolicy() {
    }

    /** Whether a stored option value means autopilot. Anything else (including unset) is supervised. */
    public static boolean isAutopilot(@Nullable String storedValue) {
        return AUTOPILOT.equals(storedValue);
    }

    /** The permission to enforce: ASK becomes ALLOW in autopilot; ALLOW and DENY are unchanged. */
    @NotNull
    public static ToolPermission apply(@NotNull ToolPermission permission, boolean autopilot) {
        return autopilot && permission == ToolPermission.ASK ? ToolPermission.ALLOW : permission;
    }
}
