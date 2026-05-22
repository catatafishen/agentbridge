package com.github.catatafishen.agentbridge.psi.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprehensive MCP schema validation test. Validates that every production tool's
 * JSON Schema conforms to the requirements enforced by OpenAI/Copilot backends.
 *
 * <p>This test catches issues like:
 * <ul>
 *   <li>Array properties without {@code items} (the bug that caused issue #416)</li>
 *   <li>Properties listed in {@code required} but missing from {@code properties}</li>
 *   <li>Properties without {@code type} or {@code description}</li>
 *   <li>Invalid type values</li>
 * </ul>
 *
 * <p>Runs as a {@code @TestFactory} so each tool appears as a separate test case in reports.
 * When adding a new tool, add its fully-qualified class name to {@link #ALL_TOOL_CLASSES}.
 */
class McpSchemaValidationTest {

    private static final Set<String> VALID_JSON_SCHEMA_TYPES = Set.of(
        "string", "integer", "number", "boolean", "array", "object"
    );

    /**
     * All production tool classes. Mirrors the factories in PsiBridgeService.
     * Keep sorted by package for readability.
     */
    private static final String[] ALL_TOOL_CLASSES = {
        // debug
        "com.github.catatafishen.agentbridge.psi.tools.debug.breakpoints.BreakpointListTool",
        "com.github.catatafishen.agentbridge.psi.tools.debug.breakpoints.BreakpointAddTool",
        "com.github.catatafishen.agentbridge.psi.tools.debug.breakpoints.BreakpointAddExceptionTool",
        "com.github.catatafishen.agentbridge.psi.tools.debug.breakpoints.BreakpointUpdateTool",
        "com.github.catatafishen.agentbridge.psi.tools.debug.breakpoints.BreakpointRemoveTool",
        "com.github.catatafishen.agentbridge.psi.tools.debug.session.DebugSessionListTool",
        "com.github.catatafishen.agentbridge.psi.tools.debug.session.DebugSessionStopTool",
        "com.github.catatafishen.agentbridge.psi.tools.debug.navigation.DebugStepTool",
        "com.github.catatafishen.agentbridge.psi.tools.debug.navigation.DebugRunToLineTool",
        "com.github.catatafishen.agentbridge.psi.tools.debug.inspection.DebugSnapshotTool",
        "com.github.catatafishen.agentbridge.psi.tools.debug.inspection.DebugVariableDetailTool",
        "com.github.catatafishen.agentbridge.psi.tools.debug.inspection.DebugInspectFrameTool",
        "com.github.catatafishen.agentbridge.psi.tools.debug.inspection.DebugEvaluateTool",
        "com.github.catatafishen.agentbridge.psi.tools.debug.inspection.DebugReadConsoleTool",
        // editing
        "com.github.catatafishen.agentbridge.psi.tools.editing.ReplaceSymbolBodyTool",
        "com.github.catatafishen.agentbridge.psi.tools.editing.InsertBeforeSymbolTool",
        "com.github.catatafishen.agentbridge.psi.tools.editing.InsertAfterSymbolTool",
        // editor
        "com.github.catatafishen.agentbridge.psi.tools.editor.OpenInEditorTool",
        "com.github.catatafishen.agentbridge.psi.tools.editor.ShowDiffTool",
        "com.github.catatafishen.agentbridge.psi.tools.editor.CreateScratchFileTool",
        "com.github.catatafishen.agentbridge.psi.tools.editor.ListScratchFilesTool",
        "com.github.catatafishen.agentbridge.psi.tools.editor.RunScratchFileTool",
        "com.github.catatafishen.agentbridge.psi.tools.editor.GetChatHtmlTool",
        "com.github.catatafishen.agentbridge.psi.tools.editor.GetActiveFileTool",
        "com.github.catatafishen.agentbridge.psi.tools.editor.GetOpenEditorsTool",
        "com.github.catatafishen.agentbridge.psi.tools.editor.ListThemesTool",
        "com.github.catatafishen.agentbridge.psi.tools.editor.SetThemeTool",
        "com.github.catatafishen.agentbridge.psi.tools.editor.QueryTurnsTool",
        // file
        "com.github.catatafishen.agentbridge.psi.tools.file.ReadFileTool",
        "com.github.catatafishen.agentbridge.psi.tools.file.WriteFileTool",
        "com.github.catatafishen.agentbridge.psi.tools.file.EditTextTool",
        "com.github.catatafishen.agentbridge.psi.tools.file.CreateFileTool",
        "com.github.catatafishen.agentbridge.psi.tools.file.DeleteFileTool",
        "com.github.catatafishen.agentbridge.psi.tools.file.RenameFileTool",
        "com.github.catatafishen.agentbridge.psi.tools.file.MoveFileTool",
        "com.github.catatafishen.agentbridge.psi.tools.file.UndoTool",
        "com.github.catatafishen.agentbridge.psi.tools.file.RedoTool",
        "com.github.catatafishen.agentbridge.psi.tools.file.ReloadFromDiskTool",
        // git
        "com.github.catatafishen.agentbridge.psi.tools.git.GitStatusTool",
        "com.github.catatafishen.agentbridge.psi.tools.git.GitDiffTool",
        "com.github.catatafishen.agentbridge.psi.tools.git.GitLogTool",
        "com.github.catatafishen.agentbridge.psi.tools.git.GitBlameTool",
        "com.github.catatafishen.agentbridge.psi.tools.git.GitShowTool",
        "com.github.catatafishen.agentbridge.psi.tools.git.GetFileHistoryTool",
        "com.github.catatafishen.agentbridge.psi.tools.git.GitRemoteTool",
        "com.github.catatafishen.agentbridge.psi.tools.git.GitConfigTool",
        "com.github.catatafishen.agentbridge.psi.tools.git.GitCommitTool",
        "com.github.catatafishen.agentbridge.psi.tools.git.GitStageTool",
        "com.github.catatafishen.agentbridge.psi.tools.git.GitUnstageTool",
        "com.github.catatafishen.agentbridge.psi.tools.git.GitBranchTool",
        "com.github.catatafishen.agentbridge.psi.tools.git.GitStashTool",
        "com.github.catatafishen.agentbridge.psi.tools.git.GitRevertTool",
        "com.github.catatafishen.agentbridge.psi.tools.git.GitTagTool",
        "com.github.catatafishen.agentbridge.psi.tools.git.GitPushTool",
        "com.github.catatafishen.agentbridge.psi.tools.git.GitResetTool",
        "com.github.catatafishen.agentbridge.psi.tools.git.GitRebaseTool",
        "com.github.catatafishen.agentbridge.psi.tools.git.GitFetchTool",
        "com.github.catatafishen.agentbridge.psi.tools.git.GitPullTool",
        "com.github.catatafishen.agentbridge.psi.tools.git.GitMergeTool",
        "com.github.catatafishen.agentbridge.psi.tools.git.GitCherryPickTool",
        "com.github.catatafishen.agentbridge.psi.tools.git.GitConflictsTool",
        "com.github.catatafishen.agentbridge.psi.tools.git.GitConflictShowTool",
        "com.github.catatafishen.agentbridge.psi.tools.git.GitConflictResolveTool",
        // infrastructure
        "com.github.catatafishen.agentbridge.psi.tools.infrastructure.PromptUserTool",
        "com.github.catatafishen.agentbridge.psi.tools.infrastructure.HttpRequestTool",
        "com.github.catatafishen.agentbridge.psi.tools.infrastructure.RunCommandTool",
        "com.github.catatafishen.agentbridge.psi.tools.infrastructure.ReadIdeLogTool",
        "com.github.catatafishen.agentbridge.psi.tools.infrastructure.GetNotificationsTool",
        "com.github.catatafishen.agentbridge.psi.tools.infrastructure.ListRunTabsTool",
        "com.github.catatafishen.agentbridge.psi.tools.infrastructure.ReadRunOutputTool",
        "com.github.catatafishen.agentbridge.psi.tools.infrastructure.ReadBuildOutputTool",
        "com.github.catatafishen.agentbridge.psi.tools.infrastructure.InteractWithModalTool",
        // memory
        "com.github.catatafishen.agentbridge.psi.tools.memory.MemorySearchTool",
        "com.github.catatafishen.agentbridge.psi.tools.memory.MemoryStoreTool",
        "com.github.catatafishen.agentbridge.psi.tools.memory.MemoryStatusTool",
        "com.github.catatafishen.agentbridge.psi.tools.memory.MemoryWakeUpTool",
        "com.github.catatafishen.agentbridge.psi.tools.memory.MemoryRecallTool",
        "com.github.catatafishen.agentbridge.psi.tools.memory.MemoryKgQueryTool",
        "com.github.catatafishen.agentbridge.psi.tools.memory.MemoryKgAddTool",
        "com.github.catatafishen.agentbridge.psi.tools.memory.MemoryKgInvalidateTool",
        "com.github.catatafishen.agentbridge.psi.tools.memory.MemoryKgTimelineTool",
        // navigation
        "com.github.catatafishen.agentbridge.psi.tools.navigation.ListProjectFilesTool",
        "com.github.catatafishen.agentbridge.psi.tools.navigation.FindFileTool",
        "com.github.catatafishen.agentbridge.psi.tools.navigation.GetFileOutlineTool",
        "com.github.catatafishen.agentbridge.psi.tools.navigation.SearchSymbolsTool",
        "com.github.catatafishen.agentbridge.psi.tools.navigation.FindReferencesTool",
        "com.github.catatafishen.agentbridge.psi.tools.navigation.SearchTextTool",
        "com.github.catatafishen.agentbridge.psi.tools.navigation.GetClassOutlineTool",
        "com.github.catatafishen.agentbridge.psi.tools.navigation.ListDirectoryTreeTool",
        // project
        "com.github.catatafishen.agentbridge.psi.tools.project.GetProjectInfoTool",
        "com.github.catatafishen.agentbridge.psi.tools.project.BuildProjectTool",
        "com.github.catatafishen.agentbridge.psi.tools.project.GetIndexingStatusTool",
        "com.github.catatafishen.agentbridge.psi.tools.project.DownloadSourcesTool",
        "com.github.catatafishen.agentbridge.psi.tools.project.MarkDirectoryTool",
        "com.github.catatafishen.agentbridge.psi.tools.project.EditProjectStructureTool",
        "com.github.catatafishen.agentbridge.psi.tools.project.ListRunConfigurationsTool",
        "com.github.catatafishen.agentbridge.psi.tools.project.RunConfigurationTool",
        "com.github.catatafishen.agentbridge.psi.tools.project.CreateRunConfigurationTool",
        "com.github.catatafishen.agentbridge.psi.tools.project.EditRunConfigurationTool",
        "com.github.catatafishen.agentbridge.psi.tools.project.DeleteRunConfigurationTool",
        "com.github.catatafishen.agentbridge.psi.tools.project.GetProjectModulesTool",
        "com.github.catatafishen.agentbridge.psi.tools.project.GetProjectDependenciesTool",
        // quality
        "com.github.catatafishen.agentbridge.psi.tools.quality.GetProblemsTool",
        "com.github.catatafishen.agentbridge.psi.tools.quality.GetHighlightsTool",
        "com.github.catatafishen.agentbridge.psi.tools.quality.GetAvailableActionsTool",
        "com.github.catatafishen.agentbridge.psi.tools.quality.GetActionOptionsTool",
        "com.github.catatafishen.agentbridge.psi.tools.quality.ApplyQuickfixTool",
        "com.github.catatafishen.agentbridge.psi.tools.quality.ApplyActionTool",
        "com.github.catatafishen.agentbridge.psi.tools.quality.SuppressInspectionTool",
        "com.github.catatafishen.agentbridge.psi.tools.quality.OptimizeImportsTool",
        "com.github.catatafishen.agentbridge.psi.tools.quality.FormatCodeTool",
        "com.github.catatafishen.agentbridge.psi.tools.quality.AddToDictionaryTool",
        "com.github.catatafishen.agentbridge.psi.tools.quality.GetCompilationErrorsTool",
        "com.github.catatafishen.agentbridge.psi.tools.quality.PopupRespondTool",
        "com.github.catatafishen.agentbridge.psi.tools.quality.RunQodanaTool",
        "com.github.catatafishen.agentbridge.psi.tools.quality.RunSonarQubeAnalysisTool",
        "com.github.catatafishen.agentbridge.psi.tools.quality.GetSonarRuleDescriptionTool",
        // refactoring
        "com.github.catatafishen.agentbridge.psi.tools.refactoring.RefactorTool",
        "com.github.catatafishen.agentbridge.psi.tools.refactoring.GoToDeclarationTool",
        "com.github.catatafishen.agentbridge.psi.tools.refactoring.GetTypeHierarchyTool",
        "com.github.catatafishen.agentbridge.psi.tools.refactoring.FindImplementationsTool",
        "com.github.catatafishen.agentbridge.psi.tools.refactoring.FindSuperMethodsTool",
        "com.github.catatafishen.agentbridge.psi.tools.refactoring.GetCallHierarchyTool",
        "com.github.catatafishen.agentbridge.psi.tools.refactoring.GetDocumentationTool",
        "com.github.catatafishen.agentbridge.psi.tools.refactoring.GetSymbolInfoTool",
        "com.github.catatafishen.agentbridge.psi.tools.refactoring.ConvertJavaToKotlinTool",
        // terminal
        "com.github.catatafishen.agentbridge.psi.tools.terminal.RunInTerminalTool",
        "com.github.catatafishen.agentbridge.psi.tools.terminal.WriteTerminalInputTool",
        "com.github.catatafishen.agentbridge.psi.tools.terminal.ReadTerminalOutputTool",
        "com.github.catatafishen.agentbridge.psi.tools.terminal.ListTerminalsTool",
        // testing
        "com.github.catatafishen.agentbridge.psi.tools.testing.ListTestsTool",
        "com.github.catatafishen.agentbridge.psi.tools.testing.RunTestsTool",
        "com.github.catatafishen.agentbridge.psi.tools.testing.GetCoverageTool",
    };

    /**
     * Tools whose constructors have instrumented @NotNull checks that prevent null-arg
     * instantiation. Their schemas are still indirectly validated via ToolSchemaBuilderTest.
     */
    private static final Set<String> KNOWN_UNINSTANTIABLE = Set.of(
        "PopupRespondTool", "GetTypeHierarchyTool", "FindImplementationsTool",
        "FindSuperMethodsTool", "ConvertJavaToKotlinTool"
    );

    @TestFactory
    Collection<DynamicTest> allToolSchemasAreValid() {
        List<DynamicTest> tests = new ArrayList<>();
        List<String> unexpectedFailures = new ArrayList<>();

        for (String className : ALL_TOOL_CLASSES) {
            String simpleName = className.substring(className.lastIndexOf('.') + 1);
            Tool tool = loadAndInstantiate(className);
            if (tool == null) {
                if (!KNOWN_UNINSTANTIABLE.contains(simpleName)) {
                    unexpectedFailures.add(simpleName);
                }
                continue;
            }
            tests.add(DynamicTest.dynamicTest(
                "Schema: " + tool.id(),
                () -> validateToolSchema(tool)
            ));
        }

        assertTrue(unexpectedFailures.isEmpty(),
            "Unexpected instantiation failures (not in KNOWN_UNINSTANTIABLE): " + unexpectedFailures);
        assertTrue(tests.size() >= 100,
            "Expected 100+ validated tools but got " + tests.size() + " — update ALL_TOOL_CLASSES");

        return tests;
    }

    private void validateToolSchema(Tool tool) {
        JsonObject schema = tool.inputSchema();
        assertNotNull(schema, tool.id() + " inputSchema() returned null");

        String ctx = tool.id();
        validateRootStructure(schema, ctx);
        validateProperties(schema, ctx);
        validateRequiredArray(schema, ctx);
    }

    private void validateRootStructure(JsonObject schema, String ctx) {
        assertTrue(schema.has("type"), ctx + ": schema missing 'type'");
        assertEquals("object", schema.get("type").getAsString(),
            ctx + ": schema root type must be 'object'");
        assertTrue(schema.has("properties"), ctx + ": schema missing 'properties'");
        assertTrue(schema.get("properties").isJsonObject(),
            ctx + ": 'properties' must be a JSON object");
        assertTrue(schema.has("required"), ctx + ": schema missing 'required'");
        assertTrue(schema.get("required").isJsonArray(),
            ctx + ": 'required' must be a JSON array");
    }

    private void validateProperties(JsonObject schema, String ctx) {
        JsonObject properties = schema.getAsJsonObject("properties");
        for (var entry : properties.entrySet()) {
            String propName = entry.getKey();
            String propCtx = ctx + "." + propName;

            assertTrue(entry.getValue().isJsonObject(),
                propCtx + ": property value must be a JSON object");
            JsonObject prop = entry.getValue().getAsJsonObject();

            // Every property must have a type
            assertTrue(prop.has("type"), propCtx + ": missing 'type'");
            String type = prop.get("type").getAsString();
            assertFalse(type.isBlank(), propCtx + ": 'type' must not be blank");
            assertTrue(VALID_JSON_SCHEMA_TYPES.contains(type),
                propCtx + ": invalid type '" + type + "', must be one of " + VALID_JSON_SCHEMA_TYPES);

            // Every property must have a description
            assertTrue(prop.has("description"), propCtx + ": missing 'description'");
            assertFalse(prop.get("description").getAsString().isBlank(),
                propCtx + ": 'description' must not be blank");

            // Array properties MUST have 'items' (OpenAI/Copilot rejects schemas without it)
            if ("array".equals(type)) {
                assertTrue(prop.has("items"),
                    propCtx + ": array property MUST have 'items' — see issue #416");
                assertTrue(prop.get("items").isJsonObject(),
                    propCtx + ": 'items' must be a JSON object");
                JsonObject items = prop.getAsJsonObject("items");
                assertTrue(items.has("type"),
                    propCtx + ".items: must have 'type'");
            }

            // Object properties should have additionalProperties or properties
            if ("object".equals(type)) {
                boolean hasAdditionalProps = prop.has("additionalProperties");
                boolean hasProperties = prop.has("properties");
                assertTrue(hasAdditionalProps || hasProperties,
                    propCtx + ": object property must have 'additionalProperties' or 'properties'");
            }
        }
    }

    private void validateRequiredArray(JsonObject schema, String ctx) {
        JsonArray required = schema.getAsJsonArray("required");
        JsonObject properties = schema.getAsJsonObject("properties");

        for (JsonElement elem : required) {
            String reqName = elem.getAsString();
            assertTrue(properties.has(reqName),
                ctx + ": required field '" + reqName + "' not found in properties");
        }
    }

    /**
     * Load and instantiate a Tool class by name. Passes null for all constructor parameters
     * since {@code inputSchema()} is a pure method that doesn't use project state.
     */
    private Tool loadAndInstantiate(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
                try {
                    ctor.setAccessible(true);
                    Object[] args = new Object[ctor.getParameterCount()];
                    return (Tool) ctor.newInstance(args);
                } catch (Exception ignored) {
                    // Try next constructor if this one fails
                }
            }
        } catch (ClassNotFoundException e) {
            return null;
        }
        return null;
    }
}
