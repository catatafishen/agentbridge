package com.github.catatafishen.agentbridge.ui.util;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ModelFavorites}.
 * <p>
 * Uses a HashMap-based fake for {@link PropertiesComponent} and Mockito for {@link Project}.
 */
class ModelFavoritesTest {

    private static final String KEY = "agentbridge.favoriteModels";

    /**
     * Minimal in-memory stand-in for the abstract PropertiesComponent.
     */
    private static class FakePropertiesComponent extends PropertiesComponent {
        private final HashMap<String, String> data = new HashMap<>();

        @Override
        public @Nullable String getValue(@NotNull String name) {
            return data.get(name);
        }

        @Override
        public void setValue(@NotNull String name, @Nullable String value) {
            if (value == null) data.remove(name);
            else data.put(name, value);
        }

        @Override
        public void setValue(@NotNull String name, @Nullable String value, @Nullable String defaultValue) {
            setValue(name, value);
        }

        @Override
        public void unsetValue(@NotNull String name) {
            data.remove(name);
        }

        @Override
        public boolean isValueSet(@NotNull String name) {
            return data.containsKey(name);
        }

        @Override
        public void setValue(@NotNull String name, float value, float defaultValue) {
            data.put(name, String.valueOf(value));
        }

        @Override
        public void setValue(@NotNull String name, int value, int defaultValue) {
            data.put(name, String.valueOf(value));
        }

        @Override
        public void setValue(@NotNull String name, boolean value, boolean defaultValue) {
            data.put(name, String.valueOf(value));
        }

        @Override
        public boolean updateValue(@NotNull String name, boolean value) {
            String prev = data.put(name, String.valueOf(value));
            return !String.valueOf(value).equals(prev);
        }

        @Override
        public @Nullable String @Nullable [] getValues(@NotNull String name) {
            return null;
        }

        @Override
        public void setValues(@NotNull String name, @Nullable String @Nullable [] values) {
            // not used by ModelFavorites
        }

        @Override
        public @Nullable java.util.List<String> getList(@NotNull String name) {
            return null;
        }

        @Override
        public void setList(@NotNull String name, @Nullable java.util.Collection<String> values) {
            // not used by ModelFavorites
        }
    }

    private FakePropertiesComponent fakeProps;
    private ModelFavorites favorites;

    @BeforeEach
    void setUp() {
        fakeProps = new FakePropertiesComponent();
        Project mockProject = Mockito.mock(Project.class);

        // Mock the static PropertiesComponent.getInstance(project) to return our fake
        try (var mockedStatic = Mockito.mockStatic(PropertiesComponent.class)) {
            mockedStatic.when(() -> PropertiesComponent.getInstance(mockProject))
                .thenReturn(fakeProps);

            favorites = new ModelFavorites(mockProject);
        }

        // Verify injection worked
        assertNotNull(favorites);
    }

    // ── Empty state ────────────────────────────────────────────────────────────

    @Test
    void toSet_returnsEmptySet_whenNoFavoritesSet() {
        assertTrue(favorites.toSet().isEmpty());
    }

    @Test
    void isFavorite_returnsFalse_forAnyId_whenNoFavoritesSet() {
        assertFalse(favorites.isFavorite("gpt-4o"));
        assertFalse(favorites.isFavorite("claude-3-5-sonnet"));
        assertFalse(favorites.isFavorite("any-model-id"));
    }

    // ── Toggle adds ───────────────────────────────────────────────────────────

    @Test
    void toggle_addsModelId_whenNotAlreadyFavorite() {
        favorites.toggle("gpt-4o");

        assertTrue(favorites.isFavorite("gpt-4o"));
    }

    @Test
    void toSet_containsModel_afterToggleAdds() {
        favorites.toggle("gpt-4o");

        assertTrue(favorites.toSet().contains("gpt-4o"));
    }

    // ── Toggle removes ───────────────────────────────────────────────────────

    @Test
    void toggle_removesModelId_whenAlreadyFavorite() {
        favorites.toggle("gpt-4o");
        favorites.toggle("gpt-4o");

        assertFalse(favorites.isFavorite("gpt-4o"));
    }

    @Test
    void toSet_doesNotContainModel_afterSecondToggle() {
        favorites.toggle("gpt-4o");
        favorites.toggle("gpt-4o");

        assertFalse(favorites.toSet().contains("gpt-4o"));
    }

