package com.github.catatafishen.agentbridge.client.acp;

import com.github.catatafishen.agentbridge.acp.protocol.NewSessionResponse;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link KiroClient#parseCommandsAvailable(JsonObject)} — the pure parser that
 * turns a Kiro {@code _kiro.dev/commands/available} payload into command records with
 * descriptions preserved for the prompt autocomplete.
 */
class KiroClientCommandsTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    @DisplayName("parses name and description for each command")
    void parsesNameAndDescription() {
        List<NewSessionResponse.AvailableCommand> commands = KiroClient.parseCommandsAvailable(json("""
            {"commands":[
              {"name":"compact","description":"Compact the conversation"},
              {"name":"clear","description":"Clear the session"}
            ]}"""));

        assertEquals(2, commands.size());
        assertEquals("compact", commands.get(0).name());
        assertEquals("Compact the conversation", commands.get(0).description());
        assertEquals("clear", commands.get(1).name());
        assertEquals("Clear the session", commands.get(1).description());
    }

    @Test
    @DisplayName("missing description defaults to empty string")
    void missingDescriptionDefaultsToEmpty() {
        List<NewSessionResponse.AvailableCommand> commands = KiroClient.parseCommandsAvailable(json("""
            {"commands":[{"name":"help"}]}"""));

        assertEquals(1, commands.size());
        assertEquals("help", commands.get(0).name());
        assertEquals("", commands.get(0).description());
    }

    @Test
    @DisplayName("skips entries with missing, null, or blank names")
    void skipsInvalidNames() {
        List<NewSessionResponse.AvailableCommand> commands = KiroClient.parseCommandsAvailable(json("""
            {"commands":[
              {"description":"no name"},
              {"name":null,"description":"null name"},
              {"name":"  ","description":"blank name"},
              {"name":"valid","description":"ok"}
            ]}"""));

        assertEquals(1, commands.size());
        assertEquals("valid", commands.get(0).name());
    }

    @Test
    @DisplayName("skips non-object array entries")
    void skipsNonObjectEntries() {
        List<NewSessionResponse.AvailableCommand> commands = KiroClient.parseCommandsAvailable(json("""
            {"commands":["string-entry",42,{"name":"valid"}]}"""));

        assertEquals(1, commands.size());
        assertEquals("valid", commands.get(0).name());
    }

    @Test
    @DisplayName("missing commands array yields an empty list")
    void missingCommandsArrayYieldsEmpty() {
        assertTrue(KiroClient.parseCommandsAvailable(json("{}")).isEmpty());
    }
}
