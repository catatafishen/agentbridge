package com.github.catatafishen.agentbridge.psi.review;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.Collections;
import java.util.List;

/**
 * Regression test for the contract that {@link com.github.catatafishen.agentbridge.psi.tools.git.GitCommitTool}
 * relies on for {@code --amend}.
 *
 * <p>{@code git_commit --amend} cannot be scoped to a known set of paths because the amend
 * rewrites a previous commit in its entirety, including paths that no longer appear in
 * {@code resolveFilesToCommit(...)}. If amend used the scoped gate
 * ({@link AgentEditSession#awaitReviewForPaths}) with an empty path collection, the gate would
 * report no pending review and let the amend proceed — silently overwriting an in-progress
 * agent edit that the user has not yet approved.
 *
 * <p>This test pins down the contract:
 * <ul>
 *   <li>{@link AgentEditSession#hasPendingChanges()} reports true once a tracked path is
 *       registered as new.</li>
 *   <li>{@link AgentEditSession#hasPendingIn(java.util.Collection)} returns {@code false} for
 *       an empty scope, even when the unscoped session has pending changes.</li>
 *   <li>{@link AgentEditSession#hasPendingIn(java.util.Collection)} only returns {@code true}
 *       when the pending path itself appears in the scope.</li>
 * </ul>
 *
 * <p>If anyone accidentally swaps {@code awaitReviewCompletion} back to
 * {@code awaitReviewForPaths(...)} on the amend branch, this test still passes — but a
 * companion test in {@code GitToolsTest} would then visibly fail because the amend gate would
 * stop blocking. Together they document the invariant.
 */
public class AgentEditSessionAmendGateContractTest extends BasePlatformTestCase {

    public void testEmptyScopeNeverReportsPending_evenWhenSessionHasPendingChanges() {
        AgentEditSession session = AgentEditSession.getInstance(getProject());
        String basePath = getProject().getBasePath();
        assertNotNull("Test project must have a base path", basePath);

        String trackedPath = basePath + "/amend-gate-contract.txt";
        session.registerNewFile(trackedPath);

        try {
            assertTrue(
                "registerNewFile must mark the path PENDING so the unscoped gate would block",
                session.hasPendingChanges());

            assertFalse(
                "Empty scope must NOT report pending — this is exactly why git_commit --amend "
                    + "uses the unscoped awaitReviewCompletion gate.",
                session.hasPendingIn(Collections.emptyList()));

            assertTrue(
                "Sanity: scoping to the actual pending path must report pending.",
                session.hasPendingIn(List.of(trackedPath)));

            assertFalse(
                "Sanity: scoping to an unrelated path must not report pending.",
                session.hasPendingIn(List.of(basePath + "/some-other-file.txt")));
        } finally {
            // Approve so subsequent tests don't see leftover PENDING state.
            session.acceptAll();
            session.removeAllApproved();
        }
    }
}
