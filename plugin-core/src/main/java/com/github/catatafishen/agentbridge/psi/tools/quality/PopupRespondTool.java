package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.ui.renderers.SimpleStatusRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Resolves a pending {@link PendingPopupService.Pending} popup by re-running the original
 * tool with a {@link PopupHandler.SelectByValue} (or by cancelling).
 *
 * <p><b>Why this exists — DO NOT REMOVE without reading
 * {@code .agent-work/popup-interaction-design-2026-04-30.md}.</b>
 *
 * <p>Companion to {@link ApplyActionTool} / {@link ApplyQuickfixTool}: when one of those tools
 * intercepts a popup chooser, the popup is cancelled (the EDT cannot stay frozen waiting for an
 * out-of-band agent decision), the choices are snapshotted, and the original call returns
 * success with a {@code popup_id}. The agent then calls {@code popup_respond} with that id and
 * either {@code "select"} (with {@code value_id} or {@code index}) or {@code "cancel"}.
 *
 * <h2>v1 limitations (intentional)</h2>
 * <ul>
 *   <li>Selecting a row with {@code hasSubstep=true} is rejected — the popup-cancel-and-replay
 *       model does not yet handle chained popups (the chained popup would re-freeze the EDT
 *       inside the replay).</li>
 *   <li>If replay opens a brand-new popup that is identical to the previous one (loop), the
 *       call returns an error pointing the agent at {@code edit_text}.</li>
 * </ul>
 */
public final class PopupRespondTool extends QualityTool {

    private static final Logger LOG = Logger.getInstance(PopupRespondTool.class);

    private static final String PARAM_POPUP_ID = "popup_id";
    private static final String PARAM_ACTION = "action";
    private static final String PARAM_VALUE_ID = "value_id";
    private static final String PARAM_INDEX = "index";
    private static final String ACTION_SELECT = "select";
    private static final String ACTION_CANCEL = "cancel";

    private final Map<String, Replayable> replayables;

    /**
     * @param replayables map from {@link Tool#id()} to the {@link Replayable} implementation —
     *                    typically {@code apply_action → ApplyActionTool},
     *                    {@code apply_quickfix → ApplyQuickfixTool}.
     */
    public PopupRespondTool(@NotNull Project project, @NotNull Map<String, Replayable> replayables) {
        super(project);
        this.replayables = Map.copyOf(replayables);
    }

    @Override
    public @NotNull String id() {
        return PopupGateLogic.POPUP_RESPOND_TOOL;
    }

    @Override
    public @NotNull String displayName() {
        return "Popup Respond";
    }

