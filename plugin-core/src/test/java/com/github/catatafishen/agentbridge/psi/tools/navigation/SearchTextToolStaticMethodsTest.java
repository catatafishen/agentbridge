package com.github.catatafishen.agentbridge.psi.tools.navigation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-unit tests for the private static
 * {@code SearchTextTool.compileSearchPattern(String, boolean, boolean)} method.
 *
 * <p>No IntelliJ platform dependencies — uses reflection to access the private
 * method and standard JUnit 5 assertions to verify the compiled {@link Pattern}.
 */
@DisplayName("SearchTextTool.compileSearchPattern")
class SearchTextToolStaticMethodsTest {

    private static Method compileSearchPattern;

    @BeforeAll
    static void setUp() throws NoSuchMethodException {
        compileSearchPattern = SearchTextTool.class.getDeclaredMethod(
            "compileSearchPattern", String.class, boolean.class, boolean.class);
        compileSearchPattern.setAccessible(true);
    }

    /**
     * Helper that invokes the private static method via reflection.
     *
     * @return the compiled {@link Pattern}, or {@code null} when the method returns null
     */
    private static Pattern compile(String query, boolean isRegex, boolean caseSensitive)
        throws ReflectiveOperationException {
        return (Pattern) compileSearchPattern.invoke(null, query, isRegex, caseSensitive);
    }

    // ── Literal mode (isRegex = false) ──────────────────────────────────────

    @Nested
    @DisplayName("Literal mode (isRegex=false)")
    class LiteralMode {

        @Test
        @DisplayName("case-sensitive: 'hello' matches 'hello' but not 'Hello'")
        void literalCaseSensitive() throws ReflectiveOperationException {
            Pattern p = compile("hello", false, true);
            assertNotNull(p);
            assertTrue(p.matcher("hello").find(), "'hello' should match 'hello'");
            assertFalse(p.matcher("Hello").find(), "'hello' should NOT match 'Hello'");
        }

        @Test
        @DisplayName("case-insensitive: 'hello' matches both 'hello' and 'HELLO'")
        void literalCaseInsensitive() throws ReflectiveOperationException {
            Pattern p = compile("hello", false, false);
            assertNotNull(p);
            assertTrue(p.matcher("hello").find(), "'hello' should match 'hello'");
            assertTrue(p.matcher("HELLO").find(), "'hello' should match 'HELLO'");
            assertTrue(p.matcher("HeLLo").find(), "'hello' should match 'HeLLo'");
        }

        @Test
        @DisplayName("special regex chars are treated literally: 'file.txt' matches exactly 'file.txt'")
        void literalSpecialChars() throws ReflectiveOperationException {
            Pattern p = compile("file.txt", false, true);
            assertNotNull(p);
            assertTrue(p.matcher("file.txt").find(), "Should match literal 'file.txt'");
            assertFalse(p.matcher("filextxt").find(),
                "Dot must NOT act as regex wildcard in literal mode");
        }

        @Test
        @DisplayName("dot-star treated literally: 'a.*b' matches 'a.*b' not 'aXXXb'")
        void literalDotStar() throws ReflectiveOperationException {
            Pattern p = compile("a.*b", false, true);
            assertNotNull(p);
            assertTrue(p.matcher("a.*b").find(), "Should match the literal string 'a.*b'");
            assertFalse(p.matcher("aXXXb").find(),
                "'.*' must NOT act as regex wildcard in literal mode");
        }
    }

    // ── Regex mode (isRegex = true) ─────────────────────────────────────────

    @Nested
    @DisplayName("Regex mode (isRegex=true)")
    class RegexMode {

        @Test
        @DisplayName("case-sensitive: 'hel+o' matches 'hello'/'helllllo' but not 'Hello'")
        void regexCaseSensitive() throws ReflectiveOperationException {
            Pattern p = compile("hel+o", true, true);
            assertNotNull(p);
            assertTrue(p.matcher("hello").find(), "'hel+o' should match 'hello'");
            assertTrue(p.matcher("helllllo").find(), "'hel+o' should match 'helllllo'");
            assertFalse(p.matcher("Hello").find(), "'hel+o' should NOT match 'Hello'");
        }

        @Test
        @DisplayName("case-insensitive: 'hel+o' matches 'HELLO'")
        void regexCaseInsensitive() throws ReflectiveOperationException {
            Pattern p = compile("hel+o", true, false);
            assertNotNull(p);
            assertTrue(p.matcher("HELLO").find(), "'hel+o' should match 'HELLO'");
            assertTrue(p.matcher("hello").find(), "'hel+o' should match 'hello'");
            assertTrue(p.matcher("HeLLo").find(), "'hel+o' should match 'HeLLo'");
        }

        @Test
        @DisplayName("alternation groups: '(foo|bar)' matches 'foo' and 'bar'")
        void regexGroups() throws ReflectiveOperationException {
            Pattern p = compile("(foo|bar)", true, true);
            assertNotNull(p);
            assertTrue(p.matcher("foo").find(), "Should match 'foo'");
            assertTrue(p.matcher("bar").find(), "Should match 'bar'");
            assertFalse(p.matcher("baz").find(), "Should NOT match 'baz'");
        }
    }

