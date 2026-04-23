package com.github.catatafishen.agentbridge.psi;

import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ToolReadinessGate}. The gate's individual methods that
 * touch IntelliJ services ({@code awaitSmartMode}, {@code awaitProjectInitialised},
 * {@code checkNoBuildInProgress}) require live IDE wiring and are exercised by
 * integration tests; here we cover the pure-logic paths and the message builders
 * that production code emits.
 */
class ToolReadinessGateTest {

    @Test
    void checkNoModal_inHeadlessTestEnvironment_returnsNull() {
        // In the test JVM there are no AWT modal dialogs, so the check passes.
        assertNull(ToolReadinessGate.checkNoModal("test_tool"));
    }

    @Test
    void indexingErrorMessage_pointsToGetIndexingStatus() {
        String msg = ToolReadinessGate.indexingErrorMessage("foo");
        assertTrue(msg.startsWith("Error: "), "must use Error: prefix so MCP marks isError");
        assertTrue(msg.contains("'foo'"), "message must reference the calling tool");
        assertTrue(msg.contains("get_indexing_status"), "must nudge agent to the readiness tool");
        assertTrue(msg.contains("wait=true"), "must tell agent how to await completion");
    }

    @Test
    void modalErrorMessage_includesInteractWithModalNudge() {
        String msg = ToolReadinessGate.modalErrorMessage("foo", " Modal: 'Settings'.");
        assertTrue(msg.startsWith("Error: "));
        assertTrue(msg.contains("'foo'"));
        assertTrue(msg.contains("Modal: 'Settings'."), "detail string must be embedded verbatim");
        assertTrue(msg.contains("interact_with_modal"));
    }

    @Test
    void projectInitErrorMessage_isActionable() {
        String msg = ToolReadinessGate.projectInitErrorMessage("foo");
        assertTrue(msg.startsWith("Error: "));
        assertTrue(msg.contains("'foo'"));
        assertTrue(msg.contains("Retry"), "must tell agent the failure is transient");
    }

    @Test
    void buildInProgressErrorMessage_isActionable() {
        String msg = ToolReadinessGate.buildInProgressErrorMessage("build_project");
        assertTrue(msg.startsWith("Error: "));
        assertTrue(msg.contains("'build_project'"));
        assertTrue(msg.contains("build to finish"), "must tell agent why and what to do");
    }

    @Test
    void definitionDefaults_areAllFalse() {
        ToolDefinition empty = new ToolDefinition() {
            @Override public @NotNull String id() { return "x"; }
            @Override public @NotNull Kind kind() { return Kind.READ; }
            @Override public @NotNull String displayName() { return "X"; }
            @Override public @NotNull String description() { return "x"; }
            @Override public @NotNull ToolRegistry.Category category() { return ToolRegistry.Category.OTHER; }
            @Override public @Nullable String execute(@NotNull JsonObject args) { return null; }
        };
        assertNotNull(empty);
        // All three readiness flags default to false to preserve backwards compat.
        assertFalse(empty.requiresIndex());
        assertFalse(empty.requiresSmartProject());
        assertFalse(empty.requiresInteractiveEdt());
    }

    @Test
    void messageBuilders_areStableForRepeatedCalls() {
        // Guard against accidental non-determinism (e.g. timestamp interpolation).
        assertEquals(
            ToolReadinessGate.indexingErrorMessage("t"),
            ToolReadinessGate.indexingErrorMessage("t"));
        assertEquals(
            ToolReadinessGate.modalErrorMessage("t", " d."),
            ToolReadinessGate.modalErrorMessage("t", " d."));
    }
}
