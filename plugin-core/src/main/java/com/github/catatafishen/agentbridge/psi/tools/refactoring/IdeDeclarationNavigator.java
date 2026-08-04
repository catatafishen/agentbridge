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

        Editor[] sourceEditor = new Editor[1];
        int[] initialOffset = new int[1];
        EdtUtil.invokeAndWait(() -> {
            Editor editor = FileEditorManager.getInstance(project).openTextEditor(
                new OpenFileDescriptor(project, sourceFile, sourceOffset), true);
            if (editor == null) return;
            int safeOffset = Math.max(0, Math.min(sourceOffset, editor.getDocument().getTextLength()));
            editor.getCaretModel().moveToOffset(safeOffset);
            sourceEditor[0] = editor;
            initialOffset[0] = safeOffset;
        });
        if (sourceEditor[0] == null) return null;

        CompletableFuture<Location> result = new CompletableFuture<>();
        Disposable listeners = Disposer.newDisposable("AgentBridge go-to-declaration listener");
        Alarm retryAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, listeners);
        AtomicBoolean actionStarted = new AtomicBoolean(false);
        subscribeToNavigation(
            sourceFile, initialOffset[0], actionStarted, result, listeners);

        EdtUtil.invokeLater(() -> invokeActionWhenReady(
            action, sourceEditor[0], sourceFile, initialOffset[0], actionStarted, result,
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
    }

    private void invokeActionWhenReady(
        AnAction action, Editor sourceEditor, VirtualFile sourceFile, int sourceOffset,
        AtomicBoolean actionStarted, CompletableFuture<Location> result, Alarm retryAlarm) {
        if (result.isDone()) return;
        if (project.isDisposed() || sourceEditor.isDisposed()) {
            result.complete(null);
            return;
        }

        try {
            actionStarted.set(true);
            ActionCallback callback = ActionManager.getInstance().tryToExecute(
                action, null, sourceEditor.getContentComponent(), "AgentBridge", true);
            callback.doWhenRejected(() -> {
                if (!result.isDone()) {
                    retryAlarm.addRequest(
                        () -> invokeActionWhenReady(
                            action, sourceEditor, sourceFile, sourceOffset, actionStarted, result,
                            retryAlarm),
                        ACTION_RETRY_DELAY_MILLIS);
                }
            });
            captureSelectedEditorLater(sourceFile, sourceOffset, result);
        } catch (RuntimeException e) {
            LOG.warn("Failed to invoke IDE go-to-declaration action", e);
            result.complete(null);
        }
    }

    private void subscribeToNavigation(
        VirtualFile sourceFile, int sourceOffset, AtomicBoolean actionStarted,
        CompletableFuture<Location> result, Disposable listeners) {
        EditorFactory.getInstance().getEventMulticaster().addCaretListener(
            new CaretListener() {
                @Override
                public void caretPositionChanged(@NotNull CaretEvent event) {
                    if (actionStarted.get()) {
                        completeIfNavigated(
                            event.getEditor(), sourceFile, sourceOffset, result);
                    }
                }
            },
            listeners);

        project.getMessageBus().connect(listeners).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            new FileEditorManagerListener() {
                @Override
                public void selectionChanged(@NotNull FileEditorManagerEvent event) {
                    if (actionStarted.get()) {
                        captureSelectedEditorLater(sourceFile, sourceOffset, result);
                    }
                }
            });
    }

    private void captureSelectedEditorLater(
        VirtualFile sourceFile, int sourceOffset, CompletableFuture<Location> result) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Editor selected = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (selected != null) {
                completeIfNavigated(selected, sourceFile, sourceOffset, result);
            }
        });
    }

    private void completeIfNavigated(
        Editor editor, VirtualFile sourceFile, int sourceOffset,
        CompletableFuture<Location> result) {
        if (result.isDone() || editor.getProject() != project) return;
        VirtualFile selectedFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (selectedFile == null) return;

        int selectedOffset = editor.getCaretModel().getOffset();
        if (!sourceFile.equals(selectedFile) || selectedOffset != sourceOffset) {
            result.complete(new Location(selectedFile, selectedOffset));
        }
    }

    record Location(@NotNull VirtualFile file, int offset) {
    }
}
