package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.bridge.MessageFormatter
import com.github.catatafishen.agentbridge.services.ToolCallRecord

/**
 * How a tool call's chip ends when the agent stops before reporting it
 * ([com.github.catatafishen.agentbridge.services.ToolCallTracker.stopAllInFlight]):
 * a tool that already ran shows its own outcome, any other shows the stop reason.
 */
object StoppedToolCall {

    @JvmStatic
    fun status(record: ToolCallRecord): String =
        if (record.state == ToolCallRecord.State.COMPLETED) MessageFormatter.ChipStatus.COMPLETE
        else MessageFormatter.ChipStatus.FAILED

    @JvmStatic
    fun update(record: ToolCallRecord, reason: String): ChatPanelApi.ToolCallUpdate =
        ChatPanelApi.ToolCallUpdate(details = record.mcpResult ?: reason)
}
