package com.github.catatafishen.agentbridge.client.acp;

import com.github.catatafishen.agentbridge.model.SessionUpdate;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenCode's {@code _meta["opencode/compaction"]} marker (issue #1240), in the wire shape of
 * OpenCode {@code packages/cli/src/acp/compaction.ts}.
 */
class OpenCodeCompactionMarkerTest {

    private static JsonObject marker(String status, String reason) {
        JsonObject marker = new JsonObject();
        marker.addProperty("status", status);
        marker.addProperty("messageId", "msg_1");
        marker.addProperty("reason", reason);
        JsonObject meta = new JsonObject();
        meta.add(OpenCodeCompactionMarker.META_KEY, marker);
        JsonObject update = new JsonObject();
        update.addProperty("sessionUpdate", "session_info_update");
        update.add("_meta", meta);
        return update;
    }

    @Test
    void manualCompactionCompletionIsACompaction() {
        SessionUpdate.Compaction c = OpenCodeCompactionMarker.parse(marker("completed", "manual"));

        assertNotNull(c);
        assertEquals(SessionUpdate.CompactionStatus.COMPLETED, c.status());
        assertTrue(c.manual());
        assertNull(c.error());
    }

    @Test
    void autoCompactionStartIsNotManual() {
        SessionUpdate.Compaction c = OpenCodeCompactionMarker.parse(marker("started", "auto"));

        assertNotNull(c);
        assertEquals(SessionUpdate.CompactionStatus.STARTED, c.status());
        assertFalse(c.manual());
    }

    @Test
    void failedCompactionCarriesTheErrorMessage() {
        JsonObject update = marker("failed", "manual");
        JsonObject error = new JsonObject();
        error.addProperty("type", "compaction.failed");
        error.addProperty("message", "summarizer timed out");
        update.getAsJsonObject("_meta").getAsJsonObject(OpenCodeCompactionMarker.META_KEY).add("error", error);

        SessionUpdate.Compaction c = OpenCodeCompactionMarker.parse(update);

        assertNotNull(c);
        assertEquals(SessionUpdate.CompactionStatus.FAILED, c.status());
        assertEquals("summarizer timed out", c.error());
    }

    @Test
    void unknownStatusIsLeftToTheStandardParser() {
        assertNull(OpenCodeCompactionMarker.parse(marker("paused", "auto")));
    }

    @Test
    void markerHasNoCompactionIdOrSummary() {
        SessionUpdate.Compaction c = OpenCodeCompactionMarker.parse(marker("completed", "auto"));

        assertNotNull(c);
        assertNull(c.compactionId());
        assertNull(c.summary());
    }

    @Test
    void sessionInfoUpdateWithoutMarkerIsLeftToTheStandardParser() {
        JsonObject update = new JsonObject();
        update.addProperty("sessionUpdate", "session_info_update");
        update.addProperty("title", "Fix login");

        assertNull(OpenCodeCompactionMarker.parse(update));
    }

    @Test
    void markerOnAnotherUpdateTypeIsIgnored() {
        JsonObject update = marker("completed", "manual");
        update.addProperty("sessionUpdate", "agent_message_chunk");

        assertNull(OpenCodeCompactionMarker.parse(update));
    }
}
