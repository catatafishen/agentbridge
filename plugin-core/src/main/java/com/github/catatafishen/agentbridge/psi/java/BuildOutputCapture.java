package com.github.catatafishen.agentbridge.psi.java;

import com.intellij.execution.process.ProcessOutputType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.compiler.CompilationStatusListener;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.compiler.CompilerTopics;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener;
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;

/**
 * Records what a build prints while it is in flight, from both runners a single build can use.
 * <p>
 * The Build tool window cannot be used for this on its own: a build can run Gradle tasks and then a
 * JPS pass for non-Gradle modules, and the JPS pass overwrites the shared "Build Output" tab, so the
 * Gradle output is gone by the time the build finishes. This captures
 * <ul>
 *   <li>Gradle/Maven task output, kept as a bounded tail so a verbose build cannot exhaust the heap;</li>
 *   <li>JPS compiler errors, so a failure in the JPS pass is not hidden behind Gradle's "BUILD SUCCESSFUL".</li>
 * </ul>
 * Close this object when the build is done.
 */
final class BuildOutputCapture implements AutoCloseable {

    private final Disposable disposable = Disposer.newDisposable("BuildOutputCapture");
    private final Object lock = new Object();
    private final BoundedTail tail;
    private final StringBuilder diagnostics = new StringBuilder();

    /**
     * @param limit maximum number of characters of external-task output to return (the tail is kept)
     */
    BuildOutputCapture(@NotNull Project project, int limit) {
        this.tail = new BoundedTail(limit);
        ExternalSystemProgressNotificationManager.getInstance().addNotificationListener(
            new ExternalSystemTaskNotificationListener() {
                @Override
                public void onTaskOutput(@NotNull ExternalSystemTaskId id, @NotNull String output, @NotNull ProcessOutputType outputType) {
                    if (project.equals(id.findProject())) {
                        tail.append(output);
                    }
                }
            }, disposable);
        project.getMessageBus().connect(disposable).subscribe(CompilerTopics.COMPILATION_STATUS,
            new CompilationStatusListener() {
                @Override
                public void compilationFinished(boolean aborted, int errors, int warnings, @NotNull CompileContext context) {
                    recordDiagnostics(context);
                }
            });
    }

    private void recordDiagnostics(CompileContext context) {
        StringBuilder sb = new StringBuilder();
        ProjectBuildSupport.appendCompilerMessages(sb, context, CompilerMessageCategory.ERROR, "ERROR", Integer.MAX_VALUE);
        synchronized (lock) {
            diagnostics.append(sb);
        }
    }

    /**
     * @return the last {@code limit} characters the external-system tasks printed, or an empty string if none ran
     */
    @NotNull String text() {
        return tail.text();
    }

    /**
     * @return true if earlier output was dropped
     */
    boolean isTruncated() {
        return tail.isTruncated();
    }

    /**
     * @return JPS compiler errors reported during the build, or an empty string if there were none
     */
    @NotNull String diagnostics() {
        synchronized (lock) {
            return diagnostics.toString();
        }
    }

    @Override
    public void close() {
        Disposer.dispose(disposable);
    }
}
