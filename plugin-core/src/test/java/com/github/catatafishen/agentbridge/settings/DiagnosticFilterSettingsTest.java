package com.github.catatafishen.agentbridge.settings;

import com.intellij.lang.annotation.HighlightSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticFilterSettingsTest {

    private DiagnosticFilterSettings settings;

    @BeforeEach
    void setUp() {
        settings = new DiagnosticFilterSettings();
    }

    // ── Default state ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("default: errors shown")
    void defaultShowErrors() {
        assertTrue(settings.isShowErrors());
    }

    @Test
    @DisplayName("default: warnings shown")
    void defaultShowWarnings() {
        assertTrue(settings.isShowWarnings());
    }

    @Test
    @DisplayName("default: weak warnings shown")
    void defaultShowWeakWarnings() {
        assertTrue(settings.isShowWeakWarnings());
    }

    @Test
    @DisplayName("default: information shown")
    void defaultShowInformation() {
        assertTrue(settings.isShowInformation());
    }

    @Test
    void defaultSpellCheckingSuppressed() {
        assertTrue(settings.isInspectionSuppressed("SpellCheckingInspection"));
    }

    @Test
    @DisplayName("default: unknown inspection is not suppressed")
    void defaultUnknownNotSuppressed() {
        assertFalse(settings.isInspectionSuppressed("SomeOtherInspection"));
    }

    // ── isSeverityEnabled ─────────────────────────────────────────────────────

    @Test
    @DisplayName("ERROR severity enabled by default")
    void errorEnabledByDefault() {
        assertTrue(settings.isSeverityEnabled(HighlightSeverity.ERROR));
    }

    @Test
    @DisplayName("WARNING severity enabled by default")
    void warningEnabledByDefault() {
        assertTrue(settings.isSeverityEnabled(HighlightSeverity.WARNING));
    }

    @Test
    @DisplayName("WEAK_WARNING severity enabled by default")
    void weakWarningEnabledByDefault() {
        assertTrue(settings.isSeverityEnabled(HighlightSeverity.WEAK_WARNING));
    }

    @Test
    @DisplayName("INFORMATION severity enabled by default")
    void informationEnabledByDefault() {
        assertTrue(settings.isSeverityEnabled(HighlightSeverity.INFORMATION));
    }

    @Test
    @DisplayName("disabling information hides INFORMATION-level highlights")
    void disablingInformationHidesInformation() {
        settings.setShowInformation(false);
        assertFalse(settings.isSeverityEnabled(HighlightSeverity.INFORMATION));
    }

    @Test
    @DisplayName("disabling information does not hide weak warnings or higher")
    void disablingInformationDoesNotHideHigherSeverities() {
        settings.setShowInformation(false);
        assertTrue(settings.isSeverityEnabled(HighlightSeverity.WEAK_WARNING));
        assertTrue(settings.isSeverityEnabled(HighlightSeverity.WARNING));
        assertTrue(settings.isSeverityEnabled(HighlightSeverity.ERROR));
    }

    @Test
    @DisplayName("TEXT_ATTRIBUTES severity always excluded regardless of myVal")
    void textAttributesAlwaysExcluded() {
        // In IntelliJ, TEXT_ATTRIBUTES.myVal == INFORMATION.myVal == 10.
        // They can only be distinguished by name. Verify the name-based guard works.
        assertFalse(settings.isSeverityEnabled(HighlightSeverity.TEXT_ATTRIBUTES));
    }

    @Test
    @DisplayName("GRAMMAR_ERROR severity enabled by default")
    void grammarErrorEnabledByDefault() {
        var grammarError = new HighlightSeverity("GRAMMAR_ERROR", HighlightSeverity.INFORMATION.myVal + 5);
        assertTrue(settings.isSeverityEnabled(grammarError));
    }

    @Test
    @DisplayName("STYLE_SUGGESTION severity enabled by default")
    void styleSuggestionEnabledByDefault() {
        var styleSuggestion = new HighlightSeverity("STYLE_SUGGESTION", HighlightSeverity.INFORMATION.myVal + 4);
        assertTrue(settings.isSeverityEnabled(styleSuggestion));
    }

    @Test
    @DisplayName("disabling grammar and spelling hides GRAMMAR_ERROR and STYLE_SUGGESTION")
    void disablingGrammarHidesGrammarSeverities() {
        settings.setShowGrammarAndSpelling(false);
        assertFalse(settings.isSeverityEnabled(new HighlightSeverity("GRAMMAR_ERROR", HighlightSeverity.INFORMATION.myVal + 5)));
        assertFalse(settings.isSeverityEnabled(new HighlightSeverity("STYLE_SUGGESTION", HighlightSeverity.INFORMATION.myVal + 4)));
        assertFalse(settings.isSeverityEnabled(new HighlightSeverity("TYPO", HighlightSeverity.INFORMATION.myVal + 3)));
    }

    @Test
    @DisplayName("disabling information does not hide grammar or spelling severities")
    void disablingInformationDoesNotHideGrammarSeverities() {
        settings.setShowInformation(false);
        assertTrue(settings.isSeverityEnabled(new HighlightSeverity("GRAMMAR_ERROR", HighlightSeverity.INFORMATION.myVal + 5)));
        assertTrue(settings.isSeverityEnabled(new HighlightSeverity("STYLE_SUGGESTION", HighlightSeverity.INFORMATION.myVal + 4)));
    }

    @Test
    @DisplayName("severity below INFORMATION always excluded")
    void belowInformationAlwaysExcluded() {
        var belowInfo = new HighlightSeverity("BELOW_INFO", HighlightSeverity.INFORMATION.myVal - 1);
        assertFalse(settings.isSeverityEnabled(belowInfo));
    }

    @Test
    @DisplayName("custom severity above ERROR treated as error-level")
    void customSeverityAboveErrorTreatedAsError() {
        var aboveError = new HighlightSeverity("CRITICAL", HighlightSeverity.ERROR.myVal + 100);
        settings.setShowErrors(true);
        assertTrue(settings.isSeverityEnabled(aboveError));

        settings.setShowErrors(false);
        assertFalse(settings.isSeverityEnabled(aboveError));
    }

    @Test
    @DisplayName("disabling errors hides ERROR-level highlights")
    void disablingErrorsHidesErrors() {
        settings.setShowErrors(false);
        assertFalse(settings.isSeverityEnabled(HighlightSeverity.ERROR));
    }

    @Test
    @DisplayName("disabling warnings hides WARNING-level highlights")
    void disablingWarningsHidesWarnings() {
        settings.setShowWarnings(false);
        assertFalse(settings.isSeverityEnabled(HighlightSeverity.WARNING));
    }

    @Test
    @DisplayName("disabling warnings does not hide errors")
    void disablingWarningsDoesNotHideErrors() {
        settings.setShowWarnings(false);
        assertTrue(settings.isSeverityEnabled(HighlightSeverity.ERROR));
    }

    @Test
    @DisplayName("disabling weak warnings hides WEAK_WARNING-level highlights")
    void disablingWeakWarningsHidesWeakWarnings() {
        settings.setShowWeakWarnings(false);
        assertFalse(settings.isSeverityEnabled(HighlightSeverity.WEAK_WARNING));
    }

    @Test
    @DisplayName("disabling weak warnings does not hide warnings or errors")
    void disablingWeakWarningsDoesNotHideHigherSeverities() {
        settings.setShowWeakWarnings(false);
        assertTrue(settings.isSeverityEnabled(HighlightSeverity.WARNING));
        assertTrue(settings.isSeverityEnabled(HighlightSeverity.ERROR));
    }

    // ── isInspectionSuppressed ────────────────────────────────────────────────

    @Test
    @DisplayName("added inspection ID is suppressed")
    void addedInspectionIsSuppressed() {
        settings.setSuppressedInspectionIds(List.of("MyInspection"));
        assertTrue(settings.isInspectionSuppressed("MyInspection"));
    }

    @Test
    @DisplayName("removing all inspection IDs leaves nothing suppressed")
    void clearingSuppressedLeavesNothingSuppressed() {
        settings.setSuppressedInspectionIds(List.of());
        assertFalse(settings.isInspectionSuppressed("SpellCheckingInspection"));
    }

    @Test
    @DisplayName("inspection ID matching is case-sensitive")
    void suppressionIsCaseSensitive() {
        assertFalse(settings.isInspectionSuppressed("spellcheckinginspection"));
        assertFalse(settings.isInspectionSuppressed("SPELLCHECKINGINSPECTION"));
    }

    // ── getSuppressedInspectionIds returns unmodifiable view ──────────────────

    @Test
    @DisplayName("getSuppressedInspectionIds returns unmodifiable list")
    void suppressedListIsUnmodifiable() {
        assertThrows(UnsupportedOperationException.class,
            () -> settings.getSuppressedInspectionIds().add("X"));
    }

    // ── loadState ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("loadState replaces internal state completely")
    void loadStateReplacesState() {
        var state = new DiagnosticFilterSettings.State();
        state.setShowErrors(false);
        state.setShowWarnings(false);
        state.setShowWeakWarnings(false);
        state.setShowInformation(false);
        state.setSuppressedInspectionIds(List.of("FooInspection"));

        settings.loadState(state);

        assertFalse(settings.isShowErrors());
        assertFalse(settings.isShowWarnings());
        assertFalse(settings.isShowWeakWarnings());
        assertFalse(settings.isShowInformation());
        assertTrue(settings.isInspectionSuppressed("FooInspection"));
        assertFalse(settings.isInspectionSuppressed("SpellCheckingInspection"));
    }
}
