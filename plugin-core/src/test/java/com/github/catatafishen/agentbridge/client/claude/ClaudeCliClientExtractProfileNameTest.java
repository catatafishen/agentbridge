package com.github.catatafishen.agentbridge.client.claude;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link ClaudeCliClient#extractProfileName(List)}.
 */
class ClaudeCliClientExtractProfileNameTest {

    @Test
    void returnsProfileName_whenPresent() {
        List<String> args = List.of("--acp", "--profile", "my-profile", "--stdio");
        assertEquals("my-profile", ClaudeCliClient.extractProfileName(args));
    }

    @Test
    void returnsNull_whenNotPresent() {
        List<String> args = List.of("--acp", "--stdio");
        assertNull(ClaudeCliClient.extractProfileName(args));
    }

    @Test
    void returnsNull_forEmptyList() {
        assertNull(ClaudeCliClient.extractProfileName(List.of()));
    }

    @Test
    void returnsNull_whenProfileIsLastElement() {
        // --profile at the end with no following value
        List<String> args = List.of("--acp", "--profile");
        assertNull(ClaudeCliClient.extractProfileName(args));
    }

    @Test
    void returnsFirstProfile_whenMultiplePresent() {
        List<String> args = List.of("--profile", "first", "--profile", "second");
        assertEquals("first", ClaudeCliClient.extractProfileName(args));
    }

    @Test
    void returnsProfileName_atStartOfList() {
        List<String> args = List.of("--profile", "start-profile", "--verbose");
        assertEquals("start-profile", ClaudeCliClient.extractProfileName(args));
    }

    @Test
    void doesNotMatchPartialFlag() {
        // "--profiles" is not "--profile"
        List<String> args = List.of("--profiles", "not-a-match", "--acp");
        assertNull(ClaudeCliClient.extractProfileName(args));
    }

    @Test
    void returnsProfileName_withHyphenatedValue() {
        List<String> args = List.of("--acp", "--profile", "my-complex-profile-name");
        assertEquals("my-complex-profile-name", ClaudeCliClient.extractProfileName(args));
    }

    @Test
    void singleNonProfileArg_returnsNull() {
        List<String> args = List.of("--verbose");
        assertNull(ClaudeCliClient.extractProfileName(args));
    }
}
