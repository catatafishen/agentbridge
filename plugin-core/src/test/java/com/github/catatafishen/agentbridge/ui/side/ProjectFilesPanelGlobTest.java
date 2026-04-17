package com.github.catatafishen.agentbridge.ui.side;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ProjectFilesPanel#glob(String, String, String)} — the simple glob matcher
 * used to populate the Project Files tree. Pure I/O; does not need the IDE fixture.
 */
final class ProjectFilesPanelGlobTest {

    @Test
    void glob_missingDirectoryReturnsEmpty(@TempDir Path base) {
        List<ProjectFilesPanel.FileNode> result =
            ProjectFilesPanel.glob(base.toString(), "nonexistent/dir", "*.md");
        assertEquals(List.of(), result);
    }

    @Test
    void glob_flatExtensionPatternMatchesAndSorts(@TempDir Path base) throws IOException {
        Path dir = Files.createDirectories(base.resolve("agents"));
        Files.writeString(dir.resolve("zebra.md"), "");
        Files.writeString(dir.resolve("alpha.md"), "");
        Files.writeString(dir.resolve("skip.txt"), "");

        List<ProjectFilesPanel.FileNode> result =
            ProjectFilesPanel.glob(base.toString(), "agents", "*.md");

        assertEquals(2, result.size());
        assertEquals("alpha.md", result.get(0).label);
        assertEquals("zebra.md", result.get(1).label);
    }

    @Test
    void glob_matchesMultiDotSuffix(@TempDir Path base) throws IOException {
        Path dir = Files.createDirectories(base.resolve("inst"));
        Files.writeString(dir.resolve("build.instructions.md"), "");
        Files.writeString(dir.resolve("test.instructions.md"), "");
        Files.writeString(dir.resolve("readme.md"), "");

        List<ProjectFilesPanel.FileNode> result =
            ProjectFilesPanel.glob(base.toString(), "inst", "*.instructions.md");

        assertEquals(2, result.size());
        assertEquals("build.instructions.md", result.get(0).label);
        assertEquals("test.instructions.md", result.get(1).label);
    }

    @Test
    void glob_nestedFileNamePattern(@TempDir Path base) throws IOException {
        Path skills = Files.createDirectories(base.resolve("skills"));
        Path skillA = Files.createDirectories(skills.resolve("skill-a"));
        Path skillB = Files.createDirectories(skills.resolve("skill-b"));
        Files.writeString(skillA.resolve("SKILL.md"), "");
        Files.writeString(skillB.resolve("SKILL.md"), "");
        // Subdirectory without SKILL.md should be ignored
        Files.createDirectories(skills.resolve("empty-skill"));

        List<ProjectFilesPanel.FileNode> result =
            ProjectFilesPanel.glob(base.toString(), "skills", "*/SKILL.md");

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(n -> n.label.equals("skill-a/SKILL.md")));
        assertTrue(result.stream().anyMatch(n -> n.label.equals("skill-b/SKILL.md")));
    }

    @Test
    void fileNode_existsIsComputedOnce(@TempDir Path base) throws IOException {
        Path file = base.resolve("TODO.md");
        ProjectFilesPanel.FileNode missing = new ProjectFilesPanel.FileNode(
            base.toString(), "TODO.md", "TODO", true);
        assertEquals(false, missing.exists);

        Files.writeString(file, "");
        ProjectFilesPanel.FileNode present = new ProjectFilesPanel.FileNode(
            base.toString(), "TODO.md", "TODO", true);
        assertEquals(true, present.exists);
        // Cached state survives subsequent deletion — renderer won't stat again
        Files.delete(file);
        assertEquals(true, present.exists);
    }

    @Test
    void relativize_returnsPosixRelativePath(@TempDir Path base) throws IOException {
        Path nested = Files.createDirectories(base.resolve("a/b"));
        Path file = Files.writeString(nested.resolve("c.md"), "");
        String rel = ProjectFilesPanel.relativize(base.toString(), file.toFile());
        assertEquals("a/b/c.md", rel);
    }
}
