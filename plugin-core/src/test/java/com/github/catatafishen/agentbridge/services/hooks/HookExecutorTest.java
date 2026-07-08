package com.github.catatafishen.agentbridge.services.hooks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookExecutorTest {

    @ParameterizedTest(name = "parseResult returns NoOp for {0} stdout")
    @NullSource
    @ValueSource(strings = {"", "   \n  "})
    void parseResult_blankOrNullStdout_returnsNoOp(String stdout) {
        var result = HookExecutor.parseResult(HookTrigger.SUCCESS, stdout);
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

    @ParameterizedTest(name = "parseResult for {0} trigger parses output field as replacement")
    @EnumSource(value = HookTrigger.class, names = {"SUCCESS", "FAILURE"})
    void parseResult_outputField_replaceOutput(HookTrigger trigger) {
        var result = HookExecutor.parseResult(trigger, "{\"output\":\"replaced text\"}");
        assertInstanceOf(HookResult.OutputModification.class, result);
        var mod = (HookResult.OutputModification) result;
        assertTrue(mod.isReplacement());
        assertEquals("replaced text", mod.replacedOutput());
    }

    @ParameterizedTest(name = "parseResult for {0} trigger parses append field")
    @EnumSource(value = HookTrigger.class, names = {"SUCCESS", "FAILURE"})
    void parseResult_appendField_appendOutput(HookTrigger trigger) {
        var result = HookExecutor.parseResult(trigger, "{\"append\":\"\\nTip: create a PR\"}");
        assertInstanceOf(HookResult.OutputModification.class, result);
        var mod = (HookResult.OutputModification) result;
        assertFalse(mod.isReplacement());
        assertEquals("\nTip: create a PR", mod.appendedText());
    }

    @ParameterizedTest(name = "parseResult for {0} trigger treats plain text as replacement")
    @EnumSource(value = HookTrigger.class, names = {"SUCCESS", "FAILURE"})
    void parseResult_plainText_treatedAsReplacement(HookTrigger trigger) {
        var result = HookExecutor.parseResult(trigger, "plain text output\n");
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
    void parseResult_success_jsonWithoutOutputOrAppend_treatedAsPlainText() {
        var result = HookExecutor.parseResult(HookTrigger.SUCCESS, "{\"message\":\"ignored\"}");
        assertInstanceOf(HookResult.OutputModification.class, result);
        var mod = (HookResult.OutputModification) result;
        assertTrue(mod.isReplacement());
        assertEquals("{\"message\":\"ignored\"}", mod.replacedOutput());
    }

    @Test
    void parseResult_failure_stateOverrideSuccess_resolvesError() {
        var result = HookExecutor.parseResult(HookTrigger.FAILURE, "{\"output\":\"Fixed output\",\"state\":\"success\"}");
        assertInstanceOf(HookResult.OutputModification.class, result);
        var mod = (HookResult.OutputModification) result;
        assertTrue(mod.isReplacement());
        assertEquals("Fixed output", mod.replacedOutput());
        assertEquals(Boolean.TRUE, mod.stateOverride());
    }

    @Test
    void parseResult_success_stateOverrideError_convertsToError() {
        var result = HookExecutor.parseResult(HookTrigger.SUCCESS, "{\"output\":\"Flagged output\",\"state\":\"error\"}");
        assertInstanceOf(HookResult.OutputModification.class, result);
        var mod = (HookResult.OutputModification) result;
        assertTrue(mod.isReplacement());
        assertEquals("Flagged output", mod.replacedOutput());
        assertEquals(Boolean.FALSE, mod.stateOverride());
    }

    @Test
    void parseResult_success_noStateField_noOverride() {
        var result = HookExecutor.parseResult(HookTrigger.SUCCESS, "{\"output\":\"normal\"}");
        assertInstanceOf(HookResult.OutputModification.class, result);
        var mod = (HookResult.OutputModification) result;
        assertNull(mod.stateOverride());
    }

    @Test
    void parseResult_failure_appendWithStateOverride() {
        var result = HookExecutor.parseResult(HookTrigger.FAILURE, "{\"append\":\"\\nRetried and succeeded\",\"state\":\"success\"}");
        assertInstanceOf(HookResult.OutputModification.class, result);
        var mod = (HookResult.OutputModification) result;
        assertFalse(mod.isReplacement());
        assertEquals("\nRetried and succeeded", mod.appendedText());
        assertEquals(Boolean.TRUE, mod.stateOverride());
    }

    @Test
    void isPosixShellExe_posixShells_returnTrue() {
        assertTrue(HookExecutor.isPosixShellExe("sh"));
        assertTrue(HookExecutor.isPosixShellExe("bash"));
        assertTrue(HookExecutor.isPosixShellExe("zsh"));
        assertTrue(HookExecutor.isPosixShellExe("/usr/bin/bash"));
        assertTrue(HookExecutor.isPosixShellExe("/bin/sh"));
        assertTrue(HookExecutor.isPosixShellExe("C:\\Program Files\\Git\\bin\\bash.exe"));
    }

    @Test
    void isPosixShellExe_nonPosixShells_returnFalse() {
        assertFalse(HookExecutor.isPosixShellExe("powershell"));
        assertFalse(HookExecutor.isPosixShellExe("PowerShell"));
        assertFalse(HookExecutor.isPosixShellExe("pwsh"));
        assertFalse(HookExecutor.isPosixShellExe("cmd"));
        assertFalse(HookExecutor.isPosixShellExe("C:\\Windows\\System32\\cmd.exe"));
        assertFalse(HookExecutor.isPosixShellExe("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"));
    }

    @Test
    void isShellInterpreter_commonPosixShells_returnTrue() {
        assertTrue(HookExecutor.isShellInterpreter("sh"));
        assertTrue(HookExecutor.isShellInterpreter("bash"));
        assertTrue(HookExecutor.isShellInterpreter("dash"));
        assertTrue(HookExecutor.isShellInterpreter("/bin/bash"));
        assertTrue(HookExecutor.isShellInterpreter("/usr/bin/dash"));
    }

    @Test
    void isShellInterpreter_otherInterpreters_returnFalse() {
        assertFalse(HookExecutor.isShellInterpreter("python3"));
        assertFalse(HookExecutor.isShellInterpreter("node"));
        assertFalse(HookExecutor.isShellInterpreter("zsh"));
        assertFalse(HookExecutor.isShellInterpreter("powershell"));
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