    @Override
    public @NotNull String description() {
        return "Resolve a pending IDE popup chooser opened by apply_action or apply_quickfix. "
            + "When one of those tools opens a popup (e.g. an ambiguous-import chooser) the "
            + "tool returns a popup_id and the list of choices instead of failing. Use this "
            + "tool to either select a choice (action='select' with value_id or index) or "
            + "dismiss the popup (action='cancel'). Selecting a substep choice is not yet "
            + "supported.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public boolean requiresInteractiveEdt() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_POPUP_ID, TYPE_STRING,
                "The popup_id returned by the tool that opened the popup"),
            Param.required(PARAM_ACTION, TYPE_STRING,
                "Either 'select' to choose a row, or 'cancel' to dismiss the popup"),
            Param.optional(PARAM_VALUE_ID, TYPE_STRING,
                "Stable value_id of the row to select (preferred over 'index')"),
            Param.optional(PARAM_INDEX, TYPE_INTEGER,
                "0-based row index to select; only used when value_id is not provided")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has(PARAM_POPUP_ID) || !args.has(PARAM_ACTION)) {
            return "Error: 'popup_id' and 'action' parameters are required";
        }
        String popupId = args.get(PARAM_POPUP_ID).getAsString();
        String action = args.get(PARAM_ACTION).getAsString();

        PendingPopupService pps = PendingPopupService.getInstance();
        PendingPopupService.Pending pending = pps.peek();
        if (pending == null) {
            return "Error: no popup is currently pending. The popup may have already been "
                + "resolved, expired (5 min), or auto-cancelled (5 unrelated tool calls).";
        }
        if (!pending.id().equals(popupId)) {
            return "Error: popup_id mismatch (got '" + popupId + "', current pending id is '"
                + pending.id() + "'). The earlier popup has likely been resolved already.";
        }

        if (ACTION_CANCEL.equalsIgnoreCase(action)) {
            pps.cancelAndClear(popupId);
            return "Popup '" + pending.snapshot().popupTitle()
                + "' (id=" + popupId + ") cancelled. The original action ('"
                + pending.fingerprint().actionIdentity() + "') was NOT applied.";
        }
        if (!ACTION_SELECT.equalsIgnoreCase(action)) {
            return "Error: action must be 'select' or 'cancel' (got '" + action + "').";
        }

        // Locate the chosen row.
        PopupChoice chosen = resolveChoice(pending.snapshot().choices(), args);
        if (chosen == null) {
            return "Error: could not resolve a choice from the provided value_id/index. "
                + "Available value_ids: " + pending.snapshot().choices().stream()
                .map(c -> "'" + c.valueId() + "'").toList();
        }
        if (!chosen.selectable()) {
            return "Error: choice '" + chosen.text() + "' is marked non-selectable in the popup.";
        }
        if (chosen.hasSubstep()) {
            return "Error: choice '" + chosen.text() + "' opens a submenu (chained popup). "
                + "Chained popups are not yet supported by popup_respond. Pick a leaf choice "
                + "or use edit_text to make the change manually.";
        }

        // Validate fingerprint before replaying — if the file changed since snapshot, the
        // action may now do the wrong thing.
        ContextFingerprint current = currentFingerprintFor(pending.fingerprint());
        if (!pending.fingerprint().matches(current)) {
            pps.cancelAndClear(popupId);
            return "Error: context changed since the popup was captured ("
                + pending.fingerprint().describeMismatch(current)
                + "). The pending popup has been discarded; please retry the original tool call.";
        }

        Replayable replayable = replayables.get(pending.originalTool());
        if (replayable == null) {
            pps.cancelAndClear(popupId);
            return "Error: original tool '" + pending.originalTool()
                + "' does not implement replay; cannot complete popup. The pending popup has been discarded.";
        }

        // Take the slot before invoking — replay may itself attempt to register a new pending
        // (chained popup case), and we want the slot free for that. If replay fails we don't
        // resurrect it: the agent should re-issue the original call.
        PendingPopupService.Pending taken = pps.take(popupId);
        if (taken == null) {
            return "Error: pending popup vanished between peek and take (race). Please retry.";
        }

        PopupHandler.SelectByValue handler = new PopupHandler.SelectByValue(
            chosen.valueId(), chosen.index(), chosen.text());
        try {
            String result = invokeReplay(replayable, taken.originalArgs(), handler);
            // After replay, if a NEW pending was registered for a chained popup with the same
            // content digest as before, that's a loop — discard and tell the agent.
            PendingPopupService.Pending after = pps.peek();
            if (after != null && after.snapshot().contentDigest()
                .equals(taken.snapshot().contentDigest())) {
                pps.cancelAndClear(after.id());
                return "Error: popup loop detected — replaying the action opened the same "
                    + "chooser again. The action will not converge non-interactively. Use "
                    + "edit_text instead.";
            }
            return result;
        } catch (Exception e) {
            LOG.warn("popup_respond: replay of '" + taken.originalTool() + "' failed", e);
            return "Error: replay failed: " + e.getMessage();
        }
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SimpleStatusRenderer.INSTANCE;
    }

    @Nullable
    private static PopupChoice resolveChoice(@NotNull List<PopupChoice> choices,
                                             @NotNull JsonObject args) {
        if (args.has(PARAM_VALUE_ID)) {
            String wantId = args.get(PARAM_VALUE_ID).getAsString();
            for (PopupChoice c : choices) {
                if (c.valueId().equals(wantId)) return c;
            }
        }
        if (args.has(PARAM_INDEX)) {
            int idx = args.get(PARAM_INDEX).getAsInt();
            if (idx >= 0 && idx < choices.size()) return choices.get(idx);
        }
        return null;
    }

    /**
     * Builds a fingerprint of the <em>current</em> document state for the file the snapshot
     * targeted, for comparison against the snapshot's stored fingerprint. Returns a fingerprint
     * with {@code documentModStamp = -1} when the file can't be resolved (which still passes
     * {@link ContextFingerprint#matches} because the original may also have had no stamp).
     */
    @NotNull
    private ContextFingerprint currentFingerprintFor(@NotNull ContextFingerprint snapshotFp) {
        long stamp = -1;
        if (snapshotFp.filePath() != null) {
            VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(snapshotFp.filePath());
            if (vf != null) {
                var doc = FileDocumentManager.getInstance().getDocument(vf);
                if (doc != null) stamp = doc.getModificationStamp();
            }
        }
        return new ContextFingerprint(
            project.getName(), snapshotFp.filePath(), stamp, snapshotFp.actionIdentity());
    }

    private static String invokeReplay(@NotNull Replayable replayable,
                                       @NotNull JsonObject args,
                                       @NotNull PopupHandler.SelectByValue handler) throws InterruptedException, ExecutionException, TimeoutException {
        // Replay needs to run on the EDT (same as the original execute()) because most quality
        // tools dispatch their core work via EdtUtil.invokeLater. Calling replay directly from
        // an MCP worker thread would just chain another invokeLater — that's fine, but we
        // future-proof by also providing the worker-thread fallback in case a Replayable is
        // synchronous.
        CompletableFuture<String> f = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                f.complete(replayable.replay(args, handler));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                f.completeExceptionally(ie);
            } catch (Exception e) {
                f.completeExceptionally(e);
            }
        });
        return f.get(60, TimeUnit.SECONDS);
    }
}
