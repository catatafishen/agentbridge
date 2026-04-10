package com.github.catatafishen.agentbridge.settings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectFilesSettingsTest {

    private ProjectFilesSettings settings;

    @BeforeEach
    void setUp() {
        settings = new ProjectFilesSettings();
    }

    @Test
    @DisplayName("default entries are non-empty and contain expected shortcuts")
    void defaultEntriesContainExpectedShortcuts() {
        List<ProjectFilesSettings.FileEntry> entries = settings.getEntries();
        assertFalse(entries.isEmpty());
        assertTrue(entries.stream().anyMatch(e -> e.getLabel().equals("TODO")));
        assertTrue(entries.stream().anyMatch(e -> e.isGlob()));
    }

    @Test
    @DisplayName("setEntries replaces the list with a defensive copy")
    void setEntriesReplacesList() {
        var entry = new ProjectFilesSettings.FileEntry("MyLabel", "some/path.md", false, "Group");
        settings.setEntries(List.of(entry));
        assertEquals(1, settings.getEntries().size());
        assertEquals("MyLabel", settings.getEntries().get(0).getLabel());
    }

    @Test
    @DisplayName("setEntries is a defensive copy — external mutations do not affect stored list")
    void setEntriesIsDefensiveCopy() {
        var entry = new ProjectFilesSettings.FileEntry("A", "a.md", false);
        var mutable = new java.util.ArrayList<>(List.of(entry));
        settings.setEntries(mutable);
        mutable.add(new ProjectFilesSettings.FileEntry("B", "b.md", false));
        assertEquals(1, settings.getEntries().size()); // copy was not affected
    }

    @Test
    @DisplayName("FileEntry default constructor produces empty fields")
    void fileEntryDefaultConstructorProducesEmptyFields() {
        var entry = new ProjectFilesSettings.FileEntry();
        assertEquals("", entry.getLabel());
        assertEquals("", entry.getPath());
        assertFalse(entry.isGlob());
        assertEquals("", entry.getGroup());
    }

    @Test
    @DisplayName("FileEntry three-arg constructor sets fields and defaults group to empty")
    void fileEntryThreeArgConstructor() {
        var entry = new ProjectFilesSettings.FileEntry("L", "p", true);
        assertEquals("L", entry.getLabel());
        assertEquals("p", entry.getPath());
        assertTrue(entry.isGlob());
        assertEquals("", entry.getGroup());
    }

    @Test
    @DisplayName("FileEntry four-arg constructor sets all fields including group")
    void fileEntryFourArgConstructor() {
        var entry = new ProjectFilesSettings.FileEntry("L", "p", false, "G");
        assertEquals("G", entry.getGroup());
    }

    @Test
    @DisplayName("FileEntry setters update all fields")
    void fileEntrySetters() {
        var entry = new ProjectFilesSettings.FileEntry();
        entry.setLabel("NewLabel");
        entry.setPath("new/path.md");
        entry.setGlob(true);
        entry.setGroup("NewGroup");

        assertEquals("NewLabel", entry.getLabel());
        assertEquals("new/path.md", entry.getPath());
        assertTrue(entry.isGlob());
        assertEquals("NewGroup", entry.getGroup());
    }

    @Test
    @DisplayName("getState returns the same state managed by the settings object")
    void getStateReturnsCurrentState() {
        var entry = new ProjectFilesSettings.FileEntry("T", "t.md", false);
        settings.setEntries(List.of(entry));
        assertEquals(1, settings.getState().getEntries().size());
    }

    @Test
    @DisplayName("loadState replaces the internal state")
    void loadStateReplacesState() {
        var newState = new ProjectFilesSettings.State();
        newState.setEntries(List.of(new ProjectFilesSettings.FileEntry("X", "x.md", false)));
        settings.loadState(newState);
        assertEquals(1, settings.getEntries().size());
        assertEquals("X", settings.getEntries().get(0).getLabel());
    }

    @Test
    @DisplayName("getDefaults static method returns non-empty list")
    void getDefaultsIsNotEmpty() {
        assertFalse(ProjectFilesSettings.getDefaults().isEmpty());
    }
}
