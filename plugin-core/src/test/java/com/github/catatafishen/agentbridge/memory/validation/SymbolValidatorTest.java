package com.github.catatafishen.agentbridge.memory.validation;

import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SymbolValidator} — pure/static logic only.
 * PSI-dependent resolution is not tested here (requires running IntelliJ platform).
 */
class SymbolValidatorTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static SymbolValidator.ValidationResult valid(String ref, String type) {
        return new SymbolValidator.ValidationResult(ref, true, type);
    }

    private static SymbolValidator.ValidationResult invalid(String ref, String type) {
        return new SymbolValidator.ValidationResult(ref, false, type);
    }

    // ── ValidationResult record ───────────────────────────────────────────

    @Nested
    @DisplayName("ValidationResult record")
    class ValidationResultTests {

        @Test
        @DisplayName("stores all constructor fields")
        void holdsAllFields() {
            var result = new SymbolValidator.ValidationResult("com.example.Foo", true, "fqn");
            assertEquals("com.example.Foo", result.reference());
            assertTrue(result.valid());
            assertEquals("fqn", result.type());
        }

        @Test
        @DisplayName("valid=false is preserved")
        void invalidResult_holdsValidFalse() {
            var result = new SymbolValidator.ValidationResult("src/Foo.java:10", false, "file_line");
            assertFalse(result.valid());
            assertEquals("file_line", result.type());
        }

        @Test
        @DisplayName("record equality works")
        void recordEquality() {
            var a = new SymbolValidator.ValidationResult("ref", true, "fqn");
            var b = new SymbolValidator.ValidationResult("ref", true, "fqn");
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }
    }

    // ── allValid ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("allValid")
    class AllValidTests {

        @Test
        @DisplayName("empty list returns true")
        void emptyList_returnsTrue() {
            assertTrue(SymbolValidator.allValid(List.of()));
        }

        @Test
        @DisplayName("all valid results returns true")
        void allValidResults_returnsTrue() {
            List<SymbolValidator.ValidationResult> results = List.of(
                valid("com.example.Foo", "fqn"),
                valid("Foo.java:42", "file_line"),
                valid("src/Foo.java", "file_path")
            );
            assertTrue(SymbolValidator.allValid(results));
        }

        @Test
        @DisplayName("single invalid result returns false")
        void oneInvalid_returnsFalse() {
            List<SymbolValidator.ValidationResult> results = List.of(
                valid("com.example.Foo", "fqn"),
                invalid("Missing.java:99", "file_line")
            );
            assertFalse(SymbolValidator.allValid(results));
        }

        @Test
        @DisplayName("all invalid returns false")
        void allInvalid_returnsFalse() {
            List<SymbolValidator.ValidationResult> results = List.of(
                invalid("com.example.Missing", "fqn"),
                invalid("Ghost.java:1", "file_line")
            );
            assertFalse(SymbolValidator.allValid(results));
        }
    }

    // ── anyInvalid ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("anyInvalid")
    class AnyInvalidTests {

        @Test
        @DisplayName("empty list returns false")
        void emptyList_returnsFalse() {
            assertFalse(SymbolValidator.anyInvalid(List.of()));
        }

        @Test
        @DisplayName("all valid returns false")
        void allValid_returnsFalse() {
            List<SymbolValidator.ValidationResult> results = List.of(
                valid("com.example.Foo", "fqn"),
                valid("src/Foo.java", "file_path")
            );
            assertFalse(SymbolValidator.anyInvalid(results));
        }

        @Test
        @DisplayName("one invalid returns true")
        void oneInvalid_returnsTrue() {
            List<SymbolValidator.ValidationResult> results = List.of(
                valid("com.example.Foo", "fqn"),
                invalid("Ghost.java:99", "file_line")
            );
            assertTrue(SymbolValidator.anyInvalid(results));
        }

        @Test
        @DisplayName("all invalid returns true")
        void allInvalid_returnsTrue() {
            List<SymbolValidator.ValidationResult> results = List.of(
                invalid("Missing.java:1", "file_line"),
                invalid("src/Gone.java", "file_path")
            );
            assertTrue(SymbolValidator.anyInvalid(results));
        }
    }

    // ── validate with empty list ─────────────────────────────────────────

    @Nested
    @DisplayName("validate with empty references")
    class ValidateEmptyListTests {

        @Test
        @DisplayName("empty list returns empty result list")
        void emptyList_returnsEmpty() {
            Project project = mock(Project.class);
            List<SymbolValidator.ValidationResult> results =
                SymbolValidator.validate(project, List.of());
            assertTrue(results.isEmpty());
        }
    }

    // ── validate — unknown-type refs (no project access needed) ──────────

    @Nested
    @DisplayName("validate — unknown pattern refs")
    class ValidateUnknownPatternTests {

        @Test
        @DisplayName("plain word with no dots is unknown type and invalid")
        void plainWord_unknownType() {
            Project project = mock(Project.class);
            List<SymbolValidator.ValidationResult> results =
                SymbolValidator.validate(project, List.of("hello"));
            assertEquals(1, results.size());
            assertEquals("unknown", results.get(0).type());
            assertFalse(results.get(0).valid());
            assertEquals("hello", results.get(0).reference());
        }

        @Test
        @DisplayName("all-uppercase constant is unknown type")
        void uppercaseConstant_unknownType() {
            Project project = mock(Project.class);
            List<SymbolValidator.ValidationResult> results =
                SymbolValidator.validate(project, List.of("CONSTANT_NAME"));
            assertEquals("unknown", results.get(0).type());
            assertFalse(results.get(0).valid());
        }

        @Test
        @DisplayName("numeric string is unknown type")
        void numericString_unknownType() {
            Project project = mock(Project.class);
            List<SymbolValidator.ValidationResult> results =
                SymbolValidator.validate(project, List.of("12345"));
            assertEquals("unknown", results.get(0).type());
            assertFalse(results.get(0).valid());
        }

        @Test
        @DisplayName("multiple unknown-type refs all map to unknown")
        void multipleUnknownRefs_allUnknown() {
            Project project = mock(Project.class);
            List<SymbolValidator.ValidationResult> results =
                SymbolValidator.validate(project, List.of("foo", "BAR", "123"));
            assertEquals(3, results.size());
            results.forEach(r -> {
                assertEquals("unknown", r.type());
                assertFalse(r.valid());
            });
        }
    }

    // ── validate — file_line refs with null base path ────────────────────

    @Nested
    @DisplayName("validate — file:line refs with null project base path")
    class ValidateFileLineRefTests {

        @Test
        @DisplayName("Foo.java:42 with null base path → file_line, invalid")
        void fileLineRef_nullBasePath_invalid() {
            Project project = mock(Project.class);
            when(project.getBasePath()).thenReturn(null);
            List<SymbolValidator.ValidationResult> results =
                SymbolValidator.validate(project, List.of("Foo.java:42"));
            assertEquals(1, results.size());
            assertEquals("file_line", results.get(0).type());
            assertFalse(results.get(0).valid());
            assertEquals("Foo.java:42", results.get(0).reference());
        }

        @Test
        @DisplayName("range ref Bar.java:10-20 with null base path → file_line, invalid")
        void rangeRef_nullBasePath_invalid() {
            Project project = mock(Project.class);
            when(project.getBasePath()).thenReturn(null);
            List<SymbolValidator.ValidationResult> results =
                SymbolValidator.validate(project, List.of("Bar.java:10-20"));
            assertEquals("file_line", results.get(0).type());
            assertFalse(results.get(0).valid());
        }

        @Test
        @DisplayName("Kotlin file ref Config.kt:5 with null base path → file_line, invalid")
        void kotlinFileRef_nullBasePath_invalid() {
            Project project = mock(Project.class);
            when(project.getBasePath()).thenReturn(null);
            List<SymbolValidator.ValidationResult> results =
                SymbolValidator.validate(project, List.of("Config.kt:5"));
            assertEquals("file_line", results.get(0).type());
            assertFalse(results.get(0).valid());
        }
    }

    // ── validate — file_path refs with null base path ────────────────────

    @Nested
    @DisplayName("validate — file path refs with null project base path")
    class ValidateFilePathRefTests {

        @Test
        @DisplayName("src/main/Foo.java with null base path → file_path, invalid")
        void filePath_nullBasePath_invalid() {
            Project project = mock(Project.class);
            when(project.getBasePath()).thenReturn(null);
            List<SymbolValidator.ValidationResult> results =
                SymbolValidator.validate(project, List.of("src/main/Foo.java"));
            assertEquals(1, results.size());
            assertEquals("file_path", results.get(0).type());
            assertFalse(results.get(0).valid());
            assertEquals("src/main/Foo.java", results.get(0).reference());
        }

        @Test
        @DisplayName("deep path with null base path → file_path, invalid")
        void deepPath_nullBasePath_invalid() {
            Project project = mock(Project.class);
            when(project.getBasePath()).thenReturn(null);
            List<SymbolValidator.ValidationResult> results =
                SymbolValidator.validate(project, List.of("plugin-core/src/main/java/com/example/Foo.java"));
            assertEquals("file_path", results.get(0).type());
            assertFalse(results.get(0).valid());
        }

        @Test
        @DisplayName("Kotlin file path with null base path → file_path, invalid")
        void kotlinFilePath_nullBasePath_invalid() {
            Project project = mock(Project.class);
            when(project.getBasePath()).thenReturn(null);
            List<SymbolValidator.ValidationResult> results =
                SymbolValidator.validate(project, List.of("ui/panel/ChatPanel.kt"));
            assertEquals("file_path", results.get(0).type());
            assertFalse(results.get(0).valid());
        }
    }

    // ── validate — mixed references ───────────────────────────────────────

    @Nested
    @DisplayName("validate — mixed reference types")
    class ValidateMixedRefsTests {

        @Test
        @DisplayName("mixed refs with null base path — each gets correct type")
        void mixedRefs_correctTypes() {
            Project project = mock(Project.class);
            when(project.getBasePath()).thenReturn(null);
            List<SymbolValidator.ValidationResult> results =
                SymbolValidator.validate(project, List.of(
                    "Foo.java:42",         // file_line
                    "src/main/Foo.java",   // file_path
                    "hello"               // unknown
                ));
            assertEquals(3, results.size());
            assertEquals("file_line", results.get(0).type());
            assertEquals("file_path", results.get(1).type());
            assertEquals("unknown", results.get(2).type());
            // all invalid (null base path or no match)
            results.forEach(r -> assertFalse(r.valid()));
        }
    }
}
