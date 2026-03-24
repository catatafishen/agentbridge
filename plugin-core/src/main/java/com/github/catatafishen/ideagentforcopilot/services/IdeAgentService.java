package com.github.catatafishen.ideagentforcopilot.services;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Application-level service for managing the AgentBridge plugin.
 * Singleton that lives for the entire IDE session.
 */
@Service(Service.Level.APP)
public final class IdeAgentService implements Disposable {
    private static final Logger LOG = Logger.getInstance(IdeAgentService.class);

    public IdeAgentService() {
        LOG.info("AgentBridge Service initialized");
    }

    @Override
    public void dispose() {
        LOG.info("AgentBridge Service disposed");
    }
}
