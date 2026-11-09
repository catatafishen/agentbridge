package com.github.catatafishen.agentbridge.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentBinaryResolverDesktopBundleTest {

    @Test
    @DisplayName("Windows win-unpacked desktop executable is rejected")
    void windowsDesktopBundleIsRejected() {
        assertTrue(AgentBinaryResolver.isDesktopAppBundle(
            "C:\\Users\\Michael\\AppData\\Local\\hermes\\hermes-agent\\apps\\desktop\\release\\win-unpacked\\Hermes.exe"));
    }

    @Test
    @DisplayName("linux-unpacked desktop executable is rejected")
    void linuxDesktopBundleIsRejected() {
        assertTrue(AgentBinaryResolver.isDesktopAppBundle("/opt/app/release/linux-unpacked/hermes"));
    }

    @Test
    @DisplayName("regular CLI locations are accepted")
    void cliLocationsAreAccepted() {
        assertFalse(AgentBinaryResolver.isDesktopAppBundle("C:\\Users\\Michael\\.local\\bin\\hermes.exe"));
        assertFalse(AgentBinaryResolver.isDesktopAppBundle("/usr/local/bin/hermes"));
    }
}
