package com.github.catatafishen.agentbridge.psi.tools.quality;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PopupChoiceTest {

    @Test
    void buildValueIdIsDeterministic() {
        assertEquals("Cell|0", PopupChoice.buildValueId("Cell", 0));
        assertEquals("com.x.Y|3", PopupChoice.buildValueId("com.x.Y", 3));
    }

    @Test
    void buildValueIdIsUniqueAcrossDifferentInputs() {
        String a = PopupChoice.buildValueId("Cell", 0);
        String b = PopupChoice.buildValueId("Cell", 1);
        String c = PopupChoice.buildValueId("Other", 0);
        assertNotEquals(a, b);
        assertNotEquals(a, c);
        assertNotEquals(b, c);
    }
}
