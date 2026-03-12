package com.github.catatafishen.ideagentforcopilot.psi.tools;

/**
 * Functional categories for grouping tools in settings and the permissions UI.
 */
public enum ToolCategory {
    FILE("File Operations"),
    GIT("Git"),
    NAVIGATION("Code Navigation"),
    QUALITY("Code Quality"),
    REFACTORING("Refactoring"),
    EDITING("Symbol Editing"),
    TESTING("Testing"),
    PROJECT("Project"),
    INFRASTRUCTURE("Infrastructure"),
    TERMINAL("Terminal"),
    EDITOR("Editor"),
    OTHER("Other");

    private final String displayName;

    ToolCategory(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
