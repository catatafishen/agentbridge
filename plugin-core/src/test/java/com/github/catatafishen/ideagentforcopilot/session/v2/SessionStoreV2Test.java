package com.github.catatafishen.ideagentforcopilot.session.v2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStoreV2Test {

    @Test
    void sessionNameExtractedFromFirstPrompt() {
        String name = SessionStoreV2.truncateSessionName("Fix the auth bug in the login page and update tests");
        assertEquals("Fix the auth bug in the login page and update tests", name);
    }

    @Test
    void sessionNameTruncatedAt60Chars() {
        String longPrompt = "This is a very long prompt that exceeds the sixty character limit for session names and should be truncated";
        String name = SessionStoreV2.truncateSessionName(longPrompt);
        assertTrue(name.length() <= 61, "name should be at most 60 chars + ellipsis");
        assertTrue(name.endsWith("…"), "truncated name should end with ellipsis");
    }

    @Test
    void sessionNameWhitespaceCollapsed() {
        String name = SessionStoreV2.truncateSessionName("  Fix   the\n  bug  ");
        assertEquals("Fix the bug", name);
    }
}
