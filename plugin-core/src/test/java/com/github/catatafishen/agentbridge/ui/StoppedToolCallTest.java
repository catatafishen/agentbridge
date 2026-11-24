package com.github.catatafishen.agentbridge.ui;

import com.github.catatafishen.agentbridge.bridge.MessageFormatter;
import com.github.catatafishen.agentbridge.services.ToolCallRecord;
import com.github.catatafishen.agentbridge.services.ToolCallTracker;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class StoppedToolCallTest {

    private static final String REASON = "Stopped by user";

    private ToolCallTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ToolCallTracker(mock(Project.class));
    }

    private ToolCallRecord toolCall(String path) {
        JsonObject args = new JsonObject();
        args.addProperty("path", path);
        return tracker.mcpRegister("read_file", args, "file", null);
    }

    @Test
    void aToolThatNeverFinishedFailsWithTheStopReason() {
        ToolCallRecord rec = toolCall("/running");

        tracker.stopAllInFlight(REASON);

        assertEquals(MessageFormatter.ChipStatus.FAILED, StoppedToolCall.status(rec));
        assertEquals(REASON, StoppedToolCall.update(rec, REASON).getDetails());
    }

    @Test
    void aToolThatSucceededCompletesWithItsResult() {
        ToolCallRecord rec = toolCall("/done");
        tracker.mcpComplete(rec.getRecordId(), "file content", true);

        tracker.stopAllInFlight(REASON);

        assertEquals(MessageFormatter.ChipStatus.COMPLETE, StoppedToolCall.status(rec));
        assertEquals("file content", StoppedToolCall.update(rec, REASON).getDetails());
    }

    @Test
    void aToolThatFailedKeepsItsOwnError() {
        ToolCallRecord rec = toolCall("/missing");
        tracker.mcpComplete(rec.getRecordId(), "File not found", false);

        tracker.stopAllInFlight(REASON);

        assertEquals(MessageFormatter.ChipStatus.FAILED, StoppedToolCall.status(rec));
        assertEquals("File not found", StoppedToolCall.update(rec, REASON).getDetails());
    }
}
