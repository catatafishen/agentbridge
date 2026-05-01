package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

/**
 * Implemented by quality tools whose actions can open popups and that support being
 * <em>re-invoked with a {@link PopupHandler.SelectByValue}</em> by
 * {@link PopupRespondTool}.
 *
 * <p><b>Why this exists — DO NOT REMOVE without reading
 * {@code .agent-work/popup-interaction-design-2026-04-30.md}.</b>
 *
 * <p>The original tool call captures a {@link PopupSnapshot} and registers a
 * {@link PendingPopupService.Pending} entry. When the agent calls {@code popup_respond},
 * the dispatcher looks up the originating tool by id ({@code apply_action}/{@code apply_quickfix})
 * and calls {@link #replay(JsonObject, PopupHandler.SelectByValue)} with the original arguments
 * and the user's selection. The implementing tool re-runs its full action pipeline but with the
 * select-by-value handler instead of the snapshot handler.
 *
 * <p>The design rationale for replaying via the same in-process tool method (rather than
 * re-dispatching through {@code PsiBridgeService.callTool}) is to avoid double-counting in
 * permission prompts, lock acquisitions, and tool-call telemetry.
 */
public interface Replayable {

    /**
     * Re-invokes the tool's action pipeline with the given {@link PopupHandler.SelectByValue}.
     *
     * @param originalArgs JSON arguments of the original tool call (preserved verbatim)
     * @param handler      selection captured from the agent's {@code popup_respond} call
     * @return human-readable result string, identical in format to the tool's normal {@code execute}.
     */
    @NotNull
    String replay(@NotNull JsonObject originalArgs,
                  @NotNull PopupHandler.SelectByValue handler)
        throws InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException;
}
