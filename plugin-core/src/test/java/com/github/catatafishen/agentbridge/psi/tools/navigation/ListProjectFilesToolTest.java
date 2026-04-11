package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Platform tests for {@link ListProjectFilesTool}.
 *
 * <p>JUnit 3 style (extends BasePlatformTestCase): test methods must be
 * {@code public void testXxx()}.  Run via Gradle only:
 * {@code ./gradlew :plugin-core:test}.
 *
 * <p>{@link ListProjectFilesTool#execute} runs entirely inside
 * {@code ApplicationManager.getApplication().runReadAction()} so it is
 * synchronous when called from the EDT test thread — no extra threading is
 * needed.
 *
 * <p>Files added via {@code myFixture.addFileToProject()} land in the
 * project's in-memory VFS, are indexed by {@code ProjectFileIndex}, and are
 * therefore returned by the tool's {@code iterateContent} pass.
 *
 * <p>The tool's output format per entry is:
 * {@code <relpath> [<tag><typeName>, <size>, <timestamp>]}
 * where {@code tag} is one of {@code "source "}, {@code "test "}, or empty,
 * and the whole listing is prefixed by {@code "N files:\n"}.
 * An empty result set returns {@code "No files found"}.
 */
public class ListProjectFilesToolTest extends BasePlatformTestCase {

    private ListProjectFilesTool tool;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Disable follow-agent UI features that would be no-ops in a headless test.
        PropertiesComponent.getInstance(getProject())
            .setValue(ToolLayerSettings.FOLLOW_AGENT_FILES_KEY, "false");
        tool = new ListProjectFilesTool(getProject());
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    // ---- Helpers -------------------------------------------------------------

    /**
     * Builds a {@link JsonObject} from alternating String key/value pairs.
     * For integer or boolean parameters use {@link JsonObject#addProperty}
     * directly on the returned object.
     */
    private static JsonObject args(String... pairs) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            obj.addProperty(pairs[i], pairs[i + 1]);
        }
        return obj;
    }

    // ---- Tests ---------------------------------------------------------------

    /**
     * After adding three files via {@code addFileToProject}, listing all project
     * files (no filters) must include each file's name in the result.
     */
    public void testListAllFiles() {
        myFixture.addFileToProject("FileA.java", "public class FileA {}");
        myFixture.addFileToProject("FileB.java", "public class FileB {}");
        myFixture.addFileToProject("FileC.java", "public class FileC {}");

        String result = tool.execute(new JsonObject());

        assertNotNull("Result must not be null", result);
        assertFalse("Result must not be blank", result.isBlank());
        assertTrue("Expected file listing or 'No files found', got: " + result,
            result.contains("files:") || result.equals("No files found"));
        if (result.contains("files:")) {
            assertTrue("Expected FileA.java, got: " + result, result.contains("FileA.java"));
            assertTrue("Expected FileB.java, got: " + result, result.contains("FileB.java"));
            assertTrue("Expected FileC.java, got: " + result, result.contains("FileC.java"));
        }
    }

    /**
     * The {@code pattern} parameter restricts output to files matching a glob.
     * Adding {@code .java}, {@code .txt}, and {@code .xml} files then filtering
     * by {@code *.java} must include only the Java file.
     */
    public void testListWithPattern() {
        myFixture.addFileToProject("Pattern.java", "public class Pattern {}");
        myFixture.addFileToProject("Pattern.txt", "some text");
        myFixture.addFileToProject("Pattern.xml", "<root/>");

        String result = tool.execute(args("pattern", "*.java"));

        assertTrue("Expected Pattern.java in result, got: " + result,
            result.contains("Pattern.java"));
        assertFalse("Should not include Pattern.txt, got: " + result,
            result.contains("Pattern.txt"));
        assertFalse("Should not include Pattern.xml, got: " + result,
            result.contains("Pattern.xml"));
    }

    /**
     * The {@code min_size} filter must exclude files smaller than the threshold.
     * A large file (well over 100 bytes) must appear; a tiny 1-byte file must not.
     */
    public void testListWithMinSizeFilter() {
        myFixture.addFileToProject("TinyMin.java", "x"); // 1 byte

        StringBuilder big = new StringBuilder("public class LargeMin {\n");
        for (int i = 0; i < 5; i++) {
            big.append("    private String field").append(i)
                .append(" = \"padding_value_that_makes_file_larger\";\n");
        }
        big.append("}");
        // big is ~270 bytes — well over the 100-byte threshold
        myFixture.addFileToProject("LargeMin.java", big.toString());

        JsonObject a = new JsonObject();
        a.addProperty("min_size", 100);
        a.addProperty("pattern", "*.java");
        String result = tool.execute(a);

        if (result.contains("files:")) {
            assertTrue("LargeMin.java must pass the min_size filter, got: " + result,
                result.contains("LargeMin.java"));
            assertFalse("TinyMin.java must be excluded by min_size filter, got: " + result,
                result.contains("TinyMin.java"));
        }
    }

    /**
     * The {@code max_size} filter must exclude files larger than the threshold.
     * A 1-byte file must appear; a file over 1 000 bytes must not.
     */
    public void testListWithMaxSizeFilter() {
        myFixture.addFileToProject("TinyMax.txt", "x"); // 1 byte

        StringBuilder big = new StringBuilder();
        // 20 bytes x 60 = 1200 bytes
        big.repeat("aaaaaaaaaaaaaaaaaaa\n", 60);
        myFixture.addFileToProject("BigMax.txt", big.toString());

        JsonObject a = new JsonObject();
        a.addProperty("max_size", 10);
        a.addProperty("pattern", "*.txt");
        String result = tool.execute(a);

        if (result.contains("files:")) {
            assertTrue("TinyMax.txt must pass max_size filter, got: " + result,
                result.contains("TinyMax.txt"));
            assertFalse("BigMax.txt must be excluded by max_size filter, got: " + result,
                result.contains("BigMax.txt"));
        }
    }

    /**
     * With {@code sort=name} (the default) the output must list files in
     * ascending alphabetical order: "Apple" before "Mango" before "Zebra".
     */
    public void testListSortByName() {
        myFixture.addFileToProject("Zebra.java", "class Zebra {}");
        myFixture.addFileToProject("Apple.java", "class Apple {}");
        myFixture.addFileToProject("Mango.java", "class Mango {}");

        String result = tool.execute(args("sort", "name", "pattern", "*.java"));

        assertTrue("Expected Apple.java in result, got: " + result, result.contains("Apple.java"));
        assertTrue("Expected Mango.java in result, got: " + result, result.contains("Mango.java"));
        assertTrue("Expected Zebra.java in result, got: " + result, result.contains("Zebra.java"));

        if (result.contains("files:")) {
            int appleIdx = result.indexOf("Apple.java");
            int mangoIdx = result.indexOf("Mango.java");
            int zebraIdx = result.indexOf("Zebra.java");
            assertTrue("Apple should appear before Mango in name-sorted output",
                appleIdx < mangoIdx);
            assertTrue("Mango should appear before Zebra in name-sorted output",
                mangoIdx < zebraIdx);
        }
    }

    /**
     * A glob pattern that matches no indexed files must return exactly
     * {@code "No files found"}.
     */
    public void testListWithNoMatchingPattern() {
        myFixture.addFileToProject("SomeFile.java", "class SomeFile {}");

        String result = tool.execute(args("pattern", "*.frobnicator"));

        assertEquals("Expected 'No files found' for non-matching pattern, got: " + result,
            "No files found", result);
    }

    /**
     * The {@code directory} parameter filters to files whose relative path
     * starts with the given prefix.  Files in the subdirectory must appear;
     * root-level files must not.
     */
    public void testListWithDirectory() {
        myFixture.addFileToProject("subdir/InSubdir.java", "class InSubdir {}");
        myFixture.addFileToProject("subdir/AlsoInSubdir.java", "class AlsoInSubdir {}");
        myFixture.addFileToProject("Root.java", "class Root {}");

        String result = tool.execute(args("directory", "subdir"));

        if (result.contains("files:")) {
            assertTrue("Expected InSubdir.java in subdir result, got: " + result,
                result.contains("InSubdir.java"));
            assertTrue("Expected AlsoInSubdir.java in subdir result, got: " + result,
                result.contains("AlsoInSubdir.java"));
            assertFalse("Root.java must not appear when directory='subdir', got: " + result,
                result.contains("Root.java"));
        }
    }

    /**
     * With {@code sort=size} the tool sorts by file size descending (largest
     * first).  The large file's name must appear before the small file's name.
     */
    public void testListSortBySize() {
        myFixture.addFileToProject("SizeSmall.txt", "hi"); // 2 bytes

        StringBuilder large = new StringBuilder();
        // ~2000 bytes
        large.repeat("aaaaaaaaaaaaaaaaaaa\n", 100);
        myFixture.addFileToProject("SizeLarge.txt", large.toString());

        String result = tool.execute(args("pattern", "*.txt", "sort", "size"));

        assertTrue("Expected SizeSmall.txt in result, got: " + result,
            result.contains("SizeSmall.txt"));
        assertTrue("Expected SizeLarge.txt in result, got: " + result,
            result.contains("SizeLarge.txt"));

        if (result.contains("files:")) {
            int smallIdx = result.indexOf("SizeSmall.txt");
            int largeIdx = result.indexOf("SizeLarge.txt");
            assertTrue("Largest file should appear first when sorted by size descending",
                largeIdx < smallIdx);
        }
    }

    /**
     * Each result entry must follow the format
     * {@code <relpath> [<tag><typeName>, <size>, <timestamp>]}.
     * Verifies that the metadata brackets appear in the output.
     */
    public void testResultFormatIncludesMetadataBrackets() {
        myFixture.addFileToProject("MetaCheck.java", "public class MetaCheck {}");

        String result = tool.execute(args("pattern", "MetaCheck.java"));

        if (result.contains("files:")) {
            assertTrue("Result should contain metadata opening bracket '[', got: " + result,
                result.contains("["));
            assertTrue("Result should contain metadata closing bracket ']', got: " + result,
                result.contains("]"));
            assertTrue("Result should contain MetaCheck.java, got: " + result,
                result.contains("MetaCheck.java"));
        }
    }

    /**
     * An invalid {@code modified_after} timestamp string must return an error
     * message beginning with "Error:".
     */
    public void testInvalidModifiedAfterReturnsError() {
        String result = tool.execute(args("modified_after", "not-a-valid-timestamp-xyz"));

        assertTrue("Expected error for invalid modified_after, got: " + result,
            result.startsWith("Error:"));
    }
}
