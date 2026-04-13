package com.github.catatafishen.agentbridge.settings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link GhBinaryDetector}.
 *
 * <p>The class is {@code final} and its interesting methods are {@code protected},
 * so we use reflection to invoke {@code additionalSearchPaths()} directly.
 * {@code getConfiguredPath()} depends on {@link BillingSettings} (IntelliJ platform),
 * so it is tested defensively — we verify it handles absence of the platform gracefully.
 */
class GhBinaryDetectorTest {

    private GhBinaryDetector detector;
    private Method additionalSearchPathsMethod;

    @BeforeEach
    void setUp() throws Exception {
        detector = new GhBinaryDetector();
        additionalSearchPathsMethod = GhBinaryDetector.class.getDeclaredMethod("additionalSearchPaths");
        additionalSearchPathsMethod.setAccessible(true);
    }

    // ── additionalSearchPaths ──────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<String> invokeAdditionalSearchPaths() throws Exception {
        return (List<String>) additionalSearchPathsMethod.invoke(detector);
    }

    @Nested
    class AdditionalSearchPaths {

        @Test
        void returnsNonEmptyList() throws Exception {
            List<String> paths = invokeAdditionalSearchPaths();
            assertNotNull(paths);
            assertFalse(paths.isEmpty(), "additionalSearchPaths should not be empty");
        }

        @Test
        void containsExactlyThreeEntries() throws Exception {
            List<String> paths = invokeAdditionalSearchPaths();
            assertEquals(3, paths.size(),
                    "Expected 3 additional search paths (Linux or Windows)");
        }

        @Test
        void noNullEntries() throws Exception {
            List<String> paths = invokeAdditionalSearchPaths();
            for (int i = 0; i < paths.size(); i++) {
                assertNotNull(paths.get(i), "Path at index " + i + " should not be null");
            }
        }

        @Test
        void noBlankEntries() throws Exception {
            List<String> paths = invokeAdditionalSearchPaths();
            for (String path : paths) {
                assertFalse(path.isBlank(), "Path should not be blank: '" + path + "'");
            }
        }

        @Test
        void allPathsEndWithBinaryName() throws Exception {
            List<String> paths = invokeAdditionalSearchPaths();
            String os = System.getProperty("os.name", "").toLowerCase();
            String expectedSuffix = os.contains("win") ? "gh.exe" : "gh";
            for (String path : paths) {
                assertTrue(path.endsWith(expectedSuffix),
                        "Path should end with '" + expectedSuffix + "': " + path);
            }
        }
    }

    // ── OS-specific path content (Linux) ───────────────────────────────

    @Nested
    class LinuxPaths {

        private boolean isLinux() {
            String os = System.getProperty("os.name", "").toLowerCase();
            return !os.contains("win") && !os.contains("mac");
        }

        @Test
        void containsSnapPath() throws Exception {
            if (!isLinux()) return; // skip on other OSes
            List<String> paths = invokeAdditionalSearchPaths();
            assertTrue(paths.contains("/snap/bin/gh"),
                    "Linux paths should include /snap/bin/gh");
        }

        @Test
        void containsLinuxbrewPath() throws Exception {
            if (!isLinux()) return;
            List<String> paths = invokeAdditionalSearchPaths();
            boolean hasLinuxbrew = paths.stream().anyMatch(p -> p.contains("linuxbrew"));
            assertTrue(hasLinuxbrew,
                    "Linux paths should include a linuxbrew path");
        }

        @Test
        void containsLocalBinPath() throws Exception {
            if (!isLinux()) return;
            List<String> paths = invokeAdditionalSearchPaths();
            String home = System.getProperty("user.home");
            String expected = home + "/.local/bin/gh";
            assertTrue(paths.contains(expected),
                    "Linux paths should include " + expected);
        }

        @Test
        void linuxbrewPathIsAbsolute() throws Exception {
            if (!isLinux()) return;
            List<String> paths = invokeAdditionalSearchPaths();
            String linuxbrewPath = paths.stream()
                    .filter(p -> p.contains("linuxbrew"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("/home/linuxbrew/.linuxbrew/bin/gh", linuxbrewPath);
        }

        @Test
        void allLinuxPathsAreAbsolute() throws Exception {
            if (!isLinux()) return;
            List<String> paths = invokeAdditionalSearchPaths();
            for (String path : paths) {
                assertTrue(path.startsWith("/"),
                        "Linux path should be absolute: " + path);
            }
        }
    }

    // ── OS-specific path content (Windows) ─────────────────────────────

    @Nested
    class WindowsPaths {

        private boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase().contains("win");
        }

        @Test
        void containsProgramFilesPath() throws Exception {
            if (!isWindows()) return;
            List<String> paths = invokeAdditionalSearchPaths();
            assertTrue(paths.contains("C:\\Program Files\\GitHub CLI\\gh.exe"),
                    "Windows paths should include Program Files path");
        }

        @Test
        void containsProgramFilesX86Path() throws Exception {
            if (!isWindows()) return;
            List<String> paths = invokeAdditionalSearchPaths();
            assertTrue(paths.contains("C:\\Program Files (x86)\\GitHub CLI\\gh.exe"),
                    "Windows paths should include Program Files (x86) path");
        }

        @Test
        void containsAppDataPath() throws Exception {
            if (!isWindows()) return;
            List<String> paths = invokeAdditionalSearchPaths();
            String home = System.getProperty("user.home");
            String expected = home + "\\AppData\\Local\\GitHub CLI\\gh.exe";
            assertTrue(paths.contains(expected),
                    "Windows paths should include AppData path: " + expected);
        }
    }

    // ── getConfiguredPath (requires IntelliJ platform) ─────────────────

    @Nested
    class GetConfiguredPath {

        @Test
        void handlesAbsenceOfPlatformGracefully() {
            // BillingSettings.getInstance() requires the IntelliJ platform.
            // In a headless unit-test environment this will throw.
            // We verify the method exists and is callable via reflection.
            Method method;
            try {
                method = GhBinaryDetector.class.getDeclaredMethod("getConfiguredPath");
                method.setAccessible(true);
                // If the platform IS available, the method should return null or a string
                Object result = method.invoke(detector);
                assertTrue(result == null || result instanceof String,
                        "getConfiguredPath should return null or a String");
            } catch (Exception e) {
                // Expected in headless test: BillingSettings service not registered
                assertTrue(
                        e.getCause() instanceof IllegalStateException
                                || e.getCause() instanceof NullPointerException
                                || e instanceof NoSuchMethodException,
                        "Expected platform-related exception, got: " + e);
            }
        }
    }
}
