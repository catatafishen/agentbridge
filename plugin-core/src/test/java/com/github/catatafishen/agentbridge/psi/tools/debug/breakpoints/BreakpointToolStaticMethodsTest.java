package com.github.catatafishen.agentbridge.psi.tools.debug.breakpoints;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for pure static helper methods in {@link BreakpointManageTool}.
 * These methods format result messages and error strings with no platform dependencies.
 */
class BreakpointToolStaticMethodsTest {

    // ── BreakpointManageTool.formatAddResult ─────────────────────────────

    @Nested
    @DisplayName("BreakpointManageTool.formatAddResult")
    class FormatAddResultTest {

        @Test
        @DisplayName("basic result with valid index and location")
        void basicResultWithIndex() throws Exception {
            String result = invokeFormatAddResult(1, "src/Main.java", 42, null, null, true, true);
            assertTrue(result.startsWith("Added breakpoint"), "Should start with 'Added breakpoint'");
            assertTrue(result.contains("index 1"), "Should contain index");
            assertTrue(result.contains("src/Main.java:42"), "Should contain location:line");
        }

        @Test
        @DisplayName("index <= 0 omits index from output")
        void negativeIndexOmitsIndex() throws Exception {
            String result = invokeFormatAddResult(-1, "App.java", 10, null, null, true, true);
            assertFalse(result.contains("index"), "Should not contain 'index' when <= 0");
            assertTrue(result.contains("App.java:10"));
        }

        @Test
        @DisplayName("zero index omits index from output")
        void zeroIndexOmitsIndex() throws Exception {
            String result = invokeFormatAddResult(0, "App.java", 1, null, null, true, true);
            assertFalse(result.contains("index 0"), "Should not contain 'index 0'");
        }

        @Test
        @DisplayName("condition is included when non-blank")
        void resultWithCondition() throws Exception {
            String result = invokeFormatAddResult(1, "Test.java", 5, "x > 0", null, true, true);
            assertTrue(result.contains("[condition: x > 0]"));
        }

        @Test
        @DisplayName("blank condition is omitted")
        void blankConditionOmitted() throws Exception {
            String result = invokeFormatAddResult(1, "Test.java", 5, "  ", null, true, true);
            assertFalse(result.contains("[condition:"));
        }

        @Test
        @DisplayName("null condition is omitted")
        void nullConditionOmitted() throws Exception {
            String result = invokeFormatAddResult(1, "Test.java", 5, null, null, true, true);
            assertFalse(result.contains("[condition:"));
        }

        @Test
        @DisplayName("log expression is included when non-blank")
        void resultWithLogExpr() throws Exception {
            String result = invokeFormatAddResult(1, "Main.java", 10, null, "Thread.currentThread()", true, true);
            assertTrue(result.contains("[log: Thread.currentThread()]"));
        }

        @Test
        @DisplayName("blank log expression is omitted")
        void blankLogExprOmitted() throws Exception {
            String result = invokeFormatAddResult(1, "Main.java", 10, null, " ", true, true);
            assertFalse(result.contains("[log:"));
        }

        @Test
        @DisplayName("disabled flag is shown when enabled=false")
        void resultDisabled() throws Exception {
            String result = invokeFormatAddResult(2, "Foo.java", 7, null, null, false, true);
            assertTrue(result.contains("[disabled]"));
        }

        @Test
        @DisplayName("enabled breakpoint does not show [disabled]")
        void resultEnabledNoFlag() throws Exception {
            String result = invokeFormatAddResult(2, "Foo.java", 7, null, null, true, true);
            assertFalse(result.contains("[disabled]"));
        }

        @Test
        @DisplayName("non-suspending flag is shown when suspend=false")
        void resultNonSuspending() throws Exception {
            String result = invokeFormatAddResult(3, "Bar.java", 15, null, null, true, false);
            assertTrue(result.contains("[non-suspending]"));
        }

