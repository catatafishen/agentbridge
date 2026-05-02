package com.github.catatafishen.agentbridge.psi.tools.navigation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link NavigationTool#safeRelativize(String, String)}.
 */
class SafeRelativizeTest {

    @Nested
    @DisplayName("Project-relative paths")
    class ProjectRelative {
        @Test
        void stripsProjectBasePath() {
            assertEquals("src/Main.java",
                NavigationTool.safeRelativize("/home/user/project", "/home/user/project/src/Main.java"));
        }

        @Test
        void handlesBackslashes() {
            assertEquals("src/Main.java",
                NavigationTool.safeRelativize("C:\\Users\\project", "C:\\Users\\project\\src\\Main.java"));
        }
    }

    @Nested
    @DisplayName("JAR-internal paths")
    class JarPaths {
        @Test
        void producesJarUrlForGradleCache() {
            String jarPath = "/home/user/.gradle/caches/guava-31.1.jar!/com/google/common/collect/ImmutableList.java";
            assertEquals("jar://" + jarPath,
                NavigationTool.safeRelativize("/home/user/project", jarPath));
        }

        @Test
        void producesJarUrlForJarInsideProject() {
            String jarPath = "/home/user/project/lib/deps.jar!/org/example/Util.java";
            assertEquals("jar://" + jarPath,
                NavigationTool.safeRelativize("/home/user/project", jarPath));
        }

        @Test
        void producesJarUrlWithNullBasePath() {
            String jarPath = "/opt/jdk/lib/rt.jar!/java/lang/String.java";
            assertEquals("jar://" + jarPath,
                NavigationTool.safeRelativize(null, jarPath));
        }
    }

    @Nested
    @DisplayName("External paths")
    class ExternalPaths {
        @Test
        void returnsFilenameOnlyForExternalPaths() {
            assertEquals("SomeFile.java",
                NavigationTool.safeRelativize("/home/user/project", "/opt/external/SomeFile.java"));
        }

        @Test
        void returnsFilenameWithNullBasePath() {
            assertEquals("MyClass.java",
                NavigationTool.safeRelativize(null, "/some/path/MyClass.java"));
        }
    }
}
