package com.github.catatafishen.agentbridge.services;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Application-level service for managing the AgentBridge plugin.
 * Singleton that lives for the entire IDE session.
 */
@Service(Service.Level.APP)
public final class AgentBridgeService implements Disposable {
    private static final Logger LOG = Logger.getInstance(AgentBridgeService.class);

    public AgentBridgeService() {
        LOG.info("AgentBridge Service initialized");
    }

    @Override
    public void dispose() {
        LOG.info("AgentBridge Service disposed");
    }
}
