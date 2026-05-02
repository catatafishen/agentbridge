package com.github.catatafishen.agentbridge.psi.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FqnResolver}.
 * <p>
 * Only the pure heuristic methods are tested here. FQN resolution methods
 * require the IntelliJ platform (JavaPsiFacade) and are tested via integration tests.
 */
class FqnResolverTest {

    @Nested
    @DisplayName("looksLikeFqn")
    class LooksLikeFqn {

        @ParameterizedTest
        @ValueSource(strings = {
            "com.example.MyClass",
            "java.util.List",
            "com.example.MyClass.myMethod",
            "org.junit.jupiter.api.Test",
            "a.b"
        })
        void shouldRecognizeFqns(String input) {
            assertTrue(FqnResolver.looksLikeFqn(input), "Should recognize as FQN: " + input);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "MyClass",
            "myMethod",
            "src/main/java/MyClass.java",
            "path/to/File.kt",
            "com/example/MyClass",
            "script.js",
            "module.ts",
            "test.py"
        })
        void shouldRejectNonFqns(String input) {
            assertFalse(FqnResolver.looksLikeFqn(input), "Should not recognize as FQN: " + input);
        }

        @Test
        void shouldRejectEmptyString() {
            assertFalse(FqnResolver.looksLikeFqn(""));
        }

        @Test
        void shouldRejectStringStartingWithNumber() {
            assertFalse(FqnResolver.looksLikeFqn("1com.example"));
        }

        @Test
        void shouldRejectFilePathsWithSlashes() {
            assertFalse(FqnResolver.looksLikeFqn("com/example/MyClass"));
            assertFalse(FqnResolver.looksLikeFqn("C:\\Users\\file.txt"));
        }
    }
}
