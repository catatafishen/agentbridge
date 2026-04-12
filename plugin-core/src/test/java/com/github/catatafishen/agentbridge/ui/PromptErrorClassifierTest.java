package com.github.catatafishen.agentbridge.ui;

import com.github.catatafishen.agentbridge.agent.AgentException;
import org.junit.jupiter.api.Test;

import java.util.List;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class PromptErrorClassifierTest {

    // Helper: an isAuthenticationError predicate that never matches
    private static final Function<String, Boolean> NO_AUTH = msg -> false;

    // Helper: an isAuthenticationError predicate that matches messages containing "auth"
    private static final Function<String, Boolean> AUTH_CONTAINS = msg -> msg.contains("auth");

    private PromptErrorClassifier.Classification classify(
        Exception exception,
        boolean turnHadContent,
        Function<String, Boolean> isAuthError,
        boolean isClientHealthy) {
        return PromptErrorClassifier.INSTANCE.classify(exception, turnHadContent, isAuthError::apply, isClientHealthy);
    }

    // ── classify: InterruptedException ──────────────────────────────────

    @Test
    void classify_interruptedException_isCancelled() {
        var result = classify(new InterruptedException("interrupted"), false, NO_AUTH, true);
        assertTrue(result.isCancelled());
    }

    @Test
    void classify_interruptedException_displayMessageIsCancelled() {
        var result = classify(new InterruptedException("interrupted"), false, NO_AUTH, true);
        assertEquals("Request cancelled", result.getDisplayMessage());
    }

    @Test
    void classify_interruptedException_shouldNotRestorePromptWhenNoContent() {
        var result = classify(new InterruptedException("interrupted"), false, NO_AUTH, true);
        // cancelled → shouldRestorePrompt = !turnHadContent && !isCancelled = false
        assertFalse(result.getShouldRestorePrompt());
    }

    @Test
    void classify_interruptedException_isRecoverable() {
        var result = classify(new InterruptedException("interrupted"), false, NO_AUTH, true);
        assertTrue(result.isRecoverable());
    }

    // ── classify: InterruptedException as cause ─────────────────────────

    @Test
    void classify_interruptedExceptionAsCause_isCancelled() {
        var wrapper = new RuntimeException("wrapper", new InterruptedException("interrupted"));
        var result = classify(wrapper, false, NO_AUTH, true);
        assertTrue(result.isCancelled());
    }

    @Test
    void classify_interruptedExceptionAsCause_displayMessageIsCancelled() {
        var wrapper = new RuntimeException("wrapper", new InterruptedException("interrupted"));
        var result = classify(wrapper, false, NO_AUTH, true);
        assertEquals("Request cancelled", result.getDisplayMessage());
    }

    // ── classify: AgentException recoverable ────────────────────────────

    @Test
    void classify_recoverableAgentException_isRecoverable() {
        var ex = new AgentException("timeout", null, true);
        var result = classify(ex, false, NO_AUTH, true);
        assertTrue(result.isRecoverable());
    }

    @Test
    void classify_recoverableAgentException_shouldRestorePromptWhenNoContent() {
        var ex = new AgentException("timeout", null, true);
        var result = classify(ex, false, NO_AUTH, true);
        assertTrue(result.getShouldRestorePrompt());
    }

    @Test
    void classify_recoverableAgentException_notCancelled() {
        var ex = new AgentException("timeout", null, true);
        var result = classify(ex, false, NO_AUTH, true);
        assertFalse(result.isCancelled());
    }

    @Test
    void classify_recoverableAgentException_prefixesMessageWithAcpError() {
        var ex = new AgentException("timeout", null, true);
        var result = classify(ex, false, NO_AUTH, true);
        assertEquals("ACP error: timeout", result.getDisplayMessage());
    }

    // ── classify: AgentException non-recoverable ────────────────────────

    @Test
    void classify_nonRecoverableAgentException_isNotRecoverable() {
        var ex = new AgentException("fatal error", null, false);
        var result = classify(ex, false, NO_AUTH, true);
        assertFalse(result.isRecoverable());
    }

    @Test
    void classify_nonRecoverableAgentException_shouldRestorePromptWhenNoContent() {
        var ex = new AgentException("fatal error", null, false);
        var result = classify(ex, false, NO_AUTH, true);
        assertTrue(result.getShouldRestorePrompt());
    }

    // ── classify: AgentException with auth error ────────────────────────

    @Test
    void classify_agentExceptionWithAuthError_isAuthError() {
        var ex = new AgentException("auth token expired", null, true);
        var result = classify(ex, false, AUTH_CONTAINS, true);
        assertTrue(result.isAuthError());
    }

    @Test
    void classify_agentExceptionWithAuthError_displayMessageContainsAuthMessage() {
        var ex = new AgentException("auth token expired", null, true);
        var result = classify(ex, false, AUTH_CONTAINS, true);
        // The message is set to the cause message that matched the auth check.
        // Since the exception itself matches, msg = causeMsg = "auth token expired"
        // Then the AgentException prefix logic: since it doesn't start with "(" → "ACP error: auth token expired"
        assertEquals("ACP error: auth token expired", result.getDisplayMessage());
    }

    // ── classify: nested auth error in cause chain ──────────────────────

    @Test
    void classify_nestedAuthErrorInCauseChain_isAuthError() {
        var innerCause = new RuntimeException("auth session invalid");
        var wrapper = new RuntimeException("something failed", innerCause);
        var result = classify(wrapper, false, AUTH_CONTAINS, true);
        assertTrue(result.isAuthError());
    }

    @Test
    void classify_nestedAuthErrorInCauseChain_displaysAuthMessage() {
        var innerCause = new RuntimeException("auth session invalid");
        var wrapper = new RuntimeException("something failed", innerCause);
        var result = classify(wrapper, false, AUTH_CONTAINS, true);
        assertEquals("auth session invalid", result.getDisplayMessage());
    }

    // ── classify: regular exception with clientHealthy=true ─────────────

    @Test
    void classify_regularExceptionClientHealthy_shouldRestorePromptWhenNoContent() {
        var ex = new RuntimeException("something went wrong");
        var result = classify(ex, false, NO_AUTH, true);
        assertTrue(result.getShouldRestorePrompt());
    }

    @Test
    void classify_regularExceptionClientHealthy_notCancelled() {
        var ex = new RuntimeException("something went wrong");
        var result = classify(ex, false, NO_AUTH, true);
        assertFalse(result.isCancelled());
    }

    @Test
    void classify_regularExceptionClientHealthy_displayMessageIsExceptionMessage() {
        var ex = new RuntimeException("something went wrong");
        var result = classify(ex, false, NO_AUTH, true);
        assertEquals("something went wrong", result.getDisplayMessage());
    }

    // ── classify: regular exception with clientHealthy=false ────────────

    @Test
    void classify_regularExceptionClientNotHealthy_notCancelled() {
        var ex = new RuntimeException("connection timeout");
        var result = classify(ex, false, NO_AUTH, false);
        assertFalse(result.isCancelled());
    }

    @Test
    void classify_regularExceptionClientNotHealthy_displaysExceptionMessage() {
        var ex = new RuntimeException("connection timeout");
        var result = classify(ex, false, NO_AUTH, false);
        assertEquals("connection timeout", result.getDisplayMessage());
    }

    // ── classify: turnHadContent=true ───────────────────────────────────

    @Test
    void classify_turnHadContent_shouldNotRestorePrompt() {
        var ex = new RuntimeException("error after content");
        var result = classify(ex, true, NO_AUTH, true);
        assertFalse(result.getShouldRestorePrompt());
    }

    @Test
    void classify_cancelledWithTurnHadContent_shouldNotRestorePrompt() {
        var ex = new InterruptedException("cancelled");
        var result = classify(ex, true, NO_AUTH, true);
        // shouldRestorePrompt = !turnHadContent && !isCancelled → false
        assertFalse(result.getShouldRestorePrompt());
    }

    // ── classify: null message exception ────────────────────────────────

    @Test
    void classify_exceptionWithNullMessage_displaysUnknownError() {
        var ex = new RuntimeException((String) null);
        var result = classify(ex, false, NO_AUTH, true);
        assertEquals("Unknown error", result.getDisplayMessage());
    }

    // ── classify: AgentException message starting with "(" ──────────────

    @Test
    void classify_agentExceptionMessageStartingWithParen_noPrefixAdded() {
        var ex = new AgentException("(error code 42)", null, false);
        var result = classify(ex, false, NO_AUTH, true);
        assertEquals("(error code 42)", result.getDisplayMessage());
    }

    // ── classify: process crash with recovery ───────────────────────────

    @Test
    void classify_processExitedUnexpectedly_clientHealthy_isProcessCrashWithRecovery() {
        var ex = new RuntimeException("process exited unexpectedly");
        var result = classify(ex, false, NO_AUTH, true);
        assertTrue(result.isProcessCrashWithRecovery());
    }

    @Test
    void classify_processExitedUnexpectedly_clientNotHealthy_notProcessCrashWithRecovery() {
        var ex = new RuntimeException("process exited unexpectedly");
        var result = classify(ex, false, NO_AUTH, false);
        assertFalse(result.isProcessCrashWithRecovery());
    }

    @Test
    void classify_processExitedUnexpectedlyInCauseChain_clientHealthy_isProcessCrashWithRecovery() {
        var inner = new RuntimeException("process exited unexpectedly");
        var wrapper = new RuntimeException("wrapper", inner);
        var result = classify(wrapper, false, NO_AUTH, true);
        assertTrue(result.isProcessCrashWithRecovery());
    }

    @Test
    void classify_processExitedUnexpectedlyCaseInsensitive_isProcessCrashWithRecovery() {
        var ex = new RuntimeException("Process Exited Unexpectedly");
        var result = classify(ex, false, NO_AUTH, true);
        assertTrue(result.isProcessCrashWithRecovery());
    }

    @Test
    void classify_cancelled_processExitedUnexpectedly_notProcessCrash() {
        // When cancelled (cause is InterruptedException), isProcessCrashWithRecovery is always false
        var wrapper = new RuntimeException("process exited unexpectedly", new InterruptedException());
        var result = classify(wrapper, false, NO_AUTH, true);
        // cause is InterruptedException → isCancelled = true → isProcessCrashWithRecovery = false
        assertFalse(result.isProcessCrashWithRecovery());
    }

    @Test
    void classify_normalException_notProcessCrashWithRecovery() {
        var ex = new RuntimeException("normal error");
        var result = classify(ex, false, NO_AUTH, true);
        assertFalse(result.isProcessCrashWithRecovery());
    }

    // ── classify: default AgentException constructor (recoverable) ──────

    @Test
    void classify_defaultAgentExceptionConstructor_isRecoverable() {
        var ex = new AgentException("default");
        var result = classify(ex, false, NO_AUTH, true);
        assertTrue(result.isRecoverable());
    }

    // ── classify: AgentException with full constructor ──────────────────

    @Test
    void classify_agentExceptionWithErrorCodeAndData_prefixesMessage() {
        var ex = new AgentException("rate limited", null, true, 429, "{\"retry_after\":30}");
        var result = classify(ex, false, NO_AUTH, true);
        assertEquals("ACP error: rate limited", result.getDisplayMessage());
    }

    // ── detectQuickReplies ──────────────────────────────────────────────

    @Test
    void detectQuickReplies_multipleOptions_returnsParsedList() {
        List<String> replies = PromptErrorClassifier.INSTANCE.detectQuickReplies("[quick-reply: A | B | C]");
        assertEquals(List.of("A", "B", "C"), replies);
    }

    @Test
    void detectQuickReplies_singleOption_returnsSingleElementList() {
        List<String> replies = PromptErrorClassifier.INSTANCE.detectQuickReplies("[quick-reply: A]");
        assertEquals(List.of("A"), replies);
    }

    @Test
    void detectQuickReplies_noTag_returnsEmptyList() {
        List<String> replies = PromptErrorClassifier.INSTANCE.detectQuickReplies("no quick replies here");
        assertTrue(replies.isEmpty());
    }

    @Test
    void detectQuickReplies_emptyString_returnsEmptyList() {
        List<String> replies = PromptErrorClassifier.INSTANCE.detectQuickReplies("");
        assertTrue(replies.isEmpty());
    }

    @Test
    void detectQuickReplies_whitespaceAroundOptions_trimmed() {
        List<String> replies = PromptErrorClassifier.INSTANCE.detectQuickReplies("[quick-reply:  A  |  B  ]");
        assertEquals(List.of("A", "B"), replies);
    }

    @Test
    void detectQuickReplies_multipleTags_returnsLastMatch() {
        String text = "[quick-reply: X | Y] some text [quick-reply: A | B | C]";
        List<String> replies = PromptErrorClassifier.INSTANCE.detectQuickReplies(text);
        assertEquals(List.of("A", "B", "C"), replies);
    }

    @Test
    void detectQuickReplies_tagEmbeddedInLongerText_parsesCorrectly() {
        String text = "Here is an error. [quick-reply: Retry | Cancel] Please choose.";
        List<String> replies = PromptErrorClassifier.INSTANCE.detectQuickReplies(text);
        assertEquals(List.of("Retry", "Cancel"), replies);
    }

    @Test
    void detectQuickReplies_extraWhitespaceInTag_parsesCorrectly() {
        List<String> replies = PromptErrorClassifier.INSTANCE.detectQuickReplies("[  quick-reply:  Yes  |  No  ]");
        assertEquals(List.of("Yes", "No"), replies);
    }

    @Test
    void detectQuickReplies_onlyPipeSeparators_filtersEmptyStrings() {
        List<String> replies = PromptErrorClassifier.INSTANCE.detectQuickReplies("[quick-reply: | | ]");
        assertTrue(replies.isEmpty());
    }

    @Test
    void detectQuickReplies_singleOptionWithTrailingPipe_filtersEmpty() {
        List<String> replies = PromptErrorClassifier.INSTANCE.detectQuickReplies("[quick-reply: A | ]");
        assertEquals(List.of("A"), replies);
    }

    // ── isCLINotFoundError ──────────────────────────────────────────────

    @Test
    void isCLINotFoundError_nonRecoverableAgentException_returnsTrue() {
        var ex = new AgentException("CLI not found", null, false);
        assertTrue(PromptErrorClassifier.INSTANCE.isCLINotFoundError(ex));
    }

    @Test
    void isCLINotFoundError_recoverableAgentException_returnsFalse() {
        var ex = new AgentException("not found");
        // Default constructor is recoverable
        assertFalse(PromptErrorClassifier.INSTANCE.isCLINotFoundError(ex));
    }

    @Test
    void isCLINotFoundError_wrappedNonRecoverableAgentExceptionInCauseChain_returnsTrue() {
        var agentEx = new AgentException("not found", null, false);
        var wrapper = new RuntimeException("wrapper", agentEx);
        assertTrue(PromptErrorClassifier.INSTANCE.isCLINotFoundError(wrapper));
    }

    @Test
    void isCLINotFoundError_regularException_returnsFalse() {
        var ex = new RuntimeException("not found");
        assertFalse(PromptErrorClassifier.INSTANCE.isCLINotFoundError(ex));
    }

    @Test
    void isCLINotFoundError_nullCauseChain_returnsFalse() {
        var ex = new RuntimeException("error");
        assertFalse(PromptErrorClassifier.INSTANCE.isCLINotFoundError(ex));
    }

    @Test
    void isCLINotFoundError_deeplyNestedNonRecoverableAgentException_returnsTrue() {
        var agentEx = new AgentException("binary missing", null, false);
        var mid = new RuntimeException("mid", agentEx);
        var outer = new RuntimeException("outer", mid);
        assertTrue(PromptErrorClassifier.INSTANCE.isCLINotFoundError(outer));
    }

    @Test
    void isCLINotFoundError_deeplyNestedRecoverableAgentException_returnsFalse() {
        var agentEx = new AgentException("timeout", null, true);
        var mid = new RuntimeException("mid", agentEx);
        var outer = new RuntimeException("outer", mid);
        assertFalse(PromptErrorClassifier.INSTANCE.isCLINotFoundError(outer));
    }

    // ── Classification data class accessors ─────────────────────────────

    @Test
    void classification_allFieldsAccessible() {
        var result = classify(new RuntimeException("test"), false, NO_AUTH, true);
        assertNotNull(result.getDisplayMessage());
        // Boolean accessors should work
        assertFalse(result.isCancelled());
        assertFalse(result.isAuthError());
        assertFalse(result.isProcessCrashWithRecovery());
    }

    @Test
    void classification_equalsAndHashCode() {
        var result1 = classify(new RuntimeException("test"), false, NO_AUTH, true);
        var result2 = classify(new RuntimeException("test"), false, NO_AUTH, true);
        assertEquals(result1, result2);
        assertEquals(result1.hashCode(), result2.hashCode());
    }

    @Test
    void classification_toString_containsFieldValues() {
        var result = classify(new RuntimeException("test"), false, NO_AUTH, true);
        String str = result.toString();
        assertTrue(str.contains("isCancelled"));
        assertTrue(str.contains("displayMessage"));
    }
}
