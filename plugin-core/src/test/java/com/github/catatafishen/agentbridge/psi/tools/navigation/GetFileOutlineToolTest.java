package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.google.gson.JsonObject;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

public class GetFileOutlineToolTest extends BasePlatformTestCase {

    private GetFileOutlineTool tool;
    private Path tempDir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        tool = new GetFileOutlineTool(getProject());
        tempDir = Files.createTempDirectory("get-file-outline-tool-test");
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            deleteDir(tempDir);
        } finally {
            super.tearDown();
        }
    }

    public void testIncludesHierarchyModifiersAndSignatures() {
        VirtualFile file = createTestFile("OutlineSample.java", """
            public class OutlineSample {
                private final String id;

                public OutlineSample(String id) {
                    this.id = id;
                }

                public String load(int count) {
                    return id + count;
                }
            }
            """);

        String result = tool.execute(args("path", file.getPath()));

        assertTrue("Expected class outline, got: " + result,
            result.contains("1: class public OutlineSample"));
        assertTrue("Expected field with modifiers and type, got: " + result,
            result.contains("  2: field private final id: String"));
        assertTrue("Expected constructor signature, got: " + result,
            result.contains("  4: method public OutlineSample(String)"));
        assertTrue("Expected method signature and return type, got: " + result,
            result.contains("  8: method public load(int): String"));
    }

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

    private static JsonObject args(String... pairs) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            obj.addProperty(pairs[i], pairs[i + 1]);
        }
        return obj;
    }

    private static void deleteDir(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}