    // ── Error / edge cases ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Error and edge cases")
    class ErrorCases {

        @Test
        @DisplayName("invalid regex returns null")
        void invalidRegexReturnsNull() throws ReflectiveOperationException {
            Pattern p = compile("[invalid", true, true);
            assertNull(p, "Invalid regex should return null");
        }

        @Test
        @DisplayName("invalid regex in literal mode still compiles (treated as literal)")
        void invalidRegexInLiteralModeCompiles() throws ReflectiveOperationException {
            // In literal mode, Pattern.LITERAL causes the string to be treated
            // as a literal sequence — no regex syntax is parsed, so even
            // malformed regex chars compile fine.
            Pattern p = compile("[invalid", false, true);
            assertNotNull(p, "Literal mode should compile even 'broken' regex chars");
            assertTrue(p.matcher("[invalid").find(), "Should match the literal string");
        }

        @Test
        @DisplayName("empty query matches everything in regex mode")
        void emptyQueryRegexMode() throws ReflectiveOperationException {
            Pattern p = compile("", true, true);
            assertNotNull(p, "Empty regex should compile (matches empty string)");
            assertTrue(p.matcher("anything").find(),
                "Empty regex matches at every position");
        }

        @Test
        @DisplayName("empty query matches everything in literal mode")
        void emptyQueryLiteralMode() throws ReflectiveOperationException {
            Pattern p = compile("", false, true);
            assertNotNull(p, "Empty literal should compile");
            assertTrue(p.matcher("anything").find(),
                "Empty literal matches at every position");
        }
    }

    // ── Pattern flags verification ──────────────────────────────────────────

    @Nested
    @DisplayName("Pattern flags verification")
    class PatternFlags {

        @Test
        @DisplayName("case-insensitive flag is set when caseSensitive=false")
        void caseInsensitiveFlagSet() throws ReflectiveOperationException {
            Pattern p = compile("test", true, false);
            assertNotNull(p);
            assertTrue((p.flags() & Pattern.CASE_INSENSITIVE) != 0,
                "CASE_INSENSITIVE flag should be set");
        }

        @Test
        @DisplayName("CASE_INSENSITIVE flag is NOT set when caseSensitive=true")
        void caseSensitiveFlagNotSet() throws ReflectiveOperationException {
            Pattern p = compile("test", true, true);
            assertNotNull(p);
            assertEquals(0, p.flags() & Pattern.CASE_INSENSITIVE,
                "CASE_INSENSITIVE flag should NOT be set");
        }

        @Test
        @DisplayName("LITERAL flag is set in literal mode (isRegex=false)")
        void literalFlagSet() throws ReflectiveOperationException {
            Pattern p = compile("test", false, true);
            assertNotNull(p);
            assertNotEquals(0, p.flags() & Pattern.LITERAL,
                "LITERAL flag should be set when isRegex=false");
        }

        @Test
        @DisplayName("LITERAL flag is NOT set in regex mode (isRegex=true)")
        void literalFlagNotSetInRegexMode() throws ReflectiveOperationException {
            Pattern p = compile("test", true, true);
            assertNotNull(p);
            assertEquals(0, p.flags() & Pattern.LITERAL,
                "LITERAL flag should NOT be set when isRegex=true");
        }

        @Test
        @DisplayName("literal + case-insensitive: both LITERAL and CASE_INSENSITIVE flags set")
        void literalCaseInsensitiveBothFlags() throws ReflectiveOperationException {
            Pattern p = compile("test", false, false);
            assertNotNull(p);
            assertNotEquals(0, p.flags() & Pattern.LITERAL,
                "LITERAL flag should be set");
            assertNotEquals(0, p.flags() & Pattern.CASE_INSENSITIVE,
                "CASE_INSENSITIVE flag should be set");
        }
    }

    @Nested
    @DisplayName("Constants")
    class Constants {

        @Test
        @DisplayName("MAX_OUTPUT_BYTES is 256 KB")
        void maxOutputBytes() throws ReflectiveOperationException {
            var field = SearchTextTool.class.getDeclaredField("MAX_OUTPUT_BYTES");
            field.setAccessible(true);
            assertEquals(256 * 1024, field.getInt(null));
        }
    }

    @Nested
    @DisplayName("SearchParams record")
    class SearchParamsRecord {

        @Test
        @DisplayName("totalOutputBytes field exists in SearchParams")
        void totalOutputBytesFieldExists() throws ReflectiveOperationException {
            // Verify the record has the totalOutputBytes component (added by this PR)
            var recordClass = Class.forName(
                "com.github.catatafishen.agentbridge.psi.tools.navigation.SearchTextTool$SearchParams");
            var field = recordClass.getDeclaredField("totalOutputBytes");
            assertNotNull(field, "SearchParams should have totalOutputBytes field");
            assertEquals(java.util.concurrent.atomic.AtomicInteger.class, field.getType());
        }
    }
}
