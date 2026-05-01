package com.github.catatafishen.agentbridge.psi;

import com.intellij.codeInspection.InspectionToolResultExporter;
import com.intellij.codeInspection.ex.GlobalInspectionContextEx;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotificationProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    /**
     * Per-provider timeout for {@link #collectEditorNotificationTexts}.
     * Providers that exceed this budget are skipped with a warning.
     */
    private static final long NOTIFICATION_PROVIDER_TIMEOUT_MS = 500;
    private static final String LANG_SHELL_SCRIPT = "Shell Script";
    private static final String LANG_JAVASCRIPT = "JavaScript";
    private static final String LANG_PYTHON = "Python";

    /**
     * Categories of source root types, used by {@link #classifySourceRootType(String)}.
     */
    enum SourceRootKind {
        SOURCE, TEST_SOURCE, RESOURCE, TEST_RESOURCE, GENERATED_SOURCE
    }

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
     * Performs an undo or redo operation while suppressing the confirmation dialog
     * that {@link com.intellij.openapi.command.impl.UndoManagerImpl} shows for
     * global (multi-file) operations.
     *
     * <p><b>Why extracted:</b> {@code UndoManagerImpl.ourNeverAskUser} is an internal
     * static field ({@code com.intellij.openapi.command.impl} package). Accessing it
     * directly from tool classes would spread internal API usage. This method keeps the
     * dependency in one place.</p>
     *
     * @param undoManager the project's UndoManager
     * @param fileEditor  the editor to undo/redo in
     * @param isUndo      true for undo, false for redo
     */
    public static void undoOrRedoSilently(
        @NotNull com.intellij.openapi.command.undo.UndoManager undoManager,
        @Nullable com.intellij.openapi.fileEditor.FileEditor fileEditor,
        boolean isUndo) {
        com.intellij.openapi.command.impl.UndoManagerImpl.ourNeverAskUser = true;
        try {
            if (isUndo) {
                undoManager.undo(fileEditor);
            } else {
                undoManager.redo(fileEditor);
            }
        } finally {
            com.intellij.openapi.command.impl.UndoManagerImpl.ourNeverAskUser = false;
        }
    }

    public static @NotNull List<String> collectEditorNotificationTexts(
        @NotNull Project project, @NotNull VirtualFile vf, @NotNull FileEditor editor) {
        List<String> notifications = new ArrayList<>();
        for (var provider : EditorNotificationProvider.EP_NAME.getExtensions(project)) {
            String text = fetchNotificationText(provider, project, vf, editor);
            if (text != null) notifications.add(text);
        }
        return notifications;
    }

    @Nullable
    private static String fetchNotificationText(
        @NotNull EditorNotificationProvider provider,
        @NotNull Project project, @NotNull VirtualFile vf, @NotNull FileEditor editor) {
        Future<Function<? super FileEditor, ? extends JComponent>> future =
            ApplicationManager.getApplication().executeOnPooledThread(
                () -> ApplicationManager.getApplication().runReadAction(
                    (Computable<Function<? super FileEditor, ? extends JComponent>>)
                        () -> provider.collectNotificationData(project, vf)));
        try {
            Function<? super FileEditor, ? extends JComponent> factory =
                future.get(NOTIFICATION_PROVIDER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (factory == null) return null;
            JComponent panel = factory.apply(editor);
            if (panel instanceof EditorNotificationPanel enp) {
                String text = enp.getText();
                return text.isEmpty() ? null : "[BANNER] " + text;
            }
            return null;
        } catch (TimeoutException e) {
            future.cancel(true);
            LOG.warn("Skipped slow EditorNotificationProvider: "
                + provider.getClass().getName()
                + " (exceeded " + NOTIFICATION_PROVIDER_TIMEOUT_MS
                + "ms — likely doing expensive index operations on EDT)");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            // Skip failing providers — some may not be compatible with the current context
            return null;
        }
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
    @SuppressWarnings("java:S1181")
    // Intentional: safety net for reflection errors (ExceptionInInitializerError, NoClassDefFoundError)
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
    @SuppressWarnings("java:S1181")
    // Intentional: safety net for reflection errors (ExceptionInInitializerError, NoClassDefFoundError)
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
     * Application-level analogue of {@link #getServiceByRawClass}. Used by reflection-based
     * integrations (e.g., SonarLint) where the service class is loaded from a foreign classloader.
     * <p>
     * <b>Why extracted:</b> {@code ApplicationManager.getApplication().getService(Class&lt;T&gt;)}
     * causes an IDE daemon false-positive when passed a raw {@code Class&lt;?&gt;} because the
     * generic bound differs between SDK versions. The {@code (Class&lt;Object&gt;)} cast silences
     * the daemon; Gradle compiles cleanly.
     */
    @SuppressWarnings("unchecked")
    public static @Nullable Object getApplicationServiceByRawClass(@NotNull Class<?> serviceClass) {
        // Cast to Class<Object> satisfies the generic bound; safe because we only use the result as Object.
        return ApplicationManager.getApplication().getService((Class<Object>) serviceClass);
    }

    /**
     * Typed version of ApplicationManager.getApplication().getService() for application-level services.
     * <p>
     * <b>Why extracted:</b> {@code Application.getService(Class<T>)} causes IDE daemon false-positives
     * because the generic bounds on {@code getService()} differ between the dev IDE and target SDK.
     * Gradle compiles cleanly.
     */
    public static <T> @NotNull T getApplicationService(@NotNull Class<T> serviceClass) {
        return ApplicationManager.getApplication().getService(serviceClass);
    }

    /**
     * Shows an IDE balloon notification in the "AgentBridge Notifications" group.
     *
     * <p><b>Why extracted:</b> {@code NotificationGroup.createNotification(String, String, NotificationType)}
     * — the 3-argument overload with a content string — is not resolved by the IDE daemon when the
     * development IDE version differs from the target SDK. The method exists in both the minimum and
     * maximum supported IDE versions (2024.3–2025.2) and the Gradle build compiles cleanly; the error
     * is a false positive in the editor. Centralising all notification creation here eliminates the
     * daemon error from every caller.</p>
     *
     * <p>Must be called on the EDT. Wrap with {@code invokeLater} if calling from a background thread.</p>
     *
     * @param project the project to scope the notification to (may be null for app-level notifications)
     * @param title   notification balloon title
     * @param content notification body text (HTML is supported)
     * @param type    notification type (INFO, WARNING, or ERROR)
     */
    public static void showNotification(
        @Nullable Project project,
        @NotNull String title,
        @NotNull String content,
        @NotNull com.intellij.notification.NotificationType type) {
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("AgentBridge Notifications")
            .createNotification(title, content, type)
            .notify(project);
    }

    /**
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
     * Returns {@code true} if the current IDE UI theme is dark.
     *
     * <p><b>Why extracted:</b> {@code UIThemeLookAndFeelInfo.isDark()} is only available from
     * IntelliJ 2022.3+. The IDE daemon resolves {@code getCurrentUIThemeLookAndFeel()} to the
     * SDK version in the dev IDE and flags {@code .isDark()} as unresolved in earlier-target
     * builds. Gradle compiles without errors. Centralising the call here confines the
     * false-positive daemon error to one place.</p>
     */
    public static boolean isCurrentThemeDark() {
        return com.intellij.ide.ui.LafManager.getInstance()
            .getCurrentUIThemeLookAndFeel().isDark();
    }

    public static void showRevisionInLogAfterRefresh(@NotNull Project project, @NotNull String fullHash) {
        showRevisionInLogAfterRefresh(project, fullHash, null);
    }

    /**
     * Repo-aware variant. {@code repoRootPath} is the absolute root of the git repository
     * the commit was made in — used both to refresh the correct {@link com.intellij.vcs.log.data.VcsLogData}
     * root in multi-repo projects, and to disambiguate the navigation target via
     * {@link com.intellij.vcs.log.impl.VcsProjectLog#showRevisionInMainLog(Project, com.intellij.openapi.vfs.VirtualFile, com.intellij.vcs.log.Hash)}.
     *
     * <p><b>Why the root matters</b>: in multi-repo projects, refreshing only the project base
     * root never indexes the new commit in the actual repo's storage. The
     * {@link com.intellij.vcs.log.data.DataPackChangeListener} then never sees the commit,
     * the 10-second cleanup fires, and meanwhile any unrelated refresh can race
     * {@link com.intellij.vcs.log.impl.VcsProjectLog#showRevisionInMainLog(Project, com.intellij.vcs.log.Hash)}
     * into selecting an older commit and emitting a "commit not found" bubble. See
     * {@code docs/bugs/COMMIT-NOT-FOUND-IN-LOG-BUG.md}.
     *
     * @param project      the current project
     * @param fullHash     the full 40-character commit SHA
     * @param repoRootPath absolute root path of the repo the commit was made in;
     *                     {@code null} means "use the legacy project-base resolution"
     *                     (only suitable for entry points like chat-chip clicks that
     *                     have no repo context).
     */
    /**
     * Overload that accepts a pre-navigation callback invoked on the EDT immediately before
     * {@code showRevisionInMainLog}. Used to open the VCS tool window only after the graph is
     * confirmed fresh — avoiding IntelliJ 2025.3's "highlight current revision" auto-navigation
     * that fires on {@code tw.activate(null)} and emits the "commit not found" bubble when the
     * graph hasn't been rebuilt yet. See COMMIT-NOT-FOUND-IN-LOG-BUG.md § Cause 5.
     */
    public static void showRevisionInLogAfterRefresh(
        @NotNull Project project, @NotNull String fullHash, @Nullable String repoRootPath,
        @Nullable Runnable preNavigationCallback) {
        showRevisionInLogAfterRefreshImpl(project, fullHash, repoRootPath, preNavigationCallback);
    }

    public static void showRevisionInLogAfterRefresh(
        @NotNull Project project, @NotNull String fullHash, @Nullable String repoRootPath) {
        showRevisionInLogAfterRefreshImpl(project, fullHash, repoRootPath, null);
    }

    private static void showRevisionInLogAfterRefreshImpl(
        @NotNull Project project, @NotNull String fullHash, @Nullable String repoRootPath,
        @Nullable Runnable preNavigationCallback) {
        var hash = com.intellij.vcs.log.impl.HashImpl.build(fullHash);
        var vcsLog = com.intellij.vcs.log.impl.VcsProjectLog.getInstance(project);
        var data = vcsLog.getDataManager();
        if (data == null) {
            // VCS log not yet initialized — skip navigation to avoid a "commit not found" error
            // bubble. The log hasn't indexed the commit yet, and there's no UI panel to navigate
            // to anyway. Best-effort follow-mode only runs when the log is already open.
            return;
        }

        com.intellij.openapi.vfs.VirtualFile repoRootVf =
            resolveLogProviderRoot(data, project, repoRootPath);
        if (repoRootVf == null) {
            // Repo isn't a registered VCS-log provider (e.g. detected-but-unregistered subfolder
            // git repo). The log can't navigate to commits it doesn't index — silently skip.
            return;
        }

        // Capture the current visible graph identity BEFORE registering the listener. We use this
        // as the freshness baseline: navigation only fires when a NEW graph/data-pack object has
        // been published (i.e. the listener observed a refresh, or the immediate race-check sees
        // that the graph reference has already changed). A pure storage check is insufficient because
        // VcsLogStorage.containsCommit can return true while the new DataPack/PermanentGraph
        // is still being built, in which case showRevisionInMainLog falls back to the previous
        // HEAD selection and emits a "commit not found" bubble. See COMMIT-NOT-FOUND-IN-LOG-BUG.md.
        Object initialGraph = getCurrentGraphIdentity(data);
        var navigated = new java.util.concurrent.atomic.AtomicBoolean(false);

        com.intellij.vcs.log.data.DataPackChangeListener listener =
            buildDataPackListener(project, data, hash, repoRootVf, initialGraph, navigated, preNavigationCallback);

        data.addDataPackChangeListener(listener);
        data.refresh(java.util.List.of(repoRootVf));

        // Race-condition guard: a fresh graph may have been published between getDataManager()
        // and addDataPackChangeListener (e.g., Git4Idea repository update fired immediately
        // after our commit). In that case the listener will never fire for our commit, so we
        // check now. We require BOTH a fresh graph reference (to avoid the storage-only race
        // documented above) AND that the commit is indexed in the target root.
        if (getCurrentGraphIdentity(data) != initialGraph
            && isCommitIndexed(data, hash, repoRootVf)
            && navigated.compareAndSet(false, true)) {
            data.removeDataPackChangeListener(listener);
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() ->
                navigateToRevisionInMainLog(project, repoRootVf, hash, preNavigationCallback));
            return;
        }

        // Timeout: clean up listener after 10 seconds to prevent leak
        com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService()
            .schedule(() -> {
                if (navigated.compareAndSet(false, true)) {
                    data.removeDataPackChangeListener(listener);
                }
            }, 10, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * Resolves the {@link com.intellij.openapi.vfs.VirtualFile} for the repo root that should
     * be refreshed/navigated. Returns {@code null} if the requested root is not a VCS-log
     * provider (e.g. unregistered subfolder repo) — callers must skip navigation in that case.
     *
     * <p>When {@code repoRootPath} is {@code null} (legacy entry points), falls back to the
     * project's base path if it is a log provider; otherwise returns the first registered
     * log provider root.
     */
    private static @Nullable com.intellij.openapi.vfs.VirtualFile resolveLogProviderRoot(
        @NotNull com.intellij.vcs.log.data.VcsLogData data,
        @NotNull Project project,
        @Nullable String repoRootPath) {
        var providers = data.getLogProviders();
        if (providers.isEmpty()) return null;

        if (repoRootPath != null) {
            for (var root : providers.keySet()) {
                if (root.getPath().equals(repoRootPath)) return root;
            }
            return null;
        }

        String basePath = project.getBasePath();
        if (basePath != null) {
            for (var root : providers.keySet()) {
                if (root.getPath().equals(basePath)) return root;
            }
        }
        return providers.keySet().iterator().next();
    }

    /**
     * Builds the {@link com.intellij.vcs.log.data.DataPackChangeListener} via dynamic Proxy.
     *
     * <p><b>Why Proxy</b>: {@code DataPackChangeListener} is an internal API whose SAM method
     * signature changed between IDE versions (DataPack → VcsLogGraphData). Using a dynamic
     * {@link java.lang.reflect.Proxy} avoids {@link AbstractMethodError} at runtime.
     *
     * <p><b>Why {@code Object initialGraph}</b>: {@code VcsLogData.getGraphData()} and
     * {@code VcsLogGraphData} are not available in every supported IDE SDK. The identity object
     * comes from {@link #getCurrentGraphIdentity(com.intellij.vcs.log.data.VcsLogData)} so this
     * compatibility class can compile against both old and new SDKs.
     */
    private static com.intellij.vcs.log.data.DataPackChangeListener buildDataPackListener(
        @NotNull Project project,
        @NotNull com.intellij.vcs.log.data.VcsLogData data,
        @NotNull com.intellij.vcs.log.Hash hash,
        @NotNull com.intellij.openapi.vfs.VirtualFile repoRootVf,
        @NotNull Object initialGraph,
        @NotNull java.util.concurrent.atomic.AtomicBoolean navigated,
        @Nullable Runnable preNavigationCallback) {
        return (com.intellij.vcs.log.data.DataPackChangeListener) java.lang.reflect.Proxy.newProxyInstance(
            com.intellij.vcs.log.data.DataPackChangeListener.class.getClassLoader(),
            new Class<?>[]{com.intellij.vcs.log.data.DataPackChangeListener.class},
            (proxy, method, args) -> {
                // Handle standard Object methods required by the Proxy contract.
                // Returning null for equals/hashCode would NPE because they unbox to primitives.
                switch (method.getName()) {
                    case "equals" -> {
                        return args != null && args.length > 0 && proxy == args[0];
                    }
                    case "hashCode" -> {
                        return System.identityHashCode(proxy);
                    }
                    case "toString" -> {
                        return "DataPackChangeListenerProxy@"
                            + Integer.toHexString(System.identityHashCode(proxy));
                    }
                    case "onDataPackChange" -> { /* handled below */ }
                    default -> {
                        return null;
                    }
                }

                // Only navigate after a NEW graph has been published AND the new commit is
                // indexed. The graph-reference check is critical: VcsLogStorage.containsCommit
                // can become true before the new PermanentGraph is built, and navigating in
                // that window selects the previous HEAD and emits a "not found" bubble.
                Object currentGraph = getPublishedGraphIdentity(data, args);
                if (currentGraph == initialGraph) return null;
                if (!isCommitIndexed(data, hash, repoRootVf)) return null;

                if (!navigated.compareAndSet(false, true)) return null;
                data.removeDataPackChangeListener(
                    (com.intellij.vcs.log.data.DataPackChangeListener) proxy);
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() ->
                    navigateToRevisionInMainLog(project, repoRootVf, hash, preNavigationCallback));
                return null;
            });
    }

    /**
     * Returns the object whose identity changes when the visible VCS Log graph/data-pack changes.
     *
     * <p>Newer IDEs expose {@code VcsLogData.getGraphData()}, which is the correct stable
     * freshness baseline. Older supported IDEs only expose {@code getDataPack()}, so reflection is
     * required to keep this compatibility shim compiling against both API shapes.
     */
    private static @NotNull Object getCurrentGraphIdentity(
        @NotNull com.intellij.vcs.log.data.VcsLogData data) {
        try {
            return invokeVcsLogDataMethod(data, "getGraphData");
        } catch (NoSuchMethodException ignored) {
            try {
                return invokeVcsLogDataMethod(data, "getDataPack");
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(
                    "VcsLogData exposes neither getGraphData() nor getDataPack()", e);
            }
        }
    }

    private static @NotNull Object invokeVcsLogDataMethod(
        @NotNull com.intellij.vcs.log.data.VcsLogData data,
        @NotNull String methodName) throws NoSuchMethodException {
        try {
            return data.getClass().getMethod(methodName).invoke(data);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unable to access VcsLogData." + methodName + "()", e);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException("VcsLogData." + methodName + "() failed", e.getCause());
        }
    }

    /**
     * Uses the listener event payload as the freshness identity when available. This avoids
     * calling version-specific VCS Log APIs from the proxy callback.
     */
    private static @NotNull Object getPublishedGraphIdentity(
        @NotNull com.intellij.vcs.log.data.VcsLogData data,
        @Nullable Object[] args) {
        if (args != null && args.length > 0 && args[0] != null) return args[0];
        return getCurrentGraphIdentity(data);
    }

    private static void navigateToRevisionInMainLog(
        @NotNull Project project,
        @NotNull com.intellij.openapi.vfs.VirtualFile repoRoot,
        @NotNull com.intellij.vcs.log.Hash hash,
        @Nullable Runnable preNavigationCallback) {
        // The pre-navigation callback (if any) opens the VCS tool window here — AFTER the graph
        // is confirmed fresh. This avoids IntelliJ 2025.3's "highlight current revision" behavior
        // that fires on tw.activate(null) and tries to navigate to HEAD before the graph is ready.
        if (preNavigationCallback != null) preNavigationCallback.run();
        // Do not skip selection when chat is active: follow-mode must still move the VCS Log
        // selection without stealing focus (that is handled inside the callback itself).
        com.intellij.vcs.log.impl.VcsProjectLog.showRevisionInMainLog(project, repoRoot, hash);
    }

    private static boolean isCommitIndexed(
        @NotNull com.intellij.vcs.log.data.VcsLogData data,
        @NotNull com.intellij.vcs.log.Hash hash,
        @NotNull com.intellij.openapi.vfs.VirtualFile repoRoot) {
        return data.getStorage().containsCommit(new com.intellij.vcs.log.CommitId(hash, repoRoot));
    }

    private static void refreshVcsLogForProject(
        @NotNull Project project,
        @NotNull com.intellij.vcs.log.data.VcsLogData data) {
        // Trigger VCS log refresh to pick up the new commit
        String basePath = project.getBasePath();
        if (basePath == null) return;
        var root = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(basePath);
        if (root != null) {
            data.refresh(java.util.List.of(root));
        }
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
     * Isolated in this class, so Git4Idea class loading is deferred until first use; if Git4Idea
     * is disabled, the caller catches {@code NoClassDefFoundError} and falls back.</p>
     */
    public static @Nullable String runIdeGitCommand(@NotNull Project project, @NotNull String[] args) {
        if (args.length == 0) return null;
        git4idea.repo.GitRepository repo = getRepository(project);
        if (repo == null) return null;
        return runIdeGitCommandWithRepo(project, repo, args);
    }

    public static @Nullable String runIdeGitCommandIn(
        @NotNull Project project, @NotNull String rootPath, @NotNull String[] args) {
        if (args.length == 0) return null;
        git4idea.repo.GitRepository repo = getRepositoryForRoot(project, rootPath);
        if (repo == null) return null;
        return runIdeGitCommandWithRepo(project, repo, args);
    }

    private static @Nullable String runIdeGitCommandWithRepo(
        @NotNull Project project, @NotNull git4idea.repo.GitRepository repo, @NotNull String[] args) {
        git4idea.commands.GitCommand command = IDE_GIT_COMMAND_MAP.get(args[0]);
        if (command == null) return null;

        git4idea.commands.GitLineHandler handler =
            new git4idea.commands.GitLineHandler(project, repo.getRoot(), command);
        handler.setSilent(true);
        handler.setStdoutSuppressed(true);
        // Prevent git from opening a text editor (e.g. for rebase --continue commit message).
        // "true" is a POSIX no-op command that exits 0, making git use the default message.
        handler.addCustomEnvironmentVariable("GIT_EDITOR", "true");
        handler.addCustomEnvironmentVariable("GIT_TERMINAL_PROMPT", "0");
        if (args.length > 1) {
            handler.addParameters(java.util.Arrays.asList(args).subList(1, args.length));
        }

        git4idea.commands.GitCommandResult result =
            git4idea.commands.Git.getInstance().runCommand(handler);
        if (result.success()) {
            return result.getOutputAsJoinedString();
        }
        return formatGitCommandError(result.getExitCode(), result.getErrorOutputAsJoinedString());
    }

    /**
     * Returns the primary GitRepository for the project, preferring the repo rooted at
     * the project base path when multiple repos exist (e.g. submodules).
     * Returns null if Git4Idea is unavailable or no repositories are registered.
     */
    public static @Nullable git4idea.repo.GitRepository getRepository(@NotNull Project project) {
        java.util.List<git4idea.repo.GitRepository> repos = getRepositories(project);
        if (repos.isEmpty()) return null;

        git4idea.repo.GitRepository repo = repos.getFirst();
        String basePath = project.getBasePath();
        if (basePath != null && repos.size() > 1) {
            for (git4idea.repo.GitRepository r : repos) {
                if (r.getRoot().getPath().equals(basePath)) return r;
            }
        }
        return repo;
    }

    /**
     * Returns all git repositories registered in this project.
     * Returns an empty list if Git4Idea is unavailable or no repositories are registered.
     */
    public static @NotNull java.util.List<git4idea.repo.GitRepository> getRepositories(@NotNull Project project) {
        try {
            return git4idea.repo.GitRepositoryManager.getInstance(project).getRepositories();
        } catch (NoClassDefFoundError e) {
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Returns absolute root paths of all git repositories detectable in the project,
     * including <b>unregistered</b> ones in subfolders that IntelliJ has discovered but
     * the user has not yet added to {@code Settings | Version Control}.
     *
     * <p>This is the source of truth for "what git repos exist in this project" — used by
     * git tool error messages and root resolution. Paths returned here are not guaranteed
     * to have a backing {@link git4idea.repo.GitRepository} instance, so APIs that require
     * one (e.g. {@code Git.getInstance().checkout(repo, ...)}) must still go through
     * {@link #getRepositoryForRoot}; CLI-based git operations work fine with just the path.
     *
     * <p>Falls back to {@link #getRepositories} alone when {@code VcsRootDetector} is
     * unavailable (older IDE, missing VCS plugin).
     */
    public static @NotNull java.util.List<String> getDetectedGitRoots(@NotNull Project project) {
        java.util.LinkedHashSet<String> roots = new java.util.LinkedHashSet<>();
        for (git4idea.repo.GitRepository r : getRepositories(project)) {
            roots.add(r.getRoot().getPath());
        }
        try {
            java.util.Collection<com.intellij.openapi.vcs.VcsRoot> detected =
                com.intellij.openapi.vcs.roots.VcsRootDetector.getInstance(project).getOrDetect();
            for (com.intellij.openapi.vcs.VcsRoot vr : detected) {
                com.intellij.openapi.vcs.AbstractVcs vcs = vr.getVcs();
                com.intellij.openapi.vfs.VirtualFile path = vr.getPath();
                if (vcs != null && "Git".equals(vcs.getName()) && path != null) {
                    roots.add(path.getPath());
                }
            }
        } catch (NoClassDefFoundError | Exception e) {
            // VcsRootDetector unavailable or detection failed — fall through to registered-only.
        }
        return new java.util.ArrayList<>(roots);
    }

    /**
     * Returns the GitRepository whose root is exactly {@code rootPath}, or null if not found.
     */
    public static @Nullable git4idea.repo.GitRepository getRepositoryForRoot(
        @NotNull Project project, @NotNull String rootPath) {
        for (git4idea.repo.GitRepository r : getRepositories(project)) {
            if (r.getRoot().getPath().equals(rootPath)) return r;
        }
        return null;
    }

    /**
     * Switches to an existing branch using Git4Idea's high-level checkout API.
     * Uses IntelliJ's configured git executable and properly refreshes the GitRepository state.
     * Returns null to signal that the caller should fall back to CLI.
     */
    public static @Nullable String ideCheckout(@NotNull Project project, @NotNull String branchName) {
        return ideCheckout(project, null, branchName);
    }

    /**
     * Root-aware variant of {@link #ideCheckout(Project, String)}.
     */
    public static @Nullable String ideCheckout(
        @NotNull Project project, @Nullable String rootPath, @NotNull String branchName) {
        git4idea.repo.GitRepository repo = rootPath != null
            ? getRepositoryForRoot(project, rootPath)
            : getRepository(project);
        if (repo == null) return null;
        git4idea.commands.GitCommandResult result =
            git4idea.commands.Git.getInstance().checkout(repo, branchName, null, false, false);
        if (result.success()) return "";
        return formatGitCommandError(result.getExitCode(), result.getErrorOutputAsJoinedString());
    }

    /**
     * Creates a new branch and switches to it using Git4Idea's high-level API.
     * When base is null, creates from HEAD. When base is specified, falls back to CLI
     * (Git4Idea's checkoutNewBranch has no start-point parameter).
     * Returns null to signal that the caller should fall back to CLI.
     */
    public static @Nullable String ideCheckoutNewBranch(@NotNull Project project,
                                                        @NotNull String branchName,
                                                        @Nullable String base) {
        return ideCheckoutNewBranch(project, null, branchName, base);
    }

    /**
     * Root-aware variant of {@link #ideCheckoutNewBranch(Project, String, String)}.
     */
    public static @Nullable String ideCheckoutNewBranch(
        @NotNull Project project, @Nullable String rootPath,
        @NotNull String branchName, @Nullable String base) {
        git4idea.repo.GitRepository repo = rootPath != null
            ? getRepositoryForRoot(project, rootPath)
            : getRepository(project);
        if (repo == null) return null;
        git4idea.commands.GitCommandResult result;
        if (base == null) {
            result = git4idea.commands.Git.getInstance().checkoutNewBranch(repo, branchName, null);
        } else {
            // branchCreate(repo, name, startPoint) creates without switching; then checkout
            result = git4idea.commands.Git.getInstance().branchCreate(repo, branchName, base);
            if (!result.success()) {
                return formatGitCommandError(result.getExitCode(), result.getErrorOutputAsJoinedString());
            }
            result = git4idea.commands.Git.getInstance().checkout(repo, branchName, null, false, false);
        }
        if (result.success()) return "";
        return formatGitCommandError(result.getExitCode(), result.getErrorOutputAsJoinedString());
    }

    /**
     * Deletes a branch using Git4Idea's high-level API.
     * Returns null to signal that the caller should fall back to CLI.
     */
    public static @Nullable String ideDeleteBranch(@NotNull Project project,
                                                   @NotNull String branchName,
                                                   boolean force) {
        return ideDeleteBranch(project, null, branchName, force);
    }

    /**
     * Root-aware variant of {@link #ideDeleteBranch(Project, String, boolean)}.
     */
    public static @Nullable String ideDeleteBranch(
        @NotNull Project project, @Nullable String rootPath,
        @NotNull String branchName, boolean force) {
        git4idea.repo.GitRepository repo = rootPath != null
            ? getRepositoryForRoot(project, rootPath)
            : getRepository(project);
        if (repo == null) return null;
        git4idea.commands.GitCommandResult result =
            git4idea.commands.Git.getInstance().branchDelete(repo, branchName, force);
        if (result.success()) return "";
        return formatGitCommandError(result.getExitCode(), result.getErrorOutputAsJoinedString());
    }

    /**
     * Formats a git command error into a human-readable message.
     *
     * @param exitCode    the process exit code
     * @param errorOutput the stderr content
     * @return formatted error string, e.g. {@code "Error (exit 128): fatal: not a git repository"}
     */
    static String formatGitCommandError(int exitCode, @NotNull String errorOutput) {
        return "Error (exit " + exitCode + "): " + errorOutput;
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
        java.util.Map.entry("ls-files", git4idea.commands.GitCommand.LS_FILES),
        java.util.Map.entry("reset", git4idea.commands.GitCommand.RESET),
        java.util.Map.entry("restore", git4idea.commands.GitCommand.RESTORE),
        java.util.Map.entry("rev-list", git4idea.commands.GitCommand.REV_LIST),
        java.util.Map.entry("rev-parse", git4idea.commands.GitCommand.REV_PARSE),
        java.util.Map.entry("revert", git4idea.commands.GitCommand.REVERT),
        java.util.Map.entry("show", git4idea.commands.GitCommand.SHOW),
        java.util.Map.entry("stash", git4idea.commands.GitCommand.STASH),
        java.util.Map.entry("status", git4idea.commands.GitCommand.STATUS),
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
        return formatPluginVersionInfo(descriptor.getName(), descriptor.getVersion());
    }

    /**
     * Formats a plugin name and version into the standard display string.
     *
     * @param name    plugin display name
     * @param version plugin version string
     * @return formatted string, e.g. {@code "My Plugin v1.2.3"}
     */
    static String formatPluginVersionInfo(@NotNull String name, @NotNull String version) {
        return name + " v" + version;
    }

    /**
     * Classifies a source-root type string (e.g. "sources", "test_sources", "resources",
     * "test_resources", "generated_sources") into a {@link SourceRootKind}.
     *
     * <p>Rules:</p>
     * <ul>
     *   <li>If the type contains "resources" → RESOURCE or TEST_RESOURCE (depending on "test_" prefix)</li>
     *   <li>If the type is "generated_sources" → GENERATED_SOURCE</li>
     *   <li>If the type starts with "test_" → TEST_SOURCE</li>
     *   <li>Otherwise → SOURCE</li>
     * </ul>
     */
    static SourceRootKind classifySourceRootType(@NotNull String type) {
        boolean isTest = type.startsWith("test_");
        if (type.contains("resources")) {
            return isTest ? SourceRootKind.TEST_RESOURCE : SourceRootKind.RESOURCE;
        }
        if ("generated_sources".equals(type)) {
            return SourceRootKind.GENERATED_SOURCE;
        }
        return isTest ? SourceRootKind.TEST_SOURCE : SourceRootKind.SOURCE;
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
        switch (classifySourceRootType(type)) {
            case TEST_RESOURCE ->
                entry.addSourceFolder(dir, org.jetbrains.jps.model.java.JavaResourceRootType.TEST_RESOURCE);
            case RESOURCE -> entry.addSourceFolder(dir, org.jetbrains.jps.model.java.JavaResourceRootType.RESOURCE);
            case GENERATED_SOURCE -> {
                var rootType = org.jetbrains.jps.model.java.JavaSourceRootType.SOURCE;
                var props = org.jetbrains.jps.model.java.JpsJavaExtensionService.getInstance()
                    .createSourceRootProperties("", true);
                entry.addSourceFolder(dir, rootType, props);
            }
            case TEST_SOURCE -> entry.addSourceFolder(dir, org.jetbrains.jps.model.java.JavaSourceRootType.TEST_SOURCE);
            case SOURCE -> entry.addSourceFolder(dir, org.jetbrains.jps.model.java.JavaSourceRootType.SOURCE);
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
                String versionStr = entry.versionString();
                if (!versionStr.isEmpty()) {
                    sb.append(" (").append(versionStr).append(")");
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
            if (displayName.contains(type)
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
     * versions. Centralizing here keeps the caller insulated.</p>
     *
     * @param text the pasted / imported text to analyze
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
                com.intellij.lang.Language lang = tryDetectWithDetector(detector, byteSeq, prefixSeq, bytes, text);
                if (lang != null) return lang;
            }
        } catch (Exception e) {
            LOG.debug("FileTypeDetector scan failed", e);
        }
        return null;
    }

    // S2637: FileTypeDetector.detect() documents null as a valid value for the VirtualFile param
    @SuppressWarnings("java:S2637")
    @Nullable
    private static com.intellij.lang.Language tryDetectWithDetector(
        com.intellij.openapi.fileTypes.FileTypeRegistry.FileTypeDetector detector,
        com.intellij.openapi.util.io.ByteArraySequence byteSeq,
        com.intellij.openapi.util.io.ByteArraySequence prefixSeq,
        byte[] bytes,
        @NotNull String text) {
        try {
            int desired = detector.getDesiredContentPrefixLength();
            var seq = desired > 0 && desired < bytes.length ? prefixSeq : byteSeq;
            // Passing null VirtualFile is the standard pattern for content-based detection; the
            // @NotNull annotation on detect() is overly strict in some SDK versions. The outer
            // try-catch handles detectors that reject a null file.
            @SuppressWarnings("DataFlowIssue")
            var ft = detector.detect(null, seq, text);
            if (ft instanceof com.intellij.openapi.fileTypes.LanguageFileType lft
                && lft.getLanguage() != com.intellij.openapi.fileTypes.PlainTextLanguage.INSTANCE) {
                // Skip PlainText — it's a catch-all fallback, not a real detection.
                return lft.getLanguage();
            }
        } catch (Exception ignored) {
            // Some detectors may throw on null VirtualFile — skip
        }
        return null;
    }

    static final java.util.Map<String, String> SHEBANG_LANG_MAP = java.util.Map.ofEntries(
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
        String langId = resolveShebangLanguageId(text);
        return langId != null ? com.intellij.lang.LanguageUtil.findRegisteredLanguage(langId) : null;
    }

    /**
     * Extracts the interpreter name from a shebang line.
     * For {@code #!/usr/bin/env python3} returns {@code "python3"}.
     * For {@code #!/bin/bash} returns {@code "bash"}.
     *
     * @return the interpreter name, or {@code null} if the text doesn't start with {@code #!}
     */
    @Nullable
    static String resolveShebangInterpreter(@NotNull String text) {
        if (!text.startsWith("#!")) return null;
        String firstLine = text.lines().findFirst().orElse("");
        return firstLine.contains("/env ")
            ? firstLine.substring(firstLine.indexOf("/env ") + 5).trim().split("\\s")[0]
            : firstLine.substring(firstLine.lastIndexOf('/') + 1).trim().split("\\s")[0];
    }

    /**
     * Resolves the shebang interpreter to a language ID via {@link #SHEBANG_LANG_MAP}.
     *
     * @return the language ID string (e.g. "Python", "Shell Script"), or {@code null}
     */
    @Nullable
    static String resolveShebangLanguageId(@NotNull String text) {
        String interpreter = resolveShebangInterpreter(text);
        return interpreter != null ? SHEBANG_LANG_MAP.get(interpreter) : null;
    }

    @Nullable
    private static com.intellij.lang.Language detectViaPatterns(@NotNull String text) {
        String langId = detectLanguageIdViaPatterns(text);
        return langId != null ? com.intellij.lang.LanguageUtil.findRegisteredLanguage(langId) : null;
    }

    /**
     * Detects a language ID from content patterns (JSON, XML, HTML, YAML, SQL, Java/Kotlin,
     * Python, Go, Rust). Returns the ID string suitable for
     * {@link com.intellij.lang.LanguageUtil#findRegisteredLanguage(String)}.
     *
     * @return language ID (e.g. "JSON", "JAVA", "Python") or {@code null}
     */
    @Nullable
    static String detectLanguageIdViaPatterns(@NotNull String text) {
        String trimmed = text.stripLeading();
        String first512 = trimmed.substring(0, Math.min(trimmed.length(), 512));

        String result = detectMarkupLanguageId(trimmed, first512);
        if (result != null) return result;

        String upper = first512.toUpperCase(java.util.Locale.ROOT);
        result = detectSqlOrJavaFamilyId(upper, first512);
        if (result != null) return result;

        return detectScriptingLanguageId(first512);
    }

    @Nullable
    static String detectMarkupLanguageId(@NotNull String trimmed, @NotNull String first512) {
        if ((trimmed.startsWith("{") || trimmed.startsWith("[")) && first512.contains("\"")) {
            return "JSON";
        }
        if (trimmed.startsWith("<?xml") || trimmed.startsWith("<!DOCTYPE")) {
            return "XML";
        }
        if (trimmed.startsWith("<html") || trimmed.startsWith("<!doctype html")) {
            return "HTML";
        }
        if (trimmed.startsWith("---")) {
            return "yaml";
        }
        return null;
    }

    @Nullable
    static String detectSqlOrJavaFamilyId(@NotNull String upper, @NotNull String first512) {
        if (upper.startsWith("SELECT ") || upper.startsWith("INSERT ") || upper.startsWith("CREATE TABLE")
            || upper.startsWith("ALTER TABLE") || upper.startsWith("DROP ")) {
            return "SQL";
        }
        if (first512.startsWith("package ") && first512.contains(";")) {
            return first512.contains("fun ") || first512.contains("val ")
                ? "kotlin" : "JAVA";
        }
        return null;
    }

    @Nullable
    static String detectScriptingLanguageId(@NotNull String first512) {
        if ((first512.contains("def ") && first512.contains(":"))
            || first512.startsWith("import ") && !first512.contains("{") && !first512.contains("from '")) {
            return LANG_PYTHON;
        }
        if (first512.contains("fun ") && first512.contains("{") && !first512.contains(";")) {
            return "kotlin";
        }
        if (first512.startsWith("package main") || (first512.contains("func ") && first512.contains("{"))) {
            return "go";
        }
        if (first512.contains("fn ") && (first512.contains("let ") || first512.contains("pub "))) {
            return "Rust";
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
    public static @NotNull Runnable subscribeDaemonListener(
        @NotNull Project project,
        @NotNull com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.DaemonListener listener) {
        var connection = project.getMessageBus().connect();
        connection.subscribe(com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC, listener);
        return connection::disconnect;
    }

    /**
     * Subscribes a {@link PsiBridgeService.ToolCallListener} to tool call events on the project
     * message bus, and returns a {@link Runnable} that disconnects the subscription.
     *
     * <p><b>Why extracted:</b> {@code project.getMessageBus().connect()} cannot be resolved in the
     * IDE daemon because the generic bounds on {@code MessageBus.connect()} differ between the dev
     * IDE and the target SDK. Cascading: {@code connection.subscribe()} also fails.
     * The Gradle build compiles without errors.</p>
     */
    public static @NotNull Runnable subscribeToolCallListener(
        @NotNull Project project,
        @NotNull PsiBridgeService.ToolCallListener listener) {
        var connection = project.getMessageBus().connect();
        connection.subscribe(PsiBridgeService.TOOL_CALL_TOPIC, listener);
        return connection::disconnect;
    }

    /**
     * Returns the list of installed UI themes.
     *
     * <p><b>Why extracted:</b> {@link com.intellij.ide.ui.LafManager#getInstalledThemes()} is
     * annotated {@code @ApiStatus.Experimental} across all supported IDE versions. There is no
     * non-experimental API to enumerate installed themes — the only way to list them is through
     * this method. The verifier warns but does not fail the build (EXPERIMENTAL_API_USAGES is
     * not in our failureLevel). This usage is intentional and accepted.</p>
     *
     * <p>Additionally, the return type changed from {@code Sequence<UIThemeLookAndFeelInfo>}
     * to {@code List<UIThemeLookAndFeelInfo>} across supported IDE versions. Centralizing here
     * also isolates callers from the Kotlin sequence adapter.</p>
     *
     * @param lafManager the LafManager instance to query
     * @return list of all installed themes
     */
    @SuppressWarnings("UnstableApiUsage") // getInstalledThemes() is @Experimental; no stable alternative exists
    public static @NotNull java.util.List<com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo> getInstalledThemes(
        @NotNull com.intellij.ide.ui.LafManager lafManager) {
        return kotlin.sequences.SequencesKt.toList(lafManager.getInstalledThemes());
    }

    /**
     * Creates an {@link com.intellij.xdebugger.XExpression} from plain text (language-agnostic, expression mode).
     *
     * <p><b>Why extracted:</b> {@code XExpression} is a public interface but the platform provides no
     * public factory. The previous approach used the internal {@code XExpressionImpl.fromText()};
     * this implementation avoids that by implementing the interface directly, using only
     * public API ({@code XExpression}, {@code EvaluationMode}).</p>
     */
    public static com.intellij.xdebugger.XExpression createXExpression(@NotNull String text) {
        return new com.intellij.xdebugger.XExpression() {
            @Override
            public @NotNull String getExpression() {
                return text;
            }

            @Override
            public @Nullable com.intellij.lang.Language getLanguage() {
                return null;
            }

            @Override
            public @NotNull com.intellij.xdebugger.evaluation.EvaluationMode getMode() {
                return com.intellij.xdebugger.evaluation.EvaluationMode.EXPRESSION;
            }

            @Override
            public @Nullable String getCustomInfo() {
                return null;
            }
        };
    }

    /**
     * Returns all registered {@link com.intellij.xdebugger.breakpoints.XBreakpointType} extensions.
     *
     * <p><b>Why extracted:</b> {@code XBreakpointType.EXTENSION_POINT_NAME} is typed with a raw
     * {@code XBreakpointType} generic, causing the IDE daemon to report "cannot resolve
     * getExtensionList()" even though the method exists on {@code ExtensionPointName}. The Gradle
     * build compiles cleanly.</p>
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static @NotNull java.util.List<com.intellij.xdebugger.breakpoints.XBreakpointType<?, ?>> listXBreakpointTypes() {
        return (java.util.List) com.intellij.xdebugger.breakpoints.XBreakpointType.EXTENSION_POINT_NAME.getExtensionList();
    }

    /**
     * Executes a {@link java.util.function.Supplier} inside a {@code WriteAction} block and
     * returns the computed result.
     *
     * <p><b>Why extracted:</b> {@code WriteAction.computeAndWait(ThrowableComputable)} is not
     * recognised as accepting a functional interface lambda by the IDE daemon (same annotation
     * issue as {@link #writeActionRunAndWait}). Using an {@link java.util.concurrent.atomic.AtomicReference}
     * and {@link #writeActionRunAndWait} sidesteps the false positive.</p>
     */
    public static <T> T writeActionComputeAndWait(@NotNull java.util.function.Supplier<T> supplier) throws Exception {
        java.util.concurrent.atomic.AtomicReference<T> ref = new java.util.concurrent.atomic.AtomicReference<>();
        writeActionRunAndWait(() -> ref.set(supplier.get()));
        return ref.get();
    }

    /**
     * A minimal wrapper around {@code JBTabs} that exposes only the operations needed by
     * {@link com.github.catatafishen.agentbridge.ui.side.SidePanel}.
     *
     * <p><b>Why extracted:</b> {@code JBTabsFactory.createTabs()} returns
     * {@code com.intellij.ui.tabs.JBTabs}, and both {@code JBTabs} and {@code TabInfo} cause
     * "Incompatible types" / "cannot apply to" false-positive daemon errors across IntelliJ
     * 2024.3–2025.2 SDK versions, even though Gradle compiles cleanly. All JBTabs API calls are
     * confined to this class so daemon errors are isolated to {@code PlatformApiCompat.java}.</p>
     */
    public static final class JBTabsPanel {

        private final com.intellij.ui.tabs.JBTabs tabs;
        private final List<com.intellij.ui.tabs.TabInfo> tabInfos = new ArrayList<>();

        private JBTabsPanel(com.intellij.ui.tabs.JBTabs tabs) {
            this.tabs = tabs;
        }

        /**
         * Returns the Swing component to embed in a parent panel.
         */
        public @NotNull JComponent getComponent() {
            return tabs.getComponent();
        }

        /**
         * Appends a tab with the given content panel and label.
         */
        public void addTab(@NotNull JComponent content, @NotNull String title) {
            com.intellij.ui.tabs.TabInfo info = new com.intellij.ui.tabs.TabInfo(content).setText(title);
            tabInfos.add(info);
            tabs.addTab(info);
        }

        /**
         * Programmatically selects the tab at the given zero-based index.
         */
        public void selectTab(int index) {
            if (index >= 0 && index < tabInfos.size()) {
                tabs.select(tabInfos.get(index), false);
            }
        }

        /**
         * Updates the visible title of the tab at {@code index}.
         * Used for badge overlays like {@code "Todos (3/7)"}.
         */
        public void setTabTitle(int index, @NotNull String title) {
            if (index >= 0 && index < tabInfos.size()) {
                tabInfos.get(index).setText(title);
            }
        }

        /**
         * Registers a tab-selection listener. {@code onTabSelected} receives the zero-based
         * index of the newly selected tab whenever the selection changes.
         */
        public void addSelectionListener(@NotNull java.util.function.IntConsumer onTabSelected,
                                         @NotNull com.intellij.openapi.Disposable disposable) {
            tabs.addListener(new com.intellij.ui.tabs.TabsListener() {
                @Override
                public void selectionChanged(com.intellij.ui.tabs.TabInfo oldSel,
                                             com.intellij.ui.tabs.TabInfo newSel) {
                    if (newSel != null) {
                        int idx = tabInfos.indexOf(newSel);
                        if (idx >= 0) onTabSelected.accept(idx);
                    }
                }
            }, disposable);
        }
    }

    /**
     * Creates a {@link JBTabsPanel} backed by {@code JBTabsFactory.createTabs()}.
     *
     * <p><b>Why extracted:</b> see {@link JBTabsPanel}.</p>
     */
    public static @NotNull JBTabsPanel createJBTabsPanel(@NotNull Project project,
                                                         @NotNull com.intellij.openapi.Disposable parentDisposable) {
        com.intellij.ui.tabs.JBTabs tabs = com.intellij.ui.tabs.JBTabsFactory.createTabs(project, parentDisposable);
        return new JBTabsPanel(tabs);
    }

    /**
     * Returns the {@link com.intellij.openapi.ui.popup.ListPopupStep} backing a
     * {@link com.intellij.ui.popup.list.ListPopupImpl}.
     *
     * <p><b>Why extracted:</b> the IDE editor daemon flags
     * {@code ListPopupImpl.getListStep()} with a spurious "Incompatible types. Found
     * ListPopupStep&lt;Object&gt;, required ListPopupStep&lt;Object&gt;" error because the
     * SDK generic signatures differ between the development IDE and the target SDK.
     * Gradle compiles cleanly. Wrapping the call in a raw-typed bridge keeps the
     * false-positive confined to this single method.</p>
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static @Nullable com.intellij.openapi.ui.popup.ListPopupStep<Object> getListStep(
        @NotNull com.intellij.ui.popup.list.ListPopupImpl popup) {
        com.intellij.openapi.ui.popup.ListPopupStep raw = popup.getListStep();
        return (com.intellij.openapi.ui.popup.ListPopupStep<Object>) raw;
    }
}
