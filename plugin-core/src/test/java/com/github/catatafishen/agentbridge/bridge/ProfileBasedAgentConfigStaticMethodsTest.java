package com.github.catatafishen.agentbridge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for pure static methods in {@link ProfileBasedAgentConfig}.
 */
class ProfileBasedAgentConfigStaticMethodsTest {

    // ── parseStandardAuthMethod ────────────────────────────

    @Test
    void parseStandardAuthMethod_returnsNullForNull() {
        assertNull(ProfileBasedAgentConfig.parseStandardAuthMethod(null));
    }

    @Test
    void parseStandardAuthMethod_returnsNullForEmptyArray() {
        assertNull(ProfileBasedAgentConfig.parseStandardAuthMethod(new JsonArray()));
    }

    @Test
    void parseStandardAuthMethod_parsesBasicFields() {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", "github-oauth");
        entry.addProperty("name", "GitHub");
        entry.addProperty("description", "Sign in with GitHub");

        JsonArray arr = new JsonArray();
        arr.add(entry);

        AuthMethod result = ProfileBasedAgentConfig.parseStandardAuthMethod(arr);
        assertNotNull(result);
        assertEquals("github-oauth", result.getId());
        assertEquals("GitHub", result.getName());
        assertEquals("Sign in with GitHub", result.getDescription());
        assertNull(result.getCommand());
        assertNull(result.getArgs());
    }

    @Test
    void parseStandardAuthMethod_defaultsToEmptyStringsWhenFieldsMissing() {
        JsonObject entry = new JsonObject();
        JsonArray arr = new JsonArray();
        arr.add(entry);

        AuthMethod result = ProfileBasedAgentConfig.parseStandardAuthMethod(arr);
        assertNotNull(result);
        assertEquals("", result.getId());
        assertEquals("", result.getName());
        assertEquals("", result.getDescription());
    }

    @Test
    void parseStandardAuthMethod_parsesTerminalAuth() {
        JsonObject termAuth = new JsonObject();
        termAuth.addProperty("command", "/usr/bin/gh");
        JsonArray args = new JsonArray();
        args.add("auth");
        args.add("login");
        termAuth.add("args", args);

        JsonObject meta = new JsonObject();
        meta.add("terminal-auth", termAuth);

        JsonObject entry = new JsonObject();
        entry.addProperty("id", "terminal");
        entry.add("_meta", meta);

        JsonArray arr = new JsonArray();
        arr.add(entry);

        AuthMethod result = ProfileBasedAgentConfig.parseStandardAuthMethod(arr);
        assertNotNull(result);
        assertEquals("/usr/bin/gh", result.getCommand());
        assertEquals(List.of("auth", "login"), result.getArgs());
    }

    @Test
    void parseStandardAuthMethod_noTerminalAuthInMeta() {
        JsonObject meta = new JsonObject();
        JsonObject entry = new JsonObject();
        entry.addProperty("id", "basic");
        entry.add("_meta", meta);

        JsonArray arr = new JsonArray();
        arr.add(entry);

        AuthMethod result = ProfileBasedAgentConfig.parseStandardAuthMethod(arr);
        assertNotNull(result);
        assertNull(result.getCommand());
    }

    @Test
    void parseStandardAuthMethod_usesOnlyFirstElement() {
        JsonObject first = new JsonObject();
        first.addProperty("id", "first");
        JsonObject second = new JsonObject();
        second.addProperty("id", "second");

        JsonArray arr = new JsonArray();
        arr.add(first);
        arr.add(second);

        AuthMethod result = ProfileBasedAgentConfig.parseStandardAuthMethod(arr);
        assertNotNull(result);
        assertEquals("first", result.getId());
    }
}
