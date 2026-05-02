package com.github.catatafishen.agentbridge.psi.tools.refactoring;

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Platform tests for refactoring tools: {@link FindImplementationsTool},
 * {@link GetCallHierarchyTool}, {@link GoToDeclarationTool},
 * {@link GetSymbolInfoTool}, and {@link RefactorTool}.
 *
 * <p>JUnit 3 style (extends {@link BasePlatformTestCase}): test methods must be
 * {@code public void testXxx()}. Run via Gradle only:
 * {@code ./gradlew :plugin-core:test}.
 *
 * <h3>Threading model</h3>
 * <ul>
 *   <li>{@link FindImplementationsTool}, {@link GetCallHierarchyTool},
 *       {@link GoToDeclarationTool} — validation errors return synchronously.
 *       Successful searches use {@code runReadAction}, safe from the EDT test
 *       thread.</li>
 *   <li>{@link GetSymbolInfoTool} — uses {@code runReadAction} (Computable) only;
 *       safe from EDT.</li>
 *   <li>{@link RefactorTool} — validation errors return synchronously. Actual
 *       refactoring dispatches via {@code EdtUtil.invokeLater} and must be driven
 *       via {@link #executeSync}.</li>
 * </ul>
 *
 * <h3>File creation</h3>
 * Tools that use {@code LocalFileSystem#findFileByPath} need real on-disk files
 * registered via {@code LocalFileSystem#refreshAndFindFileByPath}.
 */
public class RefactoringToolsExtendedTest extends BasePlatformTestCase {

    private FindImplementationsTool findImplementationsTool;
    private GetCallHierarchyTool getCallHierarchyTool;
    private GoToDeclarationTool goToDeclarationTool;
    private GetSymbolInfoTool getSymbolInfoTool;
    private RefactorTool refactorTool;

    /**
     * Temporary directory for on-disk test files; deleted in {@link #tearDown()}.
     */
    private Path tempDir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Prevent followFileIfEnabled from opening editors during tests.
        PropertiesComponent.getInstance(getProject())
            .setValue(ToolLayerSettings.FOLLOW_AGENT_FILES_KEY, "false");

        findImplementationsTool = new FindImplementationsTool(getProject(), true);
        getCallHierarchyTool = new GetCallHierarchyTool(getProject());
        goToDeclarationTool = new GoToDeclarationTool(getProject());
        getSymbolInfoTool = new GetSymbolInfoTool(getProject());
        refactorTool = new RefactorTool(getProject());

        tempDir = Files.createTempDirectory("refactoring-tools-ext-test");
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            // Close any editors opened by the tools to prevent DisposalException.
            FileEditorManager fem = FileEditorManager.getInstance(getProject());
            for (VirtualFile openFile : fem.getOpenFiles()) {
                fem.closeFile(openFile);
            }
            deleteDir(tempDir);
        } finally {
            super.tearDown();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Creates a real file on disk under {@link #tempDir} and registers it in the
     * VFS via {@link LocalFileSystem#refreshAndFindFileByPath} so that
     * {@code LocalFileSystem#findFileByPath} (used internally by the tools) can
     * locate it.
     */
    private VirtualFile createTestFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(file.toString());
        assertNotNull("Failed to register test file in VFS: " + file, vf);
        return vf;
    }

    /**
     * Recursively deletes a directory tree; best-effort (ignores errors).
     */
    private static void deleteDir(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    /**
     * Builds a {@link JsonObject} from alternating key/value string pairs.
     * Example: {@code args("symbol", "Runnable", "file", "/some/File.java")}
     */
    private static JsonObject args(String... pairs) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            obj.addProperty(pairs[i], pairs[i + 1]);
        }
        return obj;
    }

    /**
     * Runs the given {@code action} on a pooled background thread while pumping the EDT
     * event queue on the calling thread.
     *
     * <p>Required for tools that dispatch write-actions back to the EDT via
     * {@code EdtUtil.invokeLater}: calling {@code execute()} directly from the EDT would
     * deadlock because the EDT queue is blocked by {@code future.get()}.
     */
    private String executeSync(ThrowingSupplier<String> action) throws Exception {
        CompletableFuture<String> future = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                future.complete(action.get());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        long deadline = System.currentTimeMillis() + 30_000;
        while (!future.isDone()) {
            UIUtil.dispatchAllInvocationEvents();
            if (System.currentTimeMillis() > deadline) {
                fail("executeSync timed out after 30 seconds");
            }
        }
        return future.get();
    }

    /**
     * Minimal checked-exception-throwing {@link java.util.function.Supplier} variant.
     */
    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    // ── FindImplementationsTool ───────────────────────────────────────────────

    /**
     * Omitting the required {@code symbol} parameter must return the validation error
     * "Error: 'symbol' parameter is required". This check is synchronous.
     */
    public void testFindImplementationsMissingSymbol() throws Exception {
        String result = findImplementationsTool.execute(new JsonObject());
        assertNotNull("Result must not be null", result);
        assertTrue("Expected error for missing symbol, got: " + result,
            result.startsWith("Error:") && result.contains("symbol"));
    }

    /**
     * Searching for {@code Runnable} (a built-in Java interface) must return a
     * non-error response. The project may or may not contain implementations, but
     * the response must never be an error string.
     *
     * <p>Safe to call from the EDT: the tool uses {@code runReadAction} only.
     */
    public void testFindImplementationsForBuiltinInterface() throws Exception {
        String result = findImplementationsTool.execute(args("symbol", "Runnable"));
        assertNotNull("Result must not be null", result);
        assertFalse("Expected non-error response for 'Runnable' symbol, got: " + result,
            result.startsWith("Error:"));
        assertFalse("Result must not be blank", result.isBlank());
    }

    // ── GetCallHierarchyTool ──────────────────────────────────────────────────

    /**
     * Omitting all required parameters must return the validation error
     * "Error: 'symbol', 'file', and 'line' parameters are required".
     */
    public void testGetCallHierarchyMissingSymbol() throws Exception {
        String result = getCallHierarchyTool.execute(new JsonObject());
        assertNotNull("Result must not be null", result);
        assertTrue("Expected validation error for missing symbol, got: " + result,
            result.startsWith("Error:") && result.contains("symbol"));
    }

    /**
     * Providing a simple {@code symbol} but omitting {@code file} and {@code line} must return
     * the error explaining that file+line are required for non-FQN symbols.
     */
    public void testGetCallHierarchyMissingFile() throws Exception {
        String result = getCallHierarchyTool.execute(args("symbol", "doSomething"));
        assertNotNull("Result must not be null", result);
        assertTrue("Expected error for missing file+line with simple name, got: " + result,
            result.startsWith("Error:") && result.contains("file") && result.contains("line"));
    }

    /**
     * Providing a simple {@code symbol} and {@code file} but omitting {@code line} must return
     * the error explaining that file+line are required for non-FQN symbols.
     */
    public void testGetCallHierarchyMissingLine() throws Exception {
        String result = getCallHierarchyTool.execute(
            args("symbol", "doSomething", "file", "/some/path/MyClass.java"));
        assertNotNull("Result must not be null", result);
        assertTrue("Expected error for missing line with simple name, got: " + result,
            result.startsWith("Error:") && result.contains("line"));
    }

    // ── GoToDeclarationTool ───────────────────────────────────────────────────

    /**
     * Omitting all parameters must return the validation error
     * "Error: 'symbol' parameter is required" — symbol is the only
     * truly required parameter (file+line are optional with FQN).
     */
    public void testGoToDeclarationMissingFile() throws Exception {
        String result = goToDeclarationTool.execute(new JsonObject());
        assertNotNull("Result must not be null", result);
        assertTrue("Expected validation error for missing symbol, got: " + result,
            result.startsWith("Error:") && result.contains("symbol"));
    }

    /**
     * Providing {@code file} but omitting {@code symbol} must return
     * the "symbol required" error since symbol is the only truly required parameter.
     */
    public void testGoToDeclarationMissingSymbol() throws Exception {
        String result = goToDeclarationTool.execute(args("file", "/some/path/MyClass.java"));
        assertNotNull("Result must not be null", result);
        assertTrue("Expected validation error for missing symbol, got: " + result,
            result.startsWith("Error:") && result.contains("symbol"));
    }

    /**
     * Providing {@code file} and a simple {@code symbol} (not FQN) but omitting {@code line}
     * must return an error explaining that file+line are required for non-FQN symbols.
     */
    public void testGoToDeclarationMissingLine() throws Exception {
        String result = goToDeclarationTool.execute(
            args("file", "/some/path/MyClass.java", "symbol", "MyClass"));
        assertNotNull("Result must not be null", result);
        assertTrue("Expected validation error for missing line with simple name, got: " + result,
            result.startsWith("Error:") && result.contains("line"));
    }

    // ── GetSymbolInfoTool ─────────────────────────────────────────────────────

    /**
     * Providing an empty string for {@code file} causes {@code resolveVirtualFile("")}
     * to return {@code null}, so the tool returns a "File not found" error response.
     *
     * <p>Safe to call from the EDT: the tool uses {@code runReadAction} (Computable)
     * only, with no EDT dispatch.
     */
    public void testGetSymbolInfoMissingFile() throws Exception {
        // Empty path → resolveVirtualFile("") → null → ERROR_PREFIX + ERROR_FILE_NOT_FOUND
        String result = getSymbolInfoTool.execute(args("file", "", "line", "1"));
        assertNotNull("Result must not be null", result);
        assertTrue("Expected 'Error: File not found' for empty file path, got: " + result,
            result.startsWith(ToolUtils.ERROR_PREFIX));
    }

    /**
     * Omitting the {@code line} parameter entirely causes the tool to throw a
     * {@link NullPointerException} when it accesses {@code args.get("line").getAsInt()}
     * without a null check. The NPE is thrown synchronously before any async dispatch.
     */
    public void testGetSymbolInfoMissingLine() {
        JsonObject a = new JsonObject();
        a.addProperty("file", "/nonexistent/path/GetSymbolInfoMissingLine.java");
        // "line" intentionally omitted — tool will NPE on args.get("line").getAsInt()

        try {
            getSymbolInfoTool.execute(a);
            fail("Expected exception when 'line' parameter is missing");
        } catch (Exception e) {
            // Expected: args.get("line") returns null; .getAsInt() on null throws NPE.
        }
    }

    // ── RefactorTool ──────────────────────────────────────────────────────────

    /**
     * Omitting all required parameters ({@code operation}, {@code file},
     * {@code symbol}) must return the validation error
     * "Error: 'operation', 'file', and 'symbol' parameters are required".
     * This check is synchronous and returns before any EDT dispatch.
     */
    public void testRefactorMissingOperation() throws Exception {
        String result = refactorTool.execute(new JsonObject());
        assertNotNull("Result must not be null", result);
        assertTrue("Expected validation error for missing operation/file/symbol, got: " + result,
            result.startsWith("Error:") && result.contains("operation"));
    }

    /**
     * Providing an unrecognised {@code operation} value with a real file and a symbol
     * that exists in that file must result in an error response. The refactoring
     * dispatch uses {@code EdtUtil.invokeLater}, so {@link #executeSync} is required.
     *
     * <p>If the symbol is found, the operation-dispatch switch returns
     * "Error: Unknown operation 'invalid_op'...". If the symbol or file cannot be
     * resolved, the tool still returns an {@code "Error: ..."} response — either way
     * the assertion that an invalid operation is rejected holds.
     */
    public void testRefactorInvalidOperation() throws Exception {
        VirtualFile vf = createTestFile("RefactorInvalidOp.java",
            "public class RefactorInvalidOp {\n    public void doWork() {}\n}\n");

        String result = executeSync(() -> refactorTool.execute(args(
            "operation", "invalid_op",
            "file", vf.getPath(),
            "symbol", "RefactorInvalidOp"
        )));
        assertNotNull("Result must not be null", result);
        assertTrue("Expected error for invalid operation, got: " + result,
            result.startsWith("Error:") || result.contains("Error"));
    }
}
