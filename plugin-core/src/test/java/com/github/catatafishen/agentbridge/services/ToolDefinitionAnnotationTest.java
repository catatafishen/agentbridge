package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.psi.ToolResult;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ToolDefinition} default methods not covered by {@link ToolDefinitionTest}.
 * Focuses on behavior flags, annotation defaults, and {@link ToolDefinition.Kind}.
 */
class ToolDefinitionAnnotationTest {

    /**
     * Minimal stub implementing only the required abstract methods.
     */
    private static ToolDefinition stub(boolean readOnly) {
        return new ToolDefinition() {
            @Override
            public @NotNull String id() {
                return "test_tool";
            }

            @Override
            public @NotNull String displayName() {
                return "Test Tool";
            }

            @Override
            public @NotNull String description() {
                return "A test tool";
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
    @DisplayName("isIdempotent defaults")
    class IdempotentDefaults {

        @Test
        @DisplayName("read-only tool defaults to idempotent")
        void readOnlyIsIdempotent() {
            assertTrue(stub(true).isIdempotent());
        }

        @Test
        @DisplayName("non-read-only tool defaults to non-idempotent")
        void writeToolNotIdempotent() {
            assertFalse(stub(false).isIdempotent());
        }
    }

    @Nested
    @DisplayName("needsWriteLock defaults")
    class NeedsWriteLockDefaults {

        @Test
        @DisplayName("read-only tool does not need write lock")
        void readOnlyNoWriteLock() {
            assertFalse(stub(true).needsWriteLock());
        }

        @Test
        @DisplayName("write tool needs write lock")
        void writeToolNeedsWriteLock() {
            assertTrue(stub(false).needsWriteLock());
        }
    }

    @Nested
    @DisplayName("other default flags")
    class OtherDefaults {

        @Test
        @DisplayName("supportsPathSubPermissions defaults to false")
        void pathSubPermissions() {
            assertFalse(stub(false).supportsPathSubPermissions());
        }

        @Test
        @DisplayName("hasDenyControl defaults to false")
        void denyControl() {
            assertFalse(stub(false).hasDenyControl());
        }

        @Test
        @DisplayName("requiresIndex defaults to false")
        void requiresIndex() {
            assertFalse(stub(false).requiresIndex());
        }

        @Test
        @DisplayName("requiresSmartProject defaults to false")
        void requiresSmartProject() {
            assertFalse(stub(false).requiresSmartProject());
        }

        @Test
        @DisplayName("requiresInteractiveEdt defaults to false")
        void requiresInteractiveEdt() {
            assertFalse(stub(false).requiresInteractiveEdt());
        }

        @Test
        @DisplayName("inputSchema defaults to null")
        void inputSchema() {
            assertNull(stub(false).inputSchema());
        }

        @Test
        @DisplayName("resultRenderer defaults to null")
        void resultRenderer() {
            assertNull(stub(false).resultRenderer());
        }

        @Test
        @DisplayName("execute defaults to null")
        void executeDefault() throws Exception {
            assertNull(stub(false).execute(new JsonObject()));
        }

        @Test
        @DisplayName("execute with hash delegates to execute without hash")
        void executeWithHash() throws Exception {
            ToolResult result = stub(false).execute(new JsonObject(), "abc123");
            assertNull(result.content());
            assertFalse(result.isError());
        }

        @Test
        @DisplayName("hasExecutionHandler defaults to false")
        void hasExecutionHandler() {
            assertFalse(stub(false).hasExecutionHandler());
        }

        @Test
        @DisplayName("permissionTemplate defaults to null")
        void permissionTemplate() {
            assertNull(stub(false).permissionTemplate());
        }
    }

    @Nested
    @DisplayName("mcpAnnotations completeness")
    class McpAnnotationsCompleteness {

        @Test
        @DisplayName("read-only tool has idempotentHint=true in annotations")
        void readOnlyIdempotentHint() {
            JsonObject ann = stub(true).mcpAnnotations();
            assertTrue(ann.get("idempotentHint").getAsBoolean());
        }

        @Test
        @DisplayName("write tool has idempotentHint=false in annotations")
        void writeIdempotentHint() {
            JsonObject ann = stub(false).mcpAnnotations();
            assertFalse(ann.get("idempotentHint").getAsBoolean());
        }

        @Test
        @DisplayName("openWorldHint defaults to false in annotations")
        void openWorldHint() {
            JsonObject ann = stub(false).mcpAnnotations();
            assertFalse(ann.get("openWorldHint").getAsBoolean());
        }

        @Test
        @DisplayName("annotations contain all 4 hint fields plus title")
        void allFieldsPresent() {
            JsonObject ann = stub(false).mcpAnnotations();
            assertTrue(ann.has("title"));
            assertTrue(ann.has("readOnlyHint"));
            assertTrue(ann.has("destructiveHint"));
            assertTrue(ann.has("idempotentHint"));
            assertTrue(ann.has("openWorldHint"));
            assertEquals(5, ann.size());
        }
    }

    @Nested
    @DisplayName("Kind.value()")
    class KindValue {

        @Test
        @DisplayName("READ kind value is 'read'")
        void readValue() {
            assertEquals("read", ToolDefinition.Kind.READ.value());
        }

        @Test
        @DisplayName("SEARCH kind value is 'search'")
        void searchValue() {
            assertEquals("search", ToolDefinition.Kind.SEARCH.value());
        }

        @Test
        @DisplayName("EDIT kind value is 'edit'")
        void editValue() {
            assertEquals("edit", ToolDefinition.Kind.EDIT.value());
        }

        @Test
        @DisplayName("DELETE kind value is 'delete'")
        void deleteValue() {
            assertEquals("delete", ToolDefinition.Kind.DELETE.value());
        }

        @Test
        @DisplayName("MOVE kind value is 'move'")
        void moveValue() {
            assertEquals("move", ToolDefinition.Kind.MOVE.value());
        }

        @Test
        @DisplayName("WRITE kind value is 'write'")
        void writeValue() {
            assertEquals("write", ToolDefinition.Kind.WRITE.value());
        }

        @Test
        @DisplayName("EXECUTE kind value is 'execute'")
        void executeValue() {
            assertEquals("execute", ToolDefinition.Kind.EXECUTE.value());
        }

        @Test
        @DisplayName("OTHER kind value is 'other'")
        void otherValue() {
            assertEquals("other", ToolDefinition.Kind.OTHER.value());
        }

        @Test
        @DisplayName("all enum values produce lowercase strings")
        void allLowercase() {
            for (ToolDefinition.Kind kind : ToolDefinition.Kind.values()) {
                assertEquals(kind.name().toLowerCase(java.util.Locale.ROOT), kind.value());
            }
        }
    }
}
