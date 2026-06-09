package com.github.catatafishen.agentbridge.psi.graph;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Auto-indexes git commit history on project open when the Knowledge Graph is enabled.
 * Runs after project startup with a short delay to allow git4idea to initialize.
 * Only indexes commits (lightweight) — does NOT trigger a full PSI rebuild.
 */
public final class CodeGraphStartupActivity implements ProjectActivity {

    private static final Logger LOG = Logger.getInstance(CodeGraphStartupActivity.class);

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        CodeGraphSettings settings = CodeGraphSettings.getInstance(project);
        if (!settings.isEnabled()) return Unit.INSTANCE;

        // Delay to allow git4idea's GitRepositoryManager to initialize
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (project.isDisposed()) return;

            ProgressManager.getInstance().run(
                new Task.Backgroundable(project, "Indexing git history", false) {
                    @Override
                    public void run(@NotNull ProgressIndicator indicator) {
                        int count = GitCommitIndexer.getInstance(project).indexCommits(indicator);
                        if (count > 0) {
                            LOG.info("Startup git indexing: " + count + " commits indexed");
                        }
                    }
                });
        });
        return Unit.INSTANCE;
    }
}
