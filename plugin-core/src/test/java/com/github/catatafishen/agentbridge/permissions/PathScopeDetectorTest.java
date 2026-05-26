package com.github.catatafishen.agentbridge.permissions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PathScopeDetector")
class PathScopeDetectorTest {

    // ── classifyTool ────────────────────────────────────────────────────

    @Test
    @DisplayName("classifyTool: attach_external_dir is always OUTSIDE")
    void classifyToolAttachExternalDir() {
        assertEquals(PathScope.OUTSIDE_PROJECT, PathScopeDetector.classifyTool("attach_external_dir"));
    }

    @Test
    @DisplayName("classifyTool: path-bearing tools return null (need arg inspection)")
    void classifyToolPathBearingReturnsNull() {
        assertNull(PathScopeDetector.classifyTool("read_file"));
        assertNull(PathScopeDetector.classifyTool("list_directory_tree"));
        assertNull(PathScopeDetector.classifyTool("list_project_files"));
        assertNull(PathScopeDetector.classifyTool("get_file_outline"));
        assertNull(PathScopeDetector.classifyTool("find_file"));
    }

    @Test
    @DisplayName("classifyTool: search-scope tools return null (need arg inspection)")
    void classifyToolSearchScopeReturnsNull() {
        assertNull(PathScopeDetector.classifyTool("search_text"));
        assertNull(PathScopeDetector.classifyTool("search_symbols"));
        assertNull(PathScopeDetector.classifyTool("find_references"));
        assertNull(PathScopeDetector.classifyTool("find_implementations"));
    }

    @Test
    @DisplayName("classifyTool: unknown / non-path tools are NOT_APPLICABLE")
    void classifyToolNonPathDefault() {
        assertEquals(PathScope.NOT_APPLICABLE, PathScopeDetector.classifyTool("git_status"));
        assertEquals(PathScope.NOT_APPLICABLE, PathScopeDetector.classifyTool("build_project"));
        assertEquals(PathScope.NOT_APPLICABLE, PathScopeDetector.classifyTool("run_tests"));
        assertEquals(PathScope.NOT_APPLICABLE, PathScopeDetector.classifyTool("write_file"));
        assertEquals(PathScope.NOT_APPLICABLE, PathScopeDetector.classifyTool("totally_unknown_tool"));
    }

    // ── detectByPath ────────────────────────────────────────────────────

    @Test
    @DisplayName("detectByPath: null path is INSIDE")
    void detectByPathNull() {
        assertEquals(PathScope.INSIDE_PROJECT,
            PathScopeDetector.detectByPath(null, "/proj", null));
    }

    @Test
    @DisplayName("detectByPath: blank path is INSIDE")
    void detectByPathBlank() {
        assertEquals(PathScope.INSIDE_PROJECT,
            PathScopeDetector.detectByPath("   ", "/proj", null));
    }

    @Test
    @DisplayName("detectByPath: relative path with project root is INSIDE")
    void detectByPathRelative(@TempDir Path tmp) {
        String root = tmp.toAbsolutePath().toString();
        assertEquals(PathScope.INSIDE_PROJECT,
            PathScopeDetector.detectByPath("src/Foo.java", root, null));
    }

    @Test
    @DisplayName("detectByPath: relative path with no project root is INSIDE")
    void detectByPathRelativeNoRoot() {
        assertEquals(PathScope.INSIDE_PROJECT,
            PathScopeDetector.detectByPath("src/Foo.java", null, null));
    }

    @Test
    @DisplayName("detectByPath: absolute path under project root is INSIDE")
    void detectByPathAbsoluteInside(@TempDir Path tmp) throws Exception {
        Path inside = Files.createDirectories(tmp.resolve("src/main"));
        String root = tmp.toAbsolutePath().toString();
        assertEquals(PathScope.INSIDE_PROJECT,
            PathScopeDetector.detectByPath(inside.toString(), root, null));
    }

    @Test
    @DisplayName("detectByPath: absolute path outside project root is OUTSIDE")
    void detectByPathAbsoluteOutside(@TempDir Path tmp) {
        // tmp is the project, /etc is clearly outside.
        String root = tmp.toAbsolutePath().toString();
        assertEquals(PathScope.OUTSIDE_PROJECT,
            PathScopeDetector.detectByPath("/etc/hosts", root, null));
    }

    @Test
    @DisplayName("detectByPath: absolute path under an attached external root is INSIDE")
    void detectByPathAttachedRootInside(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("proj"));
        Path external = Files.createDirectories(tmp.resolve("external/lib"));
        String attachedCsv = external.toAbsolutePath().toString();

        Path inExternal = Files.createDirectories(external.resolve("src"));

