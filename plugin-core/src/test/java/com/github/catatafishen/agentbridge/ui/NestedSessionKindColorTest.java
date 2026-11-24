package com.github.catatafishen.agentbridge.ui;

import com.github.catatafishen.agentbridge.psi.tools.infrastructure.RunNestedSessionTool;
import com.github.catatafishen.agentbridge.psi.tools.infrastructure.SleepTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The tool chip of a nested session is purple, like its entry, instead of the grey of the tools without a kind.
 */
class NestedSessionKindColorTest {

    @Test
    void theNestedSessionToolHasAKindOfItsOwn() {
        assertEquals(ToolDefinitionKindKt.NESTED_KIND,
            ToolDefinitionKindKt.cssKindName(new RunNestedSessionTool(null)));
    }

    @Test
    void otherToolsWithoutAKindStayWithTheirKind() {
        // sleep has Kind.OTHER like the nested session tool had, and must not change colour with it.
        assertEquals("read", ToolDefinitionKindKt.cssKindName(new SleepTool(null)));
    }

    @Test
    void theNestedKindIsPurpleLikeTheNestedEntry() {
        assertEquals(ChatTheme.INSTANCE.getNESTED_COLOR(),
            NativeChatColors.INSTANCE.kindColor(ToolDefinitionKindKt.NESTED_KIND, null));
    }

    @Test
    void theNestedKindIsNotTheGreyOfAKindlessChip() {
        assertNotEquals(NativeChatColors.INSTANCE.kindColor("other", null),
            NativeChatColors.INSTANCE.kindColor(ToolDefinitionKindKt.NESTED_KIND, null));
    }

    @Test
    void theKindNameIsMatchedWithoutRegardToCase() {
        assertEquals(NativeChatColors.INSTANCE.kindColor(ToolDefinitionKindKt.NESTED_KIND, null),
            NativeChatColors.INSTANCE.kindColor("NESTED", null));
    }

    @Test
    void theNestedColourIsNoneOfTheKindColoursTheSettingsOffer() {
        for (String kind : new String[]{"read", "search", "edit", "execute"}) {
            assertNotEquals(NativeChatColors.INSTANCE.kindColor(kind, null),
                NativeChatColors.INSTANCE.kindColor(ToolDefinitionKindKt.NESTED_KIND, null), kind);
        }
    }
}
