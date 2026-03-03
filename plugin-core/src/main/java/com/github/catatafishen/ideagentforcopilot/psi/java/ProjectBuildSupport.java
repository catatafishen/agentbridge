package com.github.catatafishen.ideagentforcopilot.psi.java;

import com.github.catatafishen.ideagentforcopilot.psi.EdtUtil;
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
import java.util.concurrent.TimeUnit;
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

    /**
     * Runs an incremental build (or module-scoped build) using the IntelliJ compiler API.
     *
     * @param project         the current project
     * @param moduleName      optional module name; empty string for whole-project build
     * @param buildInProgress shared flag to prevent concurrent builds
     * @return human-readable build result
     */
    public static String buildProject(Project project, String moduleName, AtomicBoolean buildInProgress) throws Exception {
        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        long startTime = System.currentTimeMillis();

        EdtUtil.invokeLater(() -> {
            try {
                CompilerManager compilerManager = CompilerManager.getInstance(project);

                CompileStatusNotification callback =
                    (aborted, errorCount, warningCount, context) -> {
                        buildInProgress.set(false);
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
        } catch (Exception e) {
            buildInProgress.set(false);
            throw e;
        }
    }

    private static String formatBuildResult(boolean aborted, int errorCount, int warningCount,
                                            CompileContext context, long startTime) {
        long elapsed = System.currentTimeMillis() - startTime;
        StringBuilder sb = new StringBuilder();

        if (aborted) {
            sb.append("Build aborted.\n");
        } else if (errorCount == 0) {
            sb.append("✓ Build succeeded");
        } else {
            sb.append("✗ Build failed");
        }
        sb.append(String.format(" (%d errors, %d warnings, %.1fs)%n",
            errorCount, warningCount, elapsed / 1000.0));

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
