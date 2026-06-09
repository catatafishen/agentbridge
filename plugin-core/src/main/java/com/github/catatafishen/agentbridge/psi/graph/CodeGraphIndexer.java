package com.github.catatafishen.agentbridge.psi.graph;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates {@link CodeGraphExtractor} runs across the project and persists results
 * via {@link CodeGraphStore}. Supports two modes:
 *
 * <ul>
 *   <li><b>Full rebuild</b> — walks every project source file under a modal background
 *       task with a progress indicator.</li>
 *   <li><b>Incremental refresh</b> — re-extracts a single file when its content hash
 *       has changed. Used by the agent-edit hook to keep the graph fresh.</li>
 * </ul>
 *
 * <p>All extraction runs inside a read action. Database writes are batched per file.
 */
@Service(Service.Level.PROJECT)
public final class CodeGraphIndexer {

    private static final Logger LOG = Logger.getInstance(CodeGraphIndexer.class);

    private final Project project;
    private final AtomicBoolean indexing = new AtomicBoolean(false);

    @SuppressWarnings("unused")
    public CodeGraphIndexer(@NotNull Project project) {
        this.project = project;
    }

    public static CodeGraphIndexer getInstance(@NotNull Project project) {
        return project.getService(CodeGraphIndexer.class);
    }

    public boolean isIndexing() {
        return indexing.get();
    }

    /**
     * Run a full project-wide rebuild as a background task. Safe to call from the EDT.
     * If an indexing job is already running, this call is a no-op.
     *
     * @param onDone optional callback invoked on the EDT after the rebuild finishes
     */
    public void rebuildAll(@Nullable Runnable onDone) {
        if (!indexing.compareAndSet(false, true)) {
            LOG.info("Code graph rebuild already in progress; ignoring new request");
            return;
        }
        Task.Backgroundable task = new Task.Backgroundable(project, "Building code graph", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    doFullRebuild(indicator);
                    indicator.setText("Indexing git commits…");
                    GitCommitIndexer.getInstance(project).indexCommits(indicator);
                } finally {
                    indexing.set(false);
                    CodeGraphSettings.getInstance(project).setLastFullIndexAt(System.currentTimeMillis());
                    if (onDone != null) {
                        ApplicationManager.getApplication().invokeLater(onDone);
                    }
                }
            }
        };
        ProgressManager.getInstance().run(task);
    }

    /**
     * Re-extract a single file if its content hash has changed.
     * Returns {@code true} if the file was re-indexed, {@code false} if skipped.
     * <p>
     * Uses non-blocking read actions so that pending write actions (e.g., file reload on EDT)
     * can interrupt and proceed, preventing long UI freezes.
     * May throw {@link com.intellij.openapi.progress.ProcessCanceledException} if the project
     * is disposed or the file becomes invalid mid-operation.
     */
    public boolean refreshFile(@NotNull VirtualFile vf) {
        if (!vf.isValid() || vf.isDirectory()) return false;
        if (project.isDisposed()) return false;
        ProjectFileIndex idx = ProjectFileIndex.getInstance(project);

        Boolean inProject = ReadAction.nonBlocking(() -> idx.isInProject(vf))
            .expireWhen(() -> project.isDisposed() || !vf.isValid())
            .executeSynchronously();
        if (inProject == null || !inProject) return false;

        Boolean result = ReadAction.nonBlocking(() -> extractAndStore(vf))
            .expireWhen(() -> project.isDisposed() || !vf.isValid())
            .executeSynchronously();
        return result != null && result;
    }

    private void doFullRebuild(@NotNull ProgressIndicator indicator) {
        indicator.setIndeterminate(false);
        indicator.setText("Collecting project files…");

        List<VirtualFile> files = new ArrayList<>();
        ApplicationManager.getApplication().runReadAction(() -> {
            ProjectFileIndex idx = ProjectFileIndex.getInstance(project);
            FileBasedIndex.getInstance().iterateIndexableFiles(vf -> {
                if (vf.isValid() && !vf.isDirectory() && idx.isInSource(vf)) {
                    files.add(vf);
                }
                return true;
            }, project, indicator);
        });

        int total = files.size();
        indicator.setText("Indexing " + total + " files…");
        AtomicInteger done = new AtomicInteger();

        // Clear all existing data before a full rebuild so deleted files disappear.
        CodeGraphStore store = CodeGraphStore.getInstance(project);
        try {
            store.queryRaw("SELECT COUNT(*) FROM graph_nodes"); // probe — schema present?
        } catch (Exception e) {
            LOG.warn("graph_nodes table not initialized", e);
            return;
        }
        // Fast path: drop and recreate via deleting by file in a loop is too slow.
        // We use raw delete via the store's internal API by walking known files in graph_file_index.
        // For simplicity, just re-index — INSERT OR REPLACE handles per-file overwrite.

        for (VirtualFile vf : files) {
            indicator.checkCanceled();
            if (!vf.isValid() || project.isDisposed()) continue;
            try {
                ReadAction.nonBlocking(() -> {
                        extractAndStore(vf);
                        return true;
                    })
                    .expireWhen(() -> project.isDisposed() || !vf.isValid())
                    .executeSynchronously();
            } catch (com.intellij.openapi.progress.ProcessCanceledException pce) {
                throw pce;
            } catch (Exception e) {
                LOG.debug("Code graph extraction skipped for " + vf.getName() + ": " + e.getMessage());
            }
            int n = done.incrementAndGet();
            indicator.setFraction((double) n / total);
            if (n % 25 == 0) {
                indicator.setText2(vf.getName() + "  (" + n + "/" + total + ")");
            }
        }
    }

    /**
     * Returns true if a re-index actually ran, false if the file's hash was unchanged.
     */
    private boolean extractAndStore(@NotNull VirtualFile vf) {
        PsiFile psi = PsiManager.getInstance(project).findFile(vf);
        if (psi == null) return false;
        CodeGraphExtractor extractor = new CodeGraphExtractor(project);
        CodeGraphExtractor.FileExtraction extraction;
        try {
            extraction = extractor.extract(psi);
        } catch (com.intellij.openapi.progress.ProcessCanceledException pce) {
            throw pce;
        } catch (Exception e) {
            LOG.warn("Failed to extract code graph for " + vf.getPath(), e);
            return false;
        }

        CodeGraphStore store = CodeGraphStore.getInstance(project);
        String existingHash = store.getFileHash(extraction.relativePath());
        if (extraction.contentHash().equals(existingHash)) {
            return false;
        }
        store.deleteByFile(extraction.relativePath());
        store.upsertNodes(extraction.nodes());
        store.insertEdges(extraction.edges());
        store.setFileIndex(
            extraction.relativePath(),
            extraction.contentHash(),
            extraction.nodes().size(),
            extraction.edges().size());
        return true;
    }

    /**
     * Synchronous full rebuild — used by tests. Does not show a progress UI.
     */
    public void rebuildAllSync() {
        if (!indexing.compareAndSet(false, true)) return;
        try {
            doFullRebuild(new EmptyProgressIndicator());
        } finally {
            indexing.set(false);
            CodeGraphSettings.getInstance(project).setLastFullIndexAt(System.currentTimeMillis());
        }
    }
}