    @Test
    void isFavorite_returnsFalse_afterSecondToggle() {
        favorites.toggle("gpt-4o");
        assertTrue(favorites.isFavorite("gpt-4o"));

        favorites.toggle("gpt-4o");
        assertFalse(favorites.isFavorite("gpt-4o"));
    }

    // ── Multiple favorites ─────────────────────────────────────────────────────

    @Test
    void toSet_containsMultipleFavorites() {
        favorites.toggle("gpt-4o");
        favorites.toggle("claude-3-5-sonnet");
        favorites.toggle("gemini-2-0-flash");

        Set<String> set = favorites.toSet();
        assertEquals(3, set.size());
        assertTrue(set.contains("gpt-4o"));
        assertTrue(set.contains("claude-3-5-sonnet"));
        assertTrue(set.contains("gemini-2-0-flash"));
    }

    @Test
    void isFavorite_returnsCorrectState_forMultipleFavorites() {
        favorites.toggle("gpt-4o");
        favorites.toggle("claude-3-5-sonnet");

        assertTrue(favorites.isFavorite("gpt-4o"));
        assertTrue(favorites.isFavorite("claude-3-5-sonnet"));
        assertFalse(favorites.isFavorite("gemini-2-0-flash"));
    }

    @Test
    void toggle_removesOneFavorite_leavingOthersIntact() {
        favorites.toggle("gpt-4o");
        favorites.toggle("claude-3-5-sonnet");
        favorites.toggle("gemini-2-0-flash");

        favorites.toggle("claude-3-5-sonnet");

        Set<String> set = favorites.toSet();
        assertEquals(2, set.size());
        assertTrue(set.contains("gpt-4o"));
        assertFalse(set.contains("claude-3-5-sonnet"));
        assertTrue(set.contains("gemini-2-0-flash"));
    }

    // ── getInstance factory method ────────────────────────────────────────────

    @Test
    void getInstance_returnsNewInstance_eachCall() {
        // The getInstance factory method creates a new instance each time.
        // This is by design - the class does not cache instances.
        Project project = Mockito.mock(Project.class);

        try (var mockedStatic = Mockito.mockStatic(PropertiesComponent.class)) {
            mockedStatic.when(() -> PropertiesComponent.getInstance(project))
                .thenReturn(fakeProps);

            // Kotlin companion object methods are accessed via .Companion in Java
            ModelFavorites instance1 = ModelFavorites.Companion.getInstance(project);
            ModelFavorites instance2 = ModelFavorites.Companion.getInstance(project);

            // Each call creates a new instance (no caching)
            assertNotSame(instance1, instance2);
        }
    }

    @Test
    void getInstance_returnsDifferentInstance_forDifferentProject() {
        Project project1 = Mockito.mock(Project.class);
        Project project2 = Mockito.mock(Project.class);

        // Mock static to return different fakes for different projects
        FakePropertiesComponent fakeProps2 = new FakePropertiesComponent();
        try (var mockedStatic = Mockito.mockStatic(PropertiesComponent.class)) {
            mockedStatic.when(() -> PropertiesComponent.getInstance(project1))
                .thenReturn(fakeProps);
            mockedStatic.when(() -> PropertiesComponent.getInstance(project2))
                .thenReturn(fakeProps2);

            ModelFavorites instance1 = ModelFavorites.Companion.getInstance(project1);
            ModelFavorites instance2 = ModelFavorites.Companion.getInstance(project2);

            assertNotSame(instance1, instance2);
        }
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    @Test
    void toggle_persistsToPropertiesComponent() {
        favorites.toggle("gpt-4o");

        String stored = fakeProps.getValue(KEY);
        assertNotNull(stored);
        assertTrue(stored.contains("gpt-4o"));
    }

    @Test
    void toSet_readsFromPropertiesComponent() {
        fakeProps.setValue(KEY, "[\"gpt-4o\",\"claude-3-5-sonnet\"]");

        Set<String> set = favorites.toSet();

        assertEquals(2, set.size());
        assertTrue(set.contains("gpt-4o"));
        assertTrue(set.contains("claude-3-5-sonnet"));
    }

    @Test
    void toSet_returnsEmptySet_forCorruptedJson() {
        fakeProps.setValue(KEY, "not valid json");

        assertTrue(favorites.toSet().isEmpty());
    }

    @Test
    void toSet_returnsEmptySet_forInvalidJsonType() {
        // JSON is valid but not an array
        fakeProps.setValue(KEY, "{\"key\": \"value\"}");

        assertTrue(favorites.toSet().isEmpty());
    }
}
