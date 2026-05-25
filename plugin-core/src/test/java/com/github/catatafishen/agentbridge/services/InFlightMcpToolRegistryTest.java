package com.github.catatafishen.agentbridge.services;

import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link InFlightMcpToolRegistry}.
 *
 * <p>Constructs the registry directly (bypassing project-service lookup) since the
 * per-project instance only stores an in-memory map.
 */
class InFlightMcpToolRegistryTest {

    private final InFlightMcpToolRegistry registry = new InFlightMcpToolRegistry(mock(Project.class));

    @Test
    void cancelAll_completesRegisteredFutures_withSentinel() throws Exception {
        CompletableFuture<String> a = new CompletableFuture<>();
        CompletableFuture<String> b = new CompletableFuture<>();
        registry.register("a", a);
        registry.register("b", b);

        registry.cancelAll("agent process exited");

        assertEquals(InFlightMcpToolRegistry.CANCELLATION_SENTINEL,
            a.get(1, TimeUnit.SECONDS));
        assertEquals(InFlightMcpToolRegistry.CANCELLATION_SENTINEL,
            b.get(1, TimeUnit.SECONDS));
    }

    @Test
    void cancelAll_emptyRegistry_doesNotThrow() {
        assertDoesNotThrow(() -> registry.cancelAll("test"));
    }

    @Test
    void unregister_removesFuture_soCancelAllIsNoOp() {
        CompletableFuture<String> a = new CompletableFuture<>();
        registry.register("a", a);
        registry.unregister("a");

        registry.cancelAll("agent process exited");

        assertFalse(a.isDone(), "Unregistered future must not be completed by cancelAll");
    }

    @Test
    void cancelAll_doesNotOverwriteAlreadyCompletedFutures() throws Exception {
        CompletableFuture<String> a = new CompletableFuture<>();
        a.complete("real-user-answer");
        registry.register("a", a);

        registry.cancelAll("agent process exited");

        assertEquals("real-user-answer", a.get(1, TimeUnit.SECONDS));
    }

    @Test
    void cancelAll_isIdempotent() throws Exception {
        CompletableFuture<String> a = new CompletableFuture<>();
        registry.register("a", a);

        registry.cancelAll("first");
        assertDoesNotThrow(() -> registry.cancelAll("second"));

        assertEquals(InFlightMcpToolRegistry.CANCELLATION_SENTINEL,
            a.get(1, TimeUnit.SECONDS));
    }
}
