package com.github.catatafishen.agentbridge.psi.tools;

import com.github.catatafishen.agentbridge.psi.tools.database.GetSchemaTool;
import com.github.catatafishen.agentbridge.psi.tools.database.ListDataSourcesTool;
import com.github.catatafishen.agentbridge.psi.tools.database.ListTablesTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.breakpoints.BreakpointAddExceptionTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.breakpoints.BreakpointAddTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.breakpoints.BreakpointListTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.breakpoints.BreakpointRemoveTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.breakpoints.BreakpointUpdateTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.inspection.DebugEvaluateTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.inspection.DebugInspectFrameTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.inspection.DebugReadConsoleTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.inspection.DebugSnapshotTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.inspection.DebugVariableDetailTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.navigation.DebugRunToLineTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.navigation.DebugStepTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.session.DebugSessionListTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.session.DebugSessionStopTool;
import com.github.catatafishen.agentbridge.psi.tools.editing.InsertAfterSymbolTool;
import com.github.catatafishen.agentbridge.psi.tools.editing.InsertBeforeSymbolTool;
import com.github.catatafishen.agentbridge.psi.tools.editing.ReplaceSymbolBodyTool;
import com.github.catatafishen.agentbridge.psi.tools.editor.CreateScratchFileTool;
import com.github.catatafishen.agentbridge.psi.tools.editor.GetActiveFileTool;
import com.github.catatafishen.agentbridge.psi.tools.editor.GetOpenEditorsTool;
import com.github.catatafishen.agentbridge.psi.tools.editor.ListScratchFilesTool;
import com.github.catatafishen.agentbridge.psi.tools.editor.ListThemesTool;
import com.github.catatafishen.agentbridge.psi.tools.editor.OpenInEditorTool;
import com.github.catatafishen.agentbridge.psi.tools.editor.RunScratchFileTool;
import com.github.catatafishen.agentbridge.psi.tools.editor.SearchConversationHistoryTool;
import com.github.catatafishen.agentbridge.psi.tools.editor.SetThemeTool;
import com.github.catatafishen.agentbridge.psi.tools.editor.ShowDiffTool;
import com.github.catatafishen.agentbridge.psi.tools.file.CreateFileTool;
import com.github.catatafishen.agentbridge.psi.tools.file.DeleteFileTool;
import com.github.catatafishen.agentbridge.psi.tools.file.EditTextTool;
import com.github.catatafishen.agentbridge.psi.tools.file.MoveFileTool;
import com.github.catatafishen.agentbridge.psi.tools.file.ReadFileTool;
import com.github.catatafishen.agentbridge.psi.tools.file.RedoTool;
import com.github.catatafishen.agentbridge.psi.tools.file.ReloadFromDiskTool;
import com.github.catatafishen.agentbridge.psi.tools.file.RenameFileTool;
import com.github.catatafishen.agentbridge.psi.tools.file.UndoTool;
import com.github.catatafishen.agentbridge.psi.tools.file.WriteFileTool;
import com.github.catatafishen.agentbridge.psi.tools.git.GetFileHistoryTool;
import com.github.catatafishen.agentbridge.psi.tools.git.GitBlameTool;
import com.github.catatafishen.agentbridge.psi.tools.git.GitBranchTool;
import com.github.catatafishen.agentbridge.psi.tools.git.GitCherryPickTool;
import com.github.catatafishen.agentbridge.psi.tools.git.GitCommitTool;
import com.github.catatafishen.agentbridge.psi.tools.git.GitConfigTool;
import com.github.catatafishen.agentbridge.psi.tools.git.GitDiffTool;
import com.github.catatafishen.agentbridge.psi.tools.git.GitFetchTool;
import com.github.catatafishen.agentbridge.psi.tools.git.GitLogTool;
import com.github.catatafishen.agentbridge.psi.tools.git.GitMergeTool;
import com.github.catatafishen.agentbridge.psi.tools.git.GitPullTool;
import com.github.catatafishen.agentbridge.psi.tools.git.GitPushTool;
import com.github.catatafishen.agentbridge.psi.tools.git.GitRebaseTool;
import com.github.catatafishen.agentbridge.psi.tools.git.GitRemoteTool;
import com.github.catatafishen.agentbridge.psi.tools.git.GitResetTool;
import com.github.catatafishen.agentbridge.psi.tools.git.GitRevertTool;
import com.github.catatafishen.agentbridge.psi.tools.git.GitShowTool;
import com.github.catatafishen.agentbridge.psi.tools.git.GitStageTool;
import com.github.catatafishen.agentbridge.psi.tools.git.GitStashTool;
import com.github.catatafishen.agentbridge.psi.tools.git.GitStatusTool;
import com.github.catatafishen.agentbridge.psi.tools.git.GitTagTool;
import com.github.catatafishen.agentbridge.psi.tools.git.GitUnstageTool;
import com.github.catatafishen.agentbridge.psi.tools.infrastructure.AskUserTool;
import com.github.catatafishen.agentbridge.psi.tools.infrastructure.GetNotificationsTool;
import com.github.catatafishen.agentbridge.psi.tools.infrastructure.HttpRequestTool;
import com.github.catatafishen.agentbridge.psi.tools.infrastructure.InteractWithModalTool;
import com.github.catatafishen.agentbridge.psi.tools.infrastructure.ListRunTabsTool;
import com.github.catatafishen.agentbridge.psi.tools.infrastructure.ReadBuildOutputTool;
import com.github.catatafishen.agentbridge.psi.tools.infrastructure.ReadIdeLogTool;
import com.github.catatafishen.agentbridge.psi.tools.infrastructure.ReadRunOutputTool;
import com.github.catatafishen.agentbridge.psi.tools.infrastructure.RunCommandTool;
import com.github.catatafishen.agentbridge.psi.tools.navigation.FindReferencesTool;
import com.github.catatafishen.agentbridge.psi.tools.navigation.GetClassOutlineTool;
import com.github.catatafishen.agentbridge.psi.tools.navigation.GetFileOutlineTool;
import com.github.catatafishen.agentbridge.psi.tools.navigation.ListDirectoryTreeTool;
import com.github.catatafishen.agentbridge.psi.tools.navigation.ListProjectFilesTool;
import com.github.catatafishen.agentbridge.psi.tools.navigation.SearchSymbolsTool;
import com.github.catatafishen.agentbridge.psi.tools.navigation.SearchTextTool;
import com.github.catatafishen.agentbridge.psi.tools.project.BuildProjectTool;
import com.github.catatafishen.agentbridge.psi.tools.project.CreateRunConfigurationTool;
import com.github.catatafishen.agentbridge.psi.tools.project.DeleteRunConfigurationTool;
import com.github.catatafishen.agentbridge.psi.tools.project.DownloadSourcesTool;
import com.github.catatafishen.agentbridge.psi.tools.project.EditProjectStructureTool;
import com.github.catatafishen.agentbridge.psi.tools.project.EditRunConfigurationTool;
import com.github.catatafishen.agentbridge.psi.tools.project.GetIndexingStatusTool;
import com.github.catatafishen.agentbridge.psi.tools.project.GetProjectDependenciesTool;
import com.github.catatafishen.agentbridge.psi.tools.project.GetProjectInfoTool;
import com.github.catatafishen.agentbridge.psi.tools.project.GetProjectModulesTool;
import com.github.catatafishen.agentbridge.psi.tools.project.ListRunConfigurationsTool;
import com.github.catatafishen.agentbridge.psi.tools.project.MarkDirectoryTool;
import com.github.catatafishen.agentbridge.psi.tools.project.RunConfigurationTool;
import com.github.catatafishen.agentbridge.psi.tools.quality.AddToDictionaryTool;
import com.github.catatafishen.agentbridge.psi.tools.quality.ApplyActionTool;
import com.github.catatafishen.agentbridge.psi.tools.quality.ApplyQuickfixTool;
import com.github.catatafishen.agentbridge.psi.tools.quality.FormatCodeTool;
import com.github.catatafishen.agentbridge.psi.tools.quality.GetActionOptionsTool;
import com.github.catatafishen.agentbridge.psi.tools.quality.GetAvailableActionsTool;
import com.github.catatafishen.agentbridge.psi.tools.quality.GetCompilationErrorsTool;
import com.github.catatafishen.agentbridge.psi.tools.quality.GetHighlightsTool;
import com.github.catatafishen.agentbridge.psi.tools.quality.GetProblemsTool;
import com.github.catatafishen.agentbridge.psi.tools.quality.GetSonarRuleDescriptionTool;
import com.github.catatafishen.agentbridge.psi.tools.quality.OptimizeImportsTool;
import com.github.catatafishen.agentbridge.psi.tools.quality.RunSonarQubeAnalysisTool;
import com.github.catatafishen.agentbridge.psi.tools.quality.SuppressInspectionTool;
import com.github.catatafishen.agentbridge.psi.tools.refactoring.FindImplementationsTool;
import com.github.catatafishen.agentbridge.psi.tools.refactoring.FindSuperMethodsTool;
import com.github.catatafishen.agentbridge.psi.tools.refactoring.GetCallHierarchyTool;
import com.github.catatafishen.agentbridge.psi.tools.refactoring.GetDocumentationTool;
import com.github.catatafishen.agentbridge.psi.tools.refactoring.GetSymbolInfoTool;
import com.github.catatafishen.agentbridge.psi.tools.refactoring.GetTypeHierarchyTool;
import com.github.catatafishen.agentbridge.psi.tools.refactoring.GoToDeclarationTool;
import com.github.catatafishen.agentbridge.psi.tools.refactoring.RefactorTool;
import com.github.catatafishen.agentbridge.psi.tools.terminal.ListTerminalsTool;
import com.github.catatafishen.agentbridge.psi.tools.terminal.ReadTerminalOutputTool;
import com.github.catatafishen.agentbridge.psi.tools.terminal.RunInTerminalTool;
import com.github.catatafishen.agentbridge.psi.tools.terminal.WriteTerminalInputTool;
import com.github.catatafishen.agentbridge.psi.tools.testing.GetCoverageTool;
import com.github.catatafishen.agentbridge.psi.tools.testing.ListTestsTool;
import com.github.catatafishen.agentbridge.psi.tools.testing.RunTestsTool;
import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for all concrete {@link ToolDefinition} implementations.
 * <p>
 * Verifies that every tool satisfies the metadata contract required by the MCP
 * protocol and the AgentBridge UI: non-empty id/displayName/description, valid Kind,
 * non-null category, and a well-formed input schema. Each tool is instantiated with a
 * {@code null} project — safe because metadata methods never dereference {@code project}.
 * <p>
 * RunConfig tools accept an extra {@code RunConfigurationService} param; null is passed
 * since metadata methods don't use it.
 */
