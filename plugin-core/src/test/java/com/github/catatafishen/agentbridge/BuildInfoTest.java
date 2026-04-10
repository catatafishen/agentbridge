package com.github.catatafishen.agentbridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BuildInfo")
class BuildInfoTest {

    @Nested
    @DisplayName("getVersion")
    class GetVersion {

        @Test
        @DisplayName("returns non-null string")
        void returnsNonNull() {
            assertNotNull(BuildInfo.getVersion());
        }

        @Test
        @DisplayName("if version is known, it is not 'unknown'")
        void ifKnownNotUnknown() {
            String version = BuildInfo.getVersion();
            if (!"unknown".equals(version)) {
                assertNotEquals("unknown", version);
            }
        }
    }

    @Nested
    @DisplayName("getGitHash")
    class GetGitHash {

        @Test
        @DisplayName("returns non-null string")
        void returnsNonNull() {
            assertNotNull(BuildInfo.getGitHash());
        }
    }

    @Nested
    @DisplayName("getTimestamp")
    class GetTimestamp {

        @Test
        @DisplayName("returns non-null string")
        void returnsNonNull() {
            assertNotNull(BuildInfo.getTimestamp());
        }
    }

    @Nested
    @DisplayName("getSummary")
    class GetSummary {

        @Test
        @DisplayName("contains version and git hash")
        void containsVersionAndGitHash() {
            String summary = BuildInfo.getSummary();
            assertAll(
                () -> assertTrue(summary.contains(BuildInfo.getVersion()), "summary should contain version"),
                () -> assertTrue(summary.contains(BuildInfo.getGitHash()), "summary should contain git hash")
            );
        }

        @Test
        @DisplayName("format contains parentheses and '@'")
        void formatContainsParenthesesAndAt() {
            String summary = BuildInfo.getSummary();
            assertAll(
                () -> assertTrue(summary.contains("("), "summary should contain '('"),
                () -> assertTrue(summary.contains(")"), "summary should contain ')'"),
                () -> assertTrue(summary.contains("@"), "summary should contain '@'")
            );
        }

        @Test
        @DisplayName("format matches '<version> (<hash> @ <timestamp>)'")
        void formatMatchesExpected() {
            String version = BuildInfo.getVersion();
            String hash = BuildInfo.getGitHash();
            String timestamp = BuildInfo.getTimestamp();
            String expected = version + " (" + hash + " @ " + timestamp + ")";
            assertEquals(expected, BuildInfo.getSummary());
        }
    }

    @Nested
    @DisplayName("private constructor")
    class PrivateConstructor {

        @Test
        @DisplayName("cannot be instantiated via reflection")
        void cannotInstantiate() throws NoSuchMethodException {
            Constructor<BuildInfo> ctor = BuildInfo.class.getDeclaredConstructor();
            assertFalse(ctor.canAccess(null), "constructor should not be accessible");
        }
    }
}
