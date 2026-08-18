package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.bridge.TransportType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentProfileTest {

    private AgentProfile profile;

    @BeforeEach
    void setUp() {
        profile = new AgentProfile();
    }

    // ── Default constructor ───────────────────────────────────────────────────

    @Test
    @DisplayName("default constructor assigns a non-blank UUID as id")
    void defaultIdIsUuid() {
        assertNotNull(profile.getId());
        assertFalse(profile.getId().isBlank());
    }

    @Test
    @DisplayName("default displayName is 'New Agent'")
    void defaultDisplayName() {
        assertEquals("New Agent", profile.getDisplayName());
    }

    @Test
    @DisplayName("default transportType is ACP")
    void defaultTransportTypeIsAcp() {
        assertEquals(TransportType.ACP, profile.getTransportType());
    }

    @Test
    @DisplayName("default acpArgs include --acp and --stdio")
    void defaultAcpArgs() {
        assertTrue(profile.getAcpArgs().contains("--acp"));
        assertTrue(profile.getAcpArgs().contains("--stdio"));
    }

    @Test
    @DisplayName("default builtIn is false")
    void defaultBuiltInFalse() {
        assertFalse(profile.isBuiltIn());
    }

    @Test
    @DisplayName("default supportsModelFlag is true")
    void defaultSupportsModelFlagTrue() {
        assertTrue(profile.isSupportsModelFlag());
    }

    @Test
    @DisplayName("default supportsMcpConfigFlag is true")
    void defaultSupportsMcpConfigFlagTrue() {
        assertTrue(profile.isSupportsMcpConfigFlag());
    }

    // ── duplicate() ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("duplicate() produces a different id")
    void duplicateDifferentId() {
        AgentProfile copy = profile.duplicate();
        assertNotEquals(profile.getId(), copy.getId());
    }

    @Test
    @DisplayName("duplicate() appends ' (Copy)' to displayName")
    void duplicateAppendsCoToDisplayName() {
        profile.setDisplayName("My Agent");
        AgentProfile copy = profile.duplicate();
        assertEquals("My Agent (Copy)", copy.getDisplayName());
    }

    @Test
    @DisplayName("duplicate() sets builtIn to false even when original is true")
    void duplicateSetsBuiltInFalse() {
        profile.setBuiltIn(true);
        assertFalse(profile.duplicate().isBuiltIn());
    }

    @Test
    @DisplayName("duplicate() sets experimental to false even when original is true")
    void duplicateSetsExperimentalFalse() {
        profile.setExperimental(true);
        assertFalse(profile.duplicate().isExperimental());
    }

    @Test
    @DisplayName("duplicate() copies list fields defensively (mutating copy does not affect original)")
    void duplicateDefensiveCopyOfLists() {
        profile.setAcpArgs(List.of("--acp"));
        AgentProfile copy = profile.duplicate();
        copy.getAcpArgs().clear(); // asserts duplicate() returns a mutable list
        // The original should still have --acp
        assertEquals(List.of("--acp"), profile.getAcpArgs());
    }

    // ── copyForEditing() ─────────────────────────────────────────────────────

    @Test
    @DisplayName("copyForEditing() preserves the same id")
    void copyForEditingPreservesId() {
        AgentProfile copy = profile.copyForEditing();
        assertEquals(profile.getId(), copy.getId());
    }

    @Test
    @DisplayName("copyForEditing() preserves displayName unchanged")
    void copyForEditingPreservesDisplayName() {
        profile.setDisplayName("Edit Me");
        assertEquals("Edit Me", profile.copyForEditing().getDisplayName());
    }

    @Test
    @DisplayName("copyForEditing() preserves builtIn flag")
    void copyForEditingPreservesBuiltIn() {
        profile.setBuiltIn(true);
        assertTrue(profile.copyForEditing().isBuiltIn());
    }

    // ── copyFrom() ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("copyFrom() copies all fields from source, preserving destination id")
    void copyFromPreservesDestId() {
        AgentProfile dest = new AgentProfile();
        String destId = dest.getId();
        AgentProfile src = new AgentProfile();
        src.setDisplayName("Source Agent");
        dest.copyFrom(src);
        assertEquals(destId, dest.getId());
        assertEquals("Source Agent", dest.getDisplayName());
    }

    @Test
    @DisplayName("copyFrom() does not copy builtIn flag from source")
    void copyFromDoesNotCopyBuiltIn() {
        AgentProfile dest = new AgentProfile();
        dest.setBuiltIn(true);
        AgentProfile src = new AgentProfile();
        src.setBuiltIn(false);
        dest.copyFrom(src);
        // copyFrom preserves dest's own id/builtIn; check the code:
        // actually copyFrom() does NOT copy builtIn (see source) — verified it only copies non-identity fields
        assertTrue(dest.isBuiltIn()); // builtIn is NOT copied by copyFrom
    }

    @Test
    @DisplayName("getExtraCliArgs default is empty string")
    void getExtraCliArgsDefaultEmpty() {
        assertEquals("", profile.getExtraCliArgs());
    }

    @Test
    @DisplayName("setExtraCliArgs / getExtraCliArgs roundtrip")
    void setAndGetExtraCliArgs() {
        profile.setExtraCliArgs("--foo --bar");
        assertEquals("--foo --bar", profile.getExtraCliArgs());
    }

    @Test
    @DisplayName("setExtraCliArgs null guard via getExtraCliArgs")
    void getExtraCliArgsNeverNull() {
        // Field is initialized to "" — even after copyFrom with a null-field profile it should be ""
        AgentProfile other = new AgentProfile();
        profile.copyFrom(other);
        assertNotNull(profile.getExtraCliArgs());
    }

    @Test
    @DisplayName("copyFrom copies extraCliArgs from source")
    void copyFromCopiesExtraCliArgs() {
        AgentProfile src = new AgentProfile();
        src.setExtraCliArgs("--v3 --debug");
        AgentProfile dest = new AgentProfile();
        dest.copyFrom(src);
        assertEquals("--v3 --debug", dest.getExtraCliArgs());
    }

    // ── parsedExtraCliArgs() ─────────────────────────────────────────────────

    @Test
    @DisplayName("parsedExtraCliArgs returns empty list when extraCliArgs is blank")
    void parsedExtraCliArgsEmptyWhenBlank() {
        profile.setExtraCliArgs("");
        assertTrue(profile.parsedExtraCliArgs().isEmpty());
    }

    @Test
    @DisplayName("parsedExtraCliArgs returns empty list when extraCliArgs is whitespace-only")
    void parsedExtraCliArgsEmptyWhenWhitespace() {
        profile.setExtraCliArgs("   ");
        assertTrue(profile.parsedExtraCliArgs().isEmpty());
    }

    @Test
    @DisplayName("parsedExtraCliArgs tokenizes simple flags")
    void parsedExtraCliArgsSimpleFlags() {
        profile.setExtraCliArgs("--foo --bar");
        assertEquals(List.of("--foo", "--bar"), profile.parsedExtraCliArgs());
    }

    @Test
    @DisplayName("parsedExtraCliArgs handles quoted values")
    void parsedExtraCliArgsQuotedValue() {
        profile.setExtraCliArgs("--flag \"value with spaces\"");
        assertEquals(List.of("--flag", "value with spaces"), profile.parsedExtraCliArgs());
    }

    @Test
    @DisplayName("parsedExtraCliArgs single token")
    void parsedExtraCliArgsSingleToken() {
        profile.setExtraCliArgs("--v3");
        assertEquals(List.of("--v3"), profile.parsedExtraCliArgs());
    }



    @Test
    @DisplayName("getClientCssClass returns 'copilot' when binaryName contains 'copilot'")
    void cssClassCopilot() {
        profile.setBinaryName("github-copilot");
        assertEquals("copilot", profile.getClientCssClass());
    }

    @Test
    @DisplayName("getClientCssClass returns 'claude' when binaryName contains 'claude'")
    void cssClassClaude() {
        profile.setBinaryName("claude-cli");
        assertEquals("claude", profile.getClientCssClass());
    }

    @Test
    @DisplayName("getClientCssClass returns 'opencode' when binaryName contains 'opencode'")
    void cssClassOpencode() {
        profile.setBinaryName("opencode");
        assertEquals("opencode", profile.getClientCssClass());
    }

    @Test
    @DisplayName("getClientCssClass returns 'junie' when binaryName contains 'junie'")
    void cssClassJunie() {
        profile.setBinaryName("junie");
        assertEquals("junie", profile.getClientCssClass());
    }

    @Test
    @DisplayName("getClientCssClass returns 'kiro' when binaryName contains 'kiro'")
    void cssClassKiro() {
        profile.setBinaryName("kiro");
        assertEquals("kiro", profile.getClientCssClass());
    }

    @Test
    @DisplayName("getClientCssClass returns 'codex' when binaryName contains 'codex'")
    void cssClassCodex() {
        profile.setBinaryName("codex");
        assertEquals("codex", profile.getClientCssClass());
    }

    @Test
    @DisplayName("getClientCssClass returns empty string for unknown binary name")
    void cssClassUnknown() {
        profile.setBinaryName("unknown-agent");
        assertEquals("", profile.getClientCssClass());
    }

    @Test
    @DisplayName("getClientCssClass is case-insensitive (lowercases binary name)")
    void cssClassCaseInsensitive() {
        profile.setBinaryName("GitHub-Copilot");
        assertEquals("copilot", profile.getClientCssClass());
    }

    // ── getDefaultStartCommand() ──────────────────────────────────────────────

    @Test
    @DisplayName("getDefaultStartCommand returns empty string when binaryName is empty")
    void defaultStartCommandEmptyWhenNoBinary() {
        profile.setBinaryName("");
        assertEquals("", profile.getDefaultStartCommand());
    }

    @Test
    @DisplayName("getDefaultStartCommand concatenates binaryName and acpArgs")
    void defaultStartCommandConcatenates() {
        profile.setBinaryName("myagent");
        profile.setAcpArgs(List.of("--acp", "--stdio"));
        assertEquals("myagent --acp --stdio", profile.getDefaultStartCommand());
    }

    @Test
    @DisplayName("getDefaultStartCommand with no args returns just binaryName")
    void defaultStartCommandNoArgs() {
        profile.setBinaryName("myagent");
        profile.setAcpArgs(List.of());
        assertEquals("myagent", profile.getDefaultStartCommand());
    }

    // ── equals / hashCode / toString ─────────────────────────────────────────

    @Test
    @DisplayName("two profiles with same id are equal")
    void sameIdEquals() {
        AgentProfile a = new AgentProfile();
        AgentProfile b = new AgentProfile();
        b.setId(a.getId());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("two profiles with different ids are not equal")
    void differentIdNotEqual() {
        AgentProfile a = new AgentProfile();
        AgentProfile b = new AgentProfile();
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("equals with null returns false")
    void equalsNullFalse() {
        assertNotEquals(null, profile);
    }

    @Test
    @DisplayName("toString includes displayName and id")
    void toStringIncludesNameAndId() {
        profile.setDisplayName("My Agent");
        String s = profile.toString();
        assertTrue(s.contains("My Agent"));
        assertTrue(s.contains(profile.getId()));
    }
}
