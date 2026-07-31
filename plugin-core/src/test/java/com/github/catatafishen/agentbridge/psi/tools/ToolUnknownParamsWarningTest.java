package com.github.catatafishen.agentbridge.psi.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.catatafishen.agentbridge.psi.ToolResult;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers which arguments count as "unknown" when a tool result is assembled.
 *
 * <p>Hooks inject plumbing arguments such as {@code _env.GH_TOKEN} that no schema declares. Warning
 * about those told the agent its own call was malformed and that the value had been dropped, while
 * the tool had in fact consumed it — so the reserved prefix must be exempt without weakening the
 * warning for genuinely misspelled parameters.</p>
 */
class ToolUnknownParamsWarningTest {

    private static final String RESULT = "done";

    private static final class StubTool extends Tool {
        StubTool() {
            super(null, new DirectPlatformFacade());
        }

        @Override
        public @NotNull String id() {
            return "stub_tool";
        }

        @Override
        public @NotNull Kind kind() {
            return Kind.READ;
        }

        @Override
        public @NotNull String displayName() {
            return "Stub Tool";
        }

        @Override
        public @NotNull String description() {
            return "Test double.";
        }

        @Override
        public ToolRegistry.Category category() {
            return ToolRegistry.Category.OTHER;
        }

        @Override
        public @NotNull JsonObject inputSchema() {
            return schema(Param.optional("command", TYPE_STRING, "A command", ""));
        }

        @Override
        public String execute(@NotNull JsonObject args) {
            return RESULT;
        }
    }

    private static String run(JsonObject args) throws Exception {
        ToolResult result = new StubTool().execute(args, null);
        return result.content();
    }

    @Test
    @DisplayName("hook-injected _env arguments produce no warning")
    void internalArgumentsAreNotReportedAsUnknown() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("command", "gh api graphql");
        args.addProperty("_env.GH_TOKEN", "ghs_secret");

        assertEquals(RESULT, run(args));
    }

    @Test
    @DisplayName("a genuinely unknown parameter is still reported")
    void unknownArgumentsAreStillReported() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("command", "ls");
        args.addProperty("commnad", "typo");

        String content = run(args);
        assertTrue(content.startsWith("NOTE: Unknown parameter(s) [commnad]"), content);
        assertTrue(content.endsWith(RESULT), content);
    }

    @Test
    @DisplayName("an internal argument does not mask a real one alongside it")
    void internalArgumentDoesNotSuppressRealWarning() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("_env.GH_TOKEN", "ghs_secret");
        args.addProperty("bogus", "value");

        String content = run(args);
        assertTrue(content.contains("[bogus]"), content);
        assertFalse(content.contains("_env"), content);
    }
}
