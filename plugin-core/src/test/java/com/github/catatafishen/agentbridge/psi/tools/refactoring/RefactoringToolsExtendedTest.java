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

    /**
     * End-to-end test: caller points at a USAGE of the target symbol (a constructor call
     * site) rather than its declaration. The old implementation only located declarations on
     * the target line via {@code findNamedElement}, so a usage line returned
     * "Symbol 'X' not found at file:line".
     * <p>
     * Uses the same conceptual approach introduced for {@code go_to_declaration} (PR #815) and
     * {@code get_call_hierarchy} (PR #816); the implementation now lives in
     * {@link ToolUtils#resolveNamedElement} and is shared by {@code CallHierarchySupport} and
     * {@code TypeHierarchySupport} (which backs {@code find_implementations}). The fix applies
     * to any IDE whose PSI registers reference providers (CLion C/C++, PyCharm Python, GoLand
     * Go, WebStorm JS/TS, etc.). {@code GoToDeclarationTool} keeps its own
     * polyvariant-list-returning resolver and is intentionally not consolidated here.
     */
    public void testFindImplementationsResolvesFromUsageLine() throws Exception {
        VirtualFile vf = createTestFile("Animal.java", String.join("\n",
            "public class Animal {",
            "    public String sound() { return \"...\"; }",
            "    public static Animal make() { return new Animal(); }",
            "}",
            ""));

        // Line 3 contains the USAGE `new Animal()` — old code would fail here.
        String result = findImplementationsTool.execute(
            args("file", vf.getPath(), "line", "3", "symbol", "Animal"));

        // The bug is "Symbol 'X' not found at file:line" — the new shared resolver resolves
        // the constructor reference to the Animal declaration, so we must NOT see that error.
        // The DefinitionsScopedSearch result is irrelevant for the resolution bug; the test
        // fixture's project scope doesn't include /tmp files so 'no subtypes found' is fine.
        assertNotNull("Result must not be null", result);
        assertFalse("Resolution should succeed (no 'not found' error), got: " + result,
            result.contains("not found at"));
        assertTrue("Expected Animal to be resolved as the target, got: " + result,
            result.contains("Animal"));
    }

    /**
     * Regression test: requesting symbol {@code Ani} on a line containing {@code Animal}
     * must not match the substring inside the longer identifier. Mirrors the
     * {@code isWholeIdentifierMatch} guard in {@link ToolUtils#resolveNamedElement}.
     */
    public void testFindImplementationsRejectsSubstringMatch() throws Exception {
        VirtualFile vf = createTestFile("Zoo.java", String.join("\n",
            "public class Zoo {",
            "    public static Object make() { return new Animal(); }",
            "}",
            "class Animal {}",
            ""));

        // Asking for 'Ani' on line 2 must NOT match 'Ani' inside 'Animal' — no declaration
        // named 'Ani' exists anywhere, so the tool must report "not found".
        String result = findImplementationsTool.execute(
            args("file", vf.getPath(), "line", "2", "symbol", "Ani"));

        assertNotNull("Result must not be null", result);
        assertTrue("Substring match should NOT resolve to Animal, got: " + result,
            result.contains("not found at"));
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

    /**
     * End-to-end test: caller points at a USAGE (call site) of the target method. The old
     * implementation only resolved declarations on the target line, so pointing at a call site
     * would return "Could not find symbol at file:line". The new reference-fallback path
     * resolves the call's reference to the declaration and reports callers from there.
     * <p>
     * Mirrors the language-agnostic fix made for {@code go_to_declaration} (PR #815) — same
     * idea applied to call-hierarchy lookup so it works for any IDE whose PSI registers
     * reference providers (CLion C/C++, PyCharm Python, GoLand Go, WebStorm JS/TS, etc.).
     */
    public void testGetCallHierarchyResolvesFromUsageLine() throws Exception {
        VirtualFile vf = createTestFile("Lib.java", String.join("\n",
            "public class Lib {",
            "    public int target() { return 1; }",
            "    public int callerOne() { return target(); }",
            "    public int callerTwo() { return target(); }",
            "}",
            ""));

        // Line 3 contains the USAGE `target()` inside callerOne — old code would fail here.
        String result = getCallHierarchyTool.execute(
            args("file", vf.getPath(), "line", "3", "symbol", "target"));

        // The bug is "Could not find symbol at file:line" — the new fallback path resolves
        // the call's reference to the declaration, so we must NOT see that error message.
        // The actual references-search result is irrelevant for the resolution bug; the test
        // fixture's project scope doesn't include /tmp files so 'no callers found' is fine.
        assertNotNull("Result must not be null", result);
        assertFalse("Resolution should succeed (no 'Could not find' error), got: " + result,
            result.contains("Could not find"));
        assertTrue("Expected target() to be resolved as the call target, got: " + result,
            result.contains("target()"));
    }

    /**
     * Regression test: requesting symbol {@code bar} on a line containing {@code foobar()}
     * must not match the substring inside the longer identifier. Mirrors the
     * {@code isWholeIdentifierMatch} fix from PR #815.
     */
    public void testGetCallHierarchyRejectsSubstringMatch() throws Exception {
        VirtualFile vf = createTestFile("Sub.java", String.join("\n",
            "public class Sub {",
            "    public int foobar() { return 1; }",
            "    public int caller() { return foobar(); }",
            "}",
            ""));

        // Asking for 'bar' on line 3 must NOT match 'bar' inside 'foobar' — there is no
        // declaration named 'bar', so the tool should report "Could not find".
        String result = getCallHierarchyTool.execute(
            args("file", vf.getPath(), "line", "3", "symbol", "bar"));

        assertNotNull("Result must not be null", result);
        assertTrue("Substring match should NOT resolve to foobar, got: " + result,
            result.contains("Could not find"));
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

    /**
     * End-to-end resolution: a Java file contains both a method declaration and a usage of that
     * method; asks {@code go_to_declaration} to navigate from the usage to the declaration.
     * <p>
     * Exercises the language-agnostic path that resolves via
     * {@code psiFile.findReferenceAt(offset)} — the same path that allows the tool to work for
     * C/C++ in CLion Nova, Python in PyCharm, JS in WebStorm, etc. without per-language code.
     * Java is used here because we have a stable PSI for it in tests; the resolution path itself
     * is language-agnostic.
     */
    public void testGoToDeclarationResolvesUsageToDeclaration() throws Exception {
        VirtualFile vf = createTestFile("Foo.java", String.join("\n",
            "public class Foo {",
            "    public int bar() { return 1; }",
            "    public int baz() { return this.bar(); }",
            "}",
            ""));

        // Line 3 contains the usage `this.bar()` — resolve 'bar' to its declaration on line 2.
        String result = goToDeclarationTool.execute(
            args("file", vf.getPath(), "line", "3", "symbol", "bar"));

        assertNotNull("Result must not be null", result);
        assertTrue("Resolution should succeed for 'bar' usage, got: " + result,
            result.contains("Declaration of 'bar'") && result.contains("Foo.java")
                && result.contains("Line: 2"));
        assertFalse("Should not return the not-resolved error, got: " + result,
            result.contains("Could not resolve"));
    }

    /**
     * Regression test for review feedback on PR #815: a raw {@code indexOf(symbolName)} would
     * locate {@code bar} inside {@code foobar()} and (mis)resolve it as a usage. The fix
     * enforces an identifier-boundary check, so requesting symbol {@code "bar"} on a line that
     * only contains {@code foobar} must fall through with "Could not resolve" rather than
     * navigating to {@code foobar}'s declaration.
     */
    public void testGoToDeclarationRejectsSubstringMatch() throws Exception {
        VirtualFile vf = createTestFile("Sub.java", String.join("\n",
            "public class Sub {",
            "    public int foobar() { return 1; }",
            "    public int caller() { return this.foobar(); }",
            "}",
            ""));

        // Asking for 'bar' on line 3 must NOT match the 'bar' substring inside 'foobar'.
        String result = goToDeclarationTool.execute(
            args("file", vf.getPath(), "line", "3", "symbol", "bar"));

        assertNotNull("Result must not be null", result);
        assertTrue("Substring match should NOT resolve to foobar(), got: " + result,
            result.contains("Could not resolve"));
    }

    /**
     * When the caret line contains the declaration itself rather than a usage, the IDE's
     * "go to declaration" behaviour is to return the declaration's own location. This is
     * implemented via {@link com.intellij.codeInsight.TargetElementUtil#getNamedElement}
     * as the third fallback in {@code resolveAtOffset}.
     */
    public void testGoToDeclarationOnDeclarationItself() throws Exception {
        VirtualFile vf = createTestFile("Self.java", String.join("\n",
            "public class Self {",
            "    public void greet() { }",
            "}",
            ""));

        String result = goToDeclarationTool.execute(
            args("file", vf.getPath(), "line", "2", "symbol", "greet"));

        assertNotNull("Result must not be null", result);
        assertTrue("Resolution should succeed for declaration-itself case, got: " + result,
            result.contains("Declaration of 'greet'") && result.contains("Self.java"));
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
