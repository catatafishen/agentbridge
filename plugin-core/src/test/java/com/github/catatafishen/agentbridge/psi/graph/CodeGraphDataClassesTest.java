package com.github.catatafishen.agentbridge.psi.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the code graph data classes and settings that are new in this PR.
 * All classes are instantiable without IntelliJ platform fixtures.
 */
@DisplayName("Code Graph Data Classes")
class CodeGraphDataClassesTest {

    @Nested
    @DisplayName("CodeGraphSettings.State")
    class SettingsState {

        @Test
        void defaultsAreCorrect() {
            var state = new CodeGraphSettings.State();
            assertFalse(state.isEnabled(), "Should be disabled by default");
            assertTrue(state.isAutoRefreshOnAgentEdit(), "Auto-refresh should default to true");
            assertEquals(0L, state.getLastFullIndexAt());
        }

        @Test
        void enabledRoundTrips() {
            var state = new CodeGraphSettings.State();
            state.setEnabled(true);
            assertTrue(state.isEnabled());
            state.setEnabled(false);
            assertFalse(state.isEnabled());
        }

        @Test
        void autoRefreshRoundTrips() {
            var state = new CodeGraphSettings.State();
            state.setAutoRefreshOnAgentEdit(false);
            assertFalse(state.isAutoRefreshOnAgentEdit());
            state.setAutoRefreshOnAgentEdit(true);
            assertTrue(state.isAutoRefreshOnAgentEdit());
        }

        @Test
        void lastFullIndexAtRoundTrips() {
            var state = new CodeGraphSettings.State();
            state.setLastFullIndexAt(1717891200000L);
            assertEquals(1717891200000L, state.getLastFullIndexAt());
        }
    }

    @Nested
    @DisplayName("CodeGraphSettings (outer class delegation)")
    class SettingsOuter {

        @Test
        void delegatesToState() {
            var settings = new CodeGraphSettings();
            assertFalse(settings.isEnabled());

            settings.setEnabled(true);
            assertTrue(settings.isEnabled());
            assertTrue(settings.getState().isEnabled());
        }

        @Test
        void loadStateReplacesInternalState() {
            var settings = new CodeGraphSettings();
            var newState = new CodeGraphSettings.State();
            newState.setEnabled(true);
            newState.setAutoRefreshOnAgentEdit(false);
            newState.setLastFullIndexAt(12345L);

            settings.loadState(newState);

            assertTrue(settings.isEnabled());
            assertFalse(settings.isAutoRefreshOnAgentEdit());
            assertEquals(12345L, settings.getLastFullIndexAt());
        }

        @Test
        void getStateReturnsCurrentState() {
            var settings = new CodeGraphSettings();
            settings.setLastFullIndexAt(99L);
            assertEquals(99L, settings.getState().getLastFullIndexAt());
        }
    }

    @Nested
    @DisplayName("NodeData")
    class NodeDataTest {

        @Test
        void constructorSetsAllFields() {
            var node = new NodeData("id1", "MyClass", "class", "com.example.MyClass",
                "src/MyClass.java", 10, "JAVA");

            assertEquals("id1", node.id);
            assertEquals("MyClass", node.label);
            assertEquals("class", node.kind);
            assertEquals("com.example.MyClass", node.fqn);
            assertEquals("src/MyClass.java", node.sourceFile);
            assertEquals(10, node.sourceLine);
            assertEquals("JAVA", node.language);
        }

        @Test
        void nullFqnAllowed() {
            var node = new NodeData("id2", "main", "function", null,
                "src/main.py", 1, "Python");
            assertNull(node.fqn);
        }

        @Test
        void zeroSourceLineMeansUnknown() {
            var node = new NodeData("id3", "utils", "file", null,
                "src/utils.js", 0, "JavaScript");
            assertEquals(0, node.sourceLine);
        }

        @Test
        void toStringContainsKeyFields() {
            var node = new NodeData("n1", "Foo", "class", "com.Foo",
                "Foo.java", 5, "JAVA");
            String s = node.toString();
            assertTrue(s.contains("n1"));
            assertTrue(s.contains("class"));
            assertTrue(s.contains("Foo.java"));
        }
    }

    @Nested
    @DisplayName("EdgeData")
    class EdgeDataTest {

        @Test
        void constructorSetsAllFields() {
            var edge = new EdgeData("src1", "tgt1", "calls", "src/Main.java", 42);

            assertEquals("src1", edge.sourceId);
            assertEquals("tgt1", edge.targetId);
            assertEquals("calls", edge.relation);
            assertEquals("src/Main.java", edge.sourceFile);
            assertEquals(42, edge.sourceLine);
        }

        @Test
        void nullSourceFileAllowed() {
            var edge = new EdgeData("a", "b", "implements", null, 0);
            assertNull(edge.sourceFile);
            assertEquals(0, edge.sourceLine);
        }

        @Test
        void toStringShowsRelation() {
            var edge = new EdgeData("x", "y", "extends", "A.java", 1);
            String s = edge.toString();
            assertTrue(s.contains("x"));
            assertTrue(s.contains("y"));
            assertTrue(s.contains("extends"));
        }

        @Test
        void allRelationTypesWork() {
            String[] relations = {"calls", "extends", "implements", "imports", "uses", "overrides"};
            for (String rel : relations) {
                var edge = new EdgeData("a", "b", rel, null, 0);
                assertEquals(rel, edge.relation);
            }
        }
    }

    @Nested
    @DisplayName("CommitRecord and FileChange")
    class CommitRecordTest {

        @Test
        void commitRecordFieldsAccessible() {
            var files = java.util.List.of(
                new GitCommitIndexer.FileChange("src/Main.java", "M"),
                new GitCommitIndexer.FileChange("src/Test.java", "A")
            );
            var record = new GitCommitIndexer.CommitRecord(
                "abc123def456", "abc123d", "Author", "a@b.com",
                "2026-01-01T00:00:00Z", "feat: something", files
            );

            assertEquals("abc123def456", record.hash());
            assertEquals("abc123d", record.shortHash());
            assertEquals("Author", record.author());
            assertEquals("a@b.com", record.email());
            assertEquals("2026-01-01T00:00:00Z", record.timestamp());
            assertEquals("feat: something", record.message());
            assertEquals(2, record.files().size());
        }

        @Test
        void fileChangeFields() {
            var fc = new GitCommitIndexer.FileChange("path/to/file.kt", "D");
            assertEquals("path/to/file.kt", fc.path());
            assertEquals("D", fc.changeType());
        }
    }
}
