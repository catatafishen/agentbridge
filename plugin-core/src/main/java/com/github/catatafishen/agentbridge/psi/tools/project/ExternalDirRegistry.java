package com.github.catatafishen.agentbridge.psi.tools.project;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Project service that tracks directories temporarily attached as read-only IntelliJ modules.
 *
 * <p>Each attached directory is backed by a plain module (content root only, no source roots)
 * so IntelliJ indexes its files and all existing read tools work against it. The module .iml
 * is stored in {@code .agent-work/} (git-ignored) so it is never accidentally committed.
 *
 * <p>All modules are cleaned up when {@link #detach} is called, or on project close via
 * {@code dispose()}. A startup cleanup pass in {@code PsiBridgeStartup} removes any stale
 * modules left by an unclean shutdown.
 */
@Service(Service.Level.PROJECT)
public final class ExternalDirRegistry implements Disposable {

    public static final String MODULE_NAME_PREFIX = "agentbridge-ext-";

    private static final Logger LOG = Logger.getInstance(ExternalDirRegistry.class);

    /**
     * Common output/noise directories to exclude from the content root so indexing stays fast.
     */
    private static final List<String> EXCLUDED_DIR_NAMES = List.of(
        ".idea", ".git", "node_modules", "build", "out", "dist", "target",
        ".gradle", ".mvn", "__pycache__", ".next", ".nuxt", ".venv", "venv"
    );

    /**
     * Immutable data for one attached external directory.
     */
    public record AttachedDir(String alias, String moduleName, String absolutePath, String imlPath) {
    }

    private final Map<String, AttachedDir> attached = new ConcurrentHashMap<>();
    private final Project project;

    public ExternalDirRegistry(@NotNull Project project) {
        this.project = project;
    }

    public static ExternalDirRegistry getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, ExternalDirRegistry.class);
    }

    /**
     * Attaches {@code absolutePath} as a read-only external module with the given {@code alias}.
     *
     * @return null on success, or an error string beginning with "Error: ".
     */
    @SuppressWarnings("java:S112")
    @Nullable
    public String attach(@NotNull String absolutePath, @NotNull String alias) throws Exception {
        if (attached.containsKey(alias)) {
            return "Error: alias '" + alias + "' is already attached. Call detach_external_dir first or choose a different alias.";
        }
        String moduleName = MODULE_NAME_PREFIX + alias;
        if (ModuleManager.getInstance(project).findModuleByName(moduleName) != null) {
            return "Error: a module named '" + moduleName + "' already exists in the project.";
        }

        VirtualFile dir = LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath);
        if (dir == null || !dir.isDirectory()) {
            return "Error: directory not found or not accessible: " + absolutePath;
        }

        String basePath = project.getBasePath();
        if (basePath == null) {
            return "Error: project base path is unavailable";
        }

        Path imlDir = Path.of(basePath, ".agent-work");
        Files.createDirectories(imlDir);
        String imlPath = imlDir.resolve(moduleName + ".iml").toString();

        // Capture final references for lambdas
        final String finalImlPath = imlPath;
        final VirtualFile finalDir = dir;

        final String[] resultHolder = {null};  // null = success, non-null = error message
        EdtUtil.invokeAndWait(() -> {
            try {
                String error = WriteAction.compute(() -> doCreateModule(finalImlPath, finalDir));
                resultHolder[0] = error;
            } catch (Exception e) {
                resultHolder[0] = "Exception: " + e.getMessage();
            }
        });

        // null means success; any non-empty string is an error
        String setupError = resultHolder[0];
        if (setupError != null) {
            return "Error: failed to create module: " + setupError;
        }

        attached.put(alias, new AttachedDir(alias, moduleName, absolutePath, imlPath));
        LOG.info("Attached external dir: " + absolutePath + " as module '" + moduleName + "'");
        return null;
    }

    /**
     * Creates the IntelliJ module and sets up its content root. Must be called inside a WriteAction.
     *
     * @return null on success, or an error description string.
     */
    @SuppressWarnings("java:S112")
    @Nullable
    private String doCreateModule(@NotNull String imlPath, @NotNull VirtualFile dir) {
        Module module = null;
        try {
            module = ModuleManager.getInstance(project).newModule(imlPath, "JAVA_MODULE");
            ModifiableRootModel model = ModuleRootManager.getInstance(module).getModifiableModel();
            ContentEntry entry = model.addContentEntry(dir);
            for (String excluded : EXCLUDED_DIR_NAMES) {
                VirtualFile excludedDir = dir.findChild(excluded);
                if (excludedDir != null) {
                    entry.addExcludeFolder(excludedDir);
                }
            }
            model.commit();
            return null; // success
        } catch (Exception e) {
            // Module was created but setup failed — dispose it to avoid leaving a half-configured module
            if (module != null) {
                try {
                    ModuleManager.getInstance(project).disposeModule(module);
                    Files.deleteIfExists(Path.of(imlPath));
                } catch (Exception cleanup) {
                    LOG.warn("Failed to clean up partially created module at " + imlPath, cleanup);
                }
            }
            return e.getMessage();
        }
    }

    /**
     * Removes the external module for {@code alias} from the project model.
     *
     * @return null on success, or an error string.
     */
    @Nullable
    public String detach(@NotNull String alias) {
        AttachedDir dir = attached.remove(alias);
        if (dir == null) {
            return "Error: no external directory attached with alias '" + alias
                + "'. Use list_external_dirs to see what is attached.";
        }
        removeModuleAndIml(dir);
        LOG.info("Detached external dir alias '" + alias + "' (was: " + dir.absolutePath() + ")");
        return null;
    }

    /**
     * Returns an unmodifiable snapshot of all currently attached directories.
     */
    public @NotNull List<AttachedDir> listAttached() {
        return List.copyOf(attached.values());
    }

    /**
     * Returns true if {@code absolutePath} (after normalization) is inside any attached external directory.
     * Used by write tools to reject modifications to read-only external directories.
     */
    public boolean isExternalPath(@NotNull String absolutePath) {
        Path candidate;
        try {
            candidate = Path.of(absolutePath).normalize();
        } catch (java.nio.file.InvalidPathException e) {
            return false;
        }
        for (AttachedDir dir : attached.values()) {
            if (isUnderDirectory(candidate, Path.of(dir.absolutePath()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if {@code candidate} (after normalization) is equal to or inside {@code directory}.
     * Uses {@link Path#normalize()} to resolve {@code ..} segments before comparing, preventing
     * traversal-based bypasses and handling Windows vs. POSIX path separators correctly.
     */
    static boolean isUnderDirectory(Path candidate, Path directory) {
        return candidate.normalize().startsWith(directory.normalize());
    }

    /**
     * Removes all attached modules on project close.
     */
    @Override
    public void dispose() {
        if (!attached.isEmpty()) {
            LOG.info("Project closing — removing " + attached.size() + " external dir module(s)");
            List<AttachedDir> dirs = new ArrayList<>(attached.values());
            attached.clear();
            for (AttachedDir dir : dirs) {
                try {
                    removeModuleAndIml(dir);
                } catch (Exception e) {
                    LOG.warn("Failed to remove external dir module '" + dir.moduleName() + "' on dispose", e);
                }
            }
        }
    }

    private void removeModuleAndIml(@NotNull AttachedDir dir) {
        Module module = ModuleManager.getInstance(project).findModuleByName(dir.moduleName());
        if (module != null) {
            try {
                EdtUtil.invokeAndWait(() -> {
                    try {
                        WriteAction.compute(() -> doDisposeModule(module));
                    } catch (Exception e) {
                        LOG.warn("Failed to dispose module '" + dir.moduleName() + "' in WriteAction", e);
                    }
                });
            } catch (Exception e) {
                LOG.warn("Failed to remove module '" + dir.moduleName() + "'", e);
            }
        }
        try {
            Files.deleteIfExists(Path.of(dir.imlPath()));
        } catch (IOException e) {
            LOG.warn("Failed to delete .iml for '" + dir.imlPath() + "'", e);
        }
    }

    /**
     * Disposes a module inside a WriteAction. Must only be called within a write lock.
     */
    private String doDisposeModule(@NotNull Module module) {
        ModuleManager.getInstance(project).disposeModule(module);
        return "";
    }
}
