package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.file.FileTool;
import com.github.catatafishen.agentbridge.services.McpCallContext;
import com.github.catatafishen.agentbridge.ui.renderers.SimpleStatusRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Applies an IntelliJ quick-fix at a specific file and line.
 */
public final class ApplyQuickfixTool extends QualityTool implements Replayable {

    private static final Logger LOG = Logger.getInstance(ApplyQuickfixTool.class);
    private static final String PARAM_FIX_INDEX = "fix_index";

    public ApplyQuickfixTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "apply_quickfix";
    }

    @Override
    public boolean requiresInteractiveEdt() {
        return true;
    }

    @Override
    public @NotNull String displayName() {
        return "Apply Quickfix";
    }

    @Override
    public @NotNull String description() {
        return "Apply an IntelliJ quick-fix by inspection ID at a specific file and line. " +
            "The inspection_id comes from run_inspections output. " +
            "For quick-fixes by action name (from get_highlights), use apply_action instead.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required("file", TYPE_STRING, "Path to the file containing the problem"),
            Param.required("line", TYPE_INTEGER, "Line number where the problem is located"),
            Param.required(PARAM_INSPECTION_ID, TYPE_STRING, "The inspection ID from run_inspections output (e.g., 'unused')"),
            Param.optional(PARAM_FIX_INDEX, TYPE_INTEGER, "Which fix to apply if multiple are available (default: 0)")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args)
        throws InterruptedException, ExecutionException, TimeoutException {
        return executeWithHandler(args, new PopupHandler.Cancel(), false);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Re-runs the same quick-fix pipeline with a {@link PopupHandler.SelectByValue}
     * handler — see {@link PopupRespondTool} and
     * {@code .agent-work/popup-interaction-design-2026-04-30.md}.
     */
    @Override
    public @NotNull String replay(@NotNull JsonObject originalArgs,
                                  @NotNull PopupHandler.SelectByValue handler)
        throws InterruptedException, ExecutionException, TimeoutException {
        return executeWithHandler(originalArgs, handler, true);
    }

    private @NotNull String executeWithHandler(@NotNull JsonObject args,
                                               @NotNull PopupHandler handler,
                                               boolean isReplay) throws InterruptedException, ExecutionException, TimeoutException {
        if (!args.has("file") || !args.has("line") || !args.has(PARAM_INSPECTION_ID)) {
            return "Error: 'file', 'line', and '" + PARAM_INSPECTION_ID + "' parameters are required";
        }
        QuickfixRequest request = QuickfixRequest.from(args, handler, isReplay);
        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> applyQuickfixSafely(request, resultFuture));

        String result = resultFuture.get(30, TimeUnit.SECONDS);
        if (shouldFollowFile(result)) {
            FileTool.followFileIfEnabled(project, request.pathStr(), request.targetLine(), request.targetLine(),
                FileTool.HIGHLIGHT_EDIT, FileTool.agentLabel(project) + " applied fix");
        }
        return result;
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SimpleStatusRenderer.INSTANCE;
    }

    private void applyQuickfixSafely(@NotNull QuickfixRequest request,
                                     @NotNull CompletableFuture<String> resultFuture) {
        try {
            resultFuture.complete(applyQuickfixOnEdt(request));
        } catch (Exception e) {
            LOG.warn("Error in applyQuickfix", e);
            resultFuture.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
        }
    }

    private String applyQuickfixOnEdt(@NotNull QuickfixRequest request) {
        QuickfixTarget target = resolveQuickfixTarget(request);
        if (target.error() != null) return target.error();

        // Write phase: only the actual fix application needs WriteAction.
        // notifyEditComplete() must run even if WriteAction.run itself throws before
        // invoking the lambda, otherwise the ThreadLocal agent-edit marker leaks across
        // tool calls. Wrap the whole WriteAction.run() in try/finally (matches the
        // pattern used in ApplyActionTool / SuppressInspectionTool / WriteFileTool).
        FileTool.notifyBeforeEdit(project, target.vf(), target.document());
        try {
            AtomicReference<String> result = new AtomicReference<>();
            WriteAction.run(() -> result.set(applyAndReportFix(new FixApplication(
                target.lineProblems(), request.fixIndex(), request.pathStr(), request.targetLine(),
                request.inspectionId(), target.vf(), target.document(), target.document().getModificationStamp(),
                request.originalArgs(), request.handler(), request.isReplay()))));
            return result.get();
        } catch (Exception e) {
            LOG.warn("Error applying quickfix", e);
            return "Error applying quickfix: " + e.getMessage();
        } finally {
            FileTool.notifyEditComplete();
        }
    }

    private QuickfixTarget resolveQuickfixTarget(@NotNull QuickfixRequest request) {
        VirtualFile vf = resolveVirtualFile(request.pathStr());
        if (vf == null) {
            return QuickfixTarget.error(ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + request.pathStr());
        }

        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) {
            return QuickfixTarget.error(ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_CANNOT_PARSE + request.pathStr());
        }

        Document document = FileDocumentManager.getInstance().getDocument(vf);
        if (document == null) {
            return QuickfixTarget.error("Error: Cannot get document for: " + request.pathStr());
        }

        String lineError = validateLine(request, document);
        if (lineError != null) return QuickfixTarget.error(lineError);

        var profile = com.intellij.profile.codeInspection.InspectionProjectProfileManager
            .getInstance(project).getCurrentProfile();
        var toolWrapper = profile.getInspectionTool(request.inspectionId(), project);
        if (toolWrapper == null) {
            return QuickfixTarget.error("Error: Inspection '" + request.inspectionId() + "' not found. "
                + "Use the inspection ID from run_inspections output (e.g., 'RedundantCast', 'unused').");
        }

        int lineStartOffset = document.getLineStartOffset(request.targetLine() - 1);
        int lineEndOffset = document.getLineEndOffset(request.targetLine() - 1);
        List<com.intellij.codeInspection.ProblemDescriptor> lineProblems =
            findProblemsOnLine(toolWrapper.getTool(), psiFile, lineStartOffset, lineEndOffset);
        if (lineProblems.isEmpty()) return QuickfixTarget.error(noProblemsMessage(request));
        return new QuickfixTarget(vf, document, lineProblems, null);
    }

    @org.jetbrains.annotations.Nullable
    private static String validateLine(@NotNull QuickfixRequest request, @NotNull Document document) {
        if (request.targetLine() >= 1 && request.targetLine() <= document.getLineCount()) return null;
        return "Error: Line " + request.targetLine() + " is out of bounds (file has "
            + document.getLineCount() + FORMAT_LINES_SUFFIX;
    }

    private static String noProblemsMessage(@NotNull QuickfixRequest request) {
        return "No problems found for inspection '" + request.inspectionId() + "' at line " + request.targetLine()
            + " in " + request.pathStr() + ". The inspection may have been resolved, or it may be a global inspection "
            + "that doesn't support quickfixes. Try using edit_text instead.";
    }

    private static boolean shouldFollowFile(@NotNull String result) {
        return !result.startsWith("Error") && !result.startsWith("No ") && !result.startsWith("The action ");
    }

    private record QuickfixRequest(String pathStr, int targetLine, String inspectionId, int fixIndex,
                                   @NotNull JsonObject originalArgs,
                                   @NotNull PopupHandler handler, boolean isReplay) {
        static QuickfixRequest from(@NotNull JsonObject args, @NotNull PopupHandler handler, boolean isReplay) {
            return new QuickfixRequest(
                args.get("file").getAsString(),
                args.get("line").getAsInt(),
                args.get(PARAM_INSPECTION_ID).getAsString(),
                args.has(PARAM_FIX_INDEX) ? args.get(PARAM_FIX_INDEX).getAsInt() : 0,
                args,
                handler,
                isReplay
            );
        }
    }

    private record QuickfixTarget(VirtualFile vf, Document document,
                                  List<com.intellij.codeInspection.ProblemDescriptor> lineProblems,
                                  @org.jetbrains.annotations.Nullable String error) {
        static QuickfixTarget error(@NotNull String message) {
            return new QuickfixTarget(null, null, List.of(), message);
        }
    }

    private record FixApplication(List<com.intellij.codeInspection.ProblemDescriptor> lineProblems,
                                  int fixIndex, String pathStr, int targetLine,
                                  String inspectionId, VirtualFile vf, Document document,
                                  long beforeModStamp, @NotNull JsonObject originalArgs,
                                  @NotNull PopupHandler handler, boolean isReplay) {
    }

    private record PopupSuspensionRequest(String fixName, String inspectionId,
                                          @NotNull JsonObject originalArgs,
                                          @NotNull Document doc, long beforeModStamp,
                                          @NotNull PopupInterceptor.Result popupResult,
                                          @org.jetbrains.annotations.Nullable PopupSnapshot snapshot,
                                          @NotNull VirtualFile vf) {
    }

    // ── Private helpers ──────────────────────────────────────

    private List<com.intellij.codeInspection.ProblemDescriptor> findProblemsOnLine(
        com.intellij.codeInspection.InspectionProfileEntry tool, PsiFile psiFile,
        int lineStartOffset, int lineEndOffset) {
        List<com.intellij.codeInspection.ProblemDescriptor> problems = new ArrayList<>();
        if (tool instanceof com.intellij.codeInspection.LocalInspectionTool localTool) {
            var inspectionManager = com.intellij.codeInspection.InspectionManager.getInstance(project);
            var holder = new com.intellij.codeInspection.ProblemsHolder(inspectionManager, psiFile, false);
            var visitor = localTool.buildVisitor(holder, false);
            psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
                @Override
                public void visitElement(@NotNull PsiElement element) {
                    element.accept(visitor);
                    super.visitElement(element);
                }
            });
            problems.addAll(holder.getResults());
        }

        List<com.intellij.codeInspection.ProblemDescriptor> lineProblems = new ArrayList<>();
        for (var problem : problems) {
            PsiElement elem = problem.getPsiElement();
            if (elem != null) {
                int offset = elem.getTextOffset();
                if (offset >= lineStartOffset && offset <= lineEndOffset) {
                    lineProblems.add(problem);
                }
            }
        }
        return lineProblems;
    }

    @SuppressWarnings("unchecked") // QuickFix generic — safe at runtime
    private String applyAndReportFix(@NotNull FixApplication request) {
        com.intellij.codeInspection.ProblemDescriptor targetProblem =
            request.lineProblems().get(Math.min(request.fixIndex(), request.lineProblems().size() - 1));

        var fixes = targetProblem.getFixes();
        if (fixes == null || fixes.length == 0) {
            return "No quickfixes available for this problem. Description: " +
                targetProblem.getDescriptionTemplate() + ". Use edit_text to fix manually.";
        }

        var fix = fixes[Math.min(request.fixIndex(), fixes.length - 1)];
        AtomicReference<PopupSnapshot> snapRef = new AtomicReference<>();
        PopupInterceptor.Result popupResult = runFixWithPopupDetection(request, targetProblem, fix, snapRef);
        String popupMessage = handlePopupResult(request, fix.getName(), popupResult, snapRef.get());
        if (popupMessage != null) return popupMessage;

        PsiDocumentManager.getInstance(project).commitAllDocuments();
        FileDocumentManager.getInstance().saveAllDocuments();
        return formatAppliedFix(request, fixes, fix.getName());
    }

    private PopupInterceptor.Result runFixWithPopupDetection(
        @NotNull FixApplication request,
        @NotNull com.intellij.codeInspection.ProblemDescriptor targetProblem,
        @NotNull com.intellij.codeInspection.QuickFix<com.intellij.codeInspection.ProblemDescriptor> fix,
        @NotNull AtomicReference<PopupSnapshot> snapRef) {
        PopupHandler effective = wrapHandlerForCapture(request.handler(), snapRef);
        return PopupInterceptor.runDetectingPopups(null, effective,
            () -> com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                project,
                () -> fix.applyFix(project, targetProblem),
                "Apply Quick Fix: " + fix.getName(),
                null
            )
        );
    }

    @org.jetbrains.annotations.Nullable
    private String handlePopupResult(@NotNull FixApplication request, @NotNull String fixName,
                                     @NotNull PopupInterceptor.Result popupResult,
                                     @org.jetbrains.annotations.Nullable PopupSnapshot snapshot) {
        if (!popupResult.popupWasOpened()) return null;

        boolean isSelectMode = request.handler() instanceof PopupHandler.SelectByValue;
        if (!request.isReplay() && !isSelectMode) {
            String suspended = trySuspendForPopup(new PopupSuspensionRequest(
                fixName, request.inspectionId(), request.originalArgs(), request.document(),
                request.beforeModStamp(), popupResult, snapshot, request.vf()));
            if (suspended != null) return suspended;
        }
        if (isSelectMode && !popupResult.selectionScheduled()) {
            return PopupInterceptor.formatPopupBlockedError("apply_quickfix:" + fixName, popupResult);
        }
        if (request.isReplay()) {
            return "Error: replaying quick-fix '" + fixName + "' opened a chained popup ('"
                + popupResult.describe() + "'). Chained popups are not supported by popup_respond"
                + " yet — use edit_text to complete the change manually.";
        }
        return isSelectMode ? null : PopupInterceptor.formatPopupBlockedError("apply_quickfix:" + fixName, popupResult);
    }

    private static String formatAppliedFix(@NotNull FixApplication request,
                                           com.intellij.codeInspection.QuickFix<?>[] fixes,
                                           @NotNull String fixName) {
        StringBuilder sb = new StringBuilder();
        sb.append("Applied fix: ").append(fixName).append("\n");
        sb.append("  File: ").append(request.pathStr()).append(" line ").append(request.targetLine()).append("\n");
        appendOtherFixes(sb, fixes, request.fixIndex());
        return sb.toString();
    }

    private static void appendOtherFixes(StringBuilder sb, com.intellij.codeInspection.QuickFix<?>[] fixes, int fixIndex) {
        int appliedIndex = Math.min(fixIndex, fixes.length - 1);
        if (fixes.length <= 1) return;
        sb.append("  (").append(fixes.length).append(" fixes were available, applied #")
            .append(appliedIndex).append(")\n");
        sb.append("  Other available fixes:\n");
        for (int i = 0; i < fixes.length; i++) {
            if (i != appliedIndex) {
                sb.append("    ").append(i).append(": ").append(fixes[i].getName()).append("\n");
            }
        }
    }

    /**
     * Same wrapping pattern as ApplyActionTool's popup-capture wrapper: upgrade
     * {@link PopupHandler.Cancel} to {@link PopupHandler.Snapshot} so we can capture choices,
     * chain {@link PopupHandler.Snapshot} sinks, pass {@link PopupHandler.SelectByValue}
     * through unchanged.
     */
    @NotNull
    private static PopupHandler wrapHandlerForCapture(@NotNull PopupHandler in,
                                                      @NotNull AtomicReference<PopupSnapshot> sink) {
        return switch (in) {
            case PopupHandler.SelectByValue sv -> sv;
            case PopupHandler.Snapshot(var sinkFn) -> new PopupHandler.Snapshot(s -> {
                sink.set(s);
                sinkFn.accept(s);
            });
            case PopupHandler.Cancel() -> new PopupHandler.Snapshot(sink::set);
        };
    }

    /**
     * Registers a {@link PendingPopupService.Pending} for the just-intercepted popup so the
     * agent can drive it via {@code popup_respond}. Returns the suspended-message on success,
     * or {@code null} to fall through to PR #363's error.
     */
    @org.jetbrains.annotations.Nullable
    private String trySuspendForPopup(@NotNull PopupSuspensionRequest request) {
        PopupSnapshot snapshot = request.snapshot();
        if (snapshot == null || snapshot.isEmpty()) {
            LOG.info("ApplyQuickfixTool: popup intercepted but snapshot empty — falling back to error");
            return null;
        }
        // Quick-fixes run inside WriteAction with our CommandProcessor group; the IntelliJ
        // undo manager records the command. We don't attempt rollback here for v1 because
        // quick-fix popup-then-mutate is rare and cleanly undoing across nested commands is
        // brittle. If the action mutated the document before the popup, refuse to suspend so
        // the user is not left with dangling edits.
        if (request.doc().getModificationStamp() != request.beforeModStamp()) {
            LOG.info("ApplyQuickfixTool: quick-fix mutated document before opening popup; refusing to suspend");
            return null;
        }
        ContextFingerprint fp = new ContextFingerprint(
            project.getName(), request.vf().getPath(), request.beforeModStamp(), id() + "|" + request.inspectionId());
        PendingPopupService.Pending pending = PendingPopupService.getInstance().register(
            id(), request.originalArgs(), project, fp, snapshot,
            McpCallContext.currentOrFallback(), null);
        if (pending == null) return null;
        return formatPopupSuspendedMessage(request.fixName(), request.popupResult().describe(), pending);
    }

    @NotNull
    private static String formatPopupSuspendedMessage(@NotNull String fixName,
                                                      @NotNull String popupTitles,
                                                      @NotNull PendingPopupService.Pending pending) {
        StringBuilder sb = new StringBuilder();
        sb.append("The quick-fix '").append(fixName).append("' opened a popup chooser (")
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
}
