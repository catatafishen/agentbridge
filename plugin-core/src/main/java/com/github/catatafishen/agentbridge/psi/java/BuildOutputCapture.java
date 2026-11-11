package com.github.catatafishen.agentbridge.psi.java;

import com.intellij.execution.process.ProcessOutputType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener;
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;

/**
 * Records the output of external-system (Gradle/Maven) tasks run for one project while a build is in flight.
 * <p>
 * The Build tool window cannot be used for this on its own: a single build can run Gradle tasks and then
 * a JPS pass for non-Gradle modules, and the JPS pass overwrites the shared "Build Output" tab, so the
 * Gradle output is gone by the time the build finishes. Close this object when the build is done.
 */
final class BuildOutputCapture implements AutoCloseable {

    private final Disposable disposable = Disposer.newDisposable("BuildOutputCapture");
    private final StringBuffer text = new StringBuffer();

    BuildOutputCapture(@NotNull Project project) {
        ExternalSystemProgressNotificationManager.getInstance().addNotificationListener(
            new ExternalSystemTaskNotificationListener() {
                @Override
                public void onTaskOutput(@NotNull ExternalSystemTaskId id, @NotNull String output, @NotNull ProcessOutputType outputType) {
                    if (project.equals(id.findProject())) {
                        text.append(output);
                    }
                }
            }, disposable);
    }

    /**
     * @return everything the external-system tasks printed so far, or an empty string if none ran
     */
    @NotNull String text() {
        return text.toString();
    }

    @Override
    public void close() {
        Disposer.dispose(disposable);
    }
}
