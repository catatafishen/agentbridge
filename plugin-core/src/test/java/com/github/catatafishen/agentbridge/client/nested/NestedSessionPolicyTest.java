package com.github.catatafishen.agentbridge.client.nested;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NestedSessionPolicyTest {

    @Test
    void aNestedSessionKeyIsRecognisedAsSuch() {
        assertTrue(NestedSessionPolicy.isNestedSessionKey(NestedSessionPolicy.newSessionKey()));
    }

    @Test
    void everyNestedSessionGetsAKeyOfItsOwn() {
        assertNotEquals(NestedSessionPolicy.newSessionKey(), NestedSessionPolicy.newSessionKey());
    }

    @Test
    void theChatsOwnSessionKeysAreNotNestedOnes() {
        assertFalse(NestedSessionPolicy.isNestedSessionKey("legacy:abc:123"));
        assertFalse(NestedSessionPolicy.isNestedSessionKey("stateless"));
        assertFalse(NestedSessionPolicy.isNestedSessionKey("test:main"));
    }

    @Test
    void noSessionKeyIsNotANestedSession() {
        assertFalse(NestedSessionPolicy.isNestedSessionKey(null));
        assertFalse(NestedSessionPolicy.isNestedSessionKey(""));
    }

    @Test
    void theToolsThatWaitForAPersonOrStartSessionsAreForbidden() {
        assertTrue(NestedSessionPolicy.FORBIDDEN_TOOLS.contains("prompt_user"));
        assertTrue(NestedSessionPolicy.FORBIDDEN_TOOLS.contains("interact_with_modal"));
        assertTrue(NestedSessionPolicy.FORBIDDEN_TOOLS.contains("run_nested_session"));
    }

    @Test
    void ordinaryToolsAreNotForbidden() {
        assertFalse(NestedSessionPolicy.FORBIDDEN_TOOLS.contains("read_file"));
        assertFalse(NestedSessionPolicy.FORBIDDEN_TOOLS.contains("write_file"));
        assertFalse(NestedSessionPolicy.FORBIDDEN_TOOLS.contains("sleep"));
    }

    @Test
    void theChatsOwnEndpointIsTheDefaultOne() {
        assertEquals("/mcp", NestedSessionPolicy.endpointPath(null));
        assertEquals("/mcp", NestedSessionPolicy.endpointPath("legacy:abc:123"));
    }

    @Test
    void aNestedSessionGetsAnEndpointOfItsOwn() {
        String path = NestedSessionPolicy.endpointPath(NestedSessionPolicy.newSessionKey());

        assertTrue(path.startsWith("/mcp/nested/"), path);
        assertNotEquals("/mcp", path);
    }

    @Test
    void theEndpointPathLeadsBackToTheSessionKey() {
        String key = NestedSessionPolicy.newSessionKey();

        assertEquals(key, NestedSessionPolicy.sessionKeyFromPath(NestedSessionPolicy.endpointPath(key)));
    }

    @Test
    void aTrailingSlashDoesNotChangeWhichSessionAPathBelongsTo() {
        String key = NestedSessionPolicy.newSessionKey();

        assertEquals(key, NestedSessionPolicy.sessionKeyFromPath(NestedSessionPolicy.endpointPath(key) + "/"));
    }

    @Test
    void theChatsOwnEndpointAndOtherPathsAreNotNestedSessions() {
        assertNull(NestedSessionPolicy.sessionKeyFromPath("/mcp"));
        assertNull(NestedSessionPolicy.sessionKeyFromPath("/mcp/"));
        assertNull(NestedSessionPolicy.sessionKeyFromPath("/sse"));
        assertNull(NestedSessionPolicy.sessionKeyFromPath("/hooks/tool"));
        assertNull(NestedSessionPolicy.sessionKeyFromPath(null));
    }

    @Test
    void aPathThatIsNotAWellFormedTokenIsNotANestedSession() {
        assertNull(NestedSessionPolicy.sessionKeyFromPath("/mcp/nested/"));
        assertNull(NestedSessionPolicy.sessionKeyFromPath("/mcp/nested/x"));
        assertNull(NestedSessionPolicy.sessionKeyFromPath("/mcp/nested/../../etc/passwd"));
        assertNull(NestedSessionPolicy.sessionKeyFromPath("/mcp/nested/abc def ghi jkl"));
        assertNull(NestedSessionPolicy.sessionKeyFromPath("/mcp/nested/abcdefgh/extra"));
    }

    @Test
    void anEndpointIsNeverBuiltFromAKeyThatIsNotWellFormed() {
        assertThrows(IllegalArgumentException.class,
            () -> NestedSessionPolicy.endpointPath(NestedSessionPolicy.SESSION_KEY_PREFIX + "../x"));
    }

    @Test
    void aNestedSessionMayNotUseTheForbiddenToolsButTheChatMay() {
        String nested = NestedSessionPolicy.newSessionKey();

        assertTrue(NestedSessionPolicy.isForbidden(nested, "prompt_user"));
        assertFalse(NestedSessionPolicy.isForbidden(nested, "read_file"));
        assertFalse(NestedSessionPolicy.isForbidden("legacy:abc:123", "prompt_user"));
        assertFalse(NestedSessionPolicy.isForbidden(null, "prompt_user"));
    }

    @Test
    void anApprovalNobodyCanGiveIsDeniedInANestedSession() {
        String denial = NestedSessionPolicy.denialForAsk(NestedSessionPolicy.newSessionKey(), "git_push");

        assertNotNull(denial);
        assertTrue(denial.startsWith("Error: "), denial);
        assertTrue(denial.contains("git_push"));
    }

    @Test
    void theChatsOwnCallsAreStillAskedAsUsual() {
        assertNull(NestedSessionPolicy.denialForAsk("legacy:abc:123", "git_push"));
        assertNull(NestedSessionPolicy.denialForAsk(null, "git_push"));
    }

    @Test
    void theMessagesNameTheToolAndStartWithError() {
        String approval = NestedSessionPolicy.approvalRequiredMessage("git_push");
        String forbidden = NestedSessionPolicy.forbiddenToolMessage("prompt_user");

        assertTrue(approval.startsWith("Error: "), approval);
        assertTrue(approval.contains("git_push"));
        assertTrue(forbidden.startsWith("Error: "), forbidden);
        assertTrue(forbidden.contains("prompt_user"));
    }
}
