package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ToolDefinition} default method contracts —
 * {@code denyForSubAgent()}, {@code detectPermissionAbuse()}, and behavior flags.
 */
class ToolDefinitionTest {

    /**
     * Minimal ToolDefinition stub for testing defaults.
     */
    private static ToolDefinition stub(String id, boolean readOnly) {
        return new ToolDefinition() {
            @Override
            public @NotNull String id() {
                return id;
            }

            @Override
            public @NotNull String displayName() {
                return id;
            }

            @Override
            public @NotNull String description() {
                return "test";
            }

            @Override
            public @NotNull Kind kind() {
                return readOnly ? Kind.READ : Kind.EDIT;
            }

            @Override
            public @NotNull ToolRegistry.Category category() {
                return ToolRegistry.Category.OTHER;
            }

            @Override
            public boolean isReadOnly() {
                return readOnly;
            }
        };
    }

    @Nested
    @DisplayName("default behavior flags")
    class Defaults {

        @Test
        void denyForSubAgentDefaultFalse() {
            assertFalse(stub("test_tool", false).denyForSubAgent());
        }

        @Test
        void detectPermissionAbuseDefaultNull() {
            assertNull(stub("test_tool", false).detectPermissionAbuse(new JsonObject()));
        }

        @Test
        void detectPermissionAbuseNullInputReturnsNull() {
            assertNull(stub("test_tool", false).detectPermissionAbuse(null));
        }

        @Test
        void isBuiltInDefaultFalse() {
            assertFalse(stub("test_tool", false).isBuiltIn());
        }

        @Test
        void isDestructiveDefaultFalse() {
            assertFalse(stub("test_tool", false).isDestructive());
        }

        @Test
        void isOpenWorldDefaultFalse() {
            assertFalse(stub("test_tool", false).isOpenWorld());
        }
    }

    @Nested
    @DisplayName("MCP annotations")
    class McpAnnotations {

        @Test
        void includesReadOnlyHint() {
            JsonObject ann = stub("read_tool", true).mcpAnnotations();
            assertTrue(ann.get("readOnlyHint").getAsBoolean());
        }

        @Test
        void includesDestructiveHint() {
            JsonObject ann = stub("safe_tool", false).mcpAnnotations();
            assertFalse(ann.get("destructiveHint").getAsBoolean());
        }

        @Test
        void includesTitle() {
            JsonObject ann = stub("my_tool", false).mcpAnnotations();
            assertEquals("my_tool", ann.get("title").getAsString());
        }
    }

    @Nested
    @DisplayName("permission template")
    class PermissionTemplate {

        @Test
        void resolvePermissionQuestionNullTemplateReturnsNull() {
            assertNull(stub("test_tool", false).resolvePermissionQuestion(new JsonObject()));
        }

        @Test
        void resolvePermissionQuestionWithTemplate() {
            ToolDefinition tool = new ToolDefinition() {
                @Override
                public @NotNull String id() {
                    return "run";
                }

                @Override
                public @NotNull String displayName() {
                    return "Run";
                }

                @Override
                public @NotNull String description() {
                    return "test";
                }

                @Override
                public @NotNull Kind kind() {
                    return Kind.EDIT;
                }

                @Override
                public @NotNull ToolRegistry.Category category() {
                    return ToolRegistry.Category.OTHER;
                }

                @Override
                public @NotNull String permissionTemplate() {
                    return "Run: {command}";
                }
            };
            JsonObject args = new JsonObject();
            args.addProperty("command", "ls -la");
            String question = tool.resolvePermissionQuestion(args);
            assertNotNull(question);
            assertTrue(question.contains("ls -la"));
        }

        @Test
        void resolvePermissionQuestionNullArgsStripsPlaceholders() {
            ToolDefinition tool = new ToolDefinition() {
                @Override
                public @NotNull String id() {
                    return "run";
                }

                @Override
                public @NotNull String displayName() {
                    return "Run";
                }

                @Override
                public @NotNull String description() {
                    return "test";
                }

                @Override
                public @NotNull Kind kind() {
                    return Kind.EDIT;
                }

                @Override
                public @NotNull ToolRegistry.Category category() {
                    return ToolRegistry.Category.OTHER;
                }

                @Override
                public @NotNull String permissionTemplate() {
                    return "Run: {command}";
                }
            };
            String question = tool.resolvePermissionQuestion(null);
            assertNotNull(question);
            assertFalse(question.contains("{command}"));
        }
    }
}