        @Test
        @DisplayName("suspending breakpoint does not show [non-suspending]")
        void resultSuspendingNoFlag() throws Exception {
            String result = invokeFormatAddResult(3, "Bar.java", 15, null, null, true, true);
            assertFalse(result.contains("[non-suspending]"));
        }

        @Test
        @DisplayName("all options combined produce all annotations")
        void resultAllOptions() throws Exception {
            String result = invokeFormatAddResult(5, "src/App.java", 100, "i == 42", "\"hit!\"", false, false);
            assertTrue(result.contains("index 5"));
            assertTrue(result.contains("src/App.java:100"));
            assertTrue(result.contains("[condition: i == 42]"));
            assertTrue(result.contains("[log: \"hit!\"]"));
            assertTrue(result.contains("[disabled]"));
            assertTrue(result.contains("[non-suspending]"));
        }

        private String invokeFormatAddResult(int index, String location, int lineOneBased,
                                             String condition, String logExpr,
                                             boolean enabled, boolean suspend) throws Exception {
            Method m = BreakpointManageTool.class.getDeclaredMethod("formatAddResult",
                int.class, String.class, int.class, String.class, String.class,
                boolean.class, boolean.class);
            m.setAccessible(true);
            return (String) m.invoke(null, index, location, lineOneBased, condition, logExpr, enabled, suspend);
        }
    }

    // ── BreakpointManageTool.buildResolveError ───────────────────────────

    @Nested
    @DisplayName("BreakpointManageTool.buildResolveError")
    class BuildResolveErrorTest {

        @Test
        @DisplayName("error with index out of range includes range info")
        void errorWithIndexOutOfRange() throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("index", 5);
            String result = invokeBuildResolveError(args, 3);
            assertTrue(result.contains("5"), "Should mention the requested index");
            assertTrue(result.contains("1-3"), "Should show valid range");
            assertTrue(result.contains("breakpoint_list"), "Should suggest breakpoint_list");
        }

        @Test
        @DisplayName("error with index=1 and total=0 shows range 1-0")
        void errorIndexWithZeroTotal() throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("index", 1);
            String result = invokeBuildResolveError(args, 0);
            assertTrue(result.contains("1-0"), "Should show range even if total is 0");
        }

        @Test
        @DisplayName("error with file only shows file path")
        void errorWithFileOnly() throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("file", "src/Main.java");
            String result = invokeBuildResolveError(args, 5);
            assertTrue(result.contains("src/Main.java"), "Should contain file path");
            assertTrue(result.contains("No breakpoint found"), "Should say no breakpoint found");
        }

        @Test
        @DisplayName("error with file and line shows both")
        void errorWithFileAndLine() throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("file", "src/Main.java");
            args.addProperty("line", 42);
            String result = invokeBuildResolveError(args, 5);
            assertTrue(result.contains("src/Main.java"), "Should contain file path");
            assertTrue(result.contains("42"), "Should contain line number");
        }

        @Test
        @DisplayName("no selector args gives guidance message")
        void errorNoSelector() throws Exception {
            JsonObject args = new JsonObject();
            String result = invokeBuildResolveError(args, 5);
            assertTrue(result.contains("Specify"), "Should provide guidance");
            assertTrue(result.contains("index"), "Should mention 'index' option");
            assertTrue(result.contains("file"), "Should mention 'file' option");
        }

        @Test
        @DisplayName("index takes precedence over file when both present")
        void indexTakesPrecedence() throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("index", 99);
            args.addProperty("file", "src/Main.java");
            String result = invokeBuildResolveError(args, 3);
            assertTrue(result.contains("99"), "Should use the index branch");
            assertTrue(result.contains("breakpoint_list"), "Should suggest breakpoint_list");
        }

        private String invokeBuildResolveError(JsonObject args, int total) throws Exception {
            Method m = BreakpointManageTool.class.getDeclaredMethod("buildResolveError",
                JsonObject.class, int.class, String.class);
            m.setAccessible(true);
            BreakpointManageTool tool = new BreakpointManageTool(null);
            return (String) m.invoke(tool, args, total, "update");
        }
    }
}