@DisplayName("ToolDefinition contract — all tools")
class ToolDefinitionContractTest {

    // ── Git tools ─────────────────────────────────────────────────────────────

    static Stream<Tool> gitTools() {
        return Stream.of(
            new GitStatusTool(null),
            new GitDiffTool(null),
            new GitLogTool(null),
            new GitBlameTool(null),
            new GitShowTool(null),
            new GetFileHistoryTool(null),
            new GitRemoteTool(null),
            new GitConfigTool(null),
            new GitCommitTool(null),
            new GitStageTool(null),
            new GitUnstageTool(null),
            new GitBranchTool(null),
            new GitStashTool(null),
            new GitRevertTool(null),
            new GitTagTool(null),
            new GitPushTool(null),
            new GitResetTool(null),
            new GitRebaseTool(null),
            new GitFetchTool(null),
            new GitPullTool(null),
            new GitMergeTool(null),
            new GitCherryPickTool(null)
        );
    }

    // ── File tools ────────────────────────────────────────────────────────────

    static Stream<Tool> fileTools() {
        return Stream.of(
            new ReadFileTool(null),
            new WriteFileTool(null),
            new EditTextTool(null),
            new CreateFileTool(null),
            new DeleteFileTool(null),
            new RenameFileTool(null),
            new MoveFileTool(null),
            new UndoTool(null),
            new RedoTool(null),
            new ReloadFromDiskTool(null)
        );
    }

