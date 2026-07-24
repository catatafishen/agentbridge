package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallHistoryTransportTest {

    private final LiveToolCallService service = new LiveToolCallService();
    private final AtomicBoolean failBackend = new AtomicBoolean();
    private final List<ToolCallHistoryHttp.SerializationFailure> diagnostics = new ArrayList<>();
    private HttpServer server;
    private HttpClient client;
    private URI baseUri;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/tool-calls", exchange -> ChatWebServer.handleToolCallHistoryExchange(
            exchange,
            () -> {
                if (failBackend.get()) {
                    throw new IllegalStateException("backend unavailable");
                }
                return service;
            },
            diagnostics::add));
        server.start();
        baseUri = URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort());
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void productionAdapterPreservesRawPathQueryHeadersAndSuccessJson() throws Exception {
        long callId = service.recordStart("read_file", "Read file", "{\"path\":\"a.txt\"}", "read", false, null);
        service.complete(callId, "contents", 7, true);

        HttpResponse<String> list = request("GET", "/tool-calls?view=%31");
        assertJsonResponse(list, 200);
        assertEquals(1, JsonParser.parseString(list.body()).getAsJsonObject()
            .getAsJsonArray("items").size());

        HttpResponse<String> detail = request("GET", "/tool-calls/" + callId + "?ignored=yes");
        assertJsonResponse(detail, 200);
        assertEquals(callId, JsonParser.parseString(detail.body()).getAsJsonObject().get("id").getAsLong());

        HttpResponse<String> encodedId = request("GET", "/tool-calls/%31?ignored=yes");
        assertJsonResponse(encodedId, 400);
        assertEquals("Malformed tool call id",
            JsonParser.parseString(encodedId.body()).getAsJsonObject().get("error").getAsString());
    }

    @Test
    void productionAdapterEmitsRepresentativeErrorStatusesAndJsonBodies() throws Exception {
        HttpResponse<String> notFound = request("GET", "/tool-calls/999");
        assertJsonResponse(notFound, 404);
        assertEquals("Tool call not found",
            JsonParser.parseString(notFound.body()).getAsJsonObject().get("error").getAsString());

        long unavailable = service.recordStart(
            "read_file", "Read file",
            "x".repeat((int) ToolCallPayload.MAX_FULL_PAYLOAD_FIELD_BYTES + 1),
            "read", false, null);
        HttpResponse<String> gone = request("GET", "/tool-calls/" + unavailable);
        assertJsonResponse(gone, 410);
        assertEquals("Full tool call payload unavailable",
            JsonParser.parseString(gone.body()).getAsJsonObject().get("error").getAsString());

        HttpResponse<String> methodNotAllowed = request("POST", "/tool-calls");
        assertJsonResponse(methodNotAllowed, 405);
        assertEquals("Method not allowed",
            JsonParser.parseString(methodNotAllowed.body()).getAsJsonObject().get("error").getAsString());

        failBackend.set(true);
        HttpResponse<String> serverError = request("GET", "/tool-calls/1");
        assertJsonResponse(serverError, 500);
        assertEquals("Failed to load tool calls",
            JsonParser.parseString(serverError.body()).getAsJsonObject().get("error").getAsString());
        assertFalse(serverError.body().contains("backend unavailable"));
    }

    private HttpResponse<String> request(String method, String rawPathAndQuery) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(rawPathAndQuery))
            .timeout(Duration.ofSeconds(3));
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertJsonResponse(HttpResponse<String> response, int expectedStatus) {
        assertEquals(expectedStatus, response.statusCode());
        assertEquals("application/json; charset=utf-8",
            response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertTrue(JsonParser.parseString(response.body()).isJsonObject());
    }
}
