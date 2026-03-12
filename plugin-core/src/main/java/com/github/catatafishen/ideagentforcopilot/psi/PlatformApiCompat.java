package com.github.catatafishen.ideagentforcopilot.psi;

import com.intellij.codeInspection.InspectionToolResultExporter;
import com.intellij.codeInspection.ex.GlobalInspectionContextEx;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotificationProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Centralizes all IntelliJ Platform API calls that produce false-positive "cannot resolve"
 * errors in the IDE editor. These errors occur because the development IDE (running the plugin)
 * uses a different platform version than the target SDK configured in Gradle.
 *
 * <p>The Gradle build compiles cleanly against the target SDK. The IDE's daemon analyzer, however,
 * resolves symbols against its own bundled platform JARs, which may have different method
 * signatures, generics, or extension point APIs. This is a well-known issue when developing
 * IntelliJ plugins inside an IDE whose version differs from the target platform.</p>
 *
 * <p>By isolating these calls here, the rest of the codebase stays error-free in the editor,
 * and each compatibility concern is documented in one place.</p>
 */
public final class PlatformApiCompat {

    private static final Logger LOG = Logger.getInstance(PlatformApiCompat.class);
    private static final String LANG_SHELL_SCRIPT = "Shell Script";
    private static final String LANG_JAVASCRIPT = "JavaScript";
    private static final String LANG_PYTHON = "Python";

    private PlatformApiCompat() {
    }

    /**
     * Checks whether a plugin with the given ID is installed.
     *
     * <p><b>Why extracted:</b> {@code PluginManagerCore.isPluginInstalled(PluginId)} has a different
     * method signature between IDE versions — in some builds the parameter is annotated with
     * a {@code @NotNull} from a different annotations JAR, causing the IDE daemon to report
     * "cannot be applied to (PluginId)" even though the types are identical. The Gradle build
     * compiles without errors.</p>
     */
    public static boolean isPluginInstalled(@NotNull String pluginId) {
        return com.intellij.ide.plugins.PluginManagerCore.isPluginInstalled(
            com.intellij.openapi.extensions.PluginId.getId(pluginId));
    }

    /**
     * Retrieves the name of the next undo action for a given file editor.
     *
     * <p><b>Why extracted:</b> {@code UndoManager.getUndoActionNameAndDescription()} returns
     * {@code Pair<String, String>}, but the IDE daemon sometimes fails to resolve the
     * {@code .first} field on the returned {@code Pair} due to generic type annotation
     * differences ({@code @ActionText String} vs plain {@code String}) between the dev IDE
     * and the target SDK. The Gradle build compiles without errors.</p>
     */
    public static @Nullable String getUndoActionName(
        @NotNull com.intellij.openapi.command.undo.UndoManager undoManager,
        @Nullable com.intellij.openapi.fileEditor.FileEditor fileEditor) {
        return undoManager.getUndoActionNameAndDescription(fileEditor).first;
    }

    /**
     * Retrieves the name of the next redo action for a given file editor.
     *
     * <p><b>Why extracted:</b> Same generic-type annotation issue as
     * {@link #getUndoActionName} — the {@code Pair} return type of
     * {@code getRedoActionNameAndDescription()} triggers false-positive
     * daemon errors between SDK versions.</p>
     */
    public static @Nullable String getRedoActionName(
        @NotNull com.intellij.openapi.command.undo.UndoManager undoManager,
        @Nullable com.intellij.openapi.fileEditor.FileEditor fileEditor) {
        return undoManager.getRedoActionNameAndDescription(fileEditor).first;
    }

    /**
     * Collects text from editor notification banners (e.g., "Some directories are not excluded").
     *
     * <p><b>Why extracted:</b> Three API calls on this path produce false-positive errors in the IDE:</p>
     * <ul>
     *   <li>{@code EditorNotificationProvider.EP_NAME.getExtensions(project)} — the IDE cannot resolve
     *       {@code getExtensions(Project)} because {@code ProjectExtensionPointName} generics differ
     *       between the dev IDE and target platform versions.</li>
     *   <li>{@code provider.collectNotificationData(project, vf)} — cascading unresolved type from
     *       the extension point lookup above.</li>
     *   <li>{@code factory.apply(editor)} — same cascading issue; the {@code Function} return type
     *       is inferred as unknown.</li>
     * </ul>
     *
     * <p>All three methods exist and work correctly at runtime. The Gradle build compiles without errors.</p>
     */
    public static @NotNull List<String> collectEditorNotificationTexts(
        @NotNull Project project, @NotNull VirtualFile vf, @NotNull FileEditor editor) {
        List<String> notifications = new ArrayList<>();
        for (var provider : EditorNotificationProvider.EP_NAME.getExtensions(project)) {
            try {
                Function<? super FileEditor, ? extends JComponent> factory =
                    provider.collectNotificationData(project, vf);
                if (factory == null) continue;
                JComponent panel = factory.apply(editor);
                if (panel instanceof EditorNotificationPanel enp) {
                    String text = enp.getText();
                    if (text != null && !text.isEmpty()) {
                        notifications.add("[BANNER] " + text);
                    }
                }
            } catch (Exception e) {
                // Skip failing providers — some may not be compatible with the current context
            }
        }
        return notifications;
    }

