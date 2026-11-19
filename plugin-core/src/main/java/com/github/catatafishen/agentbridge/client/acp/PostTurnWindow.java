package com.github.catatafishen.agentbridge.client.acp;

import java.util.concurrent.TimeUnit;

/**
 * Decides how long an ACP client keeps showing an agent's messages after its turn has ended.
 *
 * <p>An agent can keep working after {@code session/prompt} returns, for example a background
 * sub-agent whose report the main agent sends in a turn of its own. The client keeps its update
 * consumer for a post-turn window and drops anything that arrives after the window has closed.
 *
 * <p>An agent may send nothing over ACP while its background work runs, so the window also stays
 * open while an AgentBridge MCP tool is running or the agent reports work still open (OpenCode's
 * child-session statuses). Otherwise it closes after {@link #QUIET_NANOS} without either an agent
 * message or a tool call starting or finishing.
 *
 * <p>Times are {@link System#nanoTime()} values and are compared by subtraction only.
 */
final class PostTurnWindow {

    /** Silence after which the window closes. */
    static final long QUIET_NANOS = TimeUnit.SECONDS.toNanos(120);

    private PostTurnWindow() {
    }

    /**
     * Returns how long to wait before checking again, or {@code 0} when the window should close now.
     *
     * @param nowNanos               the current time
     * @param lastAgentActivityNanos when the agent last sent a message that shows it is working
     * @param lastToolActivityNanos  when an AgentBridge tool call last started or finished
     * @param workRunning            whether an AgentBridge tool call or reported background work is running now
     * @param quietNanos             how much silence closes the window
     */
    static long nanosUntilNextCheck(long nowNanos, long lastAgentActivityNanos, long lastToolActivityNanos,
                                    boolean workRunning, long quietNanos) {
        if (workRunning) {
            return quietNanos;
        }
        long quietFor = Math.min(nowNanos - lastAgentActivityNanos, nowNanos - lastToolActivityNanos);
        return quietFor >= quietNanos ? 0 : quietNanos - quietFor;
    }
}
