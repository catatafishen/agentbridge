package com.github.catatafishen.agentbridge.psi.tools.terminal;

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Platform tests for the four terminal tools: {@link ListTerminalsTool},
 * {@link ReadTerminalOutputTool}, {@link WriteTerminalInputTool}, and
 * {@link RunInTerminalTool}, plus the {@link TerminalToolFactory}.
 *
 * <p>JUnit 3 style (extends {@link BasePlatformTestCase}): test methods must be
 * {@code public void testXxx()}. Run via Gradle only:
 * {@code ./gradlew :plugin-core:test}.
 *
 * <p><b>Threading model:</b>
 * <ul>
 *   <li>{@link ListTerminalsTool} uses {@code EdtUtil.invokeAndWait} exclusively.
 *       Because {@code invokeAndWait} detects {@code isDispatchThread() == true} and
 *       runs the runnable inline, this tool can be called directly from test methods
 *       (which already run on the EDT).</li>
 *   <li>{@link ReadTerminalOutputTool}, {@link WriteTerminalInputTool}, and
 *       {@link RunInTerminalTool} schedule their result on the EDT via
 *       {@code EdtUtil.invokeLater} and then block on a {@code CompletableFuture}.
 *       Calling {@code execute()} directly from the EDT would deadlock. These tools
 *       must be invoked via {@link #executeSync}, which runs {@code execute()} on a
 *       pooled thread while pumping the EDT event queue.</li>
 *   <li>{@link WriteTerminalInputTool#execute} and {@link RunInTerminalTool#execute}
 *       access the required {@code "input"}/{@code "command"} argument
 *       <em>before</em> any EDT dispatch. Passing an empty {@link JsonObject} causes
 *       a {@link NullPointerException} on the pooled thread, which propagates as
 *       {@link ExecutionException} from {@code future.get()}.</li>
 * </ul>
 *
 * <p><b>Test environment:</b> in the headless Gradle test sandbox the IntelliJ
 * Terminal plugin ({@code TerminalToolWindowManager}) is not present on the
 * classpath. {@link RunInTerminalTool} catches the resulting
 * {@link ClassNotFoundException} and returns a graceful error message. Similarly,
 * the Terminal tool window is not registered, so {@link ListTerminalsTool} and
 * {@link ReadTerminalOutputTool} report that no tabs are available rather than
 * throwing.
 */
public class TerminalToolsTest extends BasePlatformTestCase {

    private ListTerminalsTool listTerminalsTool;
    private ReadTerminalOutputTool readTerminalOutputTool;
    private WriteTerminalInputTool writeTerminalInputTool;
    private RunInTerminalTool runInTerminalTool;

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    public void setUp() throws Exception {
        super.setUp();
        // Disable "follow agent files" UI navigation to prevent editor-lifecycle
        // failures (DisposalException, focus stealing) in the headless test environment.
        // Use the String overload — the boolean overload silently removes the key when
        // value == defaultValue, which would leave the setting at its default.
        PropertiesComponent.getInstance(getProject())
            .setValue(ToolLayerSettings.FOLLOW_AGENT_FILES_KEY, "false");

        listTerminalsTool = new ListTerminalsTool(getProject());
        readTerminalOutputTool = new ReadTerminalOutputTool(getProject());
        writeTerminalInputTool = new WriteTerminalInputTool(getProject());
        runInTerminalTool = new RunInTerminalTool(getProject());
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Builds a {@link JsonObject} from alternating key/value String pairs.
     * Example: {@code args("tab_name", "myTab", "max_lines", "10")}.
     */
    private static JsonObject args(String... pairs) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            obj.addProperty(pairs[i], pairs[i + 1]);
        }
        return obj;
    }

    /**
     * Runs {@code tool.execute(argsObj)} on a pooled background thread while
     * pumping the EDT event queue. Required for tools that use
     * {@code EdtUtil.invokeLater}: calling {@code execute()} directly from the EDT
     * would deadlock because the scheduled EDT callback can never run while the EDT
     * thread is blocked inside {@code future.get()}.
     *
     * <p>If {@code execute()} throws (e.g. {@link NullPointerException} for a missing
     * required parameter), the exception is wrapped in {@link ExecutionException} and
     * re-thrown from {@code future.get()}.
     *
     * @throws ExecutionException    if {@code execute()} threw an exception on the
     *                               pooled thread
     * @throws IllegalStateException if the operation does not complete within 15 s
     */
    private String executeSync(Tool tool, JsonObject argsObj) throws Exception {
        CompletableFuture<String> future = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                future.complete(tool.execute(argsObj));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        long deadline = System.currentTimeMillis() + 15_000;
        while (!future.isDone()) {
            UIUtil.dispatchAllInvocationEvents();
            if (System.currentTimeMillis() > deadline) {
                fail("tool.execute() timed out after 15 seconds");
            }
        }
        return future.get();
    }

    // ── TerminalToolFactory ────────────────────────────────────────────────────

    /**
     * {@link TerminalToolFactory#create} must return a list containing exactly the
     * four expected tool instances. Availability of the terminal plugin is checked
     * at execution time (via reflection), not at factory creation time — there is
     * no guard to bypass at construction.
     */
    public void testTerminalToolFactoryCreatesAllFourTools() {
        List<Tool> tools = TerminalToolFactory.create(getProject());

        assertNotNull("Factory must not return null", tools);
        assertEquals("Factory must create exactly 4 terminal tools", 4, tools.size());

        long listCount = tools.stream().filter(ListTerminalsTool.class::isInstance).count();
        long readCount = tools.stream().filter(ReadTerminalOutputTool.class::isInstance).count();
        long writeCount = tools.stream().filter(WriteTerminalInputTool.class::isInstance).count();
        long runCount = tools.stream().filter(RunInTerminalTool.class::isInstance).count();

        assertEquals("Expected exactly one ListTerminalsTool", 1, listCount);
        assertEquals("Expected exactly one ReadTerminalOutputTool", 1, readCount);
        assertEquals("Expected exactly one WriteTerminalInputTool", 1, writeCount);
        assertEquals("Expected exactly one RunInTerminalTool", 1, runCount);
    }

    /**
     * Every tool produced by the factory must have a non-blank {@code id()},
     * {@code displayName()}, and {@code description()}.
     */
    public void testTerminalToolFactoryMetadataIsValid() {
        List<Tool> tools = TerminalToolFactory.create(getProject());

        for (Tool tool : tools) {
            String simpleName = tool.getClass().getSimpleName();
            assertNotNull(simpleName + ": id() must not be null", tool.id());
            assertFalse(simpleName + ": id() must not be blank", tool.id().isBlank());
            assertNotNull(simpleName + ": displayName() must not be null", tool.displayName());
            assertFalse(simpleName + ": displayName() must not be blank", tool.displayName().isBlank());
            assertNotNull(simpleName + ": description() must not be null", tool.description());
            assertFalse(simpleName + ": description() must not be blank", tool.description().isBlank());
        }
    }

    // ── ListTerminalsTool ──────────────────────────────────────────────────────

    /**
     * In a fresh headless test project there are no open terminal tabs.
     * {@code list_terminals} must still return a structured response containing the
     * "Open terminal tabs:" section header and an indication that no tabs are
     * available — it must never throw, return null, or return blank content.
     *
     * <p>{@link ListTerminalsTool} uses {@code EdtUtil.invokeAndWait} internally,
     * which runs inline when already on the EDT, so calling {@code execute()}
     * directly from the test method is safe.
     */
    public void testListTerminalsNoTerminals() throws Exception {
        String result = listTerminalsTool.execute(new JsonObject());

        assertNotNull("Result must not be null", result);
        assertFalse("Result must not be blank", result.isBlank());
        assertTrue("Expected 'Open terminal tabs:' section header in result, got: " + result,
            result.contains("Open terminal tabs:"));

        // In the headless test environment the Terminal tool window is either absent
        // or reports no tabs — exactly one of these phrases must appear.
        boolean noneIndicated = result.contains("(none)")
            || result.contains("not available")
            || result.contains("Could not list");
        assertTrue(
            "Expected no-terminals indication ('(none)' / 'not available' / 'Could not list'), got: " + result,
            noneIndicated);
    }

    /**
     * The response from {@code list_terminals} must always be non-null, non-blank,
     * and must not start with "Error". It must include the "Available shells:" section
     * and the closing tip line regardless of whether any terminal is open.
     */
    public void testListTerminalsResponseFormat() throws Exception {
        String result = listTerminalsTool.execute(new JsonObject());

        assertNotNull("Result must not be null", result);
        assertFalse("Result must not be blank", result.isBlank());
        assertFalse("Result must never start with 'Error', got: " + result,
            result.startsWith("Error"));
        assertTrue("Expected 'Available shells:' section in result, got: " + result,
            result.contains("Available shells:"));
        // The tip line is appended unconditionally at the end of execute()
        assertTrue("Expected closing tip referencing read_terminal_output, got: " + result,
            result.contains("read_terminal_output"));
        assertTrue("Expected closing tip referencing write_terminal_input, got: " + result,
            result.contains("write_terminal_input"));
    }

    /**
     * The "Available shells:" section must list discovered shells or at least attempt
     * to check common shell paths for the current OS. On POSIX systems {@code /bin/sh}
     * is universally available, so at least one "&#x2713;" entry is expected; on Windows
     * the section may list zero available entries but the header must still be present.
     */
    public void testListTerminalsAvailableShellsSection() throws Exception {
        String result = listTerminalsTool.execute(new JsonObject());

        assertNotNull("Result must not be null", result);
        assertTrue("Expected 'Available shells:' section in result, got: " + result,
            result.contains("Available shells:"));

        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win") && new java.io.File("/bin/sh").exists()) {
            // /bin/sh is universally available on POSIX — at least one shell must be shown
            assertTrue("Expected at least one '✓' shell entry on POSIX system, got: " + result,
                result.contains("✓"));
        }
    }

    /**
     * The default-shell line must always appear: either the actual configured shell
     * path ({@code "IntelliJ default shell: ..."}) or the graceful fallback message
     * ({@code "Could not determine IntelliJ default shell."}) when the terminal plugin
     * is absent in the test sandbox.
     */
    public void testListTerminalsDefaultShellSection() throws Exception {
        String result = listTerminalsTool.execute(new JsonObject());

        assertNotNull("Result must not be null", result);
        boolean hasDefaultShellLine = result.contains("IntelliJ default shell:")
            || result.contains("Could not determine IntelliJ default shell");
        assertTrue("Expected a default-shell line in result, got: " + result,
            hasDefaultShellLine);
    }

    /**
     * {@code list_terminals} must be declared as {@link Tool.Kind#READ} and
     * {@code isReadOnly()} must return {@code true}.
     */
    public void testListTerminalsIsReadOnly() {
        assertEquals("list_terminals must be Kind.READ",
            Tool.Kind.READ, listTerminalsTool.kind());
        assertTrue("list_terminals must be read-only",
            listTerminalsTool.isReadOnly());
    }

    // ── ReadTerminalOutputTool ─────────────────────────────────────────────────

    /**
     * When {@code tab_name} is omitted and no terminal tab is open, the tool must
     * return the standard "No terminal tab is open" message — not null, not blank,
     * and not a raw Java exception string.
     *
     * <p>{@link ReadTerminalOutputTool} uses {@code EdtUtil.invokeLater} and a
     * {@code CompletableFuture}, so it must be called via {@link #executeSync}.
     */
    public void testReadTerminalOutputNoTabName() throws Exception {
        String result = executeSync(readTerminalOutputTool, new JsonObject());

        assertNotNull("Result must not be null", result);
        assertFalse("Result must not be blank", result.isBlank());
        assertFalse("Result must not be a raw Java exception string",
            result.startsWith("java."));
        // resolveTerminalContent(null) → null when no Terminal tool window is registered
        // → execute() returns "No terminal tab is open. Use run_in_terminal to start one."
        assertTrue("Expected a no-terminal message, got: " + result,
            result.contains("No terminal tab is open")
                || result.contains("No terminal")
                || result.contains("Terminal read timed out"));
    }

    /**
     * When a non-existent {@code tab_name} is specified, the tool must return an
     * error message that references the requested tab name and directs the user to
     * {@code list_terminals}.
     *
     * <p>Expected message (when the Terminal tool window exists but the tab is absent):
     * {@code "No terminal tab found matching 'NonExistentTabXYZ_99999'. Use list_terminals to see available tabs."}
     */
    public void testReadTerminalOutputNonExistentTab() throws Exception {
        String nonExistentTab = "NonExistentTabXYZ_99999";
        String result = executeSync(readTerminalOutputTool, args("tab_name", nonExistentTab));

        assertNotNull("Result must not be null", result);
        assertFalse("Result must not be blank", result.isBlank());
        assertFalse("Result must not be a raw Java exception string",
            result.startsWith("java."));
        // resolveTerminalContent(tabName) → null → "No terminal tab found matching '...'"
        assertTrue("Expected error referencing the missing tab name, got: " + result,
            result.contains(nonExistentTab)
                || result.contains("No terminal tab found")
                || result.contains("No terminal"));
    }

    /**
     * When {@code max_lines} is set to {@code 0} (full-buffer request) and no terminal
     * is open, the tool still returns a graceful message rather than throwing.
     */
    public void testReadTerminalOutputMaxLinesZeroNoTerminal() throws Exception {
        JsonObject argsObj = new JsonObject();
        argsObj.addProperty("max_lines", 0);

        String result = executeSync(readTerminalOutputTool, argsObj);

        assertNotNull("Result must not be null", result);
        assertFalse("Result must not be blank", result.isBlank());
        assertFalse("Result must not start with 'Error'", result.startsWith("Error"));
    }

    /**
     * {@code read_terminal_output} must be declared as {@link Tool.Kind#READ} and
     * {@code isReadOnly()} must return {@code true}.
     */
    public void testReadTerminalOutputIsReadOnly() {
        assertEquals("read_terminal_output must be Kind.READ",
            Tool.Kind.READ, readTerminalOutputTool.kind());
        assertTrue("read_terminal_output must be read-only",
            readTerminalOutputTool.isReadOnly());
    }

    // ── WriteTerminalInputTool ─────────────────────────────────────────────────

    /**
     * When the required {@code "input"} argument is absent, the tool calls
     * {@code args.get("input").getAsString()} without a null-check (Gson returns
     * {@code null} for missing keys), which causes a {@link NullPointerException}
     * on the pooled thread before any EDT work is dispatched. {@link #executeSync}
     * wraps this in an {@link ExecutionException}.
     *
     * <p>This test documents the current behavior. If the implementation is later
     * updated to validate parameters explicitly and return a graceful error message,
     * this test should be updated to assert on that error string instead.
     */
    public void testWriteTerminalInputMissingInput() throws Exception {
        try {
            String result = executeSync(writeTerminalInputTool, new JsonObject());
            // Future-proof: if the tool adds explicit validation, it must return
            // a non-null, non-blank, non-exception error message.
            assertNotNull("Result (if returned) must not be null", result);
            assertFalse("Result (if returned) must not be blank", result.isBlank());
            assertFalse("Result must not be a raw Java exception string",
                result.startsWith("java."));
        } catch (ExecutionException e) {
            // Current behavior: NullPointerException from args.get("input").getAsString()
            // when the "input" key is absent from the JsonObject.
            Throwable cause = e.getCause();
            assertNotNull("ExecutionException must have a non-null cause", cause);
            assertTrue(
                "Expected NullPointerException for absent required 'input' parameter, got: "
                    + cause.getClass().getName(),
                cause instanceof NullPointerException);
        }
    }

    /**
     * When {@code "input"} is provided but no terminal is running, the tool must
     * return a helpful message directing the user to create a terminal first.
     * In the headless test sandbox the terminal plugin class is absent
     * ({@link ClassNotFoundException}), which is also caught gracefully.
     *
     * <p>Expected messages:
     * <ul>
     *   <li>Terminal plugin absent: {@code "Failed to write to terminal: ..."}</li>
     *   <li>Plugin present but no widget: {@code "No terminal found. Use run_in_terminal to create one first."}</li>
     *   <li>Timeout (very unlikely): {@code "Input sent (response timed out)."}</li>
     * </ul>
     */
    public void testWriteTerminalInputNoTerminal() throws Exception {
        String result = executeSync(writeTerminalInputTool, args("input", "echo hello"));

        assertNotNull("Result must not be null", result);
        assertFalse("Result must not be blank", result.isBlank());
        assertFalse("Result must not be a raw Java exception string",
            result.startsWith("java."));

        boolean noTerminalMessage = result.contains("No terminal found");
        boolean failedWriteMessage = result.contains("Failed to write to terminal");
        boolean timedOut = result.contains("timed out");
        assertTrue(
            "Expected no-terminal or write-failure message, got: " + result,
            noTerminalMessage || failedWriteMessage || timedOut);
    }

    /**
     * When both {@code "input"} and a non-existent {@code tab_name} are provided,
     * the error message must either reference that tab name or be a generic
     * no-terminal/write-failure message.
     *
     * <p>Primary expected message (when the terminal plugin is present):
     * {@code "No terminal found matching 'GhostTabXYZ_54321'. Use run_in_terminal to create one first."}
     */
    public void testWriteTerminalInputNoTerminalWithTabName() throws Exception {
        String ghostTab = "GhostTabXYZ_54321";
        String result = executeSync(writeTerminalInputTool,
            args("input", "ls -la", "tab_name", ghostTab));

        assertNotNull("Result must not be null", result);
        assertFalse("Result must not be blank", result.isBlank());
        assertFalse("Result must not be a raw Java exception string",
            result.startsWith("java."));

        boolean mentionsTabName = result.contains(ghostTab);
        boolean genericError = result.contains("No terminal found")
            || result.contains("Failed to write to terminal")
            || result.contains("timed out");
        assertTrue(
            "Expected error mentioning the tab name or a graceful no-terminal message, got: " + result,
            mentionsTabName || genericError);
    }

    /**
     * {@code write_terminal_input} must be declared as {@link Tool.Kind#EDIT} and
     * {@code isOpenWorld()} must return {@code true} (it interacts with the OS shell).
     */
    public void testWriteTerminalInputKindAndOpenWorld() {
        assertEquals("write_terminal_input must be Kind.EDIT",
            Tool.Kind.EDIT, writeTerminalInputTool.kind());
        assertTrue("write_terminal_input must be open-world",
            writeTerminalInputTool.isOpenWorld());
    }

    // ── RunInTerminalTool ──────────────────────────────────────────────────────

    /**
     * When the required {@code "command"} argument is absent, the tool calls
     * {@code args.get("command").getAsString()} without a null-check, causing a
     * {@link NullPointerException} on the pooled thread before any EDT work is
     * dispatched. {@link #executeSync} wraps this in an {@link ExecutionException}.
     *
     * <p>This test documents the current behavior. Should the implementation add
     * explicit parameter validation, update this test to assert on the error string.
     */
    public void testRunInTerminalMissingCommand() throws Exception {
        try {
            String result = executeSync(runInTerminalTool, new JsonObject());
            // Future-proof: if the tool adds validation, result must be a proper error message.
            assertNotNull("Result (if returned) must not be null", result);
            assertFalse("Result (if returned) must not be blank", result.isBlank());
            assertFalse("Result must not be a raw Java exception string",
                result.startsWith("java."));
        } catch (ExecutionException e) {
            // Current behavior: NPE from args.get("command").getAsString() when absent
            Throwable cause = e.getCause();
            assertNotNull("ExecutionException must have a non-null cause", cause);
            assertTrue(
                "Expected NullPointerException for absent required 'command' parameter, got: "
                    + cause.getClass().getName(),
                cause instanceof NullPointerException);
        }
    }

    /**
     * When a valid {@code command} is provided, the tool must return a non-null,
     * non-blank string and must never surface a raw Java exception.
     *
     * <p>In the headless test sandbox the expected response is
     * {@code "Terminal plugin not available. Use run_command tool instead."} because
     * {@code TerminalToolWindowManager} is absent from the classpath. Any other
     * graceful response is also acceptable.
     */
    public void testRunInTerminalWithCommand() throws Exception {
        String result = executeSync(runInTerminalTool, args("command", "echo hello"));

        assertNotNull("Result must not be null", result);
        assertFalse("Result must not be blank", result.isBlank());
        assertFalse("Result must not be a raw Java exception string",
            result.startsWith("java."));

        // All expected outcomes in the headless test environment:
        //   a) Terminal plugin absent → "Terminal plugin not available. Use run_command tool instead."
        //   b) Plugin present, creation fails → "Failed to open terminal: ... Use run_command tool instead."
        //   c) Plugin present, command sent → "Command sent to terminal '...': echo hello"
        //   d) Timeout (very unlikely) → "Terminal opened (response timed out, ...)"
        boolean isPluginUnavailable = result.contains("Terminal plugin not available");
        boolean isFailedToOpen = result.contains("Failed to open terminal");
        boolean isCommandSent = result.contains("Command sent to terminal");
        boolean isTimedOut = result.contains("timed out");
        assertTrue(
            "Expected a graceful response (plugin unavailable / failed to open / command sent / timed out), got: " + result,
            isPluginUnavailable || isFailedToOpen || isCommandSent || isTimedOut);
    }

    /**
     * {@link RunInTerminalTool} must reject shell commands that match documented
     * abuse patterns (e.g., {@code rm -rf /}). The abuse check runs before any
     * EDT work or terminal interaction is attempted, so the pooled thread returns
     * a rejection message immediately. The result must not confirm execution.
     */
    public void testRunInTerminalAbuseRejection() throws Exception {
        String result = executeSync(runInTerminalTool, args("command", "rm -rf /"));

        assertNotNull("Result must not be null for abuse command", result);
        assertFalse("Result must not be blank for abuse command", result.isBlank());
        assertFalse("Abuse command must not confirm execution, got: " + result,
            result.startsWith("Command sent to terminal"));
    }

    /**
     * {@code sudo rm} commands must also be caught by the abuse guard before any
     * terminal interaction occurs.
     */
    public void testRunInTerminalSudoRmAbuseRejection() throws Exception {
        String result = executeSync(runInTerminalTool, args("command", "sudo rm -rf /tmp/foo"));

        assertNotNull("Result must not be null", result);
        assertFalse("Result must not be blank", result.isBlank());
        assertFalse("sudo-rm abuse command must not be executed, got: " + result,
            result.startsWith("Command sent to terminal"));
    }

    /**
     * {@code run_in_terminal} must be declared as {@link Tool.Kind#EDIT} and
     * {@code isOpenWorld()} must return {@code true} (it runs arbitrary OS commands).
     */
    public void testRunInTerminalKindAndOpenWorld() {
        assertEquals("run_in_terminal must be Kind.EDIT",
            Tool.Kind.EDIT, runInTerminalTool.kind());
        assertTrue("run_in_terminal must be open-world",
            runInTerminalTool.isOpenWorld());
    }

    // ── TerminalTool shared utilities ──────────────────────────────────────────

    /**
     * {@link TerminalTool#resolveInputEscapes} must map every documented escape
     * sequence to its correct Unicode code point.
     */
    public void testResolveInputEscapes() {
        assertEquals("\\n must map to newline (LF)",
            "\n", TerminalTool.resolveInputEscapes("\\n"));
        assertEquals("\\t must map to horizontal tab (HT)",
            "\t", TerminalTool.resolveInputEscapes("\\t"));
        assertEquals("{enter} must map to carriage return (CR)",
            "\r", TerminalTool.resolveInputEscapes("{enter}"));
        assertEquals("{tab} must map to horizontal tab (HT)",
            "\t", TerminalTool.resolveInputEscapes("{tab}"));
        assertEquals("{ctrl-c} must map to ETX (U+0003)",
            "\u0003", TerminalTool.resolveInputEscapes("{ctrl-c}"));
        assertEquals("{ctrl-d} must map to EOT (U+0004)",
            "\u0004", TerminalTool.resolveInputEscapes("{ctrl-d}"));
        assertEquals("{ctrl-z} must map to SUB (U+001A)",
            "\u001A", TerminalTool.resolveInputEscapes("{ctrl-z}"));
        assertEquals("{escape} must map to ESC (U+001B)",
            "\u001B", TerminalTool.resolveInputEscapes("{escape}"));
        assertEquals("{up} must map to ANSI cursor-up sequence",
            "\u001B[A", TerminalTool.resolveInputEscapes("{up}"));
        assertEquals("{down} must map to ANSI cursor-down sequence",
            "\u001B[B", TerminalTool.resolveInputEscapes("{down}"));
        assertEquals("{right} must map to ANSI cursor-right sequence",
            "\u001B[C", TerminalTool.resolveInputEscapes("{right}"));
        assertEquals("{left} must map to ANSI cursor-left sequence",
            "\u001B[D", TerminalTool.resolveInputEscapes("{left}"));
        assertEquals("{backspace} must map to DEL (U+007F)",
            "\u007F", TerminalTool.resolveInputEscapes("{backspace}"));
    }

    /**
     * Plain text with no escape sequences must pass through
     * {@link TerminalTool#resolveInputEscapes} completely unchanged.
     */
    public void testResolveInputEscapesPlainText() {
        String plain = "echo hello world";
        assertEquals("Plain text must be returned unchanged",
            plain, TerminalTool.resolveInputEscapes(plain));
    }

    /**
     * Multiple escape sequences in a single string must all be resolved in one pass.
     * "ls{enter}{ctrl-c}" → "ls\r\u0003".
     */
    public void testResolveInputEscapesMultiple() {
        String result = TerminalTool.resolveInputEscapes("ls{enter}{ctrl-c}");
        assertEquals("Multiple escape sequences must all be resolved in one pass",
            "ls\r\u0003", result);
    }

    /**
     * {@link TerminalTool#describeInput} must wrap the raw form in quotes and include
     * a parenthesised character count when the input contains escape-sequence syntax
     * (braces or backslash), and must use a simpler quoted form for plain text.
     */
    public void testDescribeInput() {
        // Input with escape syntax: "'<raw>' (<len> chars)"
        String escapedDescription = TerminalTool.describeInput("{ctrl-c}", "\u0003");
        assertTrue("Escape-syntax description must contain the raw form, got: " + escapedDescription,
            escapedDescription.contains("{ctrl-c}"));
        assertTrue("Escape-syntax description must include parenthesised length, got: " + escapedDescription,
            escapedDescription.matches(".*\\(\\d+ chars\\).*"));

        // Plain text: "'<text>'" with no length annotation
        String plainDescription = TerminalTool.describeInput("echo hello", "echo hello");
        assertTrue("Plain-text description must quote the input, got: " + plainDescription,
            plainDescription.contains("echo hello"));
        assertFalse("Plain-text description must NOT include a parenthesised length, got: " + plainDescription,
            plainDescription.matches(".*\\(\\d+ chars\\).*"));
    }

    /**
     * {@link TerminalTool#tailLines} with {@code maxLines == 0} must return the full
     * text (modulo {@code ToolUtils.truncateOutput} size limits), not an empty string.
     */
    public void testTailLinesZeroReturnsFullText() {
        String text = "line1\nline2\nline3";
        String result = TerminalTool.tailLines(text, 0);
        assertNotNull("tailLines must not return null", result);
        assertFalse("tailLines with maxLines=0 must not be empty", result.isBlank());
        assertTrue("tailLines with maxLines=0 must contain all input lines, got: " + result,
            result.contains("line1") && result.contains("line3"));
    }

    /**
     * {@link TerminalTool#tailLines} with a positive {@code maxLines} that is smaller
     * than the total line count must return exactly the last N lines.
     * Input: "a\nb\nc\nd\ne" with maxLines=3 → "c\nd\ne".
     */
    public void testTailLinesPositiveMaxLines() {
        String text = "a\nb\nc\nd\ne";
        String result = TerminalTool.tailLines(text, 3);
        assertNotNull("tailLines must not return null", result);
        assertTrue("Expected 'c' in tail, got: " + result, result.contains("c"));
        assertTrue("Expected 'd' in tail, got: " + result, result.contains("d"));
        assertTrue("Expected 'e' in tail, got: " + result, result.contains("e"));
        assertFalse("Expected 'a' to be trimmed from tail, got: " + result, result.contains("a"));
        assertFalse("Expected 'b' to be trimmed from tail, got: " + result, result.contains("b"));
    }

    /**
     * {@link TerminalTool#tailLines} must return the entire text unchanged when the
     * total line count is less than or equal to {@code maxLines}.
     */
    public void testTailLinesFewLines() {
        String text = "only\ntwo";
        String result = TerminalTool.tailLines(text, 10);
        assertNotNull("tailLines must not return null", result);
        assertTrue("Expected 'only' in result, got: " + result, result.contains("only"));
        assertTrue("Expected 'two' in result, got: " + result, result.contains("two"));
    }

    /**
     * {@link TerminalTool#tailLines} with {@code maxLines} equal to the exact line
     * count must return all lines (boundary condition: no trimming should occur).
     */
    public void testTailLinesExactBoundary() {
        String text = "x\ny\nz";
        String result = TerminalTool.tailLines(text, 3);
        assertNotNull("tailLines must not return null", result);
        assertTrue("All three lines must be present at exact boundary, got: " + result,
            result.contains("x") && result.contains("y") && result.contains("z"));
    }

    /**
     * {@link TerminalTool#truncateForTitle} must truncate commands longer than
     * 40 characters to 37 characters followed by {@code "..."} — the same rule used
     * when auto-naming new terminal tabs.
     */
    public void testTruncateForTitle() {
        String shortCommand = "echo hello";
        assertEquals("Short command must not be truncated",
            shortCommand, TerminalTool.truncateForTitle(shortCommand));

        String longCommand = "a".repeat(50);
        String truncated = TerminalTool.truncateForTitle(longCommand);
        assertEquals("Truncated title must be exactly 40 chars (37 + '...')",
            40, truncated.length());
        assertTrue("Truncated title must end with '...'", truncated.endsWith("..."));
    }

    /**
     * A command of exactly 40 characters is at the boundary — it must not be
     * truncated. The implementation uses {@code length > 40}, not {@code >= 40}.
     */
    public void testTruncateForTitleExactBoundary() {
        String exactBoundary = "a".repeat(40);
        assertEquals("Command of exactly 40 chars must not be truncated",
            exactBoundary, TerminalTool.truncateForTitle(exactBoundary));
    }

    /**
     * A command of exactly 41 characters is one over the limit — it must be
     * truncated to 37 chars + {@code "..."}.
     */
    public void testTruncateForTitleOneOverBoundary() {
        String oneOver = "a".repeat(41);
        String truncated = TerminalTool.truncateForTitle(oneOver);
        assertEquals("Command of 41 chars must be truncated to 40",
            40, truncated.length());
        assertTrue("Truncated title must end with '...'", truncated.endsWith("..."));
    }
}
