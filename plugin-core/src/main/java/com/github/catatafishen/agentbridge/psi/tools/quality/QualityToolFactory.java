package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.psi.QodanaAnalyzer;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory producing all individual code quality tool instances.
 * Conditionally includes Qodana and SonarQube tools based on plugin availability.
 */
public final class QualityToolFactory {

    private static final Logger LOG = Logger.getInstance(QualityToolFactory.class);

    private QualityToolFactory() {
    }

    public static @NotNull List<Tool> create(@NotNull Project project, boolean hasSonar) {
        var tools = new ArrayList<Tool>();
        tools.add(new GetProblemsTool(project));
        tools.add(new GetHighlightsTool(project));
        tools.add(new GetAvailableActionsTool(project));
        tools.add(new GetActionOptionsTool(project));
        tools.add(new ApplyQuickfixTool(project));
        tools.add(new ApplyActionTool(project));
        tools.add(new SuppressInspectionTool(project));
        tools.add(new OptimizeImportsTool(project));
        tools.add(new FormatCodeTool(project));
        tools.add(new AddToDictionaryTool(project));
        tools.add(new GetCompilationErrorsTool(project));

        if (PlatformApiCompat.isPluginInstalled("org.jetbrains.qodana")) {
            QodanaAnalyzer qodana = new QodanaAnalyzer(project);
            tools.add(new RunQodanaTool(project, qodana));
            LOG.info("Qodana plugin detected — run_qodana tool registered");
        }

        if (hasSonar) {
            tools.add(new RunSonarQubeAnalysisTool(project));
            tools.add(new GetSonarRuleDescriptionTool(project));
        }

        return List.copyOf(tools);
    }
}