    /**
     * Retrieves the inspection presentation for a tool wrapper, safely handling constructor
     * mismatches in third-party inspection plugins.
     *
     * <p><b>Why extracted:</b> {@code GlobalInspectionContextEx.getPresentation()} internally calls
     * {@code createPresentation()}, which uses reflection to instantiate presentation classes.
     * Some bundled plugins (e.g., the Duplicates detector) change their constructor signature
     * across IDE versions. When the running IDE version differs from the target platform,
     * this throws {@code NoSuchMethodException} wrapped in {@code RuntimeException}.</p>
     *
     * <p>This wrapper pre-checks the presentation constructor signature before calling the
     * platform's {@code getPresentation()}. The platform's {@code createPresentation()} uses
     * reflection expecting a {@code (InspectionToolWrapper, GlobalInspectionContextEx)} constructor,
     * but some bundled tools (e.g., {@code UnusedDeclarationPresentation}) change the second
     * parameter to {@code GlobalInspectionContextImpl} across IDE versions. The platform logs
     * the resulting {@code NoSuchMethodException} at ERROR level internally before re-throwing.
     * By detecting the mismatch beforehand, we avoid triggering the platform's error logging.</p>
     *
     * <p>The outer {@code catch(Throwable)} remains as a safety net for any other reflection
     * failures not caught by the pre-check (e.g., {@code ExceptionInInitializerError},
     * {@code NoClassDefFoundError}).</p>
     */
    public static @Nullable InspectionToolResultExporter getInspectionPresentation(
        @NotNull GlobalInspectionContextEx ctx, @NotNull InspectionToolWrapper<?, ?> toolWrapper) {
        if (!hasPresentationConstructor(toolWrapper)) {
            return null;
        }
        try {
            return ctx.getPresentation(toolWrapper);
        } catch (Throwable t) {
            LOG.debug("Skipping inspection tool '" + toolWrapper.getShortName()
                + "' — presentation class incompatible: " + t.getMessage());
            return null;
        }
    }

    /**
     * Checks whether the tool's custom presentation class (if any) has the constructor
     * signature that the platform's {@code createPresentation()} expects:
     * {@code (InspectionToolWrapper, GlobalInspectionContextEx)}.
     *
     * <p>Returns {@code true} if no custom presentation is declared (the platform's default
     * presentation will be used, which always works) or if the constructor exists.
     * Returns {@code false} if the constructor signature doesn't match.</p>
     */
    private static boolean hasPresentationConstructor(@NotNull InspectionToolWrapper<?, ?> toolWrapper) {
        var ep = toolWrapper.getExtension();
        if (ep == null) {
            return true;
        }
        String presentationClassName = ep.presentation;
        if (presentationClassName == null || presentationClassName.isEmpty()) {
            return true;
        }
        try {
            // ep was loaded by its own plugin's classloader — same as getPluginDescriptor().getClassLoader()
            // which is both deprecated and experimental in newer IDE versions.
            ClassLoader classLoader = ep.getClass().getClassLoader();
            Class<?> presClass = Class.forName(presentationClassName, false, classLoader);
            presClass.getConstructor(InspectionToolWrapper.class, GlobalInspectionContextEx.class);
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            LOG.debug("Skipping tool '" + toolWrapper.getShortName()
                + "' — presentation '" + presentationClassName + "' incompatible: " + e.getMessage());
            return false;
        } catch (Throwable t) {
            LOG.debug("Error checking presentation for '" + toolWrapper.getShortName() + "': " + t.getMessage());
            return false;
        }
    }

    /**
     * Looks up a service by raw {@code Class<?>} on a project, returning it as {@code Object}.
     *
     * <p><b>Why extracted:</b> {@code Project.getService(Class<T>)} expects a concrete type parameter.
     * When called with {@code Class<?>} (e.g., a reflectively loaded Qodana service class),
     * the IDE's type checker cannot resolve the method because the wildcard type doesn't match
     * the bounded generic {@code <T>}. The Gradle compiler handles this correctly via erasure.</p>
     *
     * <p>This is used for optional integrations (Qodana) where the service class may not exist
     * at compile time and must be loaded by name.</p>
     */
    @SuppressWarnings("unchecked")
    public static @Nullable Object getServiceByRawClass(@NotNull Project project, @NotNull Class<?> serviceClass) {
        // Cast to Class<Object> satisfies the generic bound; safe because we only use the result as Object.
        return project.getService((Class<Object>) serviceClass);
    }

    /**
     * Typed version of Project.getService for use in non-reflective code.
     * <p>
     * False positive: same as {@link #getServiceByRawClass} — the IDE daemon resolves
     * {@code Project.getService(Class<T>)} against its own platform JAR where the generic
     * bounds differ. Gradle compiles cleanly.
     */
    public static <T> @NotNull T getService(@NotNull Project project, @NotNull Class<T> serviceClass) {
        return project.getService(serviceClass);
    }

