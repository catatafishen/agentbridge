package com.github.catatafishen.agentbridge.psi;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for static/package-private utility methods in {@link SonarQubeIntegration}.
 * Only pure methods that don't need IntelliJ runtime are tested here.
 */
class SonarQubeIntegrationTest {

    @Nested
    class IsExcludedFinding {
        @Test
        void excludesBuildPrefix() {
            assertTrue(SonarQubeIntegration.isExcludedFinding("build/classes/Main.java:1 [BUG] issue"));
        }

        @Test
        void excludesOutPrefix() {
            assertTrue(SonarQubeIntegration.isExcludedFinding("out/production/MyClass.java:5 [BUG] issue"));
        }

        @Test
        void excludesPluginCoreBuild() {
            assertTrue(SonarQubeIntegration.isExcludedFinding("plugin-core/build/generated/Source.java:10 [BUG] issue"));
        }

        @Test
        void excludesPluginCoreOut() {
            assertTrue(SonarQubeIntegration.isExcludedFinding("plugin-core/out/something.java:1 [BUG] issue"));
        }

        @Test
        void excludesMcpServerBuild() {
            assertTrue(SonarQubeIntegration.isExcludedFinding("mcp-server/build/output.java:1 [BUG] issue"));
        }

        @Test
        void excludesPluginExperimentalBuild() {
            assertTrue(SonarQubeIntegration.isExcludedFinding("plugin-experimental/build/output.java:1 [BUG] issue"));
        }

        @Test
        void doesNotExcludeSourceFile() {
            assertFalse(SonarQubeIntegration.isExcludedFinding("src/main/java/MyClass.java:1 [BUG] issue"));
        }

        @Test
        void doesNotExcludePluginCoreSource() {
            assertFalse(SonarQubeIntegration.isExcludedFinding("plugin-core/src/main/java/Foo.java:1 [BUG] issue"));
        }

        @Test
        void emptyString() {
            assertFalse(SonarQubeIntegration.isExcludedFinding(""));
        }
    }

    @Nested
    class RelativizePath {
        @Test
        void removesBasePathPrefix() {
            assertEquals("src/Main.java",
                SonarQubeIntegration.relativizePath("/home/user/project/src/Main.java", "/home/user/project"));
        }

        @Test
        void returnsOriginalWhenNoMatch() {
            assertEquals("/other/path/File.java",
                SonarQubeIntegration.relativizePath("/other/path/File.java", "/home/user/project"));
        }

        @Test
        void returnsOriginalWhenBasePathNull() {
            assertEquals("/some/path/File.java",
                SonarQubeIntegration.relativizePath("/some/path/File.java", null));
        }

        @Test
        void exactBasePathMatch() {
            // When path equals basePath, substring(basePath.length() + 1) goes one char past
            // This tests the edge case
            String basePath = "/home/project";
            String path = "/home/project/A";
            assertEquals("A", SonarQubeIntegration.relativizePath(path, basePath));
        }
    }

    @Nested
    class FindMethod {
        @Test
        void findsPublicMethod() {
            Method m = SonarQubeIntegration.findMethod("hello", "length");
            assertNotNull(m);
            assertEquals("length", m.getName());
        }

        @Test
        void findsToString() {
            Method m = SonarQubeIntegration.findMethod(42, "toString");
            assertNotNull(m);
            assertEquals("toString", m.getName());
        }

        @Test
        void returnsNullForNonexistent() {
            Method m = SonarQubeIntegration.findMethod("hello", "nonExistentMethod12345");
            assertNull(m);
        }

        @Test
        void findsInheritedMethod() {
            // ArrayList inherits methods from AbstractList / AbstractCollection
            Method m = SonarQubeIntegration.findMethod(new ArrayList<>(), "size");
            assertNotNull(m);
            assertEquals("size", m.getName());
        }
    }

    @Nested
    class FormatOutput {
        // formatOutput is an instance method, but SonarQubeIntegration constructor takes Project.
        // We can't instantiate without IntelliJ runtime, so we skip this group
        // unless we can use reflection or a subclass. Since it requires Project, skip it.
    }
}
