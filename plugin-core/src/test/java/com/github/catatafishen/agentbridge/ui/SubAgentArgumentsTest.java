package com.github.catatafishen.agentbridge.ui;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SubAgentArgumentsTest {

    @Test
    void readsDescriptionAndPrompt() {
        var details = SubAgentArguments.parse("""
            {"description": "Find the title", "prompt": "Read README.md", "subagent_type": "general"}
            """);

        assertNotNull(details);
        assertEquals("general", details.getAgentType());
        assertEquals("Find the title", details.getDescription());
        assertEquals("Read README.md", details.getPrompt());
    }

    @Test
    void readsTheAgentOfOpenCodesSubagentTool() {
        var details = SubAgentArguments.parse("""
            {"agent": "explore", "description": "Find usages", "prompt": "Search for Foo"}
            """);

        assertNotNull(details);
        assertEquals("explore", details.getAgentType());
        assertEquals("Find usages", details.getDescription());
    }

    @Test
    void subagentTypeTakesPrecedenceOverAgent() {
        var details = SubAgentArguments.parse("{\"agent\": \"general\", \"subagent_type\": \"explore\"}");

        assertNotNull(details);
        assertEquals("explore", details.getAgentType());
    }

    @Test
    void anAgentAloneIsADetail() {
        var details = SubAgentArguments.parse("{\"agent\": \"explore\"}");

        assertNotNull(details);
        assertEquals("explore", details.getAgentType());
        assertNull(details.getDescription());
        assertNull(details.getPrompt());
    }

    @Test
    void keepsAPromptWithoutDescriptionAndViceVersa() {
        var promptOnly = SubAgentArguments.parse("{\"prompt\": \"Do it\"}");
        var descriptionOnly = SubAgentArguments.parse("{\"description\": \"Short\"}");

        assertNotNull(promptOnly);
        assertNull(promptOnly.getAgentType());
        assertNull(promptOnly.getDescription());
        assertEquals("Do it", promptOnly.getPrompt());
        assertNotNull(descriptionOnly);
        assertEquals("Short", descriptionOnly.getDescription());
        assertNull(descriptionOnly.getPrompt());
    }

    @Test
    void emptyInputCarriesNothing() {
        assertNull(SubAgentArguments.parse("{}"), "OpenCode's first tool_call has an empty rawInput");
        assertNull(SubAgentArguments.parse("{\"description\": \"  \", \"prompt\": \"\", \"agent\": \" \"}"));
    }

    @Test
    void nonObjectsAndGarbageCarryNothing() {
        assertNull(SubAgentArguments.parse(null));
        assertNull(SubAgentArguments.parse(""));
        assertNull(SubAgentArguments.parse("[1, 2]"));
        assertNull(SubAgentArguments.parse("not json"));
    }

    @Test
    void popupParamsHoldWhatTheSubAgentWasGiven() {
        var params = SubAgentArguments.popupParams(null, "explore", "Find usages", "Search for Foo");

        assertEquals("{\"agent\":\"explore\",\"description\":\"Find usages\",\"prompt\":\"Search for Foo\"}", params);
    }

    @Test
    void popupParamsKeepKnownValuesWhenALaterUpdateLeavesThemOut() {
        var first = SubAgentArguments.popupParams(null, "general", "subagent", null);

        var updated = JsonParser.parseString(SubAgentArguments.popupParams(first, "explore", " ", "Search for Foo"))
            .getAsJsonObject();

        assertEquals("explore", updated.get("agent").getAsString(), "a later type replaces the guess");
        assertEquals("subagent", updated.get("description").getAsString(), "a blank description keeps the earlier one");
        assertEquals("Search for Foo", updated.get("prompt").getAsString());
    }

    @Test
    void popupParamsStartAfreshFromUnreadableJson() {
        assertEquals("{}", SubAgentArguments.popupParams("not json", null, null, null));
        assertEquals("{\"agent\":\"explore\"}", SubAgentArguments.popupParams("[1]", "explore", null, ""));
    }

    @Test
    void ignoresNonTextValues() {
        assertNull(SubAgentArguments.parse("{\"prompt\": {\"text\": \"x\"}, \"description\": [1]}"));
    }

    @Test
    void ignoresNumbersAndBooleans() {
        // Gson would happily convert these to "true" / "42"; only JSON strings are text.
        assertNull(SubAgentArguments.parse("{\"description\": true, \"prompt\": 42}"));

        var details = SubAgentArguments.parse("{\"description\": false, \"prompt\": \"Do it\"}");
        assertNotNull(details);
        assertNull(details.getDescription());
        assertEquals("Do it", details.getPrompt());
    }
}
