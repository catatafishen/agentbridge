package com.github.catatafishen.agentbridge.services;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents a single SSE (Server-Sent Events) client connection.
 * Holds the open {@link HttpExchange} and provides methods to send
 * SSE-formatted events back to the client.
 */
final class SseSession {

    private final String sessionId;
    private final HttpExchange exchange;
    private final OutputStream outputStream;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final CountDownLatch closedLatch = new CountDownLatch(1);

    SseSession(HttpExchange exchange) {
        this.sessionId = UUID.randomUUID().toString();
        this.exchange = exchange;
        this.outputStream = exchange.getResponseBody();
    }

    String getSessionId() {
        return sessionId;
    }

    boolean isClosed() {
        return closed.get();
    }

    /**
     * Sends an SSE event to the client.
     *
     * @param event the event type (e.g. "endpoint", "message")
     * @param data  the event data payload
     */
    synchronized void sendEvent(String event, String data) throws IOException {
        if (closed.get()) {
            throw new IOException("SSE session is closed: " + sessionId);
        }
        String frame = McpSseTransport.formatSseEvent(event, data);
        outputStream.write(frame.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    /**
     * Sends an SSE keep-alive comment to prevent proxy/client timeouts.
     */
    synchronized void sendKeepAlive() throws IOException {
        if (closed.get()) return;
        outputStream.write(McpSseTransport.formatSseKeepAlive().getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    /**
     * Blocks until this session is closed, or the calling thread is interrupted.
     * Used by the SSE handler thread to keep the HTTP exchange alive.
     */
    void awaitClose() throws InterruptedException {
        closedLatch.await();
    }

    /**
     * Closes the SSE stream and the underlying HTTP exchange.
     */
    void close() {
        if (closed.compareAndSet(false, true)) {
            closedLatch.countDown();
            try {
                outputStream.close();
            } catch (IOException ignored) {
                // Client may have already disconnected
            }
            exchange.close();
        }
    }
}
