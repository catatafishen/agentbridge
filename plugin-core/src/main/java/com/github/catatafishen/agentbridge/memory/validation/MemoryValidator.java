package com.github.catatafishen.agentbridge.memory.validation;

import com.github.catatafishen.agentbridge.memory.store.DrawerDocument;
import com.github.catatafishen.agentbridge.memory.store.MemoryStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates memory validation: loads evidence from drawers, validates each
 * reference via {@link SymbolValidator}, and updates the verification state.
 *
 * <p>This is the central entry point for both validate-on-read and
 * triggered validation (file changes, post-mining).</p>
 */
public final class MemoryValidator {

    private static final Logger LOG = Logger.getInstance(MemoryValidator.class);

    private MemoryValidator() {
    }

    /**
     * Result of validating a single drawer's evidence.
     *
     * @param drawerId      the drawer that was validated
     * @param previousState the state before validation
     * @param newState      the state after validation
     * @param totalRefs     number of evidence references checked
     * @param validRefs     number that resolved successfully
     */
    public record ValidationOutcome(
        @NotNull String drawerId,
        @NotNull String previousState,
        @NotNull String newState,
        int totalRefs,
        int validRefs
    ) {
    }

    /**
     * Validate a single drawer's evidence and update its verification state.
     *
     * @param project the IntelliJ project for PSI resolution
     * @param store   the memory store to update
     * @param drawer  the drawer to validate
     * @return the validation outcome, or null if drawer has no evidence
     */
    public static ValidationOutcome validateDrawer(
        @NotNull Project project,
        @NotNull MemoryStore store,
        @NotNull DrawerDocument drawer
    ) {
        List<String> refs = parseEvidence(drawer.evidence());
        if (refs.isEmpty()) {
            return null;
        }

        List<SymbolValidator.ValidationResult> results =
            SymbolValidator.validate(project, refs);

        int validCount = (int) results.stream()
            .filter(SymbolValidator.ValidationResult::valid)
            .count();

        String newState = determineState(validCount, results.size());
        String previousState = drawer.verificationState();

        if (!newState.equals(previousState)) {
            try {
                store.updateVerificationState(drawer.id(), newState);
            } catch (Exception e) {
                LOG.warn("Failed to update verification state for drawer: " + drawer.id(), e);
            }
        }

        return new ValidationOutcome(drawer.id(), previousState, newState,
            results.size(), validCount);
    }

    /**
     * Validate all drawers whose evidence references a given file path.
     * Used by staleness triggers when a file changes.
     *
     * @param project  the IntelliJ project
     * @param store    the memory store
     * @param filePath the file that changed (project-relative or filename)
     * @return list of validation outcomes for affected drawers
     */
    public static @NotNull List<ValidationOutcome> validateByFile(
        @NotNull Project project,
        @NotNull MemoryStore store,
        @NotNull String filePath
    ) {
        List<ValidationOutcome> outcomes = new ArrayList<>();
        try {
            List<DrawerDocument> affected = store.findByEvidence(filePath);
            for (DrawerDocument drawer : affected) {
                ValidationOutcome outcome = validateDrawer(project, store, drawer);
                if (outcome != null) {
                    outcomes.add(outcome);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to validate drawers for file: " + filePath, e);
        }
        return outcomes;
    }

    /**
     * Parse a JSON array evidence string into a list of reference strings.
     *
     * @param evidenceJson JSON array string, e.g. {@code ["com.example.Foo","Bar.java:42"]}
     * @return list of reference strings, empty if input is empty or invalid
     */
    public static @NotNull List<String> parseEvidence(@NotNull String evidenceJson) {
        if (evidenceJson.isEmpty()) return List.of();
        try {
            JsonElement element = JsonParser.parseString(evidenceJson);
            if (!element.isJsonArray()) return List.of();
            JsonArray array = element.getAsJsonArray();
            List<String> refs = new ArrayList<>(array.size());
            for (JsonElement item : array) {
                if (item.isJsonPrimitive()) {
                    refs.add(item.getAsString());
                }
            }
            return refs;
        } catch (Exception e) {
            LOG.debug("Failed to parse evidence JSON: " + evidenceJson, e);
            return List.of();
        }
    }

    /**
     * Determine verification state from validation results.
     * <ul>
     *   <li>All valid → {@code verified}</li>
     *   <li>Any invalid → {@code stale}</li>
     *   <li>No references → {@code unverified}</li>
     * </ul>
     */
    static @NotNull String determineState(int validCount, int totalCount) {
        if (totalCount == 0) return DrawerDocument.STATE_UNVERIFIED;
        if (validCount == totalCount) return DrawerDocument.STATE_VERIFIED;
        return DrawerDocument.STATE_STALE;
    }
}