    // ── Editing tools ─────────────────────────────────────────────────────────

    static Stream<Tool> editingTools() {
        return Stream.of(
            new ReplaceSymbolBodyTool(null),
            new InsertBeforeSymbolTool(null),
            new InsertAfterSymbolTool(null)
        );
    }

    // ── Debug tools ───────────────────────────────────────────────────────────

    static Stream<Tool> debugTools() {
        return Stream.of(
            new BreakpointListTool(null),
            new BreakpointAddTool(null),
            new BreakpointAddExceptionTool(null),
            new BreakpointUpdateTool(null),
            new BreakpointRemoveTool(null),
            new DebugSessionListTool(null),
            new DebugSessionStopTool(null),
            new DebugStepTool(null),
            new DebugRunToLineTool(null),
            new DebugSnapshotTool(null),
            new DebugVariableDetailTool(null),
            new DebugInspectFrameTool(null),
            new DebugEvaluateTool(null),
            new DebugReadConsoleTool(null)
        );
    }

    // ── Editor tools ──────────────────────────────────────────────────────────

    static Stream<Tool> editorTools() {
        return Stream.of(
            new OpenInEditorTool(null),
            new ShowDiffTool(null),
            new CreateScratchFileTool(null),
            new ListScratchFilesTool(null),
            new RunScratchFileTool(null),
            new GetActiveFileTool(null),
            new GetOpenEditorsTool(null),
            new ListThemesTool(null),
            new SetThemeTool(null),
            new SearchConversationHistoryTool(null)
        );
    }

