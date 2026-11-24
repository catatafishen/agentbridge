package com.github.catatafishen.agentbridge.psi.tools.terminal;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalWaitArgsTest {

    private static JsonObject args(String json) {
        return com.google.gson.JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void noWaitArgumentsMeansNoWait() {
        var parsed = TerminalWaitArgs.parse(args("{}"), true);

        assertFalse(parsed.requested());
        assertNull(parsed.error());
    }

    @Test
    void validWaitIsReturnedWithoutNotice() {
        var parsed = TerminalWaitArgs.parse(args("{\"wait_seconds\": 30}"), true);

        assertTrue(parsed.requested());
        assertEquals(30, parsed.seconds());
        assertNull(parsed.notice());
        assertNull(parsed.pattern());
    }

    @Test
    void zeroAndNegativeWaitAreRejectedNotReplacedByADefault() {
        for (String value : new String[]{"0", "-5"}) {
            var parsed = TerminalWaitArgs.parse(args("{\"wait_seconds\": " + value + "}"), true);

            assertFalse(parsed.requested());
            assertNotNull(parsed.error());
            assertTrue(parsed.error().startsWith("Error: "), parsed.error());
            assertTrue(parsed.error().contains("wait_seconds"), parsed.error());
        }
    }

    @Test
    void nonNumericWaitIsAnErrorNotAnException() {
        var parsed = TerminalWaitArgs.parse(args("{\"wait_seconds\": \"soon\"}"), true);

        assertNotNull(parsed.error());
        assertTrue(parsed.error().startsWith("Error: wait_seconds must be an integer"), parsed.error());
    }

    @Test
    void waitAboveTheCeilingIsClampedAndTheCallerIsTold() {
        var parsed = TerminalWaitArgs.parse(args("{\"wait_seconds\": 500}"), true);

        assertEquals(170, parsed.seconds());
        assertNotNull(parsed.notice());
        assertTrue(parsed.notice().contains("500"), parsed.notice());
    }

    @Test
    void waitForWithoutWaitSecondsIsRejected() {
        var parsed = TerminalWaitArgs.parse(args("{\"wait_for\": \"done\"}"), true);

        assertEquals("Error: wait_for requires wait_seconds (the maximum time to wait).", parsed.error());
    }

    @Test
    void waitForIsCompiledToAPattern() {
        var parsed = TerminalWaitArgs.parse(args("{\"wait_seconds\": 5, \"wait_for\": \"BUILD \\\\w+\"}"), true);

        assertNotNull(parsed.pattern());
        assertTrue(parsed.pattern().matcher("BUILD SUCCESSFUL").find());
    }

    @Test
    void invalidRegexIsReportedAsAnError() {
        var parsed = TerminalWaitArgs.parse(args("{\"wait_seconds\": 5, \"wait_for\": \"(unclosed\"}"), true);

        assertNotNull(parsed.error());
        assertTrue(parsed.error().startsWith("Error: wait_for is not a valid regex"), parsed.error());
    }

    @Test
    void blankWaitForMeansIdleDetection() {
        var parsed = TerminalWaitArgs.parse(args("{\"wait_seconds\": 5, \"wait_for\": \"  \"}"), true);

        assertTrue(parsed.requested());
        assertNull(parsed.pattern());
        assertNull(parsed.error());
    }

    @Test
    void waitForIsIgnoredWhenTheToolDoesNotSupportIt() {
        var parsed = TerminalWaitArgs.parse(args("{\"wait_seconds\": 5, \"wait_for\": \"(unclosed\"}"), false);

        assertTrue(parsed.requested());
        assertNull(parsed.pattern());
        assertNull(parsed.error());
    }

    @Test
    void waitForAloneIsNotAnErrorWhenUnsupported() {
        var parsed = TerminalWaitArgs.parse(args("{\"wait_for\": \"x\"}"), false);

        assertFalse(parsed.requested());
        assertNull(parsed.error());
    }

    @Test
    void jsonNullWaitSecondsMeansNotRequested() {
        var parsed = TerminalWaitArgs.parse(args("{\"wait_seconds\": null}"), true);

        assertFalse(parsed.requested());
        assertNull(parsed.error());
    }
}
