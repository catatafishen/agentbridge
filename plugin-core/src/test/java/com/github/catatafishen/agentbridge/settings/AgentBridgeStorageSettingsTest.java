package com.github.catatafishen.agentbridge.settings;

import com.github.catatafishen.agentbridge.settings.AgentBridgeStorageSettings.StorageLocationMode;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AgentBridgeStorageSettings} path-resolution helpers.
 * The state lifecycle is covered by IntelliJ's built-in PersistentStateComponent
 * machinery — tested here only via direct State manipulation.
 */
class AgentBridgeStorageSettingsTest {

    @Test
    void defaultStorageModeIsProjectDirectory() {
        AgentBridgeStorageSettings settings = new AgentBridgeStorageSettings();
        assertEquals(StorageLocationMode.PROJECT, settings.getStorageLocationMode());
    }

    @Test
    void projectDefaultStorageRootIsProjectDotAgentbridge() {
        Project project = project("Demo", "/home/user/demo");
        assertEquals(Paths.get("/home/user/demo/.agentbridge"),
            AgentBridgeStorageSettings.getProjectDefaultStorageRoot(project));
    }

    @Test
    void userHomeStorageRootIsUserHomeDotAgentbridge() {
        Path expected = Paths.get(System.getProperty("user.home"), ".agentbridge");
        assertEquals(expected, AgentBridgeStorageSettings.getUserHomeStorageRoot());
    }

    @Test
    void projectStorageDirDefaultsToProjectDotAgentbridge() {
        Project project = project("demo", "/home/user/demo");

        AgentBridgeStorageSettings settings = new AgentBridgeStorageSettings();

        assertEquals(Paths.get("/home/user/demo/.agentbridge"), settings.getProjectStorageDir(project));
    }

    @Test
    void projectStorageDirUsesUserHomeRootWhenSelected() {
        Project project = project("My Cool Project!", "/home/user/projects/cool");

        AgentBridgeStorageSettings settings = new AgentBridgeStorageSettings();
        settings.setStorageLocationMode(StorageLocationMode.USER_HOME);

        Path dir = settings.getProjectStorageDir(project);
        assertTrue(dir.toString().contains("/projects/my_cool_project_-"),
            "Expected sanitized project name in path: " + dir);
        assertTrue(dir.startsWith(AgentBridgeStorageSettings.getUserHomeStorageRoot().resolve("projects")),
            "Path was: " + dir);
    }

    @Test
    void projectStorageDirUsesCustomRootWhenSelected() {
        Project project = project("My Cool Project!", "/home/user/projects/cool");

        AgentBridgeStorageSettings settings = new AgentBridgeStorageSettings();
        settings.setStorageLocationMode(StorageLocationMode.CUSTOM);
        settings.setCustomStorageRoot("/data/ab");

        Path dir = settings.getProjectStorageDir(project);
        assertTrue(dir.toString().contains("/projects/my_cool_project_-"),
            "Expected sanitized project name in path: " + dir);
        assertTrue(dir.startsWith(Paths.get("/data/ab/projects/")), "Path was: " + dir);
    }

    @Test
    void blankCustomRootFailsWhenCustomModeSelected() {
        Project project = project("demo", "/home/user/demo");

        AgentBridgeStorageSettings settings = new AgentBridgeStorageSettings();
        settings.setStorageLocationMode(StorageLocationMode.CUSTOM);
        settings.setCustomStorageRoot("   ");

        assertThrows(IllegalStateException.class, () -> settings.getProjectStorageDir(project));
    }

    @Test
    void legacyCustomRootSelectsCustomMode() {
        AgentBridgeStorageSettings settings = new AgentBridgeStorageSettings();
        settings.setCustomStorageRoot("/data/ab");

        assertEquals(StorageLocationMode.CUSTOM, settings.getStorageLocationMode());
    }

    @Test
    void projectStorageDirDifferentForProjectsWithSameNameInSharedRoot() {
        Project p1 = project("demo", "/home/a/demo");
        Project p2 = project("demo", "/home/b/demo");

        AgentBridgeStorageSettings settings = new AgentBridgeStorageSettings();
        settings.setStorageLocationMode(StorageLocationMode.USER_HOME);

        assertNotEquals(settings.getProjectStorageDir(p1),
            settings.getProjectStorageDir(p2),
            "Projects with the same name but different base paths must get distinct directories");
    }

    @Test
    void projectMemoryDirLivesUnderProjectStorageDir() {
        Project project = project("demo", "/home/user/demo");

        AgentBridgeStorageSettings settings = new AgentBridgeStorageSettings();
        settings.setStorageLocationMode(StorageLocationMode.CUSTOM);
        settings.setCustomStorageRoot("/data/ab");

        assertEquals(settings.getProjectStorageDir(project).resolve("memory"),
            settings.getProjectMemoryDir(project));
    }

    @Test
    void toolStatsEnabledDefaultsTrue() {
        AgentBridgeStorageSettings settings = new AgentBridgeStorageSettings();
        assertTrue(settings.isToolStatsEnabled());
    }

    @Test
    void projectStorageDirFallsBackToUserHomeWhenBasePathMissing() {
        Project project = project("Headless", null);

        AgentBridgeStorageSettings settings = new AgentBridgeStorageSettings();

        Path dir = settings.getProjectStorageDir(project);
        assertTrue(dir.startsWith(AgentBridgeStorageSettings.getUserHomeStorageRoot().resolve("projects")),
            "Expected fallback under user-home projects/, got: " + dir);
    }

    @Test
    void projectDefaultStorageRootReturnsNullWhenBasePathMissing() {
        Project project = project("Headless", null);
        org.junit.jupiter.api.Assertions.assertNull(
            AgentBridgeStorageSettings.getProjectDefaultStorageRoot(project));
    }

    private static Project project(String name, String basePath) {
        Project project = Mockito.mock(Project.class);
        Mockito.when(project.getName()).thenReturn(name);
        Mockito.when(project.getBasePath()).thenReturn(basePath);
        return project;
    }
}
