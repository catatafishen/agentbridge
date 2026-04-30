package com.github.catatafishen.agentbridge.psi.tools.quality;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Identifies the IDE state under which a popup was captured, so the {@code popup_respond}
 * tool can reject replays that would land on different content than the agent saw.
 *
 * <p><b>Why this exists — DO NOT REMOVE without reading
 * {@code .agent-work/popup-interaction-design-2026-04-30.md}.</b>
 *
 * <p>Between the snapshot tool call and the {@code popup_respond} call, the agent could call
 * other tools (read-only) or the user could edit the file via the IDE. If the document mod
 * stamp or active file changed, replaying the action could:
 * <ul>
 *   <li>Open a different popup (different ambiguity set after edits)</li>
 *   <li>Apply the wrong fix to the wrong context</li>
 *   <li>Silently no-op</li>
 * </ul>
 *
 * <p>{@link #matches(ContextFingerprint)} tolerates {@code null} file paths (some actions
 * aren't file-bound) but requires exact match on document mod stamp when both fingerprints
 * carry one.
 *
 * @param projectName    project the action ran in
 * @param filePath       absolute path of the file the action targeted, or {@code null}
 * @param documentModStamp document mod stamp at invocation time, or {@code -1} when N/A
 * @param actionIdentity {@code action_name | inspection_id} — whatever uniquely names the
 *                       action, used for loop detection across replays
 */
public record ContextFingerprint(
    @NotNull String projectName,
    @Nullable String filePath,
    long documentModStamp,
    @NotNull String actionIdentity
) {

    private static final String NOW_PREFIX = "', now '";

    public ContextFingerprint {
        Objects.requireNonNull(projectName, "projectName");
        Objects.requireNonNull(actionIdentity, "actionIdentity");
    }

    /**
     * Returns true if {@code other} represents the same logical context as this fingerprint.
     * Project name and action identity must match exactly. File path must match when both are
     * present. Document mod stamp must match when both are non-negative.
     */
    public boolean matches(@NotNull ContextFingerprint other) {
        if (!projectName.equals(other.projectName)) return false;
        if (!actionIdentity.equals(other.actionIdentity)) return false;
        if (filePath != null && other.filePath != null && !filePath.equals(other.filePath)) {
            return false;
        }
        return documentModStamp < 0 || other.documentModStamp < 0
            || documentModStamp == other.documentModStamp;
    }

    /**
     * Human-readable description used in agent-facing error messages when a fingerprint check
     * fails.
     */
    @NotNull
    public String describeMismatch(@NotNull ContextFingerprint current) {
        StringBuilder sb = new StringBuilder();
        if (!projectName.equals(current.projectName)) {
            sb.append("project changed (was '").append(projectName)
                .append(NOW_PREFIX).append(current.projectName).append("'); ");
        }
        if (!actionIdentity.equals(current.actionIdentity)) {
            sb.append("action changed (was '").append(actionIdentity)
                .append(NOW_PREFIX).append(current.actionIdentity).append("'); ");
        }
        if (filePath != null && current.filePath != null && !filePath.equals(current.filePath)) {
            sb.append("file changed (was '").append(filePath)
                .append(NOW_PREFIX).append(current.filePath).append("'); ");
        }
        if (documentModStamp >= 0 && current.documentModStamp >= 0
            && documentModStamp != current.documentModStamp) {
            sb.append("document modified since snapshot (mod stamp ")
                .append(documentModStamp).append(" → ").append(current.documentModStamp)
                .append("); ");
        }
        return sb.isEmpty() ? "fingerprint mismatch" : sb.substring(0, sb.length() - 2);
    }
}
