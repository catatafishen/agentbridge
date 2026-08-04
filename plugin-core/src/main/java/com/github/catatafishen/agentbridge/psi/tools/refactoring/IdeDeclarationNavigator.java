package com.github.catatafishen.agentbridge.psi.tools.refactoring;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ActionCallback;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Invokes the IDE's real {@code GotoDeclaration} action in a live project editor and captures the
 * location selected by that action.
 *
 * <p>This is required for backend-driven products such as CLion Nova. Their declaration action is
 * delegated to Rider.Backend and is intentionally not exposed through frontend PSI reference or
 * {@code GotoDeclarationHandler} APIs.
 */
final class IdeDeclarationNavigator {

    static final String GOTO_DECLARATION_ACTION_ID = "GotoDeclaration";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
    private static final int ACTION_RETRY_DELAY_MILLIS = 250;
    private static final Logger LOG = Logger.getInstance(IdeDeclarationNavigator.class);

    private final Project project;
    private final String actionId;
    private final long timeoutMillis;

    IdeDeclarationNavigator(@NotNull Project project) {
        this(project, GOTO_DECLARATION_ACTION_ID, DEFAULT_TIMEOUT);
    }

    IdeDeclarationNavigator(@NotNull Project project, @NotNull String actionId,
                            @NotNull Duration timeout) {
        this.project = project;
        this.actionId = actionId;
        this.timeoutMillis = Math.max(1, timeout.toMillis());
    }

