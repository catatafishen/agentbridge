package com.github.catatafishen.agentbridge.psi.tools.quality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopupSnapshotTest {

    private static PopupChoice choice(String text, int idx) {
        return new PopupChoice(PopupChoice.buildValueId(text, idx), idx, text, null, true, false);
    }

    @Test
    void emptyChoicesIsEmpty() {
        PopupSnapshot s = new PopupSnapshot("title", List.of(), PopupSnapshot.KIND_LIST_STEP);
        assertTrue(s.isEmpty());
    }

    @Test
    void unsupportedKindIsEmptyEvenWithChoices() {
        PopupSnapshot s = new PopupSnapshot("title", List.of(choice("a", 0)),
            PopupSnapshot.KIND_UNSUPPORTED);
        assertTrue(s.isEmpty());
    }

    @Test
    void populatedListStepIsNotEmpty() {
        PopupSnapshot s = new PopupSnapshot("title", List.of(choice("a", 0)),
            PopupSnapshot.KIND_LIST_STEP);
        assertFalse(s.isEmpty());
    }

    @Test
    void contentDigestIsStableAcrossEquivalentSnapshots() {
        PopupSnapshot a = new PopupSnapshot("Choose Class",
            List.of(choice("com.x.Cell", 0), choice("com.y.Cell", 1)),
            PopupSnapshot.KIND_LIST_STEP);
        PopupSnapshot b = new PopupSnapshot("Choose Class",
            List.of(choice("com.x.Cell", 0), choice("com.y.Cell", 1)),
            PopupSnapshot.KIND_LIST_STEP);
        assertEquals(a.contentDigest(), b.contentDigest());
    }

    @Test
    void contentDigestChangesWhenChoicesDiffer() {
        PopupSnapshot a = new PopupSnapshot("title",
            List.of(choice("a", 0), choice("b", 1)), PopupSnapshot.KIND_LIST_STEP);
        PopupSnapshot b = new PopupSnapshot("title",
            List.of(choice("a", 0)), PopupSnapshot.KIND_LIST_STEP);
        assertNotEquals(a.contentDigest(), b.contentDigest());
    }

    @Test
    void contentDigestChangesWhenTitleDiffers() {
        PopupSnapshot a = new PopupSnapshot("Title A",
            List.of(choice("a", 0)), PopupSnapshot.KIND_LIST_STEP);
        PopupSnapshot b = new PopupSnapshot("Title B",
            List.of(choice("a", 0)), PopupSnapshot.KIND_LIST_STEP);
        assertNotEquals(a.contentDigest(), b.contentDigest());
    }

    @Test
    void choicesListIsCopiedDefensively() {
        java.util.ArrayList<PopupChoice> mutable = new java.util.ArrayList<>();
        mutable.add(choice("a", 0));
        PopupSnapshot s = new PopupSnapshot("t", mutable, PopupSnapshot.KIND_LIST_STEP);
        mutable.add(choice("b", 1));
        assertEquals(1, s.choices().size());
    }
}
