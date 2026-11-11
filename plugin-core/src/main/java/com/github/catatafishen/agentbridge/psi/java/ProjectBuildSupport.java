package com.github.catatafishen.agentbridge.psi.java;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.tools.infrastructure.ReadBuildOutputTool;
import com.google.gson.JsonObject;
import com.intellij.compiler.CompilerMessageImpl;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.compiler.CompileStatusNotification;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.CompilerMessage;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.task.ProjectTaskManager;
import org.jetbrains.concurrency.Promise;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Compiler-related build logic isolated from ProjectTools so that
 * the plugin passes verification on non-Java IDEs (PyCharm, WebStorm, GoLand).
 * <p>
 * This class references {@code com.intellij.openapi.compiler.*} which only exists
 * when {@code com.intellij.modules.java} is present. It is only ever loaded when
 * that module is confirmed available, preventing {@link NoClassDefFoundError} in
 * non-Java IDEs.
 */
public class ProjectBuildSupport {
    private static final Logger LOG = Logger.getInstance(ProjectBuildSupport.class);
    private static final int BUILD_OUTPUT_MAX_CHARS = 12_000;

    private ProjectBuildSupport() {
    }

    /**
     * Builds through {@link ProjectTaskManager}, the same API behind Build → Build Project.
     * The IDE picks the runner (Gradle, Maven or JPS), so a Gradle-delegated project runs the
     * Gradle build instead of IntelliJ's own compiler, which skips Gradle-only steps such as
     * code generation. Falls back to the JPS {@link CompilerManager} only if the task manager fails
     * to start; a failed build is reported, never retried.
     *
     * @param rebuild   rebuild instead of an incremental build
     * @param filePaths when non-empty, only these files are compiled (paths absolute or project-relative)
     */
    public static String buildProject(Project project, String moduleName, boolean rebuild,
                                      List<String> filePaths, AtomicBoolean buildInProgress)
        throws ExecutionException, InterruptedException, TimeoutException {
        // Resolve (and VFS-refresh) files here on the calling background thread, never on the UI thread.
        VirtualFile[] files = resolveFiles(project, filePaths);
        if (files.length != filePaths.size()) {
            buildInProgress.set(false);
            return "Error: Could not find all files to compile: " + filePaths;
        }

        long startNanos = System.nanoTime();
        CompletableFuture<ProjectTaskManager.Result> taskFuture = new CompletableFuture<>();
        // The guard is released when the submitted task actually finishes, not when we stop waiting for it:
        // a timeout or interrupt leaves the build running, and a second one must not start on top of it.
        taskFuture.whenComplete((r, t) -> buildInProgress.set(false));
        try (BuildOutputCapture capture = new BuildOutputCapture(project, BUILD_OUTPUT_MAX_CHARS)) {
            EdtUtil.invokeLater(() -> startTaskManagerBuild(project, moduleName, rebuild, files, taskFuture));

            ProjectTaskManager.Result result;
            try {
                result = taskFuture.get(300, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof BuildRequestException bre) {
                    return bre.getMessage();
                }
                if (rebuild || files.length > 0) {
                    // The JPS fallback can only run an incremental module/project build; running it would
                    // report success without doing what was asked.
                    LOG.warn("ProjectTaskManager build failed to run", e);
                    return "Error: The build could not be started (" + e.getMessage() + "), and the JPS fallback cannot "
                        + "honour mode='rebuild' or 'files'. Retry with a plain or module build, or check the IDE log.";
                }
                LOG.warn("ProjectTaskManager build failed to run, falling back to JPS compiler", e);
                buildInProgress.set(true);
                return buildWithCompilerManager(project, moduleName, buildInProgress);
            }
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            return formatTaskHeader(result.isAborted(), result.hasErrors(), elapsedMs)
                + selectBuildOutput(capture.text(), capture.isTruncated(), capture.diagnostics(),
                () -> readBuildWindowOutput(project));
        }
    }

    /**
     * Prefers the output captured from external-system (Gradle/Maven) tasks. The shared Build tool window
     * only holds the last pass, so it is used just when no external task ran (a JPS-only build). JPS compiler
     * errors collected during the build are appended, so a JPS failure after a successful Gradle pass is not
     * hidden behind Gradle's "BUILD SUCCESSFUL". Keeps the tail, where errors and the summary are.
     */
    static String selectBuildOutput(String captured, boolean truncated, String diagnostics,
                                    java.util.function.Supplier<String> buildWindowOutput) {
        if (captured.isBlank()) {
            return "Build tool window output:\n" + buildWindowOutput.get();
        }
        StringBuilder sb = new StringBuilder("Build output:\n");
        if (truncated) {
            sb.append("… (truncated)\n");
        }
        sb.append(captured.strip());
        if (!diagnostics.isBlank()) {
            sb.append("\n\nCompiler errors:\n").append(diagnostics.strip());
        }
        return sb.toString();
    }

