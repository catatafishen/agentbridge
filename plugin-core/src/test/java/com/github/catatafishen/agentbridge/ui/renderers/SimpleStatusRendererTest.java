package com.github.catatafishen.agentbridge.ui.renderers;

import kotlin.text.Regex;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests regex patterns in {@link SimpleStatusRenderer}.
 */
class SimpleStatusRendererTest {

    private static final SimpleStatusRenderer R = SimpleStatusRenderer.INSTANCE;

    @Nested
    class DeleteFilePattern {

        @Test
        void matchesDeleteOutput() {
            var match = R.getDELETE_FILE().find("Deleted file: src/old/File.java", 0);
            assertNotNull(match);
            assertEquals("src/old/File.java", match.getGroupValues().get(1));
        }

        @Test
        void doesNotMatchOtherOutput() {
            assertNull(R.getDELETE_FILE().find("Created file: new.java", 0));
        }
    }

    @Nested
    class UndoPattern {

        @Test
        void matchesUndoWithActions() {
            var match = R.getUNDO().find("Undid 2 action(s) on src/Main.java: edit, format", 0);
            assertNotNull(match);
            assertEquals("2", match.getGroupValues().get(1));
            assertEquals("src/Main.java", match.getGroupValues().get(2));
            assertEquals("edit, format", match.getGroupValues().get(3));
        }

        @Test
        void matchesUndoWithoutActions() {
            var match = R.getUNDO().find("Undid 1 action(s) on File.kt:", 0);
            assertNotNull(match);
            assertEquals("1", match.getGroupValues().get(1));
            assertEquals("File.kt", match.getGroupValues().get(2));
        }
    }

    @Nested
    class FormatCodePattern {

        @Test
        void matchesFormatOutput() {
            var match = R.getFORMAT_CODE().find("Code formatted: src/Main.java", 0);
            assertNotNull(match);
            assertEquals("src/Main.java", match.getGroupValues().get(1));
        }
    }

    @Nested
    class OptimizePattern {

        @Test
        void matchesOptimizeOutput() {
            var match = R.getOPTIMIZE().find("Imports optimized: src/Main.java", 0);
            assertNotNull(match);
            assertEquals("src/Main.java", match.getGroupValues().get(1));
        }
    }

    @Nested
    class DictionaryPattern {

        @Test
        void matchesDictionaryAdd() {
            var match = R.getDICTIONARY().find("Added 'agentbridge' to project dictionary", 0);
            assertNotNull(match);
            assertEquals("agentbridge", match.getGroupValues().get(1));
        }
    }

    @Nested
    class SuppressPattern {

        @Test
        void matchesSuppressOutput() {
            var match = R.getSUPPRESS().find("Suppressed 'SpellCheckingInspection' at line 42 in src/Main.java", 0);
            assertNotNull(match);
            assertEquals("SpellCheckingInspection", match.getGroupValues().get(1));
            assertEquals("42", match.getGroupValues().get(2));
            assertEquals("src/Main.java", match.getGroupValues().get(3));
        }
    }

    @Nested
    class OpenedPattern {

        @Test
        void matchesOpenWithLine() {
            var match = R.getOPENED().matchEntire("Opened src/Main.java (line 42)");
            assertNotNull(match);
            assertEquals("src/Main.java", match.getGroupValues().get(1));
            assertEquals("42", match.getGroupValues().get(2));
        }

        @Test
        void matchesOpenWithoutLine() {
            var match = R.getOPENED().matchEntire("Opened src/Main.java");
            assertNotNull(match);
            assertEquals("src/Main.java", match.getGroupValues().get(1));
            assertEquals("", match.getGroupValues().get(2));
        }
    }

    @Nested
    class ThemePattern {

        @Test
        void matchesThemeSet() {
            var match = R.getTHEME().find("Theme set to: Darcula", 0);
            assertNotNull(match);
            assertEquals("Darcula", match.getGroupValues().get(1));
        }
    }

    @Nested
    class MarkDirPattern {

        @Test
        void matchesMarkDir() {
            var match = R.getMARK_DIR().find("Directory src/gen marked as generated_sources", 0);
            assertNotNull(match);
            assertEquals("src/gen", match.getGroupValues().get(1));
            assertEquals("generated_sources", match.getGroupValues().get(2));
        }
    }

    @Nested
    class DownloadPattern {

        @Test
        void matchesDownload() {
            assertNotNull(R.getDOWNLOAD().find("Sources downloaded", 0));
        }

        @Test
        void doesNotMatchPartial() {
            assertNull(R.getDOWNLOAD().matchEntire("Sources download failed"));
        }
    }

    @Nested
    class ReloadPattern {

        @Test
        void matchesFileReload() {
            var match = R.getRELOAD().find("File src/Main.java reloaded from disk", 0);
            assertNotNull(match);
            assertEquals("src/Main.java", match.getGroupValues().get(1));
        }

        @Test
        void matchesProjectRootReload() {
            var match = R.getRELOAD().find("Project root reloaded from disk", 0);
            assertNotNull(match);
        }
    }

    @Nested
    class GenericSuccessPattern {

        @Test
        void matchesCheckmark() {
            var match = R.getGENERIC_SUCCESS().find("✓ Operation completed", 0);
            assertNotNull(match);
            assertEquals("Operation completed", match.getGroupValues().get(1));
        }
    }

    @Nested
    class ErrorPattern {

        @Test
        void matchesError() {
            var match = R.getERROR().find("Error: File not found", 0);
            assertNotNull(match);
            assertEquals("File not found", match.getGroupValues().get(1));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "Warning: something",
            "Success!",
            "No errors found",
        })
        void doesNotMatchNonErrors(String input) {
            assertNull(R.getERROR().find(input, 0));
        }
    }
}
