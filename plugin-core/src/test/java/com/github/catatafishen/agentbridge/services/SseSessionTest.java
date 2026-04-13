package com.github.catatafishen.agentbridge.services;

import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SseSession}.
 * Uses a mock {@link HttpExchange} with a {@link ByteArrayOutputStream}
 * to capture SSE frames written to the response body.
 */
class SseSessionTest {

    private HttpExchange exchange;
    private ByteArrayOutputStream baos;
    private SseSession session;

    @BeforeEach
    void setUp() throws IOException {
        exchange = mock(HttpExchange.class);
        baos = new ByteArrayOutputStream();
        when(exchange.getResponseBody()).thenReturn(baos);
        doNothing().when(exchange).sendResponseHeaders(anyInt(), anyLong());

        session = new SseSession(exchange);
    }

    // ── 1. sendEvent — correct SSE format ──────────────────

    @Test
    void sendEvent_correctSseFormat() throws IOException {
        session.sendEvent("message", "{\"id\":1}");

        assertEquals("event: message\ndata: {\"id\":1}\n\n", baos.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ── 2. sendKeepAlive — correct format ──────────────────

    @Test
    void sendKeepAlive_correctFormat() throws IOException {
        session.sendKeepAlive();

        assertEquals(": keepalive\n\n", baos.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ── 3. close — sets closed flag ────────────────────────

    @Test
    void close_setsClosedFlag() {
        session.close();

        assertTrue(session.isClosed());
    }

    // ── 4. close — is idempotent ───────────────────────────

    @Test
    void close_isIdempotent() {
        session.close();
        session.close(); // second call must not throw

        assertTrue(session.isClosed());
        // exchange.close() should only be called once
        verify(exchange, times(1)).close();
    }

    // ── 5. sendEvent after close — throws IOException ──────

    @Test
    void sendEvent_afterClose_throwsIOException() {
        session.close();

        IOException ex = assertThrows(IOException.class,
            () -> session.sendEvent("ping", "data"));
        assertTrue(ex.getMessage().contains("closed"));
    }

    // ── 6. sendKeepAlive after close — silently no-ops ─────

    @Test
    void sendKeepAlive_afterClose_doesNotWrite() throws IOException {
        // Write something first so we can verify nothing new is appended
        session.sendEvent("init", "hello");
        int sizeBeforeClose = baos.size();

        session.close();
        // sendKeepAlive silently returns when closed (does not throw)
        session.sendKeepAlive();

        assertEquals(sizeBeforeClose, baos.size(),
            "sendKeepAlive after close should not write any bytes");
    }

    // ── 7. awaitClose — unblocks when close() is called ────

    @Test
    void awaitClose_unblocksOnClose() throws InterruptedException {
        CountDownLatch threadStarted = new CountDownLatch(1);
        AtomicReference<Boolean> awaitReturned = new AtomicReference<>(false);

        Thread waiter = new Thread(() -> {
            try {
                threadStarted.countDown();
                session.awaitClose();
                awaitReturned.set(true);
            } catch (InterruptedException ignored) {
            }
        });
        waiter.start();

        // Wait for the thread to actually enter awaitClose
        assertTrue(threadStarted.await(2, TimeUnit.SECONDS));

        // Close the session — this should unblock awaitClose
        session.close();

        waiter.join(2_000);
        assertFalse(waiter.isAlive(), "Waiter thread should have finished");
        assertTrue(awaitReturned.get(), "awaitClose() should have returned");
    }

    // ── 8. new session — is not closed ─────────────────────

    @Test
    void newSession_isNotClosed() {
        assertFalse(session.isClosed());
        assertNotNull(session.getSessionId(), "sessionId should be assigned");
        assertFalse(session.getSessionId().isEmpty(), "sessionId should not be empty");
    }
}