    /**
     * Creates a JCEF load handler that calls the given callback when the main frame finishes loading.
     *
     * <p><b>Why extracted:</b> {@code CefLoadHandlerAdapter} provides default implementations for all
     * {@code CefLoadHandler} methods, but the JCEF version bundled with the dev IDE may declare
     * {@code onLoadError} with a different {@code ErrorCode} enum type than the target platform SDK.
     * In Kotlin, the compiler flags the anonymous subclass as "not implementing abstract member"
     * because of this signature mismatch. In Java, the adapter's default implementation satisfies
     * the contract and no error is reported.</p>
     */
    public static org.cef.handler.CefLoadHandler createMainFrameLoadEndHandler(@NotNull Runnable onMainFrameLoaded) {
        return new org.cef.handler.CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame, int httpStatusCode) {
                if (frame != null && frame.isMain()) {
                    onMainFrameLoaded.run();
                }
            }
        };
    }

    /**
     * Creates a JCEF display handler that logs console messages to the given logger.
     *
     * <p><b>Why extracted:</b> In newer JCEF versions, {@code LogSeverity} was moved from
     * {@code org.cef.CefSettings.LogSeverity} to a top-level {@code org.cef.LogSeverity} enum.
     * Kotlin's strict override checking flags the old import path as "overrides nothing" because
     * the parameter type doesn't match the parent's signature. In Java, the method resolution
     * handles both paths via the compiled class hierarchy without flagging an error.</p>
     */
    public static org.cef.handler.CefDisplayHandler createConsoleLogHandler(@NotNull Logger logger) {
        return new org.cef.handler.CefDisplayHandlerAdapter() {
            @Override
            public boolean onConsoleMessage(org.cef.browser.CefBrowser browser,
                                            org.cef.CefSettings.LogSeverity level,
                                            String message, String source, int line) {
                logger.info("JCEF Console [" + level + "]: " + message);
                return false;
            }
        };
    }

    /**
     * Subscribes a callback to Look-and-Feel change events on the application message bus.
     *
     * <p><b>Why extracted:</b> {@code LafManagerListener.TOPIC} is typed as
     * {@code Topic<LafManagerListener>} in Java, but Kotlin infers it as a platform type
     * {@code Topic!} which doesn't satisfy the expected generic bound in
     * {@code MessageBusConnection.subscribe()}. This is a Kotlin/Java interop issue with
     * platform types that does not affect runtime behavior.</p>
     */
    public static void subscribeLafChanges(
        @NotNull com.intellij.openapi.Disposable parentDisposable,
        @NotNull Runnable onLafChanged) {
        var conn = com.intellij.openapi.application.ApplicationManager.getApplication()
            .getMessageBus().connect(parentDisposable);
        conn.subscribe(com.intellij.ide.ui.LafManagerListener.TOPIC,
            (com.intellij.ide.ui.LafManagerListener) source -> onLafChanged.run());
    }

    /**
     * Navigates the VCS Log tool window to a specific commit by its full SHA hash.
     *
     * <p><b>Why extracted:</b> {@code com.intellij.vcs.log.Hash} and
     * {@code com.intellij.vcs.log.impl.HashImpl} are resolved against the dev IDE's VCS plugin JAR,
     * which may have different class metadata or {@code @NotNull} annotations than the target SDK.
     * The IDE daemon reports "Unknown class: com.intellij.vcs.log.Hash" and cascading resolution
     * failures on {@code showRevisionInMainLog}. The Gradle build compiles without errors.</p>
     */
    static void showRevisionInLog(@NotNull Project project, @NotNull String fullHash) {
        var vcsHash = com.intellij.vcs.log.impl.HashImpl.build(fullHash);
        com.intellij.vcs.log.impl.VcsProjectLog.showRevisionInMainLog(project, vcsHash);
    }

    /**
     * Navigates to a commit in the VCS Log after the log has refreshed its data pack.
     * <p>
     * Unlike {@link #showRevisionInLog}, this method does NOT call {@code showRevisionInMainLog}
     * immediately. Instead, it registers a {@code DataPackChangeListener} and triggers a VCS log
     * refresh. When the log finishes refreshing (and the new commit is indexed), it navigates.
     * <p>
     * Note: {@code DataPack} is deprecated (scheduled for removal) but there is no non-internal
     * replacement listener API yet. {@code VcsLogProgress.ProgressListener} exists but requires
     * {@code VcsLogData.DATA_PACK_REFRESH} which is {@code @ApiStatus.Internal}. Using deprecated
     * APIs only produces a verifier warning, while internal APIs produce a verifier failure.
     * <p>
     * This avoids the "Commit or reference 'xxx' not found" notification that
     * {@code showRevisionInMainLog} shows when called before the log has indexed a new commit.
     * <p>
     * Must be called on the EDT.
     *
     * @param project  the current project
     * @param fullHash the full 40-character commit SHA
     */
    static void showRevisionInLogAfterRefresh(@NotNull Project project, @NotNull String fullHash) {
        var hash = com.intellij.vcs.log.impl.HashImpl.build(fullHash);
        var vcsLog = com.intellij.vcs.log.impl.VcsProjectLog.getInstance(project);
        var data = vcsLog.getDataManager();
        if (data == null) {
            // Log not initialized — fall back to direct navigation
            com.intellij.vcs.log.impl.VcsProjectLog.showRevisionInMainLog(project, hash);
            return;
        }

        var navigated = new java.util.concurrent.atomic.AtomicBoolean(false);
        com.intellij.vcs.log.data.DataPackChangeListener[] listenerRef =
            new com.intellij.vcs.log.data.DataPackChangeListener[1];

        listenerRef[0] = dataPack -> {
            if (!navigated.compareAndSet(false, true)) return;
            data.removeDataPackChangeListener(listenerRef[0]);
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() ->
                com.intellij.vcs.log.impl.VcsProjectLog.showRevisionInMainLog(project, hash));
        };

        data.addDataPackChangeListener(listenerRef[0]);

        // Trigger VCS log refresh to pick up the new commit
        String basePath = project.getBasePath();
        if (basePath != null) {
            var root = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(basePath);
            if (root != null) {
                data.refresh(java.util.List.of(root));
            }
        }

        // Timeout: clean up listener after 5 seconds to prevent leak
        com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService()
            .schedule(() -> {
                if (navigated.compareAndSet(false, true)) {
                    data.removeDataPackChangeListener(listenerRef[0]);
                }
            }, 5, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * Runs a git command through IntelliJ's Git4Idea infrastructure (GitLineHandler).
     * Returns command output, or null to signal that the caller should fall back to ProcessBuilder.
     *
     * <p><b>Why extracted:</b> Multiple Git4Idea APIs produce false-positive errors:</p>
     * <ul>
     *   <li>{@code GitRepositoryManager.getRepositories()} — returns {@code @NotNull List} which the
     *       IDE reports as incompatible with {@code List<GitRepository>} due to annotation differences.</li>
     *   <li>{@code GitRepository.getRoot()} — cannot resolve due to cascading type failure.</li>
     *   <li>{@code GitLineHandler.setSilent()}, {@code setStdoutSuppressed()}, {@code addParameters()} —
     *       cannot resolve because the handler type is inferred from the unresolved repo root.</li>
     *   <li>{@code Git.getInstance().runCommand()} — cascading from above.</li>
     * </ul>
     *
     * <p>All methods exist and work correctly at runtime. The Gradle build compiles without errors.
     * Isolated in this class so Git4Idea class loading is deferred until first use; if Git4Idea
     * is disabled, the caller catches {@code NoClassDefFoundError} and falls back.</p>
     */
    static @Nullable String runIdeGitCommand(@NotNull Project project, @NotNull String[] args) {
        if (args.length == 0) return null;

        git4idea.commands.GitCommand command = IDE_GIT_COMMAND_MAP.get(args[0]);
        if (command == null) return null;

        java.util.List<git4idea.repo.GitRepository> repos =
            git4idea.repo.GitRepositoryManager.getInstance(project).getRepositories();
        if (repos.isEmpty()) return null;

        git4idea.repo.GitRepository repo = repos.getFirst();
        String basePath = project.getBasePath();
        if (basePath != null && repos.size() > 1) {
            for (git4idea.repo.GitRepository r : repos) {
                if (r.getRoot().getPath().equals(basePath)) {
                    repo = r;
                    break;
                }
            }
        }

        git4idea.commands.GitLineHandler handler =
            new git4idea.commands.GitLineHandler(project, repo.getRoot(), command);
        handler.setSilent(true);
        handler.setStdoutSuppressed(true);
        if (args.length > 1) {
            handler.addParameters(java.util.Arrays.asList(args).subList(1, args.length));
        }

        git4idea.commands.GitCommandResult result =
            git4idea.commands.Git.getInstance().runCommand(handler);
        if (result.success()) {
            return result.getOutputAsJoinedString();
        }
        return "Error (exit " + result.getExitCode() + "): " + result.getErrorOutputAsJoinedString();
    }

    private static final java.util.Map<String, git4idea.commands.GitCommand> IDE_GIT_COMMAND_MAP = java.util.Map.ofEntries(
        java.util.Map.entry("add", git4idea.commands.GitCommand.ADD),
        java.util.Map.entry("blame", git4idea.commands.GitCommand.BLAME),
        java.util.Map.entry("branch", git4idea.commands.GitCommand.BRANCH),
        java.util.Map.entry("checkout", git4idea.commands.GitCommand.CHECKOUT),
        java.util.Map.entry("cherry-pick", git4idea.commands.GitCommand.CHERRY_PICK),
        java.util.Map.entry("commit", git4idea.commands.GitCommand.COMMIT),
        java.util.Map.entry("config", git4idea.commands.GitCommand.CONFIG),
        java.util.Map.entry("diff", git4idea.commands.GitCommand.DIFF),
        java.util.Map.entry("fetch", git4idea.commands.GitCommand.FETCH),
        java.util.Map.entry("log", git4idea.commands.GitCommand.LOG),
        java.util.Map.entry("merge", git4idea.commands.GitCommand.MERGE),
        java.util.Map.entry("pull", git4idea.commands.GitCommand.PULL),
        java.util.Map.entry("push", git4idea.commands.GitCommand.PUSH),
        java.util.Map.entry("rebase", git4idea.commands.GitCommand.REBASE),
        java.util.Map.entry("remote", git4idea.commands.GitCommand.REMOTE),
        java.util.Map.entry("reset", git4idea.commands.GitCommand.RESET),
        java.util.Map.entry("restore", git4idea.commands.GitCommand.RESTORE),
        java.util.Map.entry("rev-parse", git4idea.commands.GitCommand.REV_PARSE),
        java.util.Map.entry("revert", git4idea.commands.GitCommand.REVERT),
        java.util.Map.entry("show", git4idea.commands.GitCommand.SHOW),
        java.util.Map.entry("stash", git4idea.commands.GitCommand.STASH),
        java.util.Map.entry("status", git4idea.commands.GitCommand.STATUS),
        java.util.Map.entry("switch", git4idea.commands.GitCommand.CHECKOUT),
        java.util.Map.entry("tag", git4idea.commands.GitCommand.TAG)
    );

    /**
     * Returns the plugin name and version string for our plugin, or null if unavailable.
     *
     * <p><b>Why extracted:</b> {@code PluginManagerCore.getPlugin(PluginId)} has the same
     * annotation mismatch as {@code isPluginInstalled} — the parameter type differs between
     * IDE versions. Cascading: {@code descriptor.getName()} and {@code descriptor.getVersion()}
     * fail because the return type of {@code getPlugin} is unresolved.</p>
     */
    public static @Nullable String getPluginVersionInfo(@NotNull String pluginId) {
        var descriptor = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
            com.intellij.openapi.extensions.PluginId.getId(pluginId));
        if (descriptor == null) return null;
        return descriptor.getName() + " v" + descriptor.getVersion();
    }

    /**
     * Adds a source folder to a content entry with the given type.
     *
     * <p><b>Why extracted:</b> {@code ContentEntry.addSourceFolder(VirtualFile, JpsModuleSourceRootType)}
     * cannot be resolved because the JPS model classes ({@code JavaSourceRootType},
     * {@code JavaResourceRootType}, {@code JavaSourceRootProperties}) are bundled in a separate
     * JAR whose version differs between the dev IDE and target SDK. The Gradle build resolves
     * them correctly from the configured platform dependency.</p>
     */
    public static void addSourceFolder(@NotNull com.intellij.openapi.roots.ContentEntry entry,
                                @NotNull com.intellij.openapi.vfs.VirtualFile dir,
                                @NotNull String type) {
        boolean isTest = type.startsWith("test_");
        if (type.contains("resources")) {
            var rootType = isTest
                ? org.jetbrains.jps.model.java.JavaResourceRootType.TEST_RESOURCE
                : org.jetbrains.jps.model.java.JavaResourceRootType.RESOURCE;
            entry.addSourceFolder(dir, rootType);
        } else if ("generated_sources".equals(type)) {
            var rootType = org.jetbrains.jps.model.java.JavaSourceRootType.SOURCE;
            var props = org.jetbrains.jps.model.java.JpsJavaExtensionService.getInstance()
                .createSourceRootProperties("", true);
            entry.addSourceFolder(dir, rootType, props);
        } else if (isTest) {
            entry.addSourceFolder(dir, org.jetbrains.jps.model.java.JavaSourceRootType.TEST_SOURCE);
        } else {
            entry.addSourceFolder(dir, org.jetbrains.jps.model.java.JavaSourceRootType.SOURCE);
        }
    }

    /**
     * Lists available SDK types with their suggested entries.
     *
     * <p><b>Why extracted:</b> {@code SdkType.EP_NAME.getExtensionList()} cannot be resolved
     * because the extension point's generic type differs between IDE versions. Cascading:
     * {@code sdkType.getName()}, {@code getPresentableName()}, {@code collectSdkEntries()},
     * and the returned entry's {@code homePath()}/{@code versionString()} all fail.
     * The Gradle build compiles without errors.</p>
     */
    public static @NotNull String listSdkTypes(@NotNull Project project) {
        var sb = new StringBuilder();
        var sdkTypes = com.intellij.openapi.projectRoots.SdkType.EP_NAME.getExtensionList();
        sb.append("\nAvailable SDK types:\n");
        for (var sdkType : sdkTypes) {
            sb.append("  - ").append(sdkType.getName()).append(" (").append(sdkType.getPresentableName()).append(")\n");
            var entries = sdkType.collectSdkEntries(project);
            for (var entry : entries) {
                sb.append("    suggested: ").append(entry.homePath());
                if (entry.versionString() != null) {
                    sb.append(" (").append(entry.versionString()).append(")");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Finds an SdkType by name (case-insensitive), or null if not found.
     *
     * <p><b>Why extracted:</b> Same {@code SdkType.EP_NAME.getExtensionList()} resolution issue
     * as {@link #listSdkTypes}.</p>
     */
    public static @Nullable com.intellij.openapi.projectRoots.SdkType findSdkTypeByName(@NotNull String name) {
        var sdkTypes = com.intellij.openapi.projectRoots.SdkType.EP_NAME.getExtensionList();
        for (var type : sdkTypes) {
            if (type.getName().equalsIgnoreCase(name) || type.getPresentableName().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Executes a {@link Runnable} inside a {@code WriteAction.runAndWait} block.
     *
     * <p><b>Why extracted:</b> {@code WriteAction.runAndWait(ThrowableRunnable)} is not recognized
     * as accepting a functional interface lambda by the IDE daemon, because the
     * {@code @NotNull ThrowableRunnable<E>} annotation layout differs between versions. The IDE
     * reports "ThrowableRunnable is not a functional interface". Wrapping the call here avoids
     * the false positive in calling code.</p>
     */
    @SuppressWarnings("unchecked")
    public static void writeActionRunAndWait(@NotNull Runnable action) throws Exception {
        com.intellij.openapi.application.WriteAction.runAndWait(
            (com.intellij.util.ThrowableRunnable<Exception>) action::run);
    }

    /**
     * Returns all registered configuration type display names.
     *
     * <p><b>Why extracted:</b> {@code ConfigurationType.CONFIGURATION_TYPE_EP.getExtensionList()}
     * cannot be resolved because the extension point generic differs between IDE versions.
     * Cascading: {@code getDisplayName()} fails on the unresolved type.</p>
     */
    public static @NotNull java.util.List<String> listConfigurationTypeNames() {
        var result = new java.util.ArrayList<String>();
        for (var ct : com.intellij.execution.configurations.ConfigurationType.CONFIGURATION_TYPE_EP.getExtensionList()) {
            result.add(ct.getDisplayName());
        }
        return result;
    }

    /**
     * Finds a ConfigurationType by display name or ID (case-insensitive partial match).
     *
     * <p><b>Why extracted:</b> Same {@code CONFIGURATION_TYPE_EP.getExtensionList()} resolution
     * issue as {@link #listConfigurationTypeNames}. Additionally, {@code ct.getId()} cannot
     * be resolved due to the cascading type failure.</p>
     */
    static @Nullable com.intellij.execution.configurations.ConfigurationType findConfigurationType(@NotNull String type) {
        for (var ct : com.intellij.execution.configurations.ConfigurationType.CONFIGURATION_TYPE_EP.getExtensionList()) {
            String displayName = ct.getDisplayName().toLowerCase();
            if (displayName.equals(type) || displayName.contains(type)
                || ct.getId().toLowerCase().contains(type)) {
                return ct;
            }
        }
        return null;
    }

    /**
     * Returns the classloader for a plugin by its ID, or null if the plugin is not installed.
     *
     * <p><b>Why extracted:</b> Same {@code PluginManagerCore.getPlugin(PluginId)} annotation
     * mismatch as {@link #getPluginVersionInfo}. Additionally, {@code descriptor.getPluginClassLoader()}
     * cannot be resolved because the return type of {@code getPlugin} is unresolved.</p>
     */
    public static @Nullable ClassLoader getPluginClassLoader(@NotNull String pluginId) {
        var descriptor = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
            com.intellij.openapi.extensions.PluginId.getId(pluginId));
        return descriptor != null ? descriptor.getPluginClassLoader() : null;
    }

    /**
     * Returns the filesystem path to a plugin's installation directory, or null if unavailable.
     *
     * <p><b>Why extracted:</b> {@code PluginManagerCore.getPlugin(PluginId)} has a different
     * {@code @NotNull} annotation between IDE versions, causing the daemon to report
     * "cannot be applied to (PluginId)". Cascading: {@code descriptor.getPluginPath()} also
     * fails because the return type of {@code getPlugin} is unresolved. The Gradle build
     * compiles without errors.</p>
     */
    public static @Nullable java.nio.file.Path getPluginPath(@NotNull String pluginId) {
        var descriptor = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
            com.intellij.openapi.extensions.PluginId.getId(pluginId));
        return descriptor != null ? descriptor.getPluginPath() : null;
    }

    /**
     * Searches for a ConfigurationType by flexible matching on ID and display name.
     * <p>
     * False positive: {@code ConfigurationType.CONFIGURATION_TYPE_EP.getExtensionList()} fails
     * because the IDE resolves the extension point generic differently than the target SDK.
     * Methods on the returned objects ({@code getId()}, {@code getDisplayName()}) cascade-fail.
     * Gradle compiles cleanly.
     *
     * @param idOrNameSubstring case-insensitive substring to match against ID or display name
     * @return the matching ConfigurationType, or null if not found
     */
    public static com.intellij.execution.configurations.ConfigurationType findConfigurationTypeBySearch(
        String idOrNameSubstring) {
        String lowerSearch = idOrNameSubstring.toLowerCase();
        for (var ct : com.intellij.execution.configurations.ConfigurationType
            .CONFIGURATION_TYPE_EP.getExtensionList()) {
            if (ct.getId().toLowerCase().contains(lowerSearch)
                || ct.getDisplayName().toLowerCase().contains(lowerSearch)) {
                return ct;
            }
        }
        return null;
    }

    /**
     * Detects a programming language from text content using IntelliJ's
     * {@link com.intellij.openapi.fileTypes.FileTypeRegistry.FileTypeDetector} extensions
     * and common content heuristics (shebang lines, language-specific patterns).
     *
     * <p><b>Why extracted:</b> {@code FileTypeDetector.EP_NAME} and
     * {@code LanguageUtil.getFileTypeLanguage()} have subtle signature changes across IDE
     * versions. Centralising here keeps the caller insulated.</p>
     *
     * @param text the pasted / imported text to analyse
     * @return the detected {@link com.intellij.lang.Language}, or {@code null} if unknown
     */
    @Nullable
    public static com.intellij.lang.Language detectLanguageFromContent(@NotNull String text) {
        // 1. Try IntelliJ's registered file-type detectors (handles shebang, BOM, etc.)
        com.intellij.lang.Language detected = detectViaFileTypeDetectors(text);
        if (detected != null) return detected;

        // 2. Heuristic shebang mapping
        detected = detectViaShebang(text);
        if (detected != null) return detected;

        // 3. Content-pattern heuristics
        return detectViaPatterns(text);
    }

    @Nullable
    private static com.intellij.lang.Language detectViaFileTypeDetectors(@NotNull String text) {
        try {
            byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var byteSeq = com.intellij.openapi.util.io.ByteArraySequence.create(bytes);
            int prefixLen = Math.min(bytes.length, 4096);
            var prefixSeq = new com.intellij.openapi.util.io.ByteArraySequence(bytes, 0, prefixLen);

            for (var detector : com.intellij.openapi.fileTypes.FileTypeRegistry.FileTypeDetector.EP_NAME.getExtensionList()) {
                try {
                    int desired = detector.getDesiredContentPrefixLength();
                    var seq = desired > 0 && desired < bytes.length ? prefixSeq : byteSeq;
                    var ft = detector.detect(null, seq, text);
                    if (ft instanceof com.intellij.openapi.fileTypes.LanguageFileType lft) {
                        // Skip PlainText — it's a catch-all fallback, not a real detection.
                        // Returning it would suppress pattern-based detection (e.g. JSON).
                        if (lft.getLanguage() == com.intellij.openapi.fileTypes.PlainTextLanguage.INSTANCE) continue;
                        return lft.getLanguage();
                    }
                } catch (Exception ignored) {
                    // Some detectors may throw on null VirtualFile — skip
                }
            }
        } catch (Exception e) {
            LOG.debug("FileTypeDetector scan failed", e);
        }
        return null;
    }

    private static final java.util.Map<String, String> SHEBANG_LANG_MAP = java.util.Map.ofEntries(
        java.util.Map.entry("python", LANG_PYTHON),
        java.util.Map.entry("python3", LANG_PYTHON),
        java.util.Map.entry("node", LANG_JAVASCRIPT),
        java.util.Map.entry("deno", LANG_JAVASCRIPT),
        java.util.Map.entry("bun", LANG_JAVASCRIPT),
        java.util.Map.entry("bash", LANG_SHELL_SCRIPT),
        java.util.Map.entry("sh", LANG_SHELL_SCRIPT),
        java.util.Map.entry("zsh", LANG_SHELL_SCRIPT),
        java.util.Map.entry("ruby", "Ruby"),
        java.util.Map.entry("perl", "Perl"),
        java.util.Map.entry("php", "PHP"),
        java.util.Map.entry("groovy", "Groovy"),
        java.util.Map.entry("lua", "Lua"),
        java.util.Map.entry("Rscript", "R")
    );

    @Nullable
    private static com.intellij.lang.Language detectViaShebang(@NotNull String text) {
        if (!text.startsWith("#!")) return null;
        String firstLine = text.lines().findFirst().orElse("");
        // Extract the interpreter name: #!/usr/bin/env python3 → python3
        String interpreter = firstLine.contains("/env ")
            ? firstLine.substring(firstLine.indexOf("/env ") + 5).trim().split("\\s")[0]
            : firstLine.substring(firstLine.lastIndexOf('/') + 1).trim().split("\\s")[0];
        String langId = SHEBANG_LANG_MAP.get(interpreter);
        return langId != null ? com.intellij.lang.LanguageUtil.findRegisteredLanguage(langId) : null;
    }

    @Nullable
    private static com.intellij.lang.Language detectViaPatterns(@NotNull String text) {
        String trimmed = text.stripLeading();
        String first512 = trimmed.substring(0, Math.min(trimmed.length(), 512));

        // JSON: starts with { or [ and contains " — high confidence
        if ((trimmed.startsWith("{") || trimmed.startsWith("[")) && first512.contains("\"")) {
            return com.intellij.lang.LanguageUtil.findRegisteredLanguage("JSON");
        }
        // XML/HTML: starts with < (tag or declaration)
        if (trimmed.startsWith("<?xml") || trimmed.startsWith("<!DOCTYPE")) {
            return com.intellij.lang.LanguageUtil.findRegisteredLanguage("XML");
        }
        if (trimmed.startsWith("<html") || trimmed.startsWith("<!doctype html")) {
            return com.intellij.lang.LanguageUtil.findRegisteredLanguage("HTML");
        }
        // YAML: starts with --- or has key: value patterns on first lines
        if (trimmed.startsWith("---")) {
            return com.intellij.lang.LanguageUtil.findRegisteredLanguage("yaml");
        }
        // SQL keywords (case-insensitive)
        String upper = first512.toUpperCase(java.util.Locale.ROOT);
        if (upper.startsWith("SELECT ") || upper.startsWith("INSERT ") || upper.startsWith("CREATE TABLE")
            || upper.startsWith("ALTER TABLE") || upper.startsWith("DROP ")) {
            return com.intellij.lang.LanguageUtil.findRegisteredLanguage("SQL");
        }
        // Java: package declaration or typical Java imports
        if (first512.startsWith("package ") && first512.contains(";")) {
            return first512.contains("fun ") || first512.contains("val ")
                ? com.intellij.lang.LanguageUtil.findRegisteredLanguage("kotlin")
                : com.intellij.lang.LanguageUtil.findRegisteredLanguage("JAVA");
        }
        // Python: def/class with colon, or import without braces
        if ((first512.contains("def ") && first512.contains(":"))
            || first512.startsWith("import ") && !first512.contains("{") && !first512.contains("from '")) {
            return com.intellij.lang.LanguageUtil.findRegisteredLanguage(LANG_PYTHON);
        }
        // Kotlin top-level fun
        if (first512.contains("fun ") && first512.contains("{") && !first512.contains(";")) {
            return com.intellij.lang.LanguageUtil.findRegisteredLanguage("kotlin");
        }
        // Go: package main / func
        if (first512.startsWith("package main") || (first512.contains("func ") && first512.contains("{"))) {
            return com.intellij.lang.LanguageUtil.findRegisteredLanguage("go");
        }
        // Rust: fn main, let mut, pub fn
        if (first512.contains("fn ") && (first512.contains("let ") || first512.contains("pub "))) {
            return com.intellij.lang.LanguageUtil.findRegisteredLanguage("Rust");
        }
        return null;
    }

    /**
     * Subscribes an ExecutionListener to the project message bus and returns a disconnect handle.
     * <p>
     * False positive: {@code project.getMessageBus().connect()} fails because the IDE resolves
     * MessageBus from its own platform JAR where the generic bounds on {@code connect()} differ
     * from the target SDK. The returned connection's {@code subscribe()} and {@code disconnect()}
     * cascade-fail for the same reason. Gradle compiles cleanly.
     *
     * @param project  the project to subscribe on
     * @param listener the execution listener
     * @return a Runnable that disconnects the subscription when called
     */
    public static Runnable subscribeExecutionListener(
        com.intellij.openapi.project.Project project,
        com.intellij.execution.ExecutionListener listener) {
        var connection = project.getMessageBus().connect();
        connection.subscribe(com.intellij.execution.ExecutionManager.EXECUTION_TOPIC, listener);
        return connection::disconnect;
    }

    /**
     * Returns a publisher for the given topic on the project message bus.
     *
     * <p><b>Why extracted:</b> {@code MessageBus.syncPublisher(Topic<T>)} cannot be resolved in the
     * IDE daemon because the generic bounds on {@code MessageBus} differ between the dev IDE and the
     * target SDK. Gradle compiles cleanly.</p>
     */
    public static <T> T syncPublisher(@NotNull Project project, @NotNull com.intellij.util.messages.Topic<T> topic) {
        return project.getMessageBus().syncPublisher(topic);
    }

    /**
     * Subscribes a {@link com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.DaemonListener} to
     * daemon events on the project message bus, and returns a {@link Runnable} that disconnects
     * the subscription.
     *
     * <p><b>Why extracted:</b> {@code project.getMessageBus().connect()} cannot be resolved in the
     * IDE daemon because the generic bounds on {@code MessageBus.connect()} differ between the dev
     * IDE and the target SDK. Cascading: {@code connection.subscribe()} and
     * {@code connection.disconnect()} also fail. The Gradle build compiles without errors.</p>
     */
    static @NotNull Runnable subscribeDaemonListener(
        @NotNull Project project,
        @NotNull com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.DaemonListener listener) {
        var connection = project.getMessageBus().connect();
        connection.subscribe(com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC, listener);
        return connection::disconnect;
    }

    /**
     * Launches a full IDE inspection run against the given scope and profile, and invokes
     * {@code onFinished} with the populated context once analysis is complete.
     *
     * <p><b>Why extracted:</b> The inspection infrastructure requires subclassing
     * {@code GlobalInspectionContextEx} (rather than using a plain instance) to intercept
     * {@code notifyInspectionsFinished}, which is the only reliable callback for when all
     * tool analysis has completed. {@code setExternalProfile()} and {@code doInspections()}
     * are inherited from {@code GlobalInspectionContextBase} (both public). The analysis runs
     * without opening the IDE Inspection Results tool window, which is intentional for
     * programmatic MCP use.</p>
     *
     * @param project    the current project
     * @param scope      the analysis scope to inspect
     * @param profile    the inspection profile to use
     * @param onFinished callback invoked (on a background thread) after analysis completes;
     *                   receives the fully-populated {@code GlobalInspectionContextEx}
     */
    public static void runFullInspections(
        @NotNull Project project,
        @NotNull com.intellij.analysis.AnalysisScope scope,
        @NotNull com.intellij.codeInspection.ex.InspectionProfileImpl profile,
        @NotNull java.util.function.Consumer<com.intellij.codeInspection.ex.GlobalInspectionContextEx> onFinished) {
        com.intellij.codeInspection.ex.GlobalInspectionContextEx context =
            new com.intellij.codeInspection.ex.GlobalInspectionContextEx(project) {
                @Override
                protected void notifyInspectionsFinished(@NotNull com.intellij.analysis.AnalysisScope finishedScope) {
                    super.notifyInspectionsFinished(finishedScope);
                    onFinished.accept(this);
                }
            };
        context.setExternalProfile(profile);
        // doInspections asserts EDT — launch it there; the actual file analysis runs on a
        // background thread inside the backgroundable task that doInspections creates.
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() ->
            context.doInspections(scope));
    }
}
