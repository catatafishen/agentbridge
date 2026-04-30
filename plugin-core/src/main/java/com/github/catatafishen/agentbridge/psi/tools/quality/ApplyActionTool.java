package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.file.FileTool;
import com.github.catatafishen.agentbridge.services.McpCallContext;
import com.github.catatafishen.agentbridge.ui.renderers.GitDiffRenderer;
import com.google.gson.JsonObject;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class ApplyActionTool extends QualityTool implements Replayable {

    private static final Logger LOG = Logger.getInstance(ApplyActionTool.class);
    private static final String PARAM_COLUMN = "column";
    private static final String PARAM_SYMBOL = "symbol";
    private static final String PARAM_ACTION_NAME = "action_name";
    private static final String PARAM_OPTION = "option";
    private static final String PARAM_DRY_RUN = "dry_run";
    private static final String LINE_LABEL = " line ";
    private static final String ACTION_PREFIX = "Action '";
    private static final String IMPORT_CLASS_PREFIX = "Import class '";
    private static final int AMBIGUOUS_IMPORT_FQNS_TO_LIST = 5;

    public ApplyActionTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "apply_action";
    }

    @Override
    public boolean requiresInteractiveEdt() {
        return true;
    }

    @Override
    public @NotNull String displayName() {
        return "Apply Action";
    }

    @Override
    public @NotNull String description() {
        return "Invoke a named IDE quick-fix or intention action at a specific file and line. "
            + "Action names come from get_highlights or get_available_actions output. "
            + "Use 'symbol' (preferred) or 'column' to position the caret at the correct symbol. "
            + "Use 'option' to select a radio-button or checkbox in a dialog the action may show "
            + "(get options first with get_action_options). "
            + "Use 'dry_run: true' to preview changes as a diff without applying them. "
            + "Tip: use optimize_imports to fix all missing imports at once.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required("file", TYPE_STRING, "Path to the file"),
            Param.required("line", TYPE_INTEGER, "Line number (1-based)"),
            Param.required(PARAM_ACTION_NAME, TYPE_STRING, "Exact action name from get_highlights / get_available_actions output"),
            Param.optional(PARAM_SYMBOL, TYPE_STRING, "Symbol name on the line (e.g. '_scrollRAF'). "
                + "Auto-detects the column — preferred over specifying 'column' manually."),
            Param.optional(PARAM_COLUMN, TYPE_INTEGER, "Column number (1-based, optional). Use 'symbol' instead when possible."),
            Param.optional(PARAM_OPTION, TYPE_STRING, "Option to select in a dialog the action may show "
                + "(radio button or checkbox text, from get_action_options output)."),
            Param.optional(PARAM_DRY_RUN, TYPE_BOOLEAN, "If true, shows a diff of what the action would change "
                + "without actually applying it. The change is applied then immediately undone.")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        return executeWithHandler(args, new PopupHandler.Cancel(), false);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Re-runs the same action pipeline but with a {@link PopupHandler.SelectByValue}
     * handler — see {@link PopupRespondTool} and
     * {@code .agent-work/popup-interaction-design-2026-04-30.md}.
     */
    @Override
    public @NotNull String replay(@NotNull JsonObject originalArgs,
                                  @NotNull PopupHandler.SelectByValue handler) throws Exception {
        return executeWithHandler(originalArgs, handler, true);
    }

    /**
     * Shared dispatch for both {@link #execute} and {@link #replay}. The {@code handler}
     * controls what happens when an {@link com.intellij.openapi.ui.popup.JBPopup} opens
     * during the action invocation. {@code isReplay} marks calls coming from
     * {@link PopupRespondTool} so the snapshot-and-suspend path is skipped (we want to
     * complete the action this time, not capture choices again).
     */
    private @NotNull String executeWithHandler(@NotNull JsonObject args,
                                               @NotNull PopupHandler handler,
                                               boolean isReplay) throws Exception {
        if (!args.has("file") || !args.has("line") || !args.has(PARAM_ACTION_NAME)) {
            return "Error: 'file', 'line', and 'action_name' parameters are required";
        }
        String pathStr = args.get("file").getAsString();
        int targetLine = args.get("line").getAsInt();
        String actionName = args.get(PARAM_ACTION_NAME).getAsString();
        String symbol = args.has(PARAM_SYMBOL) ? args.get(PARAM_SYMBOL).getAsString() : null;
        Integer targetCol = args.has(PARAM_COLUMN) ? args.get(PARAM_COLUMN).getAsInt() : null;
        String option = args.has(PARAM_OPTION) ? args.get(PARAM_OPTION).getAsString() : null;
        boolean dryRun = args.has(PARAM_DRY_RUN) && args.get(PARAM_DRY_RUN).getAsBoolean();

        CompletableFuture<String> future = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                future.complete(invokeAction(pathStr, targetLine, actionName, symbol, targetCol,
                    option, dryRun, args, handler, isReplay));
            } catch (AssertionError e) {
                // Some actions (e.g. SafeDeleteFix) trigger interactive dialogs via assertions
                // that fail headlessly. Catch AssertionError separately for a clear message.
                LOG.warn(ACTION_PREFIX + actionName + "' requires interactive UI at " + pathStr + ":" + targetLine, e);
                future.complete("Error: " + ACTION_PREFIX + actionName + "' requires an interactive dialog "
                    + "and cannot be applied non-interactively. "
                    + "Try get_action_options to see what dialog options it shows, "
                    + "or perform this action manually in the IDE.");
            } catch (Exception e) {
                LOG.warn("Error invoking action '" + actionName + "' at " + pathStr + ":" + targetLine, e);
                future.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
            }
        });

        String result = future.get(30, TimeUnit.SECONDS);
        if (result == null) return "Error: action invocation returned no result";
        if (!dryRun && !result.startsWith("Error") && !result.startsWith("No ")
            && !result.startsWith(ACTION_PREFIX) && !result.startsWith("Preview")
            && !result.startsWith("The action ")) {
            FileTool.followFileIfEnabled(project, pathStr, targetLine, targetLine,
                FileTool.HIGHLIGHT_EDIT, FileTool.agentLabel(project) + " applied action");
        }
        return result;
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GitDiffRenderer.INSTANCE;
    }

    // ── Private helpers ──────────────────────────────────────

    private String invokeAction(String pathStr, int targetLine, String actionName,
                                @Nullable String symbol, @Nullable Integer targetCol,
                                @Nullable String option, boolean dryRun,
                                @NotNull JsonObject originalArgs,
                                @NotNull PopupHandler handler, boolean isReplay) {
        VirtualFile vf = resolveVirtualFile(pathStr);
        if (vf == null) return ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + pathStr;

        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return "Error: Cannot get document for: " + pathStr;

        if (targetLine < 1 || targetLine > doc.getLineCount()) {
            return "Error: Line " + targetLine + " is out of bounds (file has " + doc.getLineCount() + FORMAT_LINES_SUFFIX;
        }

        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_CANNOT_PARSE + pathStr;

        // Skip ambiguous-import precheck on replay — the agent already saw the choices and
        // made a selection; refusing here would leave them stuck.
        if (!isReplay) {
            String ambiguousImportError = checkAmbiguousImport(actionName, psiFile);
            if (ambiguousImportError != null) return ambiguousImportError;
        }

        Editor editor = getOrOpenEditor(vf);
        if (editor == null) {
            return "Error: Could not open editor for " + pathStr + ". Ensure the file is open in the IDE.";
        }

        int caretCol = resolveColumn(doc, targetLine, symbol, targetCol);
        editor.getCaretModel().moveToLogicalPosition(new LogicalPosition(targetLine - 1, caretCol));

        IntentionAction action = findActionToApply(doc, targetLine, actionName, editor, psiFile);
        if (action == null) {
            List<String> available = collectAvailableActionNames(doc, targetLine, editor, psiFile);
            String hint = available.isEmpty() ? "none" : String.join(", ", available);
            return ACTION_PREFIX + actionName + "' not found at " + pathStr + LINE_LABEL + targetLine
                + ". Available: [" + hint + "]";
        }

        if (!action.isAvailable(project, editor, psiFile)) {
            return ACTION_PREFIX + actionName + "' is not currently applicable at " + pathStr
                + LINE_LABEL + targetLine + ".";
        }

        String before = doc.getText();
        long beforeModStamp = doc.getModificationStamp();
        ActionContext ctx = new ActionContext(action, editor, psiFile, doc);

        if (option != null) {
            return applyWithOption(option, actionName, pathStr, targetLine, before, ctx);
        }
        if (dryRun) {
            return applyAsDryRun(actionName, pathStr, before, ctx, vf, handler);
        }
        return applyNormally(actionName, pathStr, targetLine, before, beforeModStamp, ctx,
            vf, originalArgs, handler, isReplay);
    }

    private String applyWithOption(String option, String actionName, String pathStr, int targetLine,
                                   String before, ActionContext ctx) {
        VirtualFile vf = FileDocumentManager.getInstance().getFile(ctx.doc());
        FileTool.notifyBeforeEdit(project, vf, ctx.doc());
        boolean selected;
        try {
            selected = DialogInterceptor.runAndSelectOption(
                () -> invokeRespectingWriteAction(actionName, ctx),
                option
            );
        } finally {
            FileTool.notifyEditComplete();
        }
        PsiDocumentManager.getInstance(project).commitDocument(ctx.doc());
        FileDocumentManager.getInstance().saveDocument(ctx.doc());
        String after = ctx.doc().getText();
        String diff = DiffUtils.unifiedDiff(before, after, pathStr);
        if (!selected) {
            return "Option '" + option + "' not found in dialog for action '" + actionName + "'. "
                + "Use get_action_options to see available options.";
        }
        return formatApplyResult(actionName, pathStr, targetLine, diff, false);
    }

    private String applyAsDryRun(String actionName, String pathStr, String before,
                                 ActionContext ctx, VirtualFile vf, @NotNull PopupHandler handler) {
        AtomicReference<PopupSnapshot> snapRef = new AtomicReference<>();
        PopupHandler effective = wrapHandlerForCapture(handler, snapRef);
        PopupInterceptor.Result popupResult = PopupInterceptor.runDetectingPopups(
            ctx.editor().getComponent(),
            effective,
            () -> invokeRespectingWriteAction(actionName, ctx)
        );
        if (popupResult.popupWasOpened() && !popupResult.selectionScheduled()) {
            // Dry-run cannot meaningfully suspend on a popup — preview semantics require a
            // single deterministic outcome. Fall back to PR #363 behaviour.
            return PopupInterceptor.formatPopupBlockedError(actionName, popupResult);
        }
        PsiDocumentManager.getInstance(project).commitDocument(ctx.doc());
        String after = ctx.doc().getText();
        String diff = DiffUtils.unifiedDiff(before, after, pathStr);
        undoLastAction(vf);
        if (diff.isEmpty()) {
            return "Preview: action '" + actionName + "' would make no changes (it may require a dialog — "
                + "use get_action_options to check).";
        }
        return "Preview (not applied):\n\n" + diff;
    }

    private String applyNormally(String actionName, String pathStr, int targetLine,
                                 String before, long beforeModStamp, ActionContext ctx,
                                 VirtualFile vf, @NotNull JsonObject originalArgs,
                                 @NotNull PopupHandler handler, boolean isReplay) {
        FileTool.notifyBeforeEdit(project, vf, ctx.doc());
        PopupInterceptor.Result popupResult;
        AtomicReference<PopupSnapshot> snapRef = new AtomicReference<>();
        PopupHandler effective = wrapHandlerForCapture(handler, snapRef);
        try {
            popupResult = PopupInterceptor.runDetectingPopups(
                ctx.editor().getComponent(),
                effective,
                () -> invokeRespectingWriteAction(actionName, ctx)
            );
        } finally {
            FileTool.notifyEditComplete();
        }

        boolean popupOpened = popupResult.popupWasOpened();
        boolean isSelectMode = handler instanceof PopupHandler.SelectByValue;
        boolean isCancelMode = handler instanceof PopupHandler.Cancel;
        boolean isSnapshotMode = handler instanceof PopupHandler.Snapshot;

        if (popupOpened && (isSnapshotMode || isCancelMode) && !isReplay) {
            // Default-mode popup: try to capture a snapshot and suspend the call so the agent
            // can drive the popup via popup_respond. Falls back to PR #363 error when the
            // snapshot is unusable or rollback fails.
            String suspended = trySuspendForPopup(actionName, originalArgs, ctx.doc(),
                beforeModStamp, popupResult, snapRef.get(), vf);
            if (suspended != null) return suspended;
            return PopupInterceptor.formatPopupBlockedError(actionName, popupResult);
        }
        if (popupOpened && isSelectMode && !popupResult.selectionScheduled()) {
            return PopupInterceptor.formatPopupBlockedError(actionName, popupResult);
        }
        if (popupOpened && isReplay && isSnapshotMode) {
            // Chained popup during replay — out of scope for v1; surface a clear error.
            return "Error: replaying action '" + actionName + "' opened a chained popup ('"
                + popupResult.describe() + "'). Chained popups are not supported by popup_respond"
                + " yet — please use edit_text to complete the change manually.";
        }

        PsiDocumentManager.getInstance(project).commitDocument(ctx.doc());
        FileDocumentManager.getInstance().saveDocument(ctx.doc());
        String after = ctx.doc().getText();
        String diff = DiffUtils.unifiedDiff(before, after, pathStr);
        if (diff.isEmpty()) {
            return ACTION_PREFIX + actionName + "' made no changes. It may require user input via a dialog. "
                + "Try get_action_options to inspect what dialog options it shows.";
        }
        return formatApplyResult(actionName, pathStr, targetLine, diff, true);
    }

    /**
     * If the caller passed a {@link PopupHandler.Cancel} we upgrade it to {@link PopupHandler.Snapshot}
     * so we can still capture choices for {@link PendingPopupService} registration. If the caller
     * already passed Snapshot we chain its sink. SelectByValue is passed through unchanged.
     */
    @NotNull
    private static PopupHandler wrapHandlerForCapture(@NotNull PopupHandler in,
                                                      @NotNull AtomicReference<PopupSnapshot> sink) {
        return switch (in) {
            case PopupHandler.SelectByValue sv -> sv;
            case PopupHandler.Snapshot snap -> new PopupHandler.Snapshot(s -> {
                sink.set(s);
                snap.sink().accept(s);
            });
            case PopupHandler.Cancel ignored -> new PopupHandler.Snapshot(sink::set);
        };
    }

    /**
     * Attempts to register a {@link PendingPopupService.Pending} for the just-intercepted popup,
     * after rolling back any document changes the action made before opening the popup. Returns
     * the agent-facing success message when registration succeeds, or {@code null} to fall
     * through to the PR #363 error path.
     */
    @Nullable
    private String trySuspendForPopup(@NotNull String actionName, @NotNull JsonObject originalArgs,
                                      @NotNull Document doc, long beforeModStamp,
                                      @NotNull PopupInterceptor.Result popupResult,
                                      @Nullable PopupSnapshot snapshot, @NotNull VirtualFile vf) {
        if (snapshot == null || snapshot.isEmpty()) {
            LOG.info("ApplyActionTool: popup intercepted but snapshot was empty/null — falling back to error");
            return null;
        }
        long afterModStamp = doc.getModificationStamp();
        if (afterModStamp != beforeModStamp) {
            // Action mutated the document before opening the popup. Try to undo so the file
            // is left clean for the agent to either complete via popup_respond or pick a
            // different strategy. If we can't restore the original mod stamp, refuse to
            // suspend (PR #363 fallback).
            undoLastAction(vf);
            if (doc.getModificationStamp() != beforeModStamp) {
                LOG.warn("ApplyActionTool: rollback failed (mod stamp " + beforeModStamp + " → "
                    + doc.getModificationStamp() + "); cannot safely suspend for popup");
                return null;
            }
        }

        ContextFingerprint fp = new ContextFingerprint(
            project.getName(), vf.getPath(), beforeModStamp, id() + "|" + actionName);
        PendingPopupService.Pending pending = PendingPopupService.getInstance().register(
            id(), originalArgs, project, fp, snapshot,
            McpCallContext.currentOrFallback(), null);
        if (pending == null) {
            // Slot already taken — shouldn't happen because the gate blocks tool calls when
            // pending != null, but guard anyway.
            return null;
        }
        return formatPopupSuspendedMessage(actionName, popupResult.describe(), pending);
    }

    @NotNull
    private static String formatPopupSuspendedMessage(@NotNull String actionName,
                                                      @NotNull String popupTitles,
                                                      @NotNull PendingPopupService.Pending pending) {
        StringBuilder sb = new StringBuilder();
        sb.append("The action '").append(actionName).append("' opened a popup chooser (")
            .append(popupTitles).append("). The popup is suspended awaiting your selection ")
            .append("(popup_id=").append(pending.id()).append(").\n\n");
        sb.append("Choices:\n");
        var choices = pending.snapshot().choices();
        for (int i = 0; i < choices.size(); i++) {
            var c = choices.get(i);
            sb.append("  ").append(i).append(": ").append(c.text());
            if (c.secondaryText() != null) sb.append(" — ").append(c.secondaryText());
            if (!c.selectable()) sb.append("  (not selectable)");
            if (c.hasSubstep()) sb.append("  (opens submenu — not yet supported in v1)");
            sb.append('\n');
        }
        sb.append("\nTo complete: popup_respond(popup_id=\"").append(pending.id())
            .append("\", action=\"select\", value_id=\"<one of the value_id strings above>\")\n");
        sb.append("To cancel:   popup_respond(popup_id=\"").append(pending.id())
            .append("\", action=\"cancel\")\n\n");
        sb.append("(value_id strings: ");
        for (int i = 0; i < choices.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(choices.get(i).valueId()).append('"');
        }
        sb.append(")\n\n");
        sb.append("The popup will auto-cancel after ").append(PendingPopupService.MAX_UNRELATED_CALLS)
            .append(" unrelated tool calls or ").append(PendingPopupService.MAX_AGE.toMinutes())
            .append(" minutes.");
        return sb.toString();
    }

    /**
     * Delegates to the base-class contract-aware invocation, unpacking the {@link ActionContext}.
     */
    private void invokeRespectingWriteAction(String actionName, ActionContext ctx) {
        invokeRespectingWriteAction(actionName, ctx.action(), ctx.editor(), ctx.psiFile());
    }

    private record ActionContext(IntentionAction action, Editor editor, PsiFile psiFile, Document doc) {
    }

    static String formatApplyResult(String actionName, String pathStr, int line,
                                    String diff, boolean applied) {
        if (diff.isEmpty()) {
            return (applied ? "Applied" : "Selected option for") + " action: " + actionName
                + "\n  File: " + pathStr + LINE_LABEL + line + "\n  (no file changes)";
        }
        return (applied ? "Applied" : "Applied with option") + " action: " + actionName
            + "\n  File: " + pathStr + LINE_LABEL + line + "\n\n" + diff;
    }

    /**
     * Pre-flight check for ambiguous {@code Import class 'X'} quick-fixes.
     * <p>
     * When more than one class with the same simple name exists in the resolve scope,
     * the IDE shows a class-chooser {@link com.intellij.openapi.ui.popup.JBPopup} that
     * cannot be answered non-interactively. Invoking the action would block the EDT.
     * <p>
     * Returns an actionable error string when ambiguity is detected, otherwise {@code null}.
     * Best-effort: only matches the English action name. Returns {@code null} on any failure
     * (no Java module, PSI lookup error, etc.) so non-import actions are never blocked.
     */
    @Nullable
    private String checkAmbiguousImport(String actionName, PsiFile psiFile) {
        String simpleName = parseImportSimpleName(actionName);
        if (simpleName == null) return null;
        if (!PlatformApiCompat.isPluginInstalled("com.intellij.modules.java")) return null;
        try {
            List<String> candidates = ApplicationManager.getApplication().runReadAction(
                (Computable<List<String>>) () -> com.github.catatafishen.agentbridge.psi.java.RefactoringJavaSupport
                    .findClassFqnsByShortName(project, simpleName, psiFile)
            );
            if (candidates.size() > 1) {
                return formatAmbiguousImportError(simpleName, candidates);
            }
        } catch (Exception | LinkageError e) {
            LOG.debug("Ambiguous-import precheck skipped for '" + actionName + "'", e);
        }
        return null;
    }

    /**
     * Extracts the simple class name from an action name like {@code Import class 'Cell'}.
     * Returns {@code null} if the action name doesn't match the expected pattern.
     * <p>
     * <b>Localization caveat:</b> this only matches the English action name. In a localized
     * IDE this returns {@code null} and the precheck is a no-op — falling through to the
     * normal action invocation path. The {@code describeModalBlocker()} popup-detection
     * still surfaces the issue if the popup actually opens.
     */
    @Nullable
    static String parseImportSimpleName(String actionName) {
        if (actionName == null || !actionName.startsWith(IMPORT_CLASS_PREFIX)) return null;
        int end = actionName.indexOf('\'', IMPORT_CLASS_PREFIX.length());
        if (end <= IMPORT_CLASS_PREFIX.length()) return null;
        return actionName.substring(IMPORT_CLASS_PREFIX.length(), end);
    }

    /**
     * Formats the error message for an ambiguous import. Lists up to
     * {@value #AMBIGUOUS_IMPORT_FQNS_TO_LIST} candidate FQNs and indicates how many more exist.
     */
    static String formatAmbiguousImportError(String simpleName, List<String> candidates) {
        List<String> sorted = new ArrayList<>(candidates);
        Collections.sort(sorted);
        int show = Math.min(sorted.size(), AMBIGUOUS_IMPORT_FQNS_TO_LIST);
        StringBuilder sb = new StringBuilder();
        sb.append("Error: Import for '").append(simpleName).append("' is ambiguous (")
            .append(sorted.size()).append(" candidates: ");
        for (int i = 0; i < show; i++) {
            if (i > 0) sb.append(", ");
            sb.append(sorted.get(i));
        }
        if (sorted.size() > show) {
            sb.append(", … (").append(sorted.size() - show).append(" more)");
        }
        sb.append("). Invoking this quick-fix would open a class-chooser popup that cannot be ")
            .append("answered non-interactively (and would freeze the EDT). ")
            .append("Add the import line directly with edit_text using the fully-qualified name you want.");
        return sb.toString();
    }

    private void undoLastAction(VirtualFile vf) {
        try {
            var fem = FileEditorManager.getInstance(project);
            for (var fe : fem.getEditors(vf)) {
                if (fe instanceof TextEditor) {
                    var undoMgr = UndoManager.getInstance(project);
                    if (undoMgr.isUndoAvailable(fe)) {
                        PlatformApiCompat.undoOrRedoSilently(undoMgr, fe, true);
                        FileDocumentManager.getInstance().saveAllDocuments();
                    }
                    return;
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not undo dry-run action", e);
        }
    }

    /**
     * Finds the named action by first searching highlight quick-fixes, then falling back to
     * {@code IntentionManager} intention actions at the current caret position.
     */
    @Nullable
    private IntentionAction findActionToApply(Document doc, int targetLine, String name,
                                              Editor editor, PsiFile psiFile) {
        for (var h : highlightsOnLine(doc, targetLine)) {
            IntentionAction found = h.findRegisteredQuickFix((descriptor, range) -> {
                IntentionAction a = descriptor.getAction();
                if (name.equals(a.getText())) return a;
                return null;
            });
            if (found != null) return found;
        }
        return findIntentionByName(name, editor, psiFile);
    }

    /**
     * Collects names from both highlight quick-fixes and available intentions at the current
     * caret position, for use in "not found" error messages.
     */
    private List<String> collectAvailableActionNames(Document doc, int targetLine,
                                                     Editor editor, PsiFile psiFile) {
        List<String> names = new ArrayList<>();
        highlightsOnLine(doc, targetLine).forEach(h -> names.addAll(collectQuickFixNames(h)));
        names.addAll(collectIntentionNames(editor, psiFile));
        return names;
    }

}
