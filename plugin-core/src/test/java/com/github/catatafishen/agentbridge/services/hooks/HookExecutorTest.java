package com.github.catatafishen.agentbridge.services.hooks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookExecutorTest {

    @Test
    void parseResult_emptyStdout_returnsNoOp() {
        var result = HookExecutor.parseResult(HookTrigger.SUCCESS, "");
        assertInstanceOfNoOp(result);
    }

    @Test
    void parseResult_blankStdout_returnsNoOp() {
        var result = HookExecutor.parseResult(HookTrigger.SUCCESS, "   \n  ");
        assertInstanceOfNoOp(result);
    }

    @Test
    void parseResult_nullStdout_returnsNoOp() {
        var result = HookExecutor.parseResult(HookTrigger.SUCCESS, null);
        assertInstanceOfNoOp(result);
    }

    @Test
    void parseResult_permission_allow() {
        var result = HookExecutor.parseResult(HookTrigger.PERMISSION, "{\"decision\":\"allow\"}");
        assertInstanceOf(HookResult.PermissionDecision.class, result);
        var decision = (HookResult.PermissionDecision) result;
        assertTrue(decision.allowed());
    }

    @Test
    void parseResult_permission_deny() {
        var result = HookExecutor.parseResult(HookTrigger.PERMISSION, "{\"decision\":\"deny\",\"reason\":\"Policy violation\"}");
        assertInstanceOf(HookResult.PermissionDecision.class, result);
        var decision = (HookResult.PermissionDecision) result;
        assertFalse(decision.allowed());
        assertEquals("Policy violation", decision.reason());
    }

    @Test
    void parseResult_permission_noDecision_returnsNoOp() {
        var result = HookExecutor.parseResult(HookTrigger.PERMISSION, "{\"other\":\"field\"}");
        assertInstanceOfNoOp(result);
    }

    @Test
    void parseResult_pre_modifiedArguments() {
        var result = HookExecutor.parseResult(HookTrigger.PRE, "{\"arguments\":{\"branch\":\"safe-name\"}}");
        assertInstanceOf(HookResult.ModifiedArguments.class, result);
        var modified = (HookResult.ModifiedArguments) result;
        assertEquals("safe-name", modified.arguments().get("branch").getAsString());
    }

    @Test
    void parseResult_pre_error_blocksExecution() {
        var result = HookExecutor.parseResult(HookTrigger.PRE, "{\"error\":\"Policy violation\"}");
        assertInstanceOf(HookResult.PreHookFailure.class, result);
        var failure = (HookResult.PreHookFailure) result;
        assertEquals("Policy violation", failure.error());
    }

    @Test
    void parseResult_pre_blankError_usesDefaultMessage() {
        var result = HookExecutor.parseResult(HookTrigger.PRE, "{\"error\":\"   \"}");
        assertInstanceOf(HookResult.PreHookFailure.class, result);
        var failure = (HookResult.PreHookFailure) result;
        assertEquals("Pre-hook stopped tool execution", failure.error());
    }

    @Test
    void parseResult_pre_noArguments_returnsNoOp() {
        var result = HookExecutor.parseResult(HookTrigger.PRE, "{\"other\":\"field\"}");
        assertInstanceOfNoOp(result);
    }

    @Test
    void parseResult_success_replaceOutput() {
        var result = HookExecutor.parseResult(HookTrigger.SUCCESS, "{\"output\":\"replaced text\"}");
        assertInstanceOf(HookResult.OutputModification.class, result);
        var mod = (HookResult.OutputModification) result;
        assertTrue(mod.isReplacement());
        assertEquals("replaced text", mod.replacedOutput());
    }

    @Test
    void parseResult_success_appendOutput() {
        var result = HookExecutor.parseResult(HookTrigger.SUCCESS, "{\"append\":\"\\nTip: create a PR\"}");
        assertInstanceOf(HookResult.OutputModification.class, result);
        var mod = (HookResult.OutputModification) result;
        assertFalse(mod.isReplacement());
        assertEquals("\nTip: create a PR", mod.appendedText());
    }

    @Test
    void parseResult_success_plainText_treatedAsReplacement() {
        var result = HookExecutor.parseResult(HookTrigger.SUCCESS, "plain text output\n");
        assertInstanceOf(HookResult.OutputModification.class, result);
        var mod = (HookResult.OutputModification) result;
        assertTrue(mod.isReplacement());
        assertEquals("plain text output", mod.replacedOutput());
    }

    @Test
    void parseResult_success_nullOutput_replacesWithEmpty() {
        var result = HookExecutor.parseResult(HookTrigger.SUCCESS, "{\"output\":null}");
        assertInstanceOf(HookResult.OutputModification.class, result);
        var mod = (HookResult.OutputModification) result;
        assertTrue(mod.isReplacement());
        assertEquals("", mod.replacedOutput());
    }

    @Test
    void parseResult_failure_replaceError() {
        var result = HookExecutor.parseResult(HookTrigger.FAILURE, "{\"output\":\"Friendly error\"}");
        assertInstanceOf(HookResult.OutputModification.class, result);
        var mod = (HookResult.OutputModification) result;
        assertTrue(mod.isReplacement());
        assertEquals("Friendly error", mod.replacedOutput());
    }

    @Test
    void parseResult_failure_appendSuggestion() {
        var result = HookExecutor.parseResult(HookTrigger.FAILURE, "{\"append\":\"\\nSuggestion: check permissions\"}");
        assertInstanceOf(HookResult.OutputModification.class, result);
        var mod = (HookResult.OutputModification) result;
        assertFalse(mod.isReplacement());
        assertEquals("\nSuggestion: check permissions", mod.appendedText());
    }

    @Test
    void parseResult_success_jsonWithoutOutputOrAppend_treatedAsPlainText() {
        var result = HookExecutor.parseResult(HookTrigger.SUCCESS, "{\"message\":\"ignored\"}");
        assertInstanceOf(HookResult.OutputModification.class, result);
        var mod = (HookResult.OutputModification) result;
        assertTrue(mod.isReplacement());
        assertEquals("{\"message\":\"ignored\"}", mod.replacedOutput());
    }

    private static void assertInstanceOfNoOp(HookResult result) {
        assertInstanceOf(HookResult.NoOp.class, result);
    }

    private static <T> void assertInstanceOf(Class<T> expected, Object actual) {
        assertNotNull(actual, "Expected " + expected.getSimpleName() + " but got null");
        assertTrue(expected.isInstance(actual),
            "Expected " + expected.getSimpleName() + " but got " + actual.getClass().getSimpleName());
    }
}
