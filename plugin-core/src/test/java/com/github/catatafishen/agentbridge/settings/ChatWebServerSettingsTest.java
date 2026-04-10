package com.github.catatafishen.agentbridge.settings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatWebServerSettingsTest {

    private ChatWebServerSettings settings;

    @BeforeEach
    void setUp() {
        settings = new ChatWebServerSettings();
    }

    @Test
    @DisplayName("default port is DEFAULT_PORT constant")
    void defaultPort() {
        assertEquals(ChatWebServerSettings.DEFAULT_PORT, settings.getPort());
    }

    @Test
    @DisplayName("default enabled is false")
    void defaultEnabledIsFalse() {
        assertFalse(settings.isEnabled());
    }

    @Test
    @DisplayName("default httpsEnabled is true")
    void defaultHttpsEnabledIsTrue() {
        assertTrue(settings.isHttpsEnabled());
    }

    @Test
    @DisplayName("setPort round-trip")
    void setPortRoundTrip() {
        settings.setPort(9000);
        assertEquals(9000, settings.getPort());
    }

    @Test
    @DisplayName("setEnabled round-trip")
    void setEnabledRoundTrip() {
        settings.setEnabled(true);
        assertTrue(settings.isEnabled());
        settings.setEnabled(false);
        assertFalse(settings.isEnabled());
    }

    @Test
    @DisplayName("setHttpsEnabled round-trip")
    void setHttpsEnabledRoundTrip() {
        settings.setHttpsEnabled(false);
        assertFalse(settings.isHttpsEnabled());
        settings.setHttpsEnabled(true);
        assertTrue(settings.isHttpsEnabled());
    }

    @Test
    @DisplayName("VAPID private key defaults to empty string")
    void vapidPrivateKeyDefault() {
        assertEquals("", settings.getVapidPrivateKey());
    }

    @Test
    @DisplayName("VAPID public key defaults to empty string")
    void vapidPublicKeyDefault() {
        assertEquals("", settings.getVapidPublicKey());
    }

    @Test
    @DisplayName("setVapidPrivateKey round-trip")
    void setVapidPrivateKeyRoundTrip() {
        settings.setVapidPrivateKey("abc123private");
        assertEquals("abc123private", settings.getVapidPrivateKey());
    }

    @Test
    @DisplayName("setVapidPublicKey round-trip")
    void setVapidPublicKeyRoundTrip() {
        settings.setVapidPublicKey("abc123public");
        assertEquals("abc123public", settings.getVapidPublicKey());
    }

    @Test
    @DisplayName("setVapidPrivateKey accepts null")
    void setVapidPrivateKeyNull() {
        settings.setVapidPrivateKey("something");
        settings.setVapidPrivateKey(null);
        assertNull(settings.getVapidPrivateKey());
    }

    @Test
    @DisplayName("getState returns current state")
    void getStateReturnsCurrentState() {
        settings.setPort(8888);
        assertEquals(8888, settings.getState().getPort());
    }

    @Test
    @DisplayName("loadState replaces internal state")
    void loadStateReplacesState() {
        var newState = new ChatWebServerSettings.State();
        newState.setPort(1234);
        newState.setEnabled(true);
        settings.loadState(newState);
        assertEquals(1234, settings.getPort());
        assertTrue(settings.isEnabled());
    }

    @Test
    @DisplayName("State setters and getters are consistent")
    void stateSettersAndGettersConsistent() {
        var state = new ChatWebServerSettings.State();
        state.setPort(7777);
        state.setEnabled(true);
        state.setHttpsEnabled(false);
        assertEquals(7777, state.getPort());
        assertTrue(state.isEnabled());
        assertFalse(state.isHttpsEnabled());
    }
}
