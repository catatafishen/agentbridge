package com.github.catatafishen.agentbridge.client.acp;

import java.util.concurrent.TimeUnit;

/**
 * Decides how long an ACP client keeps showing an agent's messages after its turn has ended.
 *
 * <p>An agent can keep working after {@code session/prompt} returns, for example a background
 * sub-agent whose report the main agent sends in a turn of its own. The client keeps its update
 * consumer for a post-turn window and drops anything that arrives after the window has closed.
 *
 * <p>Some agents send nothing over ACP while their background work runs: OpenCode runs a
 * sub-agent in a child session that ACP never streams. The only sign of that work is the
 * AgentBridge MCP tools the sub-agent calls. So the window stays open while such a tool is
 * running, and closes only after {@link #QUIET_NANOS} without either an agent message or a tool
 * call starting or finishing. A sub-agent that thinks for longer than that without calling a
 * tool still outlives the window.
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
     * @param toolRunning            whether an AgentBridge tool call is running now
     * @param quietNanos             how much silence closes the window
     */
    static long nanosUntilNextCheck(long nowNanos, long lastAgentActivityNanos, long lastToolActivityNanos,
                                    boolean toolRunning, long quietNanos) {
        if (toolRunning) {
            return quietNanos;
        }
        long quietFor = Math.min(nowNanos - lastAgentActivityNanos, nowNanos - lastToolActivityNanos);
        return quietFor >= quietNanos ? 0 : quietNanos - quietFor;
    }
}
