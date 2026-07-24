package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallHistoryHttpTest {

    @Test
    void getListReturnsCompatibleSummaryItems() {
        LiveToolCallService service = new LiveToolCallService();
        long callId = service.recordStart("read_file", "Read file", "{\"path\":\"a.txt\"}", null, false, "{\"path\":\"before.txt\"}");
        service.complete(callId, "file contents", 12, true);

        ToolCallHistoryHttp.Response response = ToolCallHistoryHttp.resolve("GET", "/tool-calls", service);

        assertEquals(200, response.status());
        assertEquals(1, response.body().getAsJsonArray("items").size());
        JsonObject summary = response.body().getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals(callId, summary.get("id").getAsLong());
        assertEquals("{\"path\":\"a.txt\"}", summary.get("arguments").getAsString());
        assertEquals("file contents", summary.get("result").getAsString());
        assertEquals("/tool-calls/" + callId, summary.get("detailUrl").getAsString());
        assertFalse(summary.has("originalInput"));
    }

    @Test
    void listLazyAcquisitionExceptionPreservesLegacyEmptyItemsResponse() {
        AtomicInteger warnings = new AtomicInteger();
        ToolCallHistoryHttp.Response response = ChatWebServer.resolveToolCallHistory(
            "GET", "/tool-calls",
            () -> {
                throw new IllegalStateException("history unavailable");
            },
            ignored -> warnings.incrementAndGet());

        assertEquals(200, response.status());
        assertEquals(0, response.body().getAsJsonArray("items").size());
        assertTrue(response.error() == null);
        assertEquals(1, warnings.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST|/tool-calls", "GET|/tool-calls/not-a-number"})
    void rejectedRoutesDoNotAcquireToolCallBackend(String request) {
        String[] parts = request.split("\\|", 2);
        AtomicInteger backendCalls = new AtomicInteger();

        ToolCallHistoryHttp.Response response = ChatWebServer.resolveToolCallHistory(
            parts[0], parts[1],
            () -> {
                backendCalls.incrementAndGet();
                return new LiveToolCallService();
            },
            ignored -> { });

        assertEquals(0, backendCalls.get());
        assertEquals("POST".equals(parts[0]) ? 405 : 400, response.status());
    }

    @Test
    void detailLazyAcquisitionExceptionPropagates() {
        assertThrows(IllegalStateException.class, () -> ChatWebServer.resolveToolCallHistory(
            "GET", "/tool-calls/1", () -> {
                throw new IllegalStateException("history unavailable");
            }, ignored -> { }));
    }

    @Test
    void getDetailReturnsCompletePayloadWithoutOriginalInput() {
        LiveToolCallService service = new LiveToolCallService();
        String input = "{\"path\":\"a.txt\",\"content\":\"" + "x".repeat(8_100) + "\"}";
        String output = "result-" + "y".repeat(8_100);
        long callId = service.recordStart("write_file", "Write file", input, null, false, "{\"content\":\"before hook\"}");
        service.complete(callId, output, 34, true);

        ToolCallHistoryHttp.Response response = ToolCallHistoryHttp.resolve("GET", "/tool-calls/" + callId, service);

        assertEquals(200, response.status());
        assertEquals(input, response.body().get("arguments").getAsString());
        assertEquals(output, response.body().get("result").getAsString());
        assertFalse(response.body().has("originalInput"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/tool-calls/0", "/tool-calls/-1", "/tool-calls/+1", "/tool-calls/not-a-number",
        "/tool-calls/1/extra", "/tool-calls/1/", "/tool-calls/9223372036854775808"
    })
    void malformedOrOverflowDetailIdReturnsBadRequest(String path) {
        ToolCallHistoryHttp.Response response = ToolCallHistoryHttp.resolve("GET", path, new LiveToolCallService());

        assertEquals(400, response.status());
        assertEquals("Malformed tool call id", response.error());
    }

    @Test
    void unknownDetailReturnsNotFound() {
        ToolCallHistoryHttp.Response response = ToolCallHistoryHttp.resolve("GET", "/tool-calls/999", new LiveToolCallService());

        assertEquals(404, response.status());
        assertEquals("Tool call not found", response.error());
    }

    @Test
    void unavailableDetailReturnsGone() {
        LiveToolCallService service = new LiveToolCallService(17_500);
        long callId = service.recordStart("read_file", "Read file", "x".repeat(9_000), null, false, null);
        service.complete(callId, "done", 12, true);
        service.recordStart("write_file", "Write file", "y".repeat(9_000), null, false, null);

        ToolCallHistoryHttp.Response response = ToolCallHistoryHttp.resolve("GET", "/tool-calls/" + callId, service);

        assertEquals(410, response.status());
        assertEquals("Full tool call payload unavailable", response.error());
    }

    @Test
    void detailFailsClosedWhenPayloadIsEvictedAfterListing() {
        LiveToolCallService service = new LiveToolCallService(17_500);
        long callId = service.recordStart("read_file", "Read file", "x".repeat(9_000), null, false, null);
        service.complete(callId, "done", 12, true);

        ToolCallHistoryHttp.Response listResponse = ToolCallHistoryHttp.resolve("GET", "/tool-calls", service);
        JsonArray items = listResponse.body().getAsJsonArray("items");
        assertEquals("/tool-calls/" + callId, items.get(0).getAsJsonObject().get("detailUrl").getAsString());

        service.recordStart("write_file", "Write file", "y".repeat(9_000), null, false, null);
        ToolCallHistoryHttp.Response detailResponse = ToolCallHistoryHttp.resolve("GET", "/tool-calls/" + callId, service);

        assertEquals(410, detailResponse.status());
        assertEquals("Full tool call payload unavailable", detailResponse.error());
    }

    @Test
    void nonGetRequestReturnsMethodNotAllowed() {
        ToolCallHistoryHttp.Response response = ToolCallHistoryHttp.resolve("POST", "/tool-calls", new LiveToolCallService());

        assertEquals(405, response.status());
        assertEquals("Method not allowed", response.error());
        assertTrue(response.body() == null);
    }
}
