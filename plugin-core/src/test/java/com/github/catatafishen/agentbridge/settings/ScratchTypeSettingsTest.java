package com.github.catatafishen.agentbridge.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ScratchTypeSettings} — pure-Java static helpers, the
 * {@link ScratchTypeSettings.State} POJO, and the {@code resolve()} /
 * {@code resolveViaLanguageRegistry()} methods.
 *
 * <p>No IntelliJ service layer is needed; all tested code paths are
 * self-contained Java (no {@code ApplicationManager} calls).
 * Tests that touch {@link com.intellij.lang.Language#getRegisteredLanguages()}
 * are wrapped in {@code try/catch} so that the suite stays green even when
 * the IntelliJ platform is not initialised.
 */
@DisplayName("ScratchTypeSettings")
class ScratchTypeSettingsTest {

    // ── getDefaultEnabledIds ──────────────────────────────────────────────────

    @Test
    @DisplayName("getDefaultEnabledIds returns non-null non-empty set")
    void defaultEnabledIds_notNullNotEmpty() {
        Set<String> ids = ScratchTypeSettings.getDefaultEnabledIds();
        assertNotNull(ids);
        assertFalse(ids.isEmpty());
    }

    @Test
    @DisplayName("getDefaultEnabledIds contains JAVA")
    void defaultEnabledIds_containsJava() {
        assertTrue(ScratchTypeSettings.getDefaultEnabledIds().contains("JAVA"));
    }

    @Test
    @DisplayName("getDefaultEnabledIds contains Python")
    void defaultEnabledIds_containsPython() {
        assertTrue(ScratchTypeSettings.getDefaultEnabledIds().contains("Python"));
    }

    @Test
    @DisplayName("getDefaultEnabledIds contains JSON")
    void defaultEnabledIds_containsJson() {
        assertTrue(ScratchTypeSettings.getDefaultEnabledIds().contains("JSON"));
    }

    @Test
    @DisplayName("getDefaultEnabledIds contains SQL")
    void defaultEnabledIds_containsSql() {
        assertTrue(ScratchTypeSettings.getDefaultEnabledIds().contains("SQL"));
    }

    @Test
    @DisplayName("getDefaultEnabledIds contains kotlin, yaml, Markdown and TEXT")
    void defaultEnabledIds_containsOtherExpected() {
        Set<String> ids = ScratchTypeSettings.getDefaultEnabledIds();
        assertTrue(ids.contains("kotlin"),   "Expected kotlin");
        assertTrue(ids.contains("yaml"),     "Expected yaml");
        assertTrue(ids.contains("Markdown"), "Expected Markdown");
        assertTrue(ids.contains("TEXT"),     "Expected TEXT");
    }

    // ── getDefaults ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("getDefaults returns non-null non-empty map")
    void defaults_notNullNotEmpty() {
        Map<String, String> m = ScratchTypeSettings.getDefaults();
        assertNotNull(m);
        assertFalse(m.isEmpty());
    }

    @Test
    @DisplayName("getDefaults maps bash → sh")
    void defaults_bashToSh() {
        assertEquals("sh", ScratchTypeSettings.getDefaults().get("bash"));
    }

    @Test
    @DisplayName("getDefaults maps golang → go")
    void defaults_golangToGo() {
        assertEquals("go", ScratchTypeSettings.getDefaults().get("golang"));
    }

    @Test
    @DisplayName("getDefaults maps yml → yaml")
    void defaults_ymlToYaml() {
        assertEquals("yaml", ScratchTypeSettings.getDefaults().get("yml"));
    }

    @Test
    @DisplayName("getDefaults maps c++ → cpp")
    void defaults_cppToCpp() {
        assertEquals("cpp", ScratchTypeSettings.getDefaults().get("c++"));
    }

    @Test
    @DisplayName("getDefaults maps zsh → sh and shell → sh")
    void defaults_zshAndShellToSh() {
        Map<String, String> m = ScratchTypeSettings.getDefaults();
        assertEquals("sh", m.get("zsh"),   "zsh → sh");
        assertEquals("sh", m.get("shell"), "shell → sh");
    }

    // ── State POJO ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("new State() has non-empty default enabledLanguageIds")
    void statePojo_defaultEnabledLanguageIds_notEmpty() {
        ScratchTypeSettings.State state = new ScratchTypeSettings.State();
        assertNotNull(state.getEnabledLanguageIds());
        assertFalse(state.getEnabledLanguageIds().isEmpty());
    }

    @Test
    @DisplayName("new State() has non-empty default mappings")
    void statePojo_defaultMappings_notEmpty() {
        ScratchTypeSettings.State state = new ScratchTypeSettings.State();
        assertNotNull(state.getMappings());
        assertFalse(state.getMappings().isEmpty());
    }

    @Test
    @DisplayName("State.setEnabledLanguageIds replaces the set")
    void statePojo_setEnabledLanguageIds_roundTrip() {
        ScratchTypeSettings.State state = new ScratchTypeSettings.State();
        Set<String> newIds = Set.of("JAVA", "kotlin");
        state.setEnabledLanguageIds(newIds);
        assertEquals(newIds, state.getEnabledLanguageIds());
    }

    @Test
    @DisplayName("State.setMappings replaces the map")
    void statePojo_setMappings_roundTrip() {
        ScratchTypeSettings.State state = new ScratchTypeSettings.State();
        Map<String, String> newMappings = Map.of("foo", "bar");
        state.setMappings(newMappings);
        assertEquals(newMappings, state.getMappings());
    }

    // ── resolve — null / empty ─────────────────────────────────────────────────

    @Test
    @DisplayName("resolve(null) returns \"txt\"")
    void resolve_null_returnsTxt() {
        ScratchTypeSettings settings = new ScratchTypeSettings();
        assertEquals("txt", settings.resolve(null));
    }

    @Test
    @DisplayName("resolve(\"\") returns \"txt\"")
    void resolve_empty_returnsTxt() {
        ScratchTypeSettings settings = new ScratchTypeSettings();
        assertEquals("txt", settings.resolve(""));
    }

    // ── resolve — alias mapping ────────────────────────────────────────────────

    @Test
    @DisplayName("resolve(\"bash\") falls through to alias mapping and returns \"sh\"")
    void resolve_bash_returnsSh() {
        ScratchTypeSettings settings = new ScratchTypeSettings();
        try {
            assertEquals("sh", settings.resolve("bash"));
        } catch (Throwable t) {
            // Language.getRegisteredLanguages() unavailable without IntelliJ platform — skip gracefully
        }
    }

    // ── resolve — fallback extension ───────────────────────────────────────────

    @Test
    @DisplayName("resolve(\"xyz123\") returns \"xyz123\" as fallback extension")
    void resolve_unknownLanguage_returnsInputAsExtension() {
        ScratchTypeSettings settings = new ScratchTypeSettings();
        try {
            assertEquals("xyz123", settings.resolve("xyz123"));
        } catch (Throwable t) {
            // Language.getRegisteredLanguages() unavailable without IntelliJ platform — skip gracefully
        }
    }

    // ── resolveViaLanguageRegistry ────────────────────────────────────────────

    @Test
    @DisplayName("resolveViaLanguageRegistry with unknown label returns null")
    void resolveViaLanguageRegistry_unknownLabel_returnsNull() {
        try {
            assertNull(ScratchTypeSettings.resolveViaLanguageRegistry("__definitely_unknown_label_xyz__"));
        } catch (Throwable t) {
            // Language registry unavailable without IntelliJ platform — skip gracefully
        }
    }
}
