package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.file.FileTool;
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
import java.util.concurrent.TimeUnit;

/**
 * Applies an IntelliJ quick-fix at a specific file and line.
 */
public final class ApplyQuickfixTool extends QualityTool {

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
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("file") || !args.has("line") || !args.has(PARAM_INSPECTION_ID)) {
            return "Error: 'file', 'line', and '" + PARAM_INSPECTION_ID + "' parameters are required";
        }
        String pathStr = args.get("file").getAsString();
        int targetLine = args.get("line").getAsInt();
        String inspectionId = args.get(PARAM_INSPECTION_ID).getAsString();
        int fixIndex = args.has(PARAM_FIX_INDEX) ? args.get(PARAM_FIX_INDEX).getAsInt() : 0;

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    resultFuture.complete(ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
                    return;
                }

                // Read-only phase: resolve file, document, find problems — no WriteAction needed
                PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                if (psiFile == null) {
                    resultFuture.complete(ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_CANNOT_PARSE + pathStr);
                    return;
                }

                Document document = FileDocumentManager.getInstance().getDocument(vf);
                if (document == null) {
                    resultFuture.complete("Error: Cannot get document for: " + pathStr);
                    return;
                }

                if (targetLine < 1 || targetLine > document.getLineCount()) {
                    resultFuture.complete("Error: Line " + targetLine + " is out of bounds (file has "
                        + document.getLineCount() + FORMAT_LINES_SUFFIX);
                    return;
                }

                int lineStartOffset = document.getLineStartOffset(targetLine - 1);
                int lineEndOffset = document.getLineEndOffset(targetLine - 1);

                var profile = com.intellij.profile.codeInspection.InspectionProjectProfileManager
                    .getInstance(project).getCurrentProfile();
                var toolWrapper = profile.getInspectionTool(inspectionId, project);

                if (toolWrapper == null) {
                    resultFuture.complete("Error: Inspection '" + inspectionId + "' not found. "
                        + "Use the inspection ID from run_inspections output (e.g., 'RedundantCast', 'unused').");
                    return;
                }

                List<com.intellij.codeInspection.ProblemDescriptor> lineProblems =
                    findProblemsOnLine(toolWrapper.getTool(), psiFile, lineStartOffset, lineEndOffset);

                if (lineProblems.isEmpty()) {
                    resultFuture.complete("No problems found for inspection '" + inspectionId + "' at line " + targetLine
                        + " in " + pathStr + ". The inspection may have been resolved, or it may be a global inspection "
                        + "that doesn't support quickfixes. Try using edit_text instead.");
                    return;
                }

                // Write phase: only the actual fix application needs WriteAction.
                // notifyEditComplete() must run even if WriteAction.run itself throws before
                // invoking the lambda, otherwise the ThreadLocal agent-edit marker leaks across
                // tool calls. Wrap the whole WriteAction.run() in try/finally (matches the
                // pattern used in ApplyActionTool / SuppressInspectionTool / WriteFileTool).
                FileTool.notifyBeforeEdit(project, vf, document);
                try {
                    WriteAction.run(() -> {
                        try {
                            resultFuture.complete(applyAndReportFix(lineProblems, fixIndex, pathStr, targetLine));
                        } catch (Exception e) {
                            LOG.warn("Error applying quickfix", e);
                            resultFuture.complete("Error applying quickfix: " + e.getMessage());
                        }
                    });
                } finally {
                    FileTool.notifyEditComplete();
                }
            } catch (Exception e) {
                LOG.warn("Error in applyQuickfix", e);
                resultFuture.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
            }
        });

        String result = resultFuture.get(30, TimeUnit.SECONDS);
        if (!result.startsWith("Error") && !result.startsWith("No ")) {
            FileTool.followFileIfEnabled(project, pathStr, targetLine, targetLine,
                FileTool.HIGHLIGHT_EDIT, FileTool.agentLabel(project) + " applied fix");
        }
        return result;
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SimpleStatusRenderer.INSTANCE;
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
    private String applyAndReportFix(List<com.intellij.codeInspection.ProblemDescriptor> lineProblems,
                                     int fixIndex, String pathStr, int targetLine) {
        com.intellij.codeInspection.ProblemDescriptor targetProblem =
            lineProblems.get(Math.min(fixIndex, lineProblems.size() - 1));

        var fixes = targetProblem.getFixes();
        if (fixes == null || fixes.length == 0) {
            return "No quickfixes available for this problem. Description: " +
                targetProblem.getDescriptionTemplate() + ". Use edit_text to fix manually.";
        }

        var fix = fixes[Math.min(fixIndex, fixes.length - 1)];

        // Wrap in PopupInterceptor so a quick-fix that opens a JBPopup chooser (e.g. a
        // class-import disambiguation) cannot freeze the EDT — see PopupInterceptor and
        // .agent-work/freeze-investigation-2026-04-30.md.
        // No editor available here → null owner falls back to a global frame scan.
        PopupInterceptor.Result popupResult = PopupInterceptor.runDetectingPopups(null,
            () -> com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                project,
                () -> fix.applyFix(project, targetProblem),
                "Apply Quick Fix: " + fix.getName(),
                null
            )
        );
        if (popupResult.popupWasOpened()) {
            return PopupInterceptor.formatPopupBlockedError("apply_quickfix:" + fix.getName(), popupResult);
        }

        PsiDocumentManager.getInstance(project).commitAllDocuments();
        FileDocumentManager.getInstance().saveAllDocuments();

        StringBuilder sb = new StringBuilder();
        sb.append("Applied fix: ").append(fix.getName()).append("\n");
        sb.append("  File: ").append(pathStr).append(" line ").append(targetLine).append("\n");
        if (fixes.length > 1) {
            sb.append("  (").append(fixes.length).append(" fixes were available, applied #")
                .append(Math.min(fixIndex, fixes.length - 1)).append(")\n");
            sb.append("  Other available fixes:\n");
            for (int i = 0; i < fixes.length; i++) {
                if (i != Math.min(fixIndex, fixes.length - 1)) {
                    sb.append("    ").append(i).append(": ").append(fixes[i].getName()).append("\n");
                }
            }
        }
        return sb.toString();
    }
}
