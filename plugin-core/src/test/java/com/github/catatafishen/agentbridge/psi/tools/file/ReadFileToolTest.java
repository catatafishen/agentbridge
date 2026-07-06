package com.github.catatafishen.agentbridge.psi.tools.file;

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Platform tests for {@link ReadFileTool}.
 *
 * <p>JUnit 3 style (extends BasePlatformTestCase): test methods must be {@code public void testXxx()}.
 * Run via Gradle only: {@code ./gradlew :plugin-core:test}.
 *
 * <p><b>File creation note:</b> {@code myFixture.addFileToProject()} creates in-memory VFS files
 * that are invisible to {@code LocalFileSystem#findFileByPath}. Tests use real disk files
 * created under a temp directory, registered in the VFS via
 * {@code LocalFileSystem#refreshAndFindFileByPath}.
 */
public class ReadFileToolTest extends BasePlatformTestCase {

    private ReadFileTool tool;
    private Path tempDir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Prevent followFileIfEnabled from opening Editors during tests —
        // the default is true, and Editors opened by the tool won't be released,
        // causing CompoundRuntimeException / DisposalException in tearDown.
        PropertiesComponent.getInstance(getProject())
            .setValue(ToolLayerSettings.FOLLOW_AGENT_FILES_KEY, "false");
        tool = new ReadFileTool(getProject());
        tempDir = Files.createTempDirectory("read-file-tool-test");
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            deleteDir(tempDir);
        } finally {
            super.tearDown();
        }
    }

    /**
     * Creates a real file on disk and registers it in the VFS so that
     * {@code LocalFileSystem#findFileByPath} can find it during {@code execute()}.
     */
    private VirtualFile createTestFile(String name, String content) {
        try {
            Path file = tempDir.resolve(name);
            Files.writeString(file, content);
            VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(file.toString());
            assertNotNull("Failed to register test file in VFS: " + file, vf);
            return vf;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create test file: " + name, e);
        }
    }

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

    public void testMissingPathReturnsError() {
        String result = tool.execute(new JsonObject());
        assertEquals(ToolUtils.ERROR_PATH_REQUIRED, result);
    }

    public void testNullPathReturnsError() {
        JsonObject args = new JsonObject();
        args.add("path", null);
        String result = tool.execute(args);
        assertEquals(ToolUtils.ERROR_PATH_REQUIRED, result);
    }

    public void testFileNotFoundReturnsError() {
        JsonObject args = new JsonObject();
        args.addProperty("path", "/nonexistent/absolutely/missing.txt");
        String result = tool.execute(args);
        assertTrue("Expected file-not-found error, got: " + result,
            result.startsWith(ToolUtils.ERROR_FILE_NOT_FOUND));
    }

    public void testReadFullFileContainsLineTotalHeader() {
        VirtualFile vf = createTestFile("sample.txt", "hello\nworld\nfoo");
        JsonObject args = new JsonObject();
        args.addProperty("path", vf.getPath());

        String result = tool.execute(args);
        assertTrue("Expected [N lines total] header, got: " + result,
            result.contains("lines total"));
        assertTrue(result.contains("hello"));
        assertTrue(result.contains("world"));
        assertTrue(result.contains("foo"));
    }

    public void testReadFullFileTotalLineCount() {
        VirtualFile vf = createTestFile("lines.txt", "a\nb\nc");
        JsonObject args = new JsonObject();
        args.addProperty("path", vf.getPath());

        String result = tool.execute(args);
        assertTrue("Expected [3 lines total], got: " + result,
            result.contains("[3 lines total]"));
    }

    public void testLineRangeReturnsNumberedLines() {
        VirtualFile vf = createTestFile("range.txt", "line1\nline2\nline3");
        JsonObject args = new JsonObject();
        args.addProperty("path", vf.getPath());
        args.addProperty("start_line", 2);
        args.addProperty("end_line", 2);

        String result = tool.execute(args);
        assertTrue("Expected numbered line 2, got: " + result, result.contains("2: line2"));
        assertFalse("Should not include line1, got: " + result, result.contains("line1"));
        assertFalse("Should not include line3, got: " + result, result.contains("line3"));
    }

    public void testLineRangeMultipleLines() {
        VirtualFile vf = createTestFile("multi.txt", "a\nb\nc\nd\ne");
        JsonObject args = new JsonObject();
        args.addProperty("path", vf.getPath());
        args.addProperty("start_line", 2);
        args.addProperty("end_line", 4);

        String result = tool.execute(args);
        assertTrue(result.contains("2: b"));
        assertTrue(result.contains("3: c"));
        assertTrue(result.contains("4: d"));
        assertFalse(result.contains("1: a"));
        assertFalse(result.contains("5: e"));
    }

    public void testLineRangeNoLineTotalHeader() {
        VirtualFile vf = createTestFile("hdr.txt", "x\ny\nz");
        JsonObject args = new JsonObject();
        args.addProperty("path", vf.getPath());
        args.addProperty("start_line", 1);
        args.addProperty("end_line", 2);

        String result = tool.execute(args);
        assertFalse("Line range should not include total header, got: " + result,
            result.contains("lines total"));
        assertTrue(result.contains("1: x"));
        assertTrue(result.contains("2: y"));
    }

    public void testNullStartLineReturnsOne() {
        VirtualFile vf = createTestFile("nullstart.txt", "a\nb\nc");
        JsonObject args = new JsonObject();
        args.addProperty("path", vf.getPath());
        args.add("start_line", com.google.gson.JsonNull.INSTANCE);

        String result = tool.execute(args);
        assertTrue("Expected full file, got: " + result,
            result.contains("a\nb\nc"));
    }

    public void testZeroStartLineReturnsFallsBackToOne() {
        VirtualFile vf = createTestFile("zerostart.txt", "a\nb\nc");
        JsonObject args = new JsonObject();
        args.addProperty("path", vf.getPath());
        args.addProperty("start_line", 0);

        String result = tool.execute(args);
        assertTrue("Expected full file for start_line=0, got: " + result,
            result.contains("a\nb\nc"));
    }

    public void testNegativeStartLineReturnsError() {
        VirtualFile vf = createTestFile("negstart.txt", "a\nb\nc");
        JsonObject args = new JsonObject();
        args.addProperty("path", vf.getPath());
        args.addProperty("start_line", -1);

        String result = tool.execute(args);
        assertTrue("Expected error for start_line=-1, got: " + result,
            result.startsWith("Error:") && result.contains("start_line"));
    }

    /**
     * start_line=1 with end_line=null triggers range mode (|| means only one bound is needed).
     * The result should be numbered lines from line 1 to EOF, without the [N lines total] header.
     */
    public void testNullEndLineReturnsEOF() {
        VirtualFile vf = createTestFile("nullend.txt", "a\nb\nc");
        JsonObject args = new JsonObject();
        args.addProperty("path", vf.getPath());
        args.addProperty("start_line", 1);
        args.add("end_line", com.google.gson.JsonNull.INSTANCE);

        String result = tool.execute(args);
        // start_line=1 is set → rangeMode=true → numbered output, no header
        assertFalse("Range mode should not include [N lines total] header, got: " + result,
            result.contains("lines total"));
        assertTrue("Expected numbered line 1, got: " + result, result.contains("1: a"));
        assertTrue("Expected numbered line 2, got: " + result, result.contains("2: b"));
        assertTrue("Expected numbered line 3, got: " + result, result.contains("3: c"));
    }

    public void testZeroEndLineReturnsEOF() {
        VirtualFile vf = createTestFile("zeroend.txt", "a\nb\nc");
        JsonObject args = new JsonObject();
        args.addProperty("path", vf.getPath());
        args.addProperty("end_line", 0);

        String result = tool.execute(args);
        assertTrue("Expected full file, got: " + result,
            result.contains("a\nb\nc"));
    }

    public void testRangeWithOnlyStartLine() {
        // start_line=2 with no end_line → rangeMode=true (2 > 0 || 0 > 0), end defaults to EOF
        VirtualFile vf = createTestFile("only-start.txt", "line1\nline2\nline3");
        JsonObject args = new JsonObject();
        args.addProperty("path", vf.getPath());
        args.addProperty("start_line", 2);
        // end_line intentionally omitted

        String result = tool.execute(args);
        assertFalse("Range mode should not include [N lines total] header, got: " + result,
            result.contains("lines total"));
        assertTrue("Expected numbered line 2, got: " + result, result.contains("2: line2"));
        assertTrue("Expected numbered line 3, got: " + result, result.contains("3: line3"));
        assertFalse("Should not include line 1, got: " + result, result.contains("1: line1"));
    }

    public void testRangeWithOnlyEndLine() {
        // end_line=2 with no start_line → rangeMode=true (0 > 0 || 2 > 0), start defaults to line 1
        VirtualFile vf = createTestFile("only-end.txt", "line1\nline2\nline3");
        JsonObject args = new JsonObject();
        args.addProperty("path", vf.getPath());
        // start_line intentionally omitted
        args.addProperty("end_line", 2);

        String result = tool.execute(args);
        assertFalse("Range mode should not include [N lines total] header, got: " + result,
            result.contains("lines total"));
        assertTrue("Expected numbered line 1, got: " + result, result.contains("1: line1"));
        assertTrue("Expected numbered line 2, got: " + result, result.contains("2: line2"));
        assertFalse("Should not include line 3, got: " + result, result.contains("3: line3"));
    }

    public void testStringStartLineReturnsError() {
        VirtualFile vf = createTestFile("stringstart.txt", "a\nb\nc");
        JsonObject args = new JsonObject();
        args.addProperty("path", vf.getPath());
        args.addProperty("start_line", "foo");
        String result = tool.execute(args);
        assertTrue("Expected error for string start_line, got: " + result,
            result.startsWith("Error") && result.contains("start_line"));
    }

    public void testNegativeEndLineReturnsError() {
        VirtualFile vf = createTestFile("negend.txt", "a\nb\nc");
        JsonObject args = new JsonObject();
        args.addProperty("path", vf.getPath());
        args.addProperty("end_line", -1);
        String result = tool.execute(args);
        assertTrue("Expected error for end_line=-1, got: " + result,
            result.startsWith("Error") && result.contains("end_line"));
    }

    public void testLargeFileTruncation() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 2500; i++) {
            sb.append("line ").append(i).append("\n");
        }
        VirtualFile vf = createTestFile("large.txt", sb.toString());
        JsonObject args = new JsonObject();
        args.addProperty("path", vf.getPath());

        String result = tool.execute(args);
        assertTrue("Expected truncation notice, got: " + result,
            result.contains("[Selection too long!"));
    }
}
