package com.github.catatafishen.agentbridge.psi.review;

import com.github.catatafishen.agentbridge.settings.McpServerSettings;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies persistent diff highlights to editors for files modified during an
 * {@link AgentEditSession}. Highlights are recomputed whenever the file's document
 * changes and are cleared when the session ends.
 * <p>
 * Layering: editor-level {@link MarkupModel} highlighters at
 * {@link HighlighterLayer#SELECTION} − 1, so user selections still paint on top.
 * <p>
 * Threading: all {@link MarkupModel} mutations happen on the EDT. {@link #refreshHighlights}
 * may be called from any thread and will dispatch to the EDT.
 */
public final class AgentEditHighlighter implements Disposable {

    private static final Logger LOG = Logger.getInstance(AgentEditHighlighter.class);

    private static final Color ADDED_BG = new Color(76, 175, 80, 80);
    private static final Color MODIFIED_BG = new Color(255, 193, 7, 90);
    private static final Color DELETED_BG = new Color(244, 67, 54, 100);

    private final Project project;

    /**
     * Highlighters currently applied, keyed by the editor they live in.
     * The inner list is the set of highlighters for a single editor — removed and
     * replaced atomically on each refresh.
     */
    private final Map<Editor, List<RangeHighlighter>> active = new ConcurrentHashMap<>();

    private Disposable connectionDisposable;

    public AgentEditHighlighter(@NotNull Project project) {
        this.project = project;
        subscribeToEditorEvents();
    }

    public static AgentEditHighlighter getInstance(@NotNull Project project) {
        return project.getService(AgentEditHighlighter.class);
    }

    private void subscribeToEditorEvents() {
        connectionDisposable = Disposer.newDisposable("AgentEditHighlighter");
        Disposer.register(this, connectionDisposable);

        project.getMessageBus().connect(connectionDisposable)
            .subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER,
                new FileEditorManagerListener() {
                    @Override
                    public void fileOpened(@NotNull FileEditorManager source,
                                           @NotNull VirtualFile file) {
                        refreshHighlights(file);
                    }
                });
    }

    /**
     * Recomputes and applies highlights for all currently open text editors of
     * {@code vf}. Safe to call from any thread. No-op when the session is inactive
     * or no before-snapshot exists for the file.
     * <p>
     * When {@link McpServerSettings#isShowEditorHighlights()} is false, any existing
     * highlights for the file are removed instead of being refreshed.
     */
    public void refreshHighlights(@NotNull VirtualFile vf) {
        AgentEditSession session = AgentEditSession.getInstance(project);
        if (!session.isActive() || !McpServerSettings.getInstance(project).isShowEditorHighlights()) {
            clearForFile(vf);
            return;
        }

        List<ChangeRange> ranges = session.computeRanges(vf);
        ApplicationManager.getApplication().invokeLater(
            () -> applyOnEdt(vf, ranges),
            ignored -> project.isDisposed());
    }

    /**
     * Briefly highlights all changed ranges for {@code vf} in the editor to guide
     * the user to the changed location after navigation. The flash lasts 1.5 s and
     * is independent of the {@link McpServerSettings#isShowEditorHighlights()} toggle —
     * it fires even when persistent highlights are disabled.
     * <p>
     * Also scrolls the editor to the first change. Safe to call from any thread.
     */
    public void flashForNavigation(@NotNull VirtualFile vf) {
        List<ChangeRange> ranges = AgentEditSession.getInstance(project).computeRanges(vf);
        if (ranges.isEmpty()) return;

        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            FileEditorManager fem = FileEditorManager.getInstance(project);
            for (FileEditor fe : fem.getEditors(vf)) {
                if (fe instanceof TextEditor textEditor) {
                    flashInEditor(textEditor.getEditor(), ranges, textEditor);
                    break;
                }
            }
        }, ignored -> project.isDisposed());
    }

    /**
     * Refreshes highlights for all currently open files in the active session.
     * Called when the "show highlights" toggle is turned back on. No-op when
     * highlights are disabled — the calling toggle action checks the setting first.
     */
    public void refreshAll() {
        AgentEditSession session = AgentEditSession.getInstance(project);
        if (!session.isActive()) return;

        LocalFileSystem lfs = LocalFileSystem.getInstance();
        for (String path : session.getModifiedFilePaths()) {
            VirtualFile vf = lfs.findFileByPath(path);
            if (vf != null) refreshHighlights(vf);
        }
    }

    private void flashInEditor(@NotNull Editor editor,
                               @NotNull List<ChangeRange> ranges,
                               @NotNull Disposable parent) {
        int docLineCount = editor.getDocument().getLineCount();
        if (docLineCount == 0) return;

        List<RangeHighlighter> flashHighlighters = buildFlashHighlighters(editor, ranges, docLineCount);
        if (flashHighlighters.isEmpty()) return;

        new Alarm(Alarm.ThreadToUse.SWING_THREAD, parent).addRequest(() -> {
            for (RangeHighlighter h : flashHighlighters) {
                try {
                    editor.getMarkupModel().removeHighlighter(h);
                } catch (Exception ignored) {
                    // editor disposed concurrently
                }
            }
        }, 1500);
    }

    private @NotNull List<RangeHighlighter> buildFlashHighlighters(@NotNull Editor editor,
                                                                   @NotNull List<ChangeRange> ranges,
                                                                   int docLineCount) {
        List<RangeHighlighter> flashHighlighters = new ArrayList<>(ranges.size());
        boolean scrolled = false;

        for (ChangeRange range : ranges) {
            int[] offsets = resolveOffsets(editor, range, docLineCount);
            if (offsets == null) continue;

            if (!scrolled) {
                editor.getScrollingModel().scrollTo(
                    editor.offsetToLogicalPosition(offsets[0]), ScrollType.CENTER);
                scrolled = true;
            }

            Color bg = colorFor(range.type());
            TextAttributes attrs = new TextAttributes();
            attrs.setBackgroundColor(bg);
            flashHighlighters.add(editor.getMarkupModel().addRangeHighlighter(
                offsets[0], offsets[1],
                HighlighterLayer.SELECTION - 1,
                attrs,
                HighlighterTargetArea.LINES_IN_RANGE));
        }
        return flashHighlighters;
    }

    private static int @org.jetbrains.annotations.Nullable [] resolveOffsets(@NotNull Editor editor,
                                                                             @NotNull ChangeRange range,
                                                                             int docLineCount) {
        int startLine;
        int endLineInclusive;
        if (range.type() == ChangeType.DELETED) {
            int clamped = Math.clamp(range.startLine(), 0, docLineCount - 1);
            startLine = clamped;
            endLineInclusive = clamped;
        } else {
            if (range.startLine() >= docLineCount || range.endLine() <= range.startLine()) return null;
            startLine = range.startLine();
            endLineInclusive = Math.min(range.endLine(), docLineCount) - 1;
        }
        int start = editor.getDocument().getLineStartOffset(startLine);
        int end = editor.getDocument().getLineEndOffset(endLineInclusive);
        if (end < start) return null;
        return new int[]{start, end};
    }

    private static Color colorFor(@NotNull ChangeType type) {
        return switch (type) {
            case ADDED -> ADDED_BG;
            case MODIFIED -> MODIFIED_BG;
            case DELETED -> DELETED_BG;
        };
    }

    private void applyOnEdt(@NotNull VirtualFile vf, @NotNull List<ChangeRange> ranges) {
        if (project.isDisposed()) return;

        FileEditorManager fem = FileEditorManager.getInstance(project);
        for (FileEditor fe : fem.getEditors(vf)) {
            if (fe instanceof TextEditor textEditor) {
                applyToEditor(textEditor.getEditor(), ranges);
            }
        }
    }

    private void applyToEditor(@NotNull Editor editor, @NotNull List<ChangeRange> ranges) {
        MarkupModel markup = editor.getMarkupModel();

        List<RangeHighlighter> old = active.remove(editor);
        if (old != null) {
            for (RangeHighlighter h : old) {
                try {
                    markup.removeHighlighter(h);
                } catch (Exception e) {
                    LOG.debug("Failed to remove stale highlighter", e);
                }
            }
        }

        if (ranges.isEmpty()) return;

        int docLineCount = editor.getDocument().getLineCount();
        List<RangeHighlighter> fresh = new ArrayList<>(ranges.size());
        for (ChangeRange range : ranges) {
            RangeHighlighter h = addHighlighter(editor, markup, range, docLineCount);
            if (h != null) fresh.add(h);
        }
        if (!fresh.isEmpty()) {
            active.put(editor, fresh);
        }
    }

    private RangeHighlighter addHighlighter(@NotNull Editor editor,
                                            @NotNull MarkupModel markup,
                                            @NotNull ChangeRange range,
                                            int docLineCount) {
        if (docLineCount == 0) return null;

        Color bg = switch (range.type()) {
            case ADDED -> ADDED_BG;
            case MODIFIED -> MODIFIED_BG;
            case DELETED -> DELETED_BG;
        };

        TextAttributes attrs = new TextAttributes();
        attrs.setBackgroundColor(bg);

        int startLine;
        int endLineInclusive;
        if (range.type() == ChangeType.DELETED) {
            // Point range: mark the line at/after the deletion on the current document.
            int clamped = Math.clamp(range.startLine(), 0, docLineCount - 1);
            startLine = clamped;
            endLineInclusive = clamped;
        } else {
            if (range.startLine() >= docLineCount || range.endLine() <= range.startLine()) {
                return null;
            }
            startLine = range.startLine();
            endLineInclusive = Math.min(range.endLine(), docLineCount) - 1;
        }

        int startOffset = editor.getDocument().getLineStartOffset(startLine);
        int endOffset = editor.getDocument().getLineEndOffset(endLineInclusive);
        if (endOffset < startOffset) return null;

        RangeHighlighter h = markup.addRangeHighlighter(
            startOffset, endOffset,
            HighlighterLayer.SELECTION - 1,
            attrs,
            HighlighterTargetArea.LINES_IN_RANGE);
        h.setErrorStripeMarkColor(bg);
        return h;
    }

    /**
     * Removes highlighters for a single file across all its open editors.
     */
    public void clearForFile(@NotNull VirtualFile vf) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            FileEditorManager fem = FileEditorManager.getInstance(project);
            for (FileEditor fe : fem.getEditors(vf)) {
                if (fe instanceof TextEditor textEditor) {
                    Editor editor = textEditor.getEditor();
                    List<RangeHighlighter> hls = active.remove(editor);
                    if (hls != null) {
                        MarkupModel markup = editor.getMarkupModel();
                        for (RangeHighlighter h : hls) {
                            try {
                                markup.removeHighlighter(h);
                            } catch (Exception ignored) {
                                // editor disposed concurrently
                            }
                        }
                    }
                }
            }
        }, ignored -> project.isDisposed());
    }

    /**
     * Removes every highlighter managed by this service. Called on session end.
     */
    public void clearAll() {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Map.Entry<Editor, List<RangeHighlighter>> e : active.entrySet()) {
                Editor editor = e.getKey();
                if (editor.isDisposed()) continue;
                MarkupModel markup = editor.getMarkupModel();
                for (RangeHighlighter h : e.getValue()) {
                    try {
                        markup.removeHighlighter(h);
                    } catch (Exception ignored) {
                        // editor disposed concurrently
                    }
                }
            }
            active.clear();
        }, ignored -> project.isDisposed());
    }

    @Override
    public void dispose() {
        clearAll();
        if (connectionDisposable != null) {
            Disposer.dispose(connectionDisposable);
            connectionDisposable = null;
        }
    }
}
