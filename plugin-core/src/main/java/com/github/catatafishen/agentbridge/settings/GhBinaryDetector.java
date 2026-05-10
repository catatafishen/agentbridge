package com.github.catatafishen.agentbridge.settings;

import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.SystemProperties;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Binary detector for the {@code gh} CLI, used for Copilot billing data.
 *
 * <p>Reads the user-configured path from {@link BillingSettings}, then falls back to
 * the standard PATH-based detection (handled by the base class). Only provides
 * paths that are truly unique to the {@code gh} binary — generic directories like
 * {@code /opt/homebrew/bin} and {@code /usr/local/bin} are already covered by
 * {@link BinaryDetector#findBinaryPath(String)}.
 */
public final class GhBinaryDetector extends ClientBinaryDetector {

    @Override
    @Nullable
    protected String getConfiguredPath() {
        String custom = BillingSettings.getInstance().getGhBinaryPath();
        return (custom == null || custom.isBlank()) ? null : custom.trim();
    }

    @Override
    @NotNull
    protected List<String> additionalSearchPaths() {
        if (SystemInfo.isWindows) {
            return List.of(
                "C:\\Program Files\\GitHub CLI\\gh.exe",
                "C:\\Program Files (x86)\\GitHub CLI\\gh.exe",
                SystemProperties.getUserHome() + "\\AppData\\Local\\GitHub CLI\\gh.exe"
            );
        }
        // macOS paths (/opt/homebrew/bin, /usr/local/bin) are covered by BinaryDetector.
        // Only add Linux-specific locations here.
        return List.of(
            "/snap/bin/gh",
            "/home/linuxbrew/.linuxbrew/bin/gh",
            SystemProperties.getUserHome() + "/.local/bin/gh"
        );
    }
}
