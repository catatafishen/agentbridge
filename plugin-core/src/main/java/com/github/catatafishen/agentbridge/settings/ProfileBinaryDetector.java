package com.github.catatafishen.agentbridge.settings;

import com.github.catatafishen.agentbridge.services.AgentProfile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Binary detector for profile-based clients (Claude CLI, custom agent profiles).
 * Reads the user-configured path from {@link AgentProfile#getCustomBinaryPath()}.
 */
public final class ProfileBinaryDetector extends ClientBinaryDetector {

    private final AgentProfile profile;

    public ProfileBinaryDetector(@NotNull AgentProfile profile) {
        this.profile = profile;
    }

    @Override
    @Nullable
    protected String getConfiguredPath() {
        String custom = profile.getCustomBinaryPath();
        return custom.isEmpty() ? null : custom;
    }
}
