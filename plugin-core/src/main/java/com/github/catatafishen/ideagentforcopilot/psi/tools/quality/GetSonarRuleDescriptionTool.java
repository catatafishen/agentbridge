package com.github.catatafishen.ideagentforcopilot.psi.tools.quality;

import com.github.catatafishen.ideagentforcopilot.psi.SonarQubeIntegration;
import com.github.catatafishen.ideagentforcopilot.psi.SonarRuleDescriptions;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Fetches SonarLint rule descriptions on demand for one or more rule keys.
 * Separating descriptions from {@code run_sonarqube_analysis} keeps analysis output compact,
 * allowing 100+ findings per page without hitting transport size limits.
 */
public final class GetSonarRuleDescriptionTool extends QualityTool {

    private static final String PARAM_RULE_IDS = "rule_ids";

    public GetSonarRuleDescriptionTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "get_sonar_rule_description";
    }

    @Override
    public @NotNull String displayName() {
        return "Get SonarQube Rule Description";
    }

    @Override
    public @NotNull String description() {
        return "Get rule descriptions for one or more SonarQube rule IDs (e.g. java:S3776, kotlin:S1192). " +
            "Use after run_sonarqube_analysis to understand specific findings.";
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
        JsonObject s = schema(new Object[][]{
            {PARAM_RULE_IDS, TYPE_ARRAY, "Array of SonarQube rule IDs to look up (e.g. [\"java:S3776\", \"kotlin:S1192\"])"}
        });
        addArrayItems(s, PARAM_RULE_IDS);
        return s;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        if (!SonarQubeIntegration.isInstalled()) {
            return "Error: SonarQube for IDE plugin is not installed.";
        }

        if (!args.has(PARAM_RULE_IDS) || !args.get(PARAM_RULE_IDS).isJsonArray()) {
            return "Error: 'rule_ids' must be a non-empty JSON array of rule key strings.";
        }

        JsonArray ruleIdsJson = args.getAsJsonArray(PARAM_RULE_IDS);
        List<String> ruleIds = new ArrayList<>(ruleIdsJson.size());
        for (var element : ruleIdsJson) {
            if (element.isJsonPrimitive()) {
                ruleIds.add(element.getAsString());
            }
        }

        if (ruleIds.isEmpty()) {
            return "Error: 'rule_ids' array is empty.";
        }

        String result = SonarRuleDescriptions.buildRulesSectionForKeys(ruleIds);
        return result.isEmpty() ? "No descriptions found for the requested rule IDs." : result;
    }
}
