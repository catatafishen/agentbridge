package com.github.catatafishen.agentbridge.psi.review;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
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
import com.intellij.openapi.vfs.VirtualFile;
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

    private static final Color ADDED_BG = new Color(76, 175, 80, 40);
    private static final Color MODIFIED_BG = new Color(255, 193, 7, 45);
    private static final Color DELETED_BG = new Color(244, 67, 54, 55);

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
     */
    public void refreshHighlights(@NotNull VirtualFile vf) {
        AgentEditSession session = AgentEditSession.getInstance(project);
        if (!session.isActive()) {
            clearForFile(vf);
            return;
        }

        List<ChangeRange> ranges = session.computeRanges(vf);
        ApplicationManager.getApplication().invokeLater(
            () -> applyOnEdt(vf, ranges),
            __ -> project.isDisposed());
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

        return markup.addRangeHighlighter(
            startOffset, endOffset,
            HighlighterLayer.SELECTION - 1,
            attrs,
            HighlighterTargetArea.LINES_IN_RANGE);
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
        }, __ -> project.isDisposed());
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
        }, __ -> project.isDisposed());
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
