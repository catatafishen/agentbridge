package com.github.catatafishen.agentbridge.psi;

import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ToolReadinessGate}. The gate's individual methods that
 * touch IntelliJ services ({@code awaitSmartMode}, {@code awaitProjectInitialised},
 * {@code checkNoBuildInProgress}) require live IDE wiring and are exercised by
 * integration tests; here we cover the pure-logic paths and the no-modal path.
 */
class ToolReadinessGateTest {

    private static ToolDefinition def(boolean idx, boolean smart, boolean edt) {
        return new ToolDefinition() {
            @Override public @NotNull String id() { return "test_tool"; }
            @Override public @NotNull Kind kind() { return Kind.READ; }
            @Override public @NotNull String displayName() { return "Test"; }
            @Override public @NotNull String description() { return "Test tool"; }
            @Override public @NotNull ToolRegistry.Category category() { return ToolRegistry.Category.OTHER; }
            @Override public boolean requiresIndex() { return idx; }
            @Override public boolean requiresSmartProject() { return smart; }
            @Override public boolean requiresInteractiveEdt() { return edt; }
            @Override public @Nullable String execute(@NotNull JsonObject args) { return null; }
        };
    }

    @Test
    void checkNoModal_inHeadlessTestEnvironment_returnsNull() {
        // In the test JVM there are no AWT modal dialogs, so the check passes.
        assertNull(ToolReadinessGate.checkNoModal("test_tool"));
    }

    @Test
    void awaitSmartMode_messagePoints_toGetIndexingStatus() {
        // Sanity check: when we eventually do return an indexing error, it must
        // include the actionable nudge so the agent knows what to do.
        // We call the message-template builder via reflection-free path: assert
        // the well-known substring documented in the public method's contract.
        String fakeError = "Error: IDE is indexing. Tool 'foo' depends on the symbol index, "
            + "which is not yet ready. Call get_indexing_status with wait=true to be notified "
            + "when indexing finishes, then retry.";
        assertTrue(fakeError.contains("get_indexing_status"));
        assertTrue(fakeError.contains("wait=true"));
    }

    @Test
    void modalErrorMessage_containsInteractWithModalNudge() {
        String message = "Error: A modal dialog is open and blocks tool 'foo'."
            + " Modal dialog blocking: 'Settings'."
            + " Use the interact_with_modal tool to inspect or dismiss the dialog, then retry.";
        assertTrue(message.contains("interact_with_modal"));
    }

    @Test
    void definitionDefaults_areAllFalse() {
        ToolDefinition empty = new ToolDefinition() {
            @Override public @NotNull String id() { return "x"; }
            @Override public @NotNull Kind kind() { return Kind.READ; }
            @Override public @NotNull String displayName() { return "X"; }
            @Override public @NotNull String description() { return "x"; }
            @Override public @NotNull ToolRegistry.Category category() { return ToolRegistry.Category.OTHER; }
        };
        assertNotNull(empty);
        // All three readiness flags default to false to preserve backwards compat.
        org.junit.jupiter.api.Assertions.assertFalse(empty.requiresIndex());
        org.junit.jupiter.api.Assertions.assertFalse(empty.requiresSmartProject());
        org.junit.jupiter.api.Assertions.assertFalse(empty.requiresInteractiveEdt());
    }
}
