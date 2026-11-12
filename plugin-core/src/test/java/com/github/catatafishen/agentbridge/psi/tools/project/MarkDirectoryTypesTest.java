package com.github.catatafishen.agentbridge.psi.tools.project;

import com.github.catatafishen.agentbridge.psi.tools.navigation.FileCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkDirectoryTypesTest {

    @Test
    void markableTypesMatchScopeCategoryIds() {
        for (FileCategory c : FileCategory.values()) {
            boolean derived = c == FileCategory.IDE || c == FileCategory.OTHER || c == FileCategory.LIBRARIES;
            assertEquals(!derived, MarkDirectoryTool.VALID_TYPES.contains(c.id()), c.id());
        }
    }

    @Test
    void excludedAndUnmarkAreMarkableButDerivedCategoriesAreNot() {
        assertTrue(MarkDirectoryTool.VALID_TYPES.contains("excluded"));
        assertTrue(MarkDirectoryTool.VALID_TYPES.contains("unmark"));
        assertFalse(MarkDirectoryTool.VALID_TYPES.contains("ide"));
        assertFalse(MarkDirectoryTool.VALID_TYPES.contains("other"));
        assertFalse(MarkDirectoryTool.VALID_TYPES.contains("libraries"));
    }
}
