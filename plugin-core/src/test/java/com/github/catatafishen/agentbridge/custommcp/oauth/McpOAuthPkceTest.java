package com.github.catatafishen.agentbridge.custommcp.oauth;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class McpOAuthPkceTest {

    /** 64 random bytes → base64url without padding = ceil(64*4/3) = 86 chars. */
    @Test
    void generate_verifierIsBase64UrlOf64RandomBytes() {
        McpOAuthPkce.Params params = McpOAuthPkce.generate();
        assertEquals(86, params.verifier().length(), "verifier should be 86 base64url chars");
    }

    @Test
    void generate_challengeMatchesComputedValue() {
        McpOAuthPkce.Params params = McpOAuthPkce.generate();
        assertEquals(McpOAuthPkce.computeChallenge(params.verifier()), params.challenge());
    }

    @Test
    void generate_eachCallProducesUniquePair() {
        McpOAuthPkce.Params a = McpOAuthPkce.generate();
        McpOAuthPkce.Params b = McpOAuthPkce.generate();
        assertNotEquals(a.verifier(), b.verifier());
        assertNotEquals(a.challenge(), b.challenge());
    }

    @Test
    void computeChallenge_isDeterministic() {
        String verifier = McpOAuthPkce.generate().verifier();
        assertEquals(McpOAuthPkce.computeChallenge(verifier), McpOAuthPkce.computeChallenge(verifier));
    }

    @Test
    void computeChallenge_differsForDifferentVerifiers() {
        String v1 = McpOAuthPkce.generate().verifier();
        String v2 = McpOAuthPkce.generate().verifier();
        assertNotEquals(McpOAuthPkce.computeChallenge(v1), McpOAuthPkce.computeChallenge(v2));
    }

    /** 16 random bytes → base64url without padding = ceil(16*4/3) = 22 chars. */
    @Test
    void generateState_returnsExpectedLength() {
        assertEquals(22, McpOAuthPkce.generateState().length());
    }

    @Test
    void generateState_eachCallProducesUniqueValue() {
        Set<String> states = IntStream.range(0, 20)
            .mapToObj(i -> McpOAuthPkce.generateState())
            .collect(Collectors.toSet());
        assertEquals(20, states.size(), "all 20 state values should be distinct");
    }
}
