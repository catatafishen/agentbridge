package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.intellij.openapi.ui.popup.ListPopupStep;
import com.intellij.openapi.ui.popup.MnemonicNavigationFilter;
import com.intellij.openapi.ui.popup.SpeedSearchFilter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopupContentExtractorTest {

    @Nested
    class ExtractFromStep {

        @Test
        void extractsAllValuesFromStep() {
            var step = new FakeListPopupStep(List.of("Import java.util.List", "Import java.util.ArrayList"));
            var choices = PopupContentExtractor.extractFromStep(step);

            assertEquals(2, choices.size());
            assertEquals("Import java.util.List", choices.getFirst().text());
            assertEquals("Import java.util.ArrayList", choices.get(1).text());
        }

        @Test
        void setsCorrectIndexes() {
            var step = new FakeListPopupStep(List.of("A", "B", "C"));
            var choices = PopupContentExtractor.extractFromStep(step);

            assertEquals(0, choices.get(0).index());
            assertEquals(1, choices.get(1).index());
            assertEquals(2, choices.get(2).index());
        }

        @Test
        void buildsValueIdFromTextAndIndex() {
            var step = new FakeListPopupStep(List.of("Option A"));
            var choices = PopupContentExtractor.extractFromStep(step);

            assertEquals("Option A|0", choices.getFirst().valueId());
        }

        @Test
        void marksSelectableItems() {
            var step = new FakeListPopupStep(List.of("selectable", "not-selectable")) {
                @Override
                public boolean isSelectable(String value) {
                    return !"not-selectable".equals(value);
                }
            };
            var choices = PopupContentExtractor.extractFromStep(step);

            assertTrue(choices.get(0).selectable());
            assertFalse(choices.get(1).selectable());
        }

        @Test
        void marksSubstepItems() {
            var step = new FakeListPopupStep(List.of("has-substep", "no-substep")) {
                @Override
                public boolean hasSubstep(String value) {
                    return "has-substep".equals(value);
                }
            };
            var choices = PopupContentExtractor.extractFromStep(step);

            assertTrue(choices.get(0).hasSubstep());
            assertFalse(choices.get(1).hasSubstep());
        }

        @Test
        void handlesEmptyStep() {
            var step = new FakeListPopupStep(List.of());
            var choices = PopupContentExtractor.extractFromStep(step);
            assertTrue(choices.isEmpty());
        }

        @Test
        void handlesNullTextGracefully() {
            var step = new FakeListPopupStep(List.of("item")) {
                @Override
                public @NotNull String getTextFor(String value) {
                    throw new RuntimeException("text resolution failed");
                }
            };
            var choices = PopupContentExtractor.extractFromStep(step);

            assertEquals(1, choices.size());
            assertEquals("(unnamed)", choices.getFirst().text());
        }

        @Test
        void handlesExceptionInIsSelectable() {
            var step = new FakeListPopupStep(List.of("item")) {
                @Override
                public boolean isSelectable(String value) {
                    throw new RuntimeException("selectable check failed");
                }
            };
            var choices = PopupContentExtractor.extractFromStep(step);

            assertEquals(1, choices.size());
            assertFalse(choices.getFirst().selectable());
        }
    }

    @Nested
    class PopupChoiceBuildValueId {

        @Test
        void formatsTextAndIndex() {
            assertEquals("Option|0", PopupChoice.buildValueId("Option", 0));
            assertEquals("com.example.Class|3", PopupChoice.buildValueId("com.example.Class", 3));
        }
    }

    @Nested
    class PopupSnapshotBehavior {

        @Test
        void isEmptyWhenUnsupported() {
            var snapshot = new PopupSnapshot("title", List.of(), PopupSnapshot.KIND_UNSUPPORTED);
            assertTrue(snapshot.isEmpty());
        }

        @Test
        void isEmptyWhenNoChoices() {
            var snapshot = new PopupSnapshot("title", List.of(), PopupSnapshot.KIND_LIST_STEP);
            assertTrue(snapshot.isEmpty());
        }

        @Test
        void isNotEmptyWithChoices() {
            var choice = new PopupChoice("item|0", 0, "item", null, true, false);
            var snapshot = new PopupSnapshot("title", List.of(choice), PopupSnapshot.KIND_LIST_STEP);
            assertFalse(snapshot.isEmpty());
        }

        @Test
        void contentDigestIsConsistent() {
            var choice = new PopupChoice("item|0", 0, "item", null, true, false);
            var snapshot1 = new PopupSnapshot("title", List.of(choice), PopupSnapshot.KIND_LIST_STEP);
            var snapshot2 = new PopupSnapshot("title", List.of(choice), PopupSnapshot.KIND_LIST_STEP);
            assertEquals(snapshot1.contentDigest(), snapshot2.contentDigest());
        }

        @Test
        void contentDigestDiffersForDifferentContent() {
            var choice1 = new PopupChoice("A|0", 0, "A", null, true, false);
            var choice2 = new PopupChoice("B|0", 0, "B", null, true, false);
            var snapshot1 = new PopupSnapshot("title", List.of(choice1), PopupSnapshot.KIND_LIST_STEP);
            var snapshot2 = new PopupSnapshot("title", List.of(choice2), PopupSnapshot.KIND_LIST_STEP);
            assertNotNull(snapshot1.contentDigest());
            assertNotNull(snapshot2.contentDigest());
            assertNotEquals(snapshot1.contentDigest(), snapshot2.contentDigest());
        }
    }

    /**
     * Minimal fake implementation of ListPopupStep for testing extractFromStep.
     * Only the methods used by the extraction logic are implemented.
     */
    private static class FakeListPopupStep implements ListPopupStep<String> {
        private final List<String> values;

        FakeListPopupStep(List<String> values) {
            this.values = values;
        }

        @Override
        public @NotNull List<String> getValues() {
            return values;
        }

        @Override
        public @NotNull String getTextFor(String value) {
            return value;
        }

        @Override
        public boolean isSelectable(String value) {
            return true;
        }

        @Override
        public boolean hasSubstep(String value) {
            return false;
        }

        // --- Remaining ListPopupStep methods (unused by extractFromStep) ---

        @Override
        public @Nullable Icon getIconFor(String value) {
            return null;
        }

        @Override
        public @Nullable com.intellij.openapi.ui.popup.ListSeparator getSeparatorAbove(String value) {
            return null;
        }

        @Override
        public int getDefaultOptionIndex() {
            return -1;
        }

        @Override
        public @NotNull String getTitle() {
            return "Test Popup";
        }

        @Override
        public @Nullable com.intellij.openapi.ui.popup.PopupStep<?> onChosen(String selectedValue, boolean finalChoice) {
            return null;
        }

        @Override
        public boolean isSpeedSearchEnabled() {
            return false;
        }

        @Override
        public boolean isMnemonicsNavigationEnabled() {
            return false;
        }

        @Override
        public @Nullable Runnable getFinalRunnable() {
            return null;
        }

        @Override
        public boolean isAutoSelectionEnabled() {
            return false;
        }

        @Override
        public void canceled() {
            // Not used by extractFromStep
        }

        @Override
        public @Nullable MnemonicNavigationFilter<String> getMnemonicNavigationFilter() {
            return null;
        }

        @Override
        public @Nullable SpeedSearchFilter<String> getSpeedSearchFilter() {
            return null;
        }
    }
}
