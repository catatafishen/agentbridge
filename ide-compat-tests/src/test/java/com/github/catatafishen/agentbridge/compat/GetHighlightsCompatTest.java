package com.github.catatafishen.agentbridge.compat;

import com.github.catatafishen.agentbridge.psi.tools.quality.GetHighlightsTool;
import com.google.gson.JsonObject;
import com.intellij.openapi.vfs.VirtualFile;

/**
 * IDE compatibility test for get_highlights.
 * Expected result: PASS on both IntelliJ (IU) and CLion (CL).
 */
public class GetHighlightsCompatTest extends IdeCompatBaseTest {

    public void testGetHighlightsReturnsNonErrorResponse() throws Exception {
        // Create a platform-appropriate fixture file
        String fileName;
        String content;
        if ("CL".equals(PLATFORM_TYPE)) {
            fileName = "test.cpp";
            content = "int add(int a, int b) { return a + b; }\n";
        } else {
            fileName = "Test.java";
            content = "public class Test { public int add(int a, int b) { return a + b; } }\n";
        }

        VirtualFile vf = myFixture.configureByText(fileName, content).getVirtualFile();
        assertNotNull("Fixture file must be created", vf);

        // Trigger daemon analysis so GetHighlightsTool has cached results to read
        myFixture.doHighlighting();

        GetHighlightsTool tool = new GetHighlightsTool(getProject());
        JsonObject args = new JsonObject();
        args.addProperty("path", vf.getPath());

        // GetHighlightsTool.execute() calls ensureDaemonAnalyzed() which dispatches via
        // EdtUtil.invokeLater — must run off-EDT while pumping the queue to avoid deadlock.
        String result = executeSync(() -> tool.execute(args));

        assertNotNull("Tool must return a non-null result", result);
        assertFalse("Tool result must not start with 'Error:'", result.startsWith("Error:"));
    }
}