    // ── Navigation tools ──────────────────────────────────────────────────────

    static Stream<Tool> navigationTools() {
        return Stream.of(
            new ListProjectFilesTool(null),
            new GetFileOutlineTool(null),
            new SearchSymbolsTool(null),
            new FindReferencesTool(null),
            new SearchTextTool(null),
            new GetClassOutlineTool(null),
            new ListDirectoryTreeTool(null)
        );
    }

    // ── Project tools ─────────────────────────────────────────────────────────

    static Stream<Tool> projectTools() {
        return Stream.of(
            new GetProjectInfoTool(null),
            new BuildProjectTool(null),
            new GetIndexingStatusTool(null),
            new DownloadSourcesTool(null),
            new MarkDirectoryTool(null),
            new EditProjectStructureTool(null),
            new ListRunConfigurationsTool(null, null),
            new RunConfigurationTool(null, null),
            new CreateRunConfigurationTool(null, null),
            new EditRunConfigurationTool(null, null),
            new DeleteRunConfigurationTool(null, null),
            new GetProjectModulesTool(null),
            new GetProjectDependenciesTool(null)
        );
    }

    // ── Quality tools ─────────────────────────────────────────────────────────

    static Stream<Tool> qualityTools() {
        return Stream.of(
            new GetProblemsTool(null),
            new GetHighlightsTool(null),
            new GetAvailableActionsTool(null),
            new GetActionOptionsTool(null),
            new ApplyQuickfixTool(null),
            new ApplyActionTool(null),
            new SuppressInspectionTool(null),
            new OptimizeImportsTool(null),
            new FormatCodeTool(null),
            new AddToDictionaryTool(null),
            new GetCompilationErrorsTool(null),
            new RunSonarQubeAnalysisTool(null),
            new GetSonarRuleDescriptionTool(null)
        );
    }

    // ── Refactoring tools ─────────────────────────────────────────────────────

    static Stream<Tool> refactoringTools() {
        return Stream.of(
            new RefactorTool(null),
            new GoToDeclarationTool(null),
            new GetTypeHierarchyTool(null, true),
            new FindImplementationsTool(null, true),
            new FindSuperMethodsTool(null, true),
            new GetCallHierarchyTool(null),
            new GetDocumentationTool(null),
            new GetSymbolInfoTool(null)
        );
    }

    // ── Terminal tools ────────────────────────────────────────────────────────

    static Stream<Tool> terminalTools() {
        return Stream.of(
            new RunInTerminalTool(null),
            new WriteTerminalInputTool(null),
            new ReadTerminalOutputTool(null),
            new ListTerminalsTool(null)
        );
    }