        assertEquals(PathScope.INSIDE_PROJECT,
            PathScopeDetector.detectByPath(inExternal.toString(),
                project.toAbsolutePath().toString(),
                attachedCsv));
    }

    @Test
    @DisplayName("detectByPath: absolute path outside both project and attached roots is OUTSIDE")
    void detectByPathOutsideAttachedRoots(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("proj"));
        Path external = Files.createDirectories(tmp.resolve("external/lib"));
        String attachedCsv = external.toAbsolutePath().toString();

        assertEquals(PathScope.OUTSIDE_PROJECT,
            PathScopeDetector.detectByPath("/etc/hosts",
                project.toAbsolutePath().toString(),
                attachedCsv));
    }

    @Test
    @DisplayName("detectByPath: multiple attached roots in CSV are all considered")
    void detectByPathMultipleAttachedRoots(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("proj"));
        Path ext1 = Files.createDirectories(tmp.resolve("ext1"));
        Path ext2 = Files.createDirectories(tmp.resolve("ext2"));
        String csv = ext1.toAbsolutePath() + " , " + ext2.toAbsolutePath();

        Path target = Files.createDirectories(ext2.resolve("deep"));

        assertEquals(PathScope.INSIDE_PROJECT,
            PathScopeDetector.detectByPath(target.toString(),
                project.toAbsolutePath().toString(),
                csv));
    }

    @Test
    @DisplayName("detectByPath: invalid path string is treated as INSIDE (degrades gracefully)")
    void detectByPathInvalidPath() {
        // Java's Path.of rarely throws on POSIX, but we still cover the contract.
        assertEquals(PathScope.INSIDE_PROJECT,
            PathScopeDetector.detectByPath("", "/proj", null));
    }

    // ── detectBySearchScope ─────────────────────────────────────────────

    @Test
    @DisplayName("detectBySearchScope: 'libraries' is OUTSIDE")
    void detectBySearchScopeLibraries() {
        assertEquals(PathScope.OUTSIDE_PROJECT, PathScopeDetector.detectBySearchScope("libraries"));
    }

    @Test
    @DisplayName("detectBySearchScope: 'all' is OUTSIDE")
    void detectBySearchScopeAll() {
        assertEquals(PathScope.OUTSIDE_PROJECT, PathScopeDetector.detectBySearchScope("all"));
    }

    @Test
    @DisplayName("detectBySearchScope: 'project' is INSIDE")
    void detectBySearchScopeProject() {
        assertEquals(PathScope.INSIDE_PROJECT, PathScopeDetector.detectBySearchScope("project"));
    }

    @Test
    @DisplayName("detectBySearchScope: 'production' / 'tests' are INSIDE")
    void detectBySearchScopeProductionAndTests() {
        assertEquals(PathScope.INSIDE_PROJECT, PathScopeDetector.detectBySearchScope("production"));
        assertEquals(PathScope.INSIDE_PROJECT, PathScopeDetector.detectBySearchScope("tests"));
    }

    @Test
    @DisplayName("detectBySearchScope: null is INSIDE")
    void detectBySearchScopeNull() {
        assertEquals(PathScope.INSIDE_PROJECT, PathScopeDetector.detectBySearchScope(null));
    }

    @Test
    @DisplayName("detectBySearchScope: unrecognised value is INSIDE")
    void detectBySearchScopeUnknown() {
        assertEquals(PathScope.INSIDE_PROJECT, PathScopeDetector.detectBySearchScope("module"));
    }

    @Test
    @DisplayName("detectBySearchScope: case- and whitespace-insensitive")
    void detectBySearchScopeCaseInsensitive() {
        assertEquals(PathScope.OUTSIDE_PROJECT, PathScopeDetector.detectBySearchScope("  LIBRARIES "));
        assertEquals(PathScope.OUTSIDE_PROJECT, PathScopeDetector.detectBySearchScope("All"));
    }

    // ── tool-set membership ─────────────────────────────────────────────

    @Test
    @DisplayName("Tool-set membership: read_file is path-bearing")
    void readFileIsPathBearing() {
        assertTrue(PathScopeDetector.PATH_BEARING_TOOLS.contains("read_file"));
    }

    @Test
    @DisplayName("Tool-set membership: search_text is search-scope")
    void searchTextIsSearchScope() {
        assertTrue(PathScopeDetector.SEARCH_SCOPE_TOOLS.contains("search_text"));
    }

    @Test
    @DisplayName("Tool-set membership: attach_external_dir is always-outside")
    void attachExternalDirIsAlwaysOutside() {
        assertTrue(PathScopeDetector.ALWAYS_OUTSIDE_TOOLS.contains("attach_external_dir"));
    }
}