    private static void startTaskManagerBuild(Project project, String moduleName, boolean rebuild,
                                              VirtualFile[] files,
                                              CompletableFuture<ProjectTaskManager.Result> future) {
        try {
            ProjectTaskManager manager = ProjectTaskManager.getInstance(project);
            Promise<ProjectTaskManager.Result> promise;
            if (files.length > 0) {
                promise = manager.compile(files);
            } else if (!moduleName.isEmpty()) {
                Module module = resolveModule(project, moduleName);
                if (module == null) {
                    future.completeExceptionally(new BuildRequestException(
                        "Error: Module '" + moduleName + "' not found.\n" + listAvailableModules(project)));
                    return;
                }
                // Expand to child modules: for Gradle the named module is an empty parent whose
                // sources live in "<name>.main" / "<name>.test" (#1160).
                Module[] scope = expandWithChildModules(project, module);
                promise = rebuild ? manager.rebuild(scope) : manager.build(scope);
            } else {
                promise = rebuild ? manager.rebuildAllModules() : manager.buildAllModules();
            }
            promise.onSuccess(future::complete);
            promise.onError(future::completeExceptionally);
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
    }

    private static VirtualFile[] resolveFiles(Project project, List<String> paths) {
        String base = project.getBasePath();
        return paths.stream()
            .map(p -> java.nio.file.Path.of(p).isAbsolute() || base == null ? p : base + "/" + p)
            .map(p -> LocalFileSystem.getInstance().refreshAndFindFileByPath(p))
            .filter(java.util.Objects::nonNull)
            .toArray(VirtualFile[]::new);
    }

    /**
     * Reads the Build tool window text, which holds the real compiler/Gradle output.
     * {@code ProjectTaskManager.Result} carries only success flags, no messages.
     */
    private static String readBuildWindowOutput(Project project) {
        JsonObject args = new JsonObject();
        args.addProperty("max_chars", BUILD_OUTPUT_MAX_CHARS);
        args.addProperty("offset", -1);
        return new ReadBuildOutputTool(project).execute(args);
    }

    /**
     * Header for task-manager builds. Error and warning counts are unavailable from
     * {@code ProjectTaskManager.Result}, so only the outcome is reported. Pure function.
     */
    static String formatTaskHeader(boolean aborted, boolean hasErrors, long elapsedMs) {
        String outcome;
        if (aborted) {
            outcome = "Build aborted";
        } else if (hasErrors) {
            outcome = "✗ Build failed";
        } else {
            outcome = "✓ Build succeeded";
        }
        return String.format("%s (%.1fs)%n", outcome, elapsedMs / 1000.0);
    }

    private static final class BuildRequestException extends Exception {
        BuildRequestException(String message) {
            super(message);
        }
    }

    private static String buildWithCompilerManager(Project project, String moduleName, AtomicBoolean buildInProgress)
        throws ExecutionException, InterruptedException, TimeoutException {
        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        long startNanos = System.nanoTime();

        EdtUtil.invokeLater(() -> {
            try {
                // Capture the active editor before the build so we can restore focus
                // if "Follow Agent" is disabled (the compiler always activates the Build window)
                var activeEditor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedEditor();

                CompilerManager compilerManager = CompilerManager.getInstance(project);

                CompileStatusNotification callback =
                    (aborted, errorCount, warningCount, context) -> {
                        buildInProgress.set(false);
                        restoreFocusIfNeeded(project, activeEditor);
                        resultFuture.complete(formatBuildResult(aborted, errorCount, warningCount, context, startNanos));
                    };

                if (!moduleName.isEmpty()) {
                    Module module = resolveModule(project, moduleName);
                    if (module == null) {
                        buildInProgress.set(false);
                        resultFuture.complete("Error: Module '" + moduleName + "' not found.\n" + listAvailableModules(project));
                        return;
                    }
                    // compile(module, ...) alone would build only the named module. For Gradle projects
                    // that is the empty parent module ("plugin-core"); the sources live in the child
                    // source-set modules ("plugin-core.main", "plugin-core.test"), so compiling just the
                    // parent "succeeds" in under a second without compiling anything (#1160).
                    Module[] scope = expandWithChildModules(project, module);
                    compilerManager.make(compilerManager.createModulesCompileScope(scope, true), callback);
                } else {
                    compilerManager.make(callback);
                }
            } catch (Exception e) {
                buildInProgress.set(false);
                LOG.warn("Build error", e);
                resultFuture.complete("Error starting build: " + e.getMessage());
            }
        });

        try {
            return resultFuture.get(300, TimeUnit.SECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            buildInProgress.set(false);
            throw e;
        }
    }

    /**
     * If "Follow Agent" is disabled, re-select the editor that was active before the build
     * so the Build tool window doesn't steal keyboard focus from the user.
     */
    private static void restoreFocusIfNeeded(Project project, com.intellij.openapi.fileEditor.FileEditor previousEditor) {
        if (ToolLayerSettings.getInstance(project).getFollowAgentFiles()) {
            return;
        }
        if (previousEditor == null || previousEditor.getFile() == null) return;
        EdtUtil.invokeLater(() -> {
            // Don't steal focus from the chat prompt — the build callback fires
            // asynchronously after the tool completes, outside FocusGuard's lifetime.
            if (PsiBridgeService.isChatToolWindowActive(project)) return;
            var fem = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
            fem.openFile(previousEditor.getFile(), true);
        });
    }

    /**
     * Formats the build status header line with error/warning counts and elapsed time.
     * Pure function — no IDE dependencies.
     */
    static String formatBuildHeader(boolean aborted, int errorCount, int warningCount, long elapsedMs) {
        StringBuilder sb = new StringBuilder();
        if (aborted) {
            sb.append("Build aborted.\n");
        } else if (errorCount == 0) {
            sb.append("✓ Build succeeded");
        } else {
            sb.append("✗ Build failed");
        }
        sb.append(String.format(" (%d errors, %d warnings, %.1fs)%n",
            errorCount, warningCount, elapsedMs / 1000.0));
        return sb.toString();
    }

    private static String formatBuildResult(boolean aborted, int errorCount, int warningCount,
                                            CompileContext context, long startNanos) {
        long elapsed = (System.nanoTime() - startNanos) / 1_000_000;
        StringBuilder sb = new StringBuilder(formatBuildHeader(aborted, errorCount, warningCount, elapsed));

        appendCompilerMessages(sb, context, CompilerMessageCategory.ERROR, "ERROR", Integer.MAX_VALUE);
        appendCompilerMessages(sb, context, CompilerMessageCategory.WARNING, "WARN", 20);

        return sb.toString();
    }

    static void appendCompilerMessages(StringBuilder sb, CompileContext context,
                                               CompilerMessageCategory category,
                                               String label, int maxCount) {
        CompilerMessage[] messages = context.getMessages(category);
        int shown = 0;
        for (CompilerMessage msg : messages) {
            if (shown++ >= maxCount) {
                sb.append("  ... and ").append(messages.length - maxCount).append(" more ").append(label.toLowerCase()).append("s\n");
                break;
            }
            String file = msg.getVirtualFile() != null ? msg.getVirtualFile().getName() : "";
            sb.append("  ").append(label).append(" ").append(file);
            if (msg instanceof CompilerMessageImpl impl && impl.getLine() > 0) {
                sb.append(":").append(impl.getLine());
            }
            sb.append(" ").append(msg.getMessage()).append("\n");
        }
    }

    private static Module[] expandWithChildModules(Project project, Module module) {
        Module[] all = ModuleManager.getInstance(project).getModules();
        java.util.Set<String> wanted = new java.util.HashSet<>(selectModuleNames(
            module.getName(), java.util.Arrays.stream(all).map(Module::getName).toList()));
        return java.util.Arrays.stream(all).filter(m -> wanted.contains(m.getName())).toArray(Module[]::new);
    }

    /**
     * Returns {@code moduleName} plus every module nested under it by the Gradle/Maven naming
     * convention {@code <parent>.<child>} (e.g. {@code plugin-core.main}). Pure function.
     */
    static java.util.List<String> selectModuleNames(String moduleName, java.util.Collection<String> allNames) {
        String childPrefix = moduleName + ".";
        return allNames.stream()
            .filter(n -> n.equals(moduleName) || n.startsWith(childPrefix))
            .toList();
    }

    private static Module resolveModule(Project project, String moduleName) {
        Module module = ModuleManager.getInstance(project).findModuleByName(moduleName);
        if (module == null) {
            String projectName = project.getName();
            module = ModuleManager.getInstance(project).findModuleByName(projectName + "." + moduleName);
        }
        return module;
    }

    private static String listAvailableModules(Project project) {
        StringBuilder available = new StringBuilder("Available modules:\n");
        for (Module m : ModuleManager.getInstance(project).getModules()) {
            available.append("  ").append(m.getName()).append("\n");
        }
        return available.toString();
    }
}