    @Nullable Location navigate(@NotNull VirtualFile sourceFile, int sourceOffset) {
        AnAction action = ActionManager.getInstance().getAction(actionId);
        if (action == null) return null;

        EditorState previousEditor = captureEditorState(project);
        try {
            Editor[] sourceEditor = new Editor[1];
            int[] initialOffset = new int[1];
            EdtUtil.invokeAndWait(() -> {
                Editor editor = FileEditorManager.getInstance(project).openTextEditor(
                    new OpenFileDescriptor(project, sourceFile, sourceOffset), false);
                if (editor == null) return;
                int safeOffset =
                    Math.max(0, Math.min(sourceOffset, editor.getDocument().getTextLength()));
                editor.getCaretModel().moveToOffset(safeOffset);
                sourceEditor[0] = editor;
                initialOffset[0] = safeOffset;
            });
            if (sourceEditor[0] == null) return null;

            CompletableFuture<Location> result = new CompletableFuture<>();
            Disposable listeners = Disposer.newDisposable("AgentBridge go-to-declaration listener");
            Alarm retryAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, listeners);
            AtomicReference<NavigationAttempt> activeAttempt = new AtomicReference<>();
            subscribeToNavigation(
                sourceFile, initialOffset[0], activeAttempt, result, listeners);

            EdtUtil.invokeLater(() -> invokeActionWhenReady(
                action, sourceEditor[0], sourceFile, initialOffset[0], activeAttempt, result,
                retryAlarm));

            try {
                return result.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                result.complete(null);
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result.complete(null);
                return null;
            } catch (ExecutionException e) {
                LOG.warn("IDE go-to-declaration action failed", e.getCause());
                return null;
            } finally {
                Disposer.dispose(listeners);
            }
        } finally {
            restoreEditorState(project, previousEditor);
        }
    }

    private void invokeActionWhenReady(
        AnAction action, Editor sourceEditor, VirtualFile sourceFile, int sourceOffset,
        AtomicReference<NavigationAttempt> activeAttempt, CompletableFuture<Location> result,
        Alarm retryAlarm) {
        if (result.isDone()) return;
        if (project.isDisposed() || sourceEditor.isDisposed()) {
            result.complete(null);
            return;
        }

        NavigationAttempt attempt = new NavigationAttempt();
        activeAttempt.set(attempt);
        try {
            ActionCallback callback = ActionManager.getInstance().tryToExecute(
                action, null, sourceEditor.getContentComponent(), "AgentBridge", true);
            callback.doWhenDone(() -> {
                if (activeAttempt.get() != attempt || result.isDone()) return;
                attempt.accept(result);
                captureSelectedEditorLater(
                    sourceFile, sourceOffset, activeAttempt, attempt, result);
            });
            callback.doWhenRejected(() -> {
                attempt.reject();
                activeAttempt.compareAndSet(attempt, null);
                if (!result.isDone()) {
                    retryAlarm.addRequest(
                        () -> invokeActionWhenReady(
                            action, sourceEditor, sourceFile, sourceOffset, activeAttempt, result,
                            retryAlarm),
                        ACTION_RETRY_DELAY_MILLIS);
                }
            });
        } catch (RuntimeException e) {
            attempt.reject();
            activeAttempt.compareAndSet(attempt, null);
            LOG.warn("Failed to invoke IDE go-to-declaration action", e);
            result.complete(null);
        }
    }

    private void subscribeToNavigation(
        VirtualFile sourceFile, int sourceOffset,
        AtomicReference<NavigationAttempt> activeAttempt,
        CompletableFuture<Location> result, Disposable listeners) {
        EditorFactory.getInstance().getEventMulticaster().addCaretListener(
            new CaretListener() {
                @Override
                public void caretPositionChanged(@NotNull CaretEvent event) {
                    completeIfNavigated(
                        event.getEditor(), sourceFile, sourceOffset, activeAttempt, result);
                }
            },
            listeners);

        project.getMessageBus().connect(listeners).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            new FileEditorManagerListener() {
                @Override
                public void selectionChanged(@NotNull FileEditorManagerEvent event) {
                    NavigationAttempt attempt = activeAttempt.get();
                    if (attempt != null) {
                        captureSelectedEditorLater(
                            sourceFile, sourceOffset, activeAttempt, attempt, result);
                    }
                }
            });
    }

    private void captureSelectedEditorLater(
        VirtualFile sourceFile, int sourceOffset,
        AtomicReference<NavigationAttempt> activeAttempt, NavigationAttempt attempt,
        CompletableFuture<Location> result) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (activeAttempt.get() != attempt || attempt.isRejected() || result.isDone()) return;
            Editor selected = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (selected != null) {
                completeIfNavigated(
                    selected, sourceFile, sourceOffset, activeAttempt, result);
            }
        });
    }

    private void completeIfNavigated(
        Editor editor, VirtualFile sourceFile, int sourceOffset,
        AtomicReference<NavigationAttempt> activeAttempt,
        CompletableFuture<Location> result) {
        if (result.isDone() || editor.isDisposed() || editor.getProject() != project) return;

        NavigationAttempt attempt = activeAttempt.get();
        if (attempt == null || attempt.isRejected()) return;
        if (FileEditorManager.getInstance(project).getSelectedTextEditor() != editor) return;

        VirtualFile selectedFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (selectedFile == null) return;

        int selectedOffset = editor.getCaretModel().getOffset();
        if (!sourceFile.equals(selectedFile) || selectedOffset != sourceOffset) {
            attempt.record(new Location(selectedFile, selectedOffset), result);
        }
    }

    static @Nullable EditorState captureEditorState(@NotNull Project project) {
        if (project.isDisposed()) return null;

        EditorState[] state = new EditorState[1];
        EdtUtil.invokeAndWait(() -> {
            if (project.isDisposed()) return;
            FileEditorManager manager = FileEditorManager.getInstance(project);
            VirtualFile[] selectedFiles = manager.getSelectedFiles();
            if (selectedFiles.length == 0) return;

            VirtualFile selectedFile = selectedFiles[0];
            int caretOffset = -1;
            Editor selectedEditor = manager.getSelectedTextEditor();
            if (selectedEditor != null && !selectedEditor.isDisposed()
                && selectedFile.equals(
                FileDocumentManager.getInstance().getFile(selectedEditor.getDocument()))) {
                caretOffset = selectedEditor.getCaretModel().getOffset();
            }
            state[0] = new EditorState(selectedFile, caretOffset);
        });
        return state[0];
    }

    static void restoreEditorState(@NotNull Project project, @Nullable EditorState state) {
        if (state == null || project.isDisposed()) return;

        EdtUtil.invokeAndWait(() -> {
            if (project.isDisposed() || !state.file().isValid()) return;
            FileEditorManager manager = FileEditorManager.getInstance(project);
            if (state.caretOffset() < 0) {
                manager.openFile(state.file(), false);
                return;
            }

            Editor editor = manager.openTextEditor(
                new OpenFileDescriptor(project, state.file(), state.caretOffset()), false);
            if (editor != null && !editor.isDisposed()) {
                int safeOffset = Math.max(
                    0, Math.min(state.caretOffset(), editor.getDocument().getTextLength()));
                editor.getCaretModel().moveToOffset(safeOffset);
            }
        });
    }

    private static final class NavigationAttempt {
        private final AtomicBoolean accepted = new AtomicBoolean(false);
        private final AtomicBoolean rejected = new AtomicBoolean(false);
        private final AtomicReference<Location> candidate = new AtomicReference<>();

        void accept(CompletableFuture<Location> result) {
            if (rejected.get()) return;
            accepted.set(true);
            Location buffered = candidate.getAndSet(null);
            if (buffered != null) {
                result.complete(buffered);
            }
        }

        void reject() {
            rejected.set(true);
            candidate.set(null);
        }

        boolean isRejected() {
            return rejected.get();
        }

        void record(Location location, CompletableFuture<Location> result) {
            if (rejected.get()) return;
            if (accepted.get()) {
                result.complete(location);
                return;
            }

            candidate.compareAndSet(null, location);
            if (accepted.get() && !rejected.get()) {
                Location buffered = candidate.getAndSet(null);
                if (buffered != null) {
                    result.complete(buffered);
                }
            }
        }
    }

    record EditorState(@NotNull VirtualFile file, int caretOffset) {
    }

    record Location(@NotNull VirtualFile file, int offset) {
    }
}
