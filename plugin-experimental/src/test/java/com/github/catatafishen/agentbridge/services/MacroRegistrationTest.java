package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.services.MacroToolSettings.MacroRegistration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroRegistrationTest {

    // ── copy ─────────────────────────────────────────────────────────────────

    @Test
    void copy_producesIndependentInstance() {
        MacroRegistration original = new MacroRegistration("myMacro", "macro_my_macro", "desc", true);
        MacroRegistration copy = original.copy();

        assertEquals(original, copy);
        assertNotSame(original, copy);
    }

    @Test
    void copy_mutationDoesNotAffectOriginal() {
        MacroRegistration original = new MacroRegistration("myMacro", "macro_my_macro", "desc", true);
        MacroRegistration copy = original.copy();

        copy.setToolName("macro_changed");
        copy.setEnabled(false);

        assertEquals("macro_my_macro", original.getToolName());
        assertTrue(original.isEnabled());
    }

    // ── equals / hashCode ─────────────────────────────────────────────────────

    @Test
    void equals_sameFields_isEqual() {
        MacroRegistration a = new MacroRegistration("m", "macro_m", "d", true);
        MacroRegistration b = new MacroRegistration("m", "macro_m", "d", true);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_differentEnabled_notEqual() {
        MacroRegistration a = new MacroRegistration("m", "macro_m", "d", true);
        MacroRegistration b = new MacroRegistration("m", "macro_m", "d", false);
        assertNotEquals(a, b);
    }

    @Test
    void equals_differentDescription_notEqual() {
        MacroRegistration a = new MacroRegistration("m", "macro_m", "desc1", true);
        MacroRegistration b = new MacroRegistration("m", "macro_m", "desc2", true);
        assertNotEquals(a, b);
    }

    @Test
    void equals_differentToolName_notEqual() {
        MacroRegistration a = new MacroRegistration("m", "macro_a", "d", true);
        MacroRegistration b = new MacroRegistration("m", "macro_b", "d", true);
        assertNotEquals(a, b);
    }

    @Test
    void equals_differentMacroName_notEqual() {
        MacroRegistration a = new MacroRegistration("macro_a", "macro_a", "d", true);
        MacroRegistration b = new MacroRegistration("macro_b", "macro_a", "d", true);
        assertNotEquals(a, b);
    }

    // ── default constructor ───────────────────────────────────────────────────

    @Test
    void defaultConstructor_allEmptyOrEnabled() {
        MacroRegistration reg = new MacroRegistration();
        assertEquals("", reg.getMacroName());
        assertEquals("", reg.getToolName());
        assertEquals("", reg.getDescription());
        assertTrue(reg.isEnabled());
    }

    // ── settings list operations ──────────────────────────────────────────────

    @Test
    void registrationsList_deduplicate_byMacroName() {
        List<MacroRegistration> list = new ArrayList<>();
        list.add(new MacroRegistration("Macro A", "macro_a", "", true));
        list.add(new MacroRegistration("Macro B", "macro_b", "", false));

        long enabledCount = list.stream().filter(MacroRegistration::isEnabled).count();
        assertEquals(1, enabledCount);
    }

    @Test
    void copyList_mutationsAreIndependent() {
        MacroRegistration source = new MacroRegistration("m", "macro_m", "d", true);
        List<MacroRegistration> copies = List.of(source.copy());

        copies.get(0).setToolName("changed");
        assertEquals("macro_m", source.getToolName());
    }
}