    // ── Testing tools ─────────────────────────────────────────────────────────

    static Stream<Tool> testingTools() {
        return Stream.of(
            new ListTestsTool(null),
            new RunTestsTool(null),
            new GetCoverageTool(null)
        );
    }

    // ── Database tools ────────────────────────────────────────────────────────

    static Stream<Tool> databaseTools() {
        return Stream.of(
            new ListDataSourcesTool(null),
            new ListTablesTool(null),
            new GetSchemaTool(null)
        );
    }

    // ── Infrastructure tools ──────────────────────────────────────────────────

    static Stream<Tool> infrastructureTools() {
        return Stream.of(
            new AskUserTool(null),
            new HttpRequestTool(null),
            new RunCommandTool(null),
            new ReadIdeLogTool(null),
            new GetNotificationsTool(null),
            new ListRunTabsTool(null),
            new ReadRunOutputTool(null),
            new ReadBuildOutputTool(null),
            new InteractWithModalTool(null)
        );
    }

    // ── Combined stream for all tools ─────────────────────────────────────────

    static Stream<Tool> allTools() {
        List<Stream<Tool>> streams = List.of(
            gitTools(), fileTools(), editingTools(), debugTools(), editorTools(),
            navigationTools(), projectTools(), qualityTools(), refactoringTools(),
            terminalTools(), testingTools(), databaseTools(), infrastructureTools()
        );
        return streams.stream().flatMap(s -> s);
    }

    // ── Assertions ────────────────────────────────────────────────────────────

