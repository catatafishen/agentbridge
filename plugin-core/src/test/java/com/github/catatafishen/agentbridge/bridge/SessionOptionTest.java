package com.github.catatafishen.agentbridge.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SessionOption")
class SessionOptionTest {

    @Nested
    @DisplayName("Constructors")
    class Constructors {

        @Test
        @DisplayName("3-arg constructor sets labels and initialValue to null")
        void threeArgConstructor() {
            SessionOption option = new SessionOption("effort", "Effort", List.of("low", "high"));
            assertEquals("effort", option.key());
            assertEquals("Effort", option.displayName());
            assertEquals(List.of("low", "high"), option.values());
            assertNull(option.labels());
            assertNull(option.initialValue());
        }

        @Test
        @DisplayName("4-arg constructor sets initialValue to null")
        void fourArgConstructor() {
            Map<String, String> labels = Map.of("low", "Low Priority");
            SessionOption option = new SessionOption("effort", "Effort", List.of("low", "high"), labels);
            assertEquals("effort", option.key());
            assertEquals("Effort", option.displayName());
            assertEquals(List.of("low", "high"), option.values());
            assertEquals(labels, option.labels());
            assertNull(option.initialValue());
        }

        @Test
        @DisplayName("5-arg constructor sets all fields")
        void fiveArgConstructor() {
            Map<String, String> labels = Map.of("low", "Low Priority");
            SessionOption option = new SessionOption("effort", "Effort", List.of("low", "high"), labels, "low");
            assertEquals("effort", option.key());
            assertEquals("Effort", option.displayName());
            assertEquals(List.of("low", "high"), option.values());
            assertEquals(labels, option.labels());
            assertEquals("low", option.initialValue());
        }
    }

    @Nested
    @DisplayName("labelFor")
    class LabelFor {

        @Test
        @DisplayName("null value returns 'Default'")
        void nullValue() {
            SessionOption option = new SessionOption("k", "K", List.of("a"));
            assertEquals("Default", option.labelFor(null));
        }

        @Test
        @DisplayName("empty string returns 'Default'")
        void emptyValue() {
            SessionOption option = new SessionOption("k", "K", List.of("a"));
            assertEquals("Default", option.labelFor(""));
        }

        @Test
        @DisplayName("value with no labels map returns title-cased value")
        void noLabelsMap() {
            SessionOption option = new SessionOption("k", "K", List.of("high"));
            assertEquals("High", option.labelFor("high"));
        }

        @Test
        @DisplayName("value present in labels map returns mapped label")
        void valueInLabelsMap() {
            Map<String, String> labels = Map.of("high", "Maximum Effort");
            SessionOption option = new SessionOption("k", "K", List.of("high"), labels);
            assertEquals("Maximum Effort", option.labelFor("high"));
        }

        @Test
        @DisplayName("value NOT in labels map falls back to title-cased")
        void valueNotInLabelsMap() {
            Map<String, String> labels = Map.of("low", "Minimal");
            SessionOption option = new SessionOption("k", "K", List.of("high"), labels);
            assertEquals("High", option.labelFor("high"));
        }

        @Test
        @DisplayName("single-char value is uppercased")
        void singleChar() {
            SessionOption option = new SessionOption("k", "K", List.of("a"));
            assertEquals("A", option.labelFor("a"));
        }

        @Test
        @DisplayName("already-capitalized value stays unchanged")
        void alreadyCapitalized() {
            SessionOption option = new SessionOption("k", "K", List.of("High"));
            assertEquals("High", option.labelFor("High"));
        }

        @Test
        @DisplayName("multi-word hyphenated value only capitalizes first char")
        void multiWordHyphenated() {
            SessionOption option = new SessionOption("k", "K", List.of("very-high"));
            assertEquals("Very-high", option.labelFor("very-high"));
        }
    }
}
