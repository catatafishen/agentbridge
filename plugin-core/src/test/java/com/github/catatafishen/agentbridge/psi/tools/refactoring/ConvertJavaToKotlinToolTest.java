package com.github.catatafishen.agentbridge.psi.tools.refactoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class ConvertJavaToKotlinToolTest extends BasePlatformTestCase {

    public void testRejectsWhenKotlinPluginMissing() {
        ConvertJavaToKotlinTool tool = new ConvertJavaToKotlinTool(getProject(), false);
        JsonObject args = new JsonObject();
        JsonArray files = new JsonArray();
        files.add("Foo.java");
        args.add("files", files);

        String result = tool.execute(args);

        assertTrue("Expected 'requires the Kotlin plugin' error, got: " + result,
            result.startsWith("Error: ") && result.contains("Kotlin plugin"));
    }

    public void testRejectsMissingFilesParam() {
        ConvertJavaToKotlinTool tool = new ConvertJavaToKotlinTool(getProject(), true);
        String result = tool.execute(new JsonObject());
        assertTrue("Expected files-required error, got: " + result,
            result.startsWith("Error: ") && result.contains("'files'"));
    }

    public void testRejectsEmptyFilesArray() {
        ConvertJavaToKotlinTool tool = new ConvertJavaToKotlinTool(getProject(), true);
        JsonObject args = new JsonObject();
        args.add("files", new JsonArray());
        String result = tool.execute(args);
        assertTrue("Expected at-least-one-path error, got: " + result,
            result.startsWith("Error: ") && result.contains("at least one"));
    }

    public void testSchemaDeclaresFilesArrayWithItems() {
        ConvertJavaToKotlinTool tool = new ConvertJavaToKotlinTool(getProject(), true);
        JsonObject schema = tool.inputSchema();
        JsonObject filesProp = schema.getAsJsonObject("properties").getAsJsonObject("files");
        assertEquals("array", filesProp.get("type").getAsString());
        assertTrue("Expected items to be declared on 'files'", filesProp.has("items"));
        assertEquals("string", filesProp.getAsJsonObject("items").get("type").getAsString());
        boolean filesIsRequired = false;
        for (var el : schema.getAsJsonArray("required")) {
            if ("files".equals(el.getAsString())) filesIsRequired = true;
        }
        assertTrue("'files' must be a required parameter", filesIsRequired);
    }

    public void testSkipsNonJavaFile() throws java.io.IOException {
        ConvertJavaToKotlinTool tool = new ConvertJavaToKotlinTool(getProject(), true);
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("agentbridge-not-java", ".txt");
        try {
            java.nio.file.Files.writeString(tmp, "not java");

            JsonObject args = new JsonObject();
            JsonArray files = new JsonArray();
            files.add(tmp.toString());
            args.add("files", files);

            String result = tool.execute(args);

            assertTrue("Expected skip line for non-Java file, got: " + result,
                result.contains("skipped: Not a Java file"));
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }
}
