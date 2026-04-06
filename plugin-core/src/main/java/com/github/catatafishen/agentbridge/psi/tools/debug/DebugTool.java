package com.github.catatafishen.agentbridge.psi.tools.debug;

import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XFullValueEvaluator;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XValue;
import com.intellij.xdebugger.frame.XValueChildrenList;
import com.intellij.xdebugger.frame.XValueNode;
import com.intellij.xdebugger.frame.XValuePlace;
import com.intellij.xdebugger.frame.presentation.XValuePresentation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Abstract base class for all MCP debug tools.
 * Provides helpers for session access, async debugger API synchronization,
 * snapshot rendering, and variable/frame inspection.
 */
public abstract class DebugTool extends Tool {

    protected static final int ASYNC_TIMEOUT_SEC = 15;

    protected DebugTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.DEBUG;
    }

    // ── Session helpers ──────────────────────────────────────────────────────

    @Nullable
    protected XDebugSession currentSession() {
        return XDebuggerManager.getInstance(project).getCurrentSession();
    }

    /**
     * Returns the active session or throws with a clear user-facing message.
     */
    @NotNull
    protected XDebugSession requireSession() throws IllegalStateException {
        XDebugSession session = currentSession();
        if (session == null) {
            throw new IllegalStateException("No active debug session. Start a debug run configuration first.");
        }
        return session;
    }

    /**
     * Returns the active paused session or throws with a clear user-facing message.
     */
    @NotNull
    protected XDebugSession requirePausedSession() throws IllegalStateException {
        XDebugSession session = requireSession();
        if (session.getSuspendContext() == null) {
            throw new IllegalStateException(
                "Debug session is running (not paused). Set a breakpoint or use debug_step to pause first.");
        }
        return session;
    }

    // ── Snapshot builder ────────────────────────────────────────────────────

    @NotNull
    protected String buildSnapshot(@NotNull XDebugSession session,
                                   boolean includeSource, boolean includeVariables, boolean includeStack) {
        var suspendCtx = session.getSuspendContext();
        if (suspendCtx == null) return "(session is running - no snapshot available)";

        XSourcePosition pos = session.getCurrentPosition();
        var sb = new StringBuilder();

        sb.append("# ").append(session.getSessionName());
        if (pos != null) {
            sb.append(" - ").append(relativeSourcePath(pos)).append(':').append(pos.getLine() + 1);
        }
        sb.append(" (paused)\n\n");

        if (includeSource && pos != null) {
            sb.append(buildSourceContext(pos)).append('\n');
        }

        XExecutionStack activeStack = suspendCtx.getActiveExecutionStack();
        XStackFrame topFrame = activeStack != null ? getTopFrame(activeStack) : null;

        if (includeVariables && topFrame != null) {
            sb.append("## Variables\n");
            sb.append(computeFrameVariables(topFrame)).append('\n');
        }

        if (includeStack && activeStack != null) {
            sb.append("## Call Stack\n");
            sb.append(buildStackTrace(activeStack)).append('\n');
        }

        return sb.toString().strip();
    }

    // ── Frame traversal ──────────────────────────────────────────────────────

    @Nullable
    protected XStackFrame getTopFrame(@NotNull XExecutionStack stack) {
        CompletableFuture<XStackFrame> future = new CompletableFuture<>();
        stack.computeStackFrames(0, new XExecutionStack.XStackFrameContainer() {
            @Override
            public void addStackFrames(@NotNull List<? extends XStackFrame> frames, boolean last) {
                if (!frames.isEmpty() && !future.isDone()) future.complete(frames.getFirst());
                else if (last && !future.isDone()) future.complete(null);
            }

            @Override
            public void errorOccurred(@NotNull String err) {
                if (!future.isDone()) future.complete(null);
            }
        });
        return awaitOrNull(future);
    }

    @NotNull
    protected List<XStackFrame> getAllFrames(@NotNull XExecutionStack stack) {
        CompletableFuture<List<XStackFrame>> future = new CompletableFuture<>();
        List<XStackFrame> frames = new ArrayList<>();
        stack.computeStackFrames(0, new XExecutionStack.XStackFrameContainer() {
            @Override
            public void addStackFrames(@NotNull List<? extends XStackFrame> batch, boolean last) {
                frames.addAll(batch);
                if (last && !future.isDone()) future.complete(frames);
            }

            @Override
            public void errorOccurred(@NotNull String err) {
                if (!future.isDone()) future.complete(frames);
            }
        });
        List<XStackFrame> result = awaitOrNull(future);
        return result != null ? result : frames;
    }

    // ── Variable rendering ───────────────────────────────────────────────────

    @NotNull
    protected String computeFrameVariables(@NotNull XStackFrame frame) {
        CompletableFuture<XValueChildrenList> future = new CompletableFuture<>();
        frame.computeChildren(childrenNode(future));
        try {
            XValueChildrenList children = future.get(ASYNC_TIMEOUT_SEC, TimeUnit.SECONDS);
            var sb = new StringBuilder();
            for (int i = 0; i < children.size(); i++) {
                sb.append("  ").append(children.getName(i))
                    .append(" = ").append(renderValue(children.getValue(i))).append('\n');
            }
            return sb.toString();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "  <interrupted>\n";
        } catch (ExecutionException | TimeoutException e) {
            return "  <error computing variables: " + e.getMessage() + ">\n";
        }
    }

    @NotNull
    protected String renderValue(@NotNull XValue value) {
        CompletableFuture<String> future = new CompletableFuture<>();
        value.computePresentation(new XValueNode() {
            @Override
            public void setPresentation(@Nullable Icon icon, @Nullable String type,
                                        @Nullable String val, boolean hasChildren) {
                String withType = type != null && val != null ? "{" + type + "} " + val : null;
                if (!future.isDone())
                    future.complete(java.util.Objects.requireNonNullElse(withType, java.util.Objects.requireNonNullElse(val, "")));
            }

            @Override
            public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation,
                                        boolean hasChildren) {
                if (!future.isDone()) future.complete(renderPresentation(presentation));
            }

            @Override
            public void setFullValueEvaluator(@NotNull XFullValueEvaluator evaluator) { /* full value expansion not needed for summary */ }

            @Override
            public boolean isObsolete() {
                return false;
            }
        }, XValuePlace.TREE);

        try {
            return future.get(ASYNC_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "<interrupted>";
        } catch (ExecutionException | TimeoutException e) {
            return "<timeout>";
        }
    }

    @NotNull
    private static String renderPresentation(@NotNull XValuePresentation presentation) {
        var sb = new StringBuilder();
        if (presentation.getType() != null) sb.append('{').append(presentation.getType()).append("} ");
        presentation.renderValue(new XValuePresentation.XValueTextRenderer() {
            @Override
            public void renderValue(@NotNull String v) {
                sb.append(v);
            }

            @Override
            public void renderStringValue(@NotNull String v) {
                sb.append('"').append(v).append('"');
            }

            @Override
            public void renderStringValue(@NotNull String v, @Nullable String additionalChars, int maxLength) {
                sb.append('"').append(v).append('"');
            }

            @Override
            public void renderNumericValue(@NotNull String v) {
                sb.append(v);
            }

            @Override
            public void renderKeywordValue(@NotNull String v) {
                sb.append(v);
            }

            @Override
            public void renderValue(@NotNull String v, @NotNull com.intellij.openapi.editor.colors.TextAttributesKey key) {
                sb.append(v);
            }

            @Override
            public void renderError(@NotNull String error) {
                sb.append("<error: ").append(error).append('>');
            }

            @Override
            public void renderComment(@NotNull String comment) { /* skip decorative comments */ }

            @Override
            public void renderSpecialSymbol(@NotNull String symbol) {
                sb.append(symbol);
            }
        });
        return sb.toString();
    }

    // ── Stack trace ──────────────────────────────────────────────────────────

    @NotNull
    protected String buildStackTrace(@NotNull XExecutionStack stack) {
        List<XStackFrame> frames = getAllFrames(stack);
        var sb = new StringBuilder();
        for (int i = 0; i < frames.size(); i++) {
            XSourcePosition pos = frames.get(i).getSourcePosition();
            String loc = pos != null
                ? relativeSourcePath(pos) + ':' + (pos.getLine() + 1)
                : "<unknown>";
            sb.append(i == 0 ? "->" : "  ").append(" #").append(i).append(' ').append(loc).append('\n');
        }
        return sb.toString();
    }

    /**
     * Returns the project-relative path of a source position's file, or just the filename if the
     * project base path is unavailable.
     */
    @NotNull
    protected String relativeSourcePath(@NotNull XSourcePosition pos) {
        String basePath = project.getBasePath();
        String fullPath = pos.getFile().getPath();
        if (basePath != null && fullPath.startsWith(basePath + "/")) {
            return fullPath.substring(basePath.length() + 1);
        }
        return pos.getFile().getName();
    }

    // ── Source context ───────────────────────────────────────────────────────

    @NotNull
    protected String buildSourceContext(@NotNull XSourcePosition pos) {
        try {
            var vf = pos.getFile();
            // Cast needed: disambiguates runReadAction(Computable) from runReadAction(ThrowableComputable) for the build compiler
            com.intellij.openapi.editor.Document doc = ApplicationManager.getApplication().runReadAction(
                (com.intellij.openapi.util.Computable<com.intellij.openapi.editor.Document>) () ->
                    com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf));
            if (doc == null) return "";
            int targetLine = pos.getLine(); // 0-based
            int start = Math.max(0, targetLine - 3);
            int end = Math.min(doc.getLineCount() - 1, targetLine + 3);
            var sb = new StringBuilder("## Source Context\nFile: ").append(vf.getPath()).append('\n');
            for (int i = start; i <= end; i++) {
                String lineText = doc.getText(new TextRange(doc.getLineStartOffset(i), doc.getLineEndOffset(i)));
                sb.append(i == targetLine ? ">>" : "  ").append(i + 1).append(": ").append(lineText).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ── XCompositeNode factory ───────────────────────────────────────────────

    /**
     * Creates an {@link XCompositeNode} that completes {@code future} when all children are
     * delivered. Used by variable-expansion tools to synchronize async child computation.
     * <p>
     * {@code tooManyChildren(int)} is implemented alongside the newer {@code tooManyChildren(int, Runnable)}
     * to satisfy both 2024.3 (target SDK, new signature required) and older IDE daemons
     * that still treat the old signature as abstract.
     */
    protected XCompositeNode childrenNode(CompletableFuture<XValueChildrenList> future) {
        return new XCompositeNode() {
            @Override
            public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                if (last && !future.isDone()) future.complete(children);
            }

            @Override
            @SuppressWarnings("java:S1133")
            // required by IDE daemon versions that still treat tooManyChildren(int) as abstract; delegates to the non-deprecated signature
            public void tooManyChildren(int remaining) {
                tooManyChildren(remaining, () -> {
                });
            }

            @Override
            public void tooManyChildren(int remaining, @NotNull Runnable addMoreChildrenCallback) {
                if (!future.isDone()) future.complete(new XValueChildrenList());
            }

            @Override
            public void setAlreadySorted(boolean alreadySorted) { /* already sorted — no action needed */ }

            @Override
            public void setErrorMessage(@NotNull String err) {
                if (!future.isDone()) future.completeExceptionally(new IllegalStateException(err));
            }

            @Override
            public void setErrorMessage(@NotNull String err, @Nullable XDebuggerTreeNodeHyperlink link) {
                if (!future.isDone()) future.completeExceptionally(new IllegalStateException(err));
            }

            @Override
            public void setMessage(@NotNull String msg, @Nullable Icon icon,
                                   @NotNull com.intellij.ui.SimpleTextAttributes attrs,
                                   @Nullable XDebuggerTreeNodeHyperlink link) { /* informational message — no action needed */ }

            @Override
            public boolean isObsolete() {
                return false;
            }
        };
    }

    // ── Async utility ────────────────────────────────────────────────────────

    @Nullable
    private <T> T awaitOrNull(@NotNull CompletableFuture<T> future) {
        try {
            return future.get(ASYNC_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException e) {
            return null;
        }
    }
}
