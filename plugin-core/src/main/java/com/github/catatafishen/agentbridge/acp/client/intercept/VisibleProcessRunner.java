package com.github.catatafishen.agentbridge.acp.client.intercept;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Runs a fall-through ACP {@code terminal/create} command as a real OS process,
 * mirroring its output to a tab in the IDE Run tool window so the user can watch
 * the command live (and kill it via the standard stop button) while the agent
 * still sees a clean stdout/exit-code stream over the ACP protocol.
 *
 * <p>Wraps {@link OSProcessHandler} with a {@link ConsoleView} attached for visibility,
 * plus a {@link ProcessListener} that forwards stdout/stderr into a caller-supplied
 * {@link OutputSink}. The result returned to the caller is a live {@link Process} the
 * existing ACP terminal handler already knows how to manage for ACP lifecycle methods
 * ({@code terminal/output}, {@code terminal/wait_for_exit}, etc.).
 */
public final class VisibleProcessRunner {

    private static final Logger LOG = Logger.getInstance(VisibleProcessRunner.class);

    /** Receives raw output text exactly as the underlying process emits it. */
    @FunctionalInterface
    public interface OutputSink {
        void append(@NotNull String chunk);
    }

    private final @Nullable Project project;

    public VisibleProcessRunner(@Nullable Project project) {
        this.project = project;
    }

    /**
     * Start {@code commandLine} as a visible process. The console tab is registered with
     * the Run tool window on the EDT; the underlying process starts immediately on the
     * caller's thread so callers can operate on the returned {@link Process} synchronously.
     *
     * @param commandLine pre-configured command line (cwd/env already set)
     * @param tabTitle    title for the Run tool window tab
     * @param sink        receives every stdout/stderr chunk the process emits
     * @return the live {@link Process}; never {@code null}
     * @throws ExecutionException if the process could not be started
     */
    public @NotNull Process start(@NotNull GeneralCommandLine commandLine,
                                  @NotNull String tabTitle,
                                  @NotNull OutputSink sink) throws ExecutionException {
        Process process = commandLine.createProcess();

        // In headless test contexts (no Application) or when no Project is available,
        // fall back to plain stream-reader threads — the IDE Run-tool-window plumbing
        // requires a live Application to register services.
        if (project == null || ApplicationManager.getApplication() == null) {
            startHeadlessReaders(process, sink);
            return process;
        }

        OSProcessHandler handler = new OSProcessHandler(process, commandLine.getCommandLineString());
        handler.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                String text = event.getText();
                if (text != null && !text.isEmpty()) sink.append(text);
            }
        });

        try {
            ConsoleView console = TextConsoleBuilderFactory.getInstance()
                .createBuilder(project)
                .getConsole();
            console.attachToProcess(handler);

            // Register on the EDT — RunContentManager mutates Swing state.
            EdtUtil.invokeLater(() -> registerInRunToolWindow(console, handler, tabTitle));
        } catch (Exception e) {
            LOG.warn("Failed to attach visible console for '" + tabTitle + "' — process will still run", e);
        }

        handler.startNotify();
        return process;
    }

    /**
     * Pumps the process's stdout into the sink on a daemon thread. Used only when no
     * IntelliJ Application is available (unit tests). Stderr is folded into stdout via
     * {@link GeneralCommandLine#setRedirectErrorStream(boolean)}.
     */
    private static void startHeadlessReaders(@NotNull Process process, @NotNull OutputSink sink) {
        Thread t = new Thread(() -> {
            try (java.io.InputStream in = process.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    sink.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (Exception ignore) {
                // process closed or interrupted — nothing actionable
            }
        }, "VisibleProcessRunner-headless-reader");
        t.setDaemon(true);
        t.start();
    }

    private void registerInRunToolWindow(@NotNull ConsoleView console,
                                         @NotNull OSProcessHandler handler,
                                         @NotNull String tabTitle) {
        try {
            assert project != null; // guarded by caller — non-null when we reach here
            JComponent component = console.getComponent();
            RunContentDescriptor descriptor = new RunContentDescriptor(
                console, handler, component, tabTitle);
            descriptor.setActivateToolWindowWhenAdded(false);
            RunContentManager.getInstance(project)
                .showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor);
        } catch (Exception e) {
            LOG.warn("Could not show ACP terminal in Run tool window: " + tabTitle, e);
        }
    }

    /**
     * Convenience builder so the calling ACP terminal handler stays focused on protocol plumbing.
     */
    public static @NotNull GeneralCommandLine buildCommandLine(@NotNull String command,
                                                               @NotNull String[] args,
                                                               @Nullable String cwd,
                                                               @NotNull Map<String, String> env) {
        GeneralCommandLine cl = new GeneralCommandLine();
        cl.setExePath(command);
        for (String arg : args) cl.addParameter(arg);
        if (cwd != null) cl.setWorkDirectory(new File(cwd));
        cl.withEnvironment(env);
        cl.setCharset(StandardCharsets.UTF_8);
        cl.setRedirectErrorStream(true);
        return cl;
    }
}
