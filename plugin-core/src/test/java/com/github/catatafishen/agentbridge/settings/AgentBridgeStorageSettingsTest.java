package com.github.catatafishen.agentbridge.settings;

import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AgentBridgeStorageSettings} path-resolution helpers.
 * The state lifecycle is covered by IntelliJ's built-in PersistentStateComponent
 * machinery — tested here only via direct State manipulation.
 */
class AgentBridgeStorageSettingsTest {

    @Test
    void defaultStorageRootIsUserHomeDotAgentbridge() {
        Path expected = Paths.get(System.getProperty("user.home"), ".agentbridge");
        assertEquals(expected, AgentBridgeStorageSettings.getDefaultStorageRoot());
    }

    @Test
    void effectiveRootUsesCustomWhenSet() {
        AgentBridgeStorageSettings settings = new AgentBridgeStorageSettings();
        settings.setCustomStorageRoot("/tmp/custom-ab");
        assertEquals(Paths.get("/tmp/custom-ab"), settings.getEffectiveStorageRoot());
    }

    @Test
    void effectiveRootFallsBackToDefaultWhenBlank() {
        AgentBridgeStorageSettings settings = new AgentBridgeStorageSettings();
        settings.setCustomStorageRoot("   ");
        assertEquals(AgentBridgeStorageSettings.getDefaultStorageRoot(),
            settings.getEffectiveStorageRoot());
    }

    @Test
    void projectStorageDirIsNamespacedAndSanitized() {
        Project project = Mockito.mock(Project.class);
        Mockito.when(project.getName()).thenReturn("My Cool Project!");
        Mockito.when(project.getBasePath()).thenReturn("/home/user/projects/cool");

        AgentBridgeStorageSettings settings = new AgentBridgeStorageSettings();
        settings.setCustomStorageRoot("/data/ab");

        Path dir = settings.getProjectStorageDir(project);
        // sanitized: spaces and ! become '_', lower-cased
        assertTrue(dir.toString().contains("/projects/my_cool_project_-"),
            "Expected sanitized project name in path: " + dir);
        assertTrue(dir.startsWith(Paths.get("/data/ab/projects/")), "Path was: " + dir);
    }

    @Test
    void projectStorageDirDifferentForProjectsWithSameName() {
        Project p1 = Mockito.mock(Project.class);
        Mockito.when(p1.getName()).thenReturn("demo");
        Mockito.when(p1.getBasePath()).thenReturn("/home/a/demo");

        Project p2 = Mockito.mock(Project.class);
        Mockito.when(p2.getName()).thenReturn("demo");
        Mockito.when(p2.getBasePath()).thenReturn("/home/b/demo");

        AgentBridgeStorageSettings settings = new AgentBridgeStorageSettings();
        assertNotEquals(settings.getProjectStorageDir(p1),
            settings.getProjectStorageDir(p2),
            "Projects with the same name but different base paths must get distinct directories");
    }

    @Test
    void projectMemoryDirLivesUnderProjectStorageDir() {
        Project project = Mockito.mock(Project.class);
        Mockito.when(project.getName()).thenReturn("demo");
        Mockito.when(project.getBasePath()).thenReturn("/home/user/demo");

        AgentBridgeStorageSettings settings = new AgentBridgeStorageSettings();
        settings.setCustomStorageRoot("/data/ab");

        assertEquals(settings.getProjectStorageDir(project).resolve("memory"),
            settings.getProjectMemoryDir(project));
    }

    @Test
    void toolStatsEnabledDefaultsTrue() {
        AgentBridgeStorageSettings settings = new AgentBridgeStorageSettings();
        assertTrue(settings.isToolStatsEnabled());
    }
}
