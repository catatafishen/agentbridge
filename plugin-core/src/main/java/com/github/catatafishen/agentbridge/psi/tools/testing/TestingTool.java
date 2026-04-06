package com.github.catatafishen.agentbridge.psi.tools.testing;

import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Abstract base for testing tools. Provides shared constants and XML parsing helpers.
 */
public abstract class TestingTool extends Tool {

    protected static final String ERROR_NO_PROJECT_PATH = "No project base path";

    protected TestingTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.TESTING;
    }

    protected static int intAttr(org.w3c.dom.Node node, String attr) {
        var item = node.getAttributes().getNamedItem(attr);
        return item != null ? Integer.parseInt(item.getNodeValue()) : 0;
    }

    @SuppressWarnings("SameParameterValue") // Utility method mirrors intAttr, kept parameterized for consistency
    protected static double doubleAttr(org.w3c.dom.Node node, String attr) {
        var item = node.getAttributes().getNamedItem(attr);
        return item != null ? Double.parseDouble(item.getNodeValue()) : 0.0;
    }
}
