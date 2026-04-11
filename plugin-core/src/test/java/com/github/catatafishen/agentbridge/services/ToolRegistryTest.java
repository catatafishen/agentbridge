package com.github.catatafishen.agentbridge.services;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry(Mockito.mock(Project.class));
    }

    private static ToolDefinition def(String id, String displayName) {
        return new ToolDefinition() {
            @Override
            public @NotNull String id() {
                return id;
            }

            @Override
            public @NotNull String displayName() {
                return displayName;
            }

            @Override
            public @NotNull String description() {
                return "test";
            }

            @Override
            public @NotNull ToolDefinition.Kind kind() {
                return Kind.READ;
            }

            @Override
            public @NotNull ToolRegistry.Category category() {
                return ToolRegistry.Category.OTHER;
            }
        };
    }

    @Test
    @DisplayName("registry starts empty")
    void registryStartsEmpty() {
        assertTrue(registry.getAllTools().isEmpty());
    }

    @Test
    @DisplayName("register and findById returns the registered tool")
    void registerAndFindById() {
        var tool = def("my_tool", "My Tool");
        registry.register(tool);
        assertEquals(tool, registry.findById("my_tool"));
    }

    @Test
    @DisplayName("findById with null returns null")
    void findByIdNullReturnsNull() {
        assertNull(registry.findById(null));
    }

    @Test
    @DisplayName("findById with unknown id returns null")
    void findByIdUnknownReturnsNull() {
        assertNull(registry.findById("nonexistent"));
    }

    @Test
    @DisplayName("findDefinition returns registered tool")
    void findDefinitionReturnsRegistered() {
        var tool = def("tool_a", "Tool A");
        registry.register(tool);
        assertEquals(tool, registry.findDefinition("tool_a"));
    }

    @Test
    @DisplayName("findByDisplayName matches case-insensitively")
    void findByDisplayNameCaseInsensitive() {
        registry.register(def("git_push", "Git Push"));
        assertNotNull(registry.findByDisplayName("GIT PUSH"));
        assertNotNull(registry.findByDisplayName("git push"));
        assertNotNull(registry.findByDisplayName("Git Push"));
    }

    @Test
    @DisplayName("findByDisplayName returns null when no match")
    void findByDisplayNameNoMatch() {
        registry.register(def("git_push", "Git Push"));
        assertNull(registry.findByDisplayName("Git Pull"));
    }

    @Test
    @DisplayName("unregister removes the tool")
    void unregisterRemovesTool() {
        registry.register(def("my_tool", "My Tool"));
        registry.unregister("my_tool");
        assertNull(registry.findById("my_tool"));
    }

    @Test
    @DisplayName("unregister non-existent id is a no-op")
    void unregisterNonExistentIsNoOp() {
        assertDoesNotThrow(() -> registry.unregister("nonexistent"));
    }

    @Test
    @DisplayName("registerAll adds all tools")
    void registerAllAddsAll() {
        var tools = List.of(def("a", "A"), def("b", "B"), def("c", "C"));
        registry.registerAll(tools);
        assertEquals(3, registry.getAllTools().size());
        assertNotNull(registry.findById("a"));
        assertNotNull(registry.findById("b"));
        assertNotNull(registry.findById("c"));
    }

    @Test
    @DisplayName("registering same id twice overwrites the first")
    void registerSameIdOverwrites() {
        registry.register(def("dup", "First"));
        registry.register(def("dup", "Second"));
        var found = registry.findById("dup");
        assertNotNull(found);
        assertEquals("Second", found.displayName());
        assertEquals(1, registry.getAllTools().size());
    }

    @Test
    @DisplayName("getAllTools returns an unmodifiable copy")
    void getAllToolsIsUnmodifiable() {
        registry.register(def("t", "T"));
        var tools = registry.getAllTools();
        var extra = def("x", "X");
        // Intentionally attempting mutation to verify getAllTools() returns an unmodifiable list
        assertThrows(UnsupportedOperationException.class, () -> tools.add(extra));
    }

    @Test
    @DisplayName("getAllTools returns all registered tools (order not guaranteed)")
    void getAllToolsReturnsAllRegistered() {
        registry.register(def("first", "First"));
        registry.register(def("second", "Second"));
        registry.register(def("third", "Third"));
        var ids = registry.getAllTools().stream().map(ToolDefinition::id).collect(Collectors.toSet());
        assertEquals(Set.of("first", "second", "third"), ids);
    }
}
