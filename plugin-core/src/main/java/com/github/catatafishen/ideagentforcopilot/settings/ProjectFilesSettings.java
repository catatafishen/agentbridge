package com.github.catatafishen.ideagentforcopilot.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Application-level settings for project file shortcuts shown in the toolbar dropdown.
 * Each entry is a relative path (from project root) that appears in the Project Files menu.
 */
@Service(Service.Level.APP)
@State(name = "ProjectFilesSettings", storages = @Storage("ideAgentProjectFiles.xml"))
public final class ProjectFilesSettings implements PersistentStateComponent<ProjectFilesSettings.State> {

    private State myState = new State();

    public static ProjectFilesSettings getInstance() {
        return ApplicationManager.getApplication().getService(ProjectFilesSettings.class);
    }

    public List<FileEntry> getEntries() {
        return myState.entries;
    }

    public void setEntries(List<FileEntry> entries) {
        myState.entries = new ArrayList<>(entries);
    }

    @Override
    public @NotNull State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
    }

    public static class State {
        private List<FileEntry> entries = getDefaults();

        public List<FileEntry> getEntries() {
            return entries;
        }

        public void setEntries(List<FileEntry> entries) {
            this.entries = entries;
        }
    }

    /**
     * A single file shortcut entry.
     *
     * @see #getLabel()   Display name in the menu
     * @see #getPath()    Relative path from project root (supports glob for directory scanning)
     * @see #isGlob()     If true, path is a glob pattern (e.g., ".github/agents/*.md") and
     * matching files are listed individually in the menu
     * @see #getGroup()   Display group/category for organizing in dropdown menu (e.g., "Claude", "Junie")
     */
    public static class FileEntry {
        private String label = "";
        private String path = "";
        private boolean glob = false;
        private String group = "";

        public FileEntry() {
        }

        public FileEntry(String label, String path, boolean isGlob) {
            this(label, path, isGlob, "");
        }

        public FileEntry(String label, String path, boolean isGlob, String group) {
            this.label = label;
            this.path = path;
            this.glob = isGlob;
            this.group = group;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public boolean isGlob() {
            return glob;
        }

        public void setGlob(boolean glob) {
            this.glob = glob;
        }

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }
    }

    public static List<FileEntry> getDefaults() {
        List<FileEntry> entries = new ArrayList<>();

        // Project-level config files
        entries.add(new FileEntry("TODO", "TODO.md", false, "Project"));
        entries.add(new FileEntry("CLAUDE.md", "CLAUDE.md", false, "Project"));

        // Claude agent files
        entries.add(new FileEntry("Settings", ".agent-work/claude/settings.json", false, "Claude"));
        entries.add(new FileEntry("Instructions", ".copilot/copilot-instructions.md", false, "Claude"));
        entries.add(new FileEntry("Agents", ".agent-work/claude/agents/*.md", true, "Claude"));

        // Junie agent files (Junie expects .junie/ in project root)
        entries.add(new FileEntry("Guidelines", ".junie/guidelines.md", false, "Junie"));
        entries.add(new FileEntry("Agents", ".junie/AGENTS.md", false, "Junie"));

        // Kiro agent files
        entries.add(new FileEntry("Settings", ".agent-work/kiro/settings/cli.json", false, "Kiro"));
        entries.add(new FileEntry("Steering", ".agent-work/kiro/steering/*.md", true, "Kiro"));
        entries.add(new FileEntry("Agents", ".agent-work/kiro/agents/*.md", true, "Kiro"));

        // GitHub Copilot agent files
        entries.add(new FileEntry("Settings", ".agent-work/copilot/config.json", false, "Copilot"));
        entries.add(new FileEntry("Instructions", ".github/copilot-instructions.md", false, "Copilot"));
        entries.add(new FileEntry("Project Settings", ".github/copilot/settings.local.json", false, "Copilot"));
        entries.add(new FileEntry("Agents", ".agent-work/copilot/agents/*.md", true, "Copilot"));

        // OpenCode agent files
        entries.add(new FileEntry("Config", ".agent-work/opencode/opencode.json", false, "OpenCode"));
        entries.add(new FileEntry("Agents", ".agent-work/opencode/agents/*.md", true, "OpenCode"));

        // Shared agent definitions
        entries.add(new FileEntry("Agent Definitions", ".github/agents/*.md", true, "Shared"));

        return entries;
    }
}
