package com.github.catatafishen.agentbridge.psi.tools.git;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitShowToolStaticMethodsTest {

    @Test
    void schemaExposesSupportedPaginationParameters() {
        JsonObject properties = new GitShowTool(null).inputSchema().getAsJsonObject("properties");

        assertTrue(properties.has("offset"));
        assertTrue(properties.has("max_chars"));
    }

    @Test
    void shortOutputIsReturnedUnchanged() {
        assertEquals("short", GitShowTool.paginate("short", 0, 12_000));
    }

    @Test
    void firstPageProvidesSupportedContinuationOffset() {
        String result = GitShowTool.paginate("abcdef", 0, 3);

        assertTrue(result.startsWith("abc"), result);
        assertTrue(result.contains("Use offset=3 to continue."), result);
        assertFalse(result.contains("/tmp"), result);
    }

    @Test
    void subsequentPageReportsItsRange() {
        String result = GitShowTool.paginate("abcdef", 3, 3);

        assertTrue(result.startsWith("[Showing characters 3-6 of 6]"), result);
        assertTrue(result.endsWith("def"), result);
        assertFalse(result.contains("continue"), result);
    }

    @Test
    void offsetPastEndReturnsActionableMessage() {
        assertEquals("No git_show output at offset 10 (total length: 6 characters).",
            GitShowTool.paginate("abcdef", 10, 3));
    }
}
