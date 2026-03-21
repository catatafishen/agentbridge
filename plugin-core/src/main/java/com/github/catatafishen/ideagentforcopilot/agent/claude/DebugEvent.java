package com.github.catatafishen.ideagentforcopilot.agent.claude;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Debug event for the UI debug tab showing permission requests, denials, tool calls, etc.
 */
public class DebugEvent {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public final String timestamp;
    public final String type;  // "PERMISSION_REQUEST", "PERMISSION_DENIED", "RETRY_SENT", "TOOL_CALL", etc.
    public final String message;
    public final String details; // JSON or additional info

    public DebugEvent(String type, String message, String details) {
        this.timestamp = LocalTime.now().format(TIME_FORMAT);
        this.type = type;
        this.message = message;
        this.details = details;
    }

    @Override
    public String toString() {
        return String.format("[%s] %s: %s", timestamp, type, message);
    }
}
