package com.github.catatafishen.ideagentforcopilot.psi.tools.infrastructure;

import com.github.catatafishen.ideagentforcopilot.psi.EdtUtil;
import com.google.gson.JsonObject;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lists all active Run and Build tool window tabs so agents can pass
 * correct tab names to read_run_output or read_build_output.
 */
public final class ListRunTabsTool extends InfrastructureTool {

    public ListRunTabsTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "list_run_tabs";
    }

    @Override
    public @NotNull String displayName() {
        return "List Run Tabs";
    }

    @Override
    public @NotNull String description() {
        return "List active Run panel and Build panel tabs. "
            + "Use the returned tab names with read_run_output or read_build_output.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.READ;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[0][]);
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        var ref = new AtomicReference<String>();
        EdtUtil.invokeAndWait(() -> ref.set(buildTabList()));
        return ref.get();
    }

    private String buildTabList() {
        StringBuilder sb = new StringBuilder();
        appendRunTabs(sb);
        sb.append('\n');
        appendBuildTabs(sb);
        return sb.toString().trim();
    }

    private void appendRunTabs(StringBuilder sb) {
        sb.append("Run panel tabs (use with read_run_output):\n");
        Collection<RunContentDescriptor> runDescriptors =
            RunContentManager.getInstance(project).getAllDescriptors();
        if (runDescriptors.isEmpty()) {
            sb.append("  (none)\n");
            return;
        }
        for (var d : runDescriptors) {
            String name = d.getDisplayName() != null ? d.getDisplayName() : "(unnamed)";
            ProcessHandler handler = d.getProcessHandler();
            boolean running = handler != null && !handler.isProcessTerminated();
            sb.append("  - ").append(name);
            if (running) sb.append(" [running]");
            sb.append('\n');
        }
    }

    private void appendBuildTabs(StringBuilder sb) {
        sb.append("Build panel tabs (use with read_build_output):\n");
        var buildWindow = ToolWindowManager.getInstance(project).getToolWindow("Build");
        if (buildWindow == null) {
            sb.append("  (Build tool window not available)\n");
            return;
        }
        Content[] contents = buildWindow.getContentManager().getContents();
        if (contents.length == 0) {
            sb.append("  (none)\n");
            return;
        }
        for (var c : contents) {
            String name = c.getDisplayName() != null ? c.getDisplayName() : "(unnamed)";
            sb.append("  - ").append(name).append('\n');
        }
    }
}
