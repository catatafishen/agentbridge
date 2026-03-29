package com.github.catatafishen.ideagentforcopilot.acp.client;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global registry of all live agent sub-processes.
 *
 * <p>Registers a JVM shutdown hook that kills every tracked process when the JVM exits —
 * including on SIGTERM and {@link System#exit(int)}.  This is a safety net for the case
 * where IntelliJ crashes, is force-closed, or {@link
 * com.github.catatafishen.ideagentforcopilot.services.ActiveAgentManager#dispose()} fails
 * to complete before the JVM shuts down.  Without this hook, Kiro/Junie/Claude processes
 * can survive the IDE and keep making API calls unsupervised.
 *
 * <p>Note: shutdown hooks do <em>not</em> run on SIGKILL — that is unpreventable.
 */
final class AgentProcessRegistry {

    private static final Set<Process> PROCESSES = ConcurrentHashMap.newKeySet();

    static {
        Thread hook = new Thread(AgentProcessRegistry::killAll, "agentbridge-process-cleanup");
        hook.setDaemon(false);
        Runtime.getRuntime().addShutdownHook(hook);
    }

    private AgentProcessRegistry() {}

    static void register(Process process) {
        PROCESSES.add(process);
    }

    static void unregister(Process process) {
        if (process != null) {
            PROCESSES.remove(process);
        }
    }

    private static void killAll() {
        for (Process p : PROCESSES) {
            if (p != null && p.isAlive()) {
                try {
                    AcpClient.destroyProcessTree(p);
                } catch (Exception ignored) {
                    // Best-effort: we are shutting down, nothing useful we can do here.
                }
            }
        }
    }
}