    private static void assertValidSchema(JsonObject schema, String toolId) {
        assertNotNull(schema, toolId + ": inputSchema() returned null");
        assertEquals("object", schema.get("type").getAsString(),
            toolId + ": schema root type must be 'object'");
        assertTrue(schema.has("properties"),
            toolId + ": schema must have 'properties'");
        assertTrue(schema.get("properties").isJsonObject(),
            toolId + ": 'properties' must be a JsonObject");
        assertTrue(schema.has("required"),
            toolId + ": schema must have 'required'");
        assertTrue(schema.get("required").isJsonArray(),
            toolId + ": 'required' must be a JsonArray");

        JsonObject props = schema.getAsJsonObject("properties");
        JsonArray required = schema.getAsJsonArray("required");

        // Every property must have type and description
        for (String propName : props.keySet()) {
            JsonObject prop = props.getAsJsonObject(propName);
            assertTrue(prop.has("type"),
                toolId + ": property '" + propName + "' is missing 'type'");
            assertTrue(prop.has("description"),
                toolId + ": property '" + propName + "' is missing 'description'");
            assertFalse(prop.get("description").getAsString().isBlank(),
                toolId + ": property '" + propName + "' has blank description");
        }

        // Every required param must exist in properties
        for (var reqEl : required) {
            String reqName = reqEl.getAsString();
            assertTrue(props.has(reqName),
                toolId + ": required param '" + reqName + "' not found in properties");
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} — id")
    @MethodSource("allTools")
    @DisplayName("id() is non-empty and URL-safe")
    void toolIdIsNonEmpty(Tool tool) {
        String id = tool.id();
        assertNotNull(id);
        assertFalse(id.isBlank(), tool.getClass().getSimpleName() + ": id() is blank");
        assertTrue(id.matches("[a-z][a-z0-9_]*"),
            tool.getClass().getSimpleName() + ": id() '" + id + "' must be lowercase snake_case");
    }

    @ParameterizedTest(name = "{0} — displayName")
    @MethodSource("allTools")
    @DisplayName("displayName() is non-empty")
    void toolDisplayNameIsNonEmpty(Tool tool) {
        String name = tool.displayName();
        assertNotNull(name);
        assertFalse(name.isBlank(),
            tool.getClass().getSimpleName() + ": displayName() is blank");
    }

    @ParameterizedTest(name = "{0} — description")
    @MethodSource("allTools")
    @DisplayName("description() is non-empty and at least 10 characters")
    void toolDescriptionIsNonEmpty(Tool tool) {
        String desc = tool.description();
        assertNotNull(desc);
        assertFalse(desc.isBlank(),
            tool.getClass().getSimpleName() + ": description() is blank");
        assertTrue(desc.length() >= 10,
            tool.getClass().getSimpleName() + ": description() too short: '" + desc + "'");
    }

    @ParameterizedTest(name = "{0} — kind")
    @MethodSource("allTools")
    @DisplayName("kind() returns a valid non-null Kind")
    void toolKindIsValid(Tool tool) {
        assertNotNull(tool.kind(),
            tool.getClass().getSimpleName() + ": kind() returned null");
    }

    @ParameterizedTest(name = "{0} — category")
    @MethodSource("allTools")
    @DisplayName("category() returns a valid non-null Category")
    void toolCategoryIsValid(Tool tool) {
        assertNotNull(tool.category(),
            tool.getClass().getSimpleName() + ": category() returned null");
    }

    @ParameterizedTest(name = "{0} — inputSchema")
    @MethodSource("allTools")
    @DisplayName("inputSchema() returns a well-formed JSON Schema object")
    void toolSchemaIsValid(Tool tool) {
        assertValidSchema(tool.inputSchema(), tool.id());
    }

    @ParameterizedTest(name = "{0} — mcpAnnotations")
    @MethodSource("allTools")
    @DisplayName("mcpAnnotations() is consistent with isReadOnly/isDestructive/isIdempotent")
    void toolMcpAnnotationsAreConsistent(Tool tool) {
        JsonObject ann = tool.mcpAnnotations();
        assertNotNull(ann);
        assertEquals(tool.isReadOnly(), ann.get("readOnlyHint").getAsBoolean(),
            tool.id() + ": readOnlyHint mismatch");
        assertEquals(tool.isDestructive(), ann.get("destructiveHint").getAsBoolean(),
            tool.id() + ": destructiveHint mismatch");
        assertEquals(tool.isIdempotent(), ann.get("idempotentHint").getAsBoolean(),
            tool.id() + ": idempotentHint mismatch");
    }

    @ParameterizedTest(name = "{0} — hasExecutionHandler")
    @MethodSource("allTools")
    @DisplayName("hasExecutionHandler() returns true (all concrete tools have execute())")
    void toolHasExecutionHandler(Tool tool) {
        assertTrue(tool.hasExecutionHandler(),
            tool.id() + ": hasExecutionHandler() returned false");
    }

    @ParameterizedTest(name = "{0} — readOnly implies not destructive")
    @MethodSource("allTools")
    @DisplayName("read-only tools must not be destructive")
    void readOnlyToolsAreNotDestructive(Tool tool) {
        if (tool.isReadOnly()) {
            assertFalse(tool.isDestructive(),
                tool.id() + ": isReadOnly() and isDestructive() are both true");
        }
    }

    @ParameterizedTest(name = "{0} — needsWriteLock")
    @MethodSource("allTools")
    @DisplayName("read-only tools must not need write lock")
    void readOnlyToolsDoNotNeedWriteLock(Tool tool) {
        if (tool.isReadOnly()) {
            assertFalse(tool.needsWriteLock(),
                tool.id() + ": isReadOnly() but needsWriteLock() is true");
        }
    }

    @ParameterizedTest(name = "{0} — resultRenderer")
    @MethodSource("allTools")
    @DisplayName("resultRenderer() does not throw")
    void toolResultRendererDoesNotThrow(Tool tool) {
        // resultRenderer() is @Nullable — null means the default rendering is used.
        assertDoesNotThrow(tool::resultRenderer,
            tool.id() + ": resultRenderer() must not throw");
    }

    @ParameterizedTest(name = "{0} — permissionTemplate")
    @MethodSource("allTools")
    @DisplayName("permissionTemplate() is null or a non-blank string")
    void toolPermissionTemplateIsNullOrNonBlank(Tool tool) {
        String template = tool.permissionTemplate();
        if (template != null) {
            assertFalse(template.isBlank(),
                tool.id() + ": permissionTemplate() returned a blank string");
        }
    }
}
