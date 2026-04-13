package com.github.catatafishen.agentbridge.psi.java;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
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

    private ProjectBuildSupport() {
    }

    public static String buildProject(Project project, String moduleName, AtomicBoolean buildInProgress)
        throws ExecutionException, InterruptedException, TimeoutException {
        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        long startTime = System.currentTimeMillis();

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
                        resultFuture.complete(formatBuildResult(aborted, errorCount, warningCount, context, startTime));
                    };

                if (!moduleName.isEmpty()) {
                    Module module = resolveModule(project, moduleName);
                    if (module == null) {
                        buildInProgress.set(false);
                        resultFuture.complete("Error: Module '" + moduleName + "' not found.\n" + listAvailableModules(project));
                        return;
                    }
                    compilerManager.compile(module, callback);
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
                                            CompileContext context, long startTime) {
        long elapsed = System.currentTimeMillis() - startTime;
        StringBuilder sb = new StringBuilder(formatBuildHeader(aborted, errorCount, warningCount, elapsed));

        appendCompilerMessages(sb, context, CompilerMessageCategory.ERROR, "ERROR", Integer.MAX_VALUE);
        appendCompilerMessages(sb, context, CompilerMessageCategory.WARNING, "WARN", 20);

        return sb.toString();
    }

    private static void appendCompilerMessages(StringBuilder sb, CompileContext context,
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
