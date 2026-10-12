package com.github.catatafishen.agentbridge.ui;

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
        assertEquals("Find the title", details.getDescription());
        assertEquals("Read README.md", details.getPrompt());
    }

    @Test
    void keepsAPromptWithoutDescriptionAndViceVersa() {
        var promptOnly = SubAgentArguments.parse("{\"prompt\": \"Do it\"}");
        var descriptionOnly = SubAgentArguments.parse("{\"description\": \"Short\"}");

        assertNotNull(promptOnly);
        assertNull(promptOnly.getDescription());
        assertEquals("Do it", promptOnly.getPrompt());
        assertNotNull(descriptionOnly);
        assertEquals("Short", descriptionOnly.getDescription());
        assertNull(descriptionOnly.getPrompt());
    }

    @Test
    void emptyInputCarriesNothing() {
        assertNull(SubAgentArguments.parse("{}"), "OpenCode's first tool_call has an empty rawInput");
        assertNull(SubAgentArguments.parse("{\"description\": \"  \", \"prompt\": \"\"}"));
    }

    @Test
    void nonObjectsAndGarbageCarryNothing() {
        assertNull(SubAgentArguments.parse(null));
        assertNull(SubAgentArguments.parse(""));
        assertNull(SubAgentArguments.parse("[1, 2]"));
        assertNull(SubAgentArguments.parse("not json"));
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
