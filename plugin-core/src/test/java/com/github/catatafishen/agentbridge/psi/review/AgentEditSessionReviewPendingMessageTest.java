package com.github.catatafishen.agentbridge.psi.review;

import com.github.catatafishen.agentbridge.settings.McpServerSettings;
import com.github.catatafishen.agentbridge.ui.BroadcastChatPanel;
import com.github.catatafishen.agentbridge.ui.NativeChatPanel;
import com.github.catatafishen.agentbridge.ui.NativeMarkdownPane;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * End-to-end check of the chat message shown while a git tool is paused on the review gate: the gate
 * must put one message in the chat, the Approve button must release exactly the calls it should, and
 * the message must be retired when the wait ends however it ends.
 *
 * <p>Uses real {@link NativeChatPanel} / {@link BroadcastChatPanel} instances because the gate looks the
 * panel up through {@link BroadcastChatPanel#getInstance}. The gate blocks, so each call runs on its
 * own thread while the test thread (the EDT) pumps events and clicks buttons.
 */
public class AgentEditSessionReviewPendingMessageTest extends BasePlatformTestCase {

    private static final String APPROVE = "Approve changes";
    private static final long WAIT_MS = 15_000;

    private NativeChatPanel nativePanel;
    private BroadcastChatPanel broadcastPanel;
    private AgentEditSession session;
    private boolean previousAutoApprove;
    private final List<Thread> threads = new ArrayList<>();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        McpServerSettings settings = McpServerSettings.getInstance(getProject());
        previousAutoApprove = settings.isAutoApproveAgentEdits();
        // New rows must start PENDING, otherwise the gate has nothing to wait on.
        settings.setAutoApproveAgentEdits(false);
        session = AgentEditSession.getInstance(getProject());
        nativePanel = new NativeChatPanel(getProject());
        broadcastPanel = new BroadcastChatPanel(getProject(), nativePanel);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            for (Thread t : threads) t.interrupt();
            for (Thread t : threads) t.join(5_000);
            session.acceptAll();
            session.removeAllApproved();
            McpServerSettings.getInstance(getProject()).setAutoApproveAgentEdits(previousAutoApprove);
            Disposer.dispose(broadcastPanel);
            com.github.catatafishen.agentbridge.session.db.ConversationDatabase
                .getInstance(getProject()).dispose();
        } finally {
            super.tearDown();
        }
    }

    public void testScopedGateShowsMessageAndApproveReleasesIt() throws Exception {
        String path = trackedPath("scoped-approve.txt");
        session.registerNewFile(path);

        CompletableFuture<String> result = runGate(() -> session.awaitReviewForPaths("git commit", List.of(path)));
        waitUntil(() -> approveButtons().size() == 1, "the review message to appear");

        String text = markdown();
        assertTrue("message must name the paused operation: " + text, text.contains("git commit"));
        assertTrue("message must give the file count: " + text, text.contains("1 file"));
        assertFalse("the call must still be blocked", result.isDone());

        approveButtons().get(0).doClick();

        assertNull("approving must release the gate with no error", result.get(WAIT_MS, TimeUnit.MILLISECONDS));
        waitUntil(() -> markdown().contains("Review complete"), "the message to be marked complete");
        assertFalse("buttons must be greyed out once released", approveButtons().get(0).isEnabled());
        assertEquals("the message stays in the chat", 1, approveButtons().size());
        assertFalse("the file must no longer be pending", session.hasPendingIn(List.of(path)));
    }

    public void testScopedApproveLeavesUnrelatedPendingFilesAlone() throws Exception {
        String committed = trackedPath("committed.txt");
        String other = trackedPath("unrelated.txt");
        session.registerNewFile(committed);
        session.registerNewFile(other);

        CompletableFuture<String> result = runGate(() -> session.awaitReviewForPaths("git commit", List.of(committed)));
        waitUntil(() -> approveButtons().size() == 1, "the review message to appear");

        approveButtons().get(0).doClick();

        assertNull(result.get(WAIT_MS, TimeUnit.MILLISECONDS));
        assertFalse("the committed file is approved", session.hasPendingIn(List.of(committed)));
        assertTrue("a commit must not silently approve a file it does not touch",
            session.hasPendingIn(List.of(other)));
    }

    public void testUnscopedGateApprovesEverything() throws Exception {
        session.registerNewFile(trackedPath("a.txt"));
        session.registerNewFile(trackedPath("b.txt"));

        CompletableFuture<String> result = runGate(() -> session.awaitReviewCompletion("branch create 'x'"));
        waitUntil(() -> approveButtons().size() == 1, "the review message to appear");
        assertTrue(markdown().contains("branch create 'x'"));
        assertTrue("message must count both files: " + markdown(), markdown().contains("2 files"));

        approveButtons().get(0).doClick();

        assertNull(result.get(WAIT_MS, TimeUnit.MILLISECONDS));
        assertFalse("a worktree-wide operation approves every pending file", session.hasPendingChanges());
        waitUntil(() -> markdown().contains("Review complete"), "the message to be marked complete");
    }

    public void testApprovingThroughTheReviewPanelAlsoRetiresTheMessage() throws Exception {
        String path = trackedPath("approved-elsewhere.txt");
        session.registerNewFile(path);

        CompletableFuture<String> result = runGate(() -> session.awaitReviewForPaths("git commit", List.of(path)));
        waitUntil(() -> approveButtons().size() == 1, "the review message to appear");

        // The user approves from the diff view instead of the chat button.
        session.acceptFile(path);

        assertNull(result.get(WAIT_MS, TimeUnit.MILLISECONDS));
        waitUntil(() -> markdown().contains("Review complete"), "the message to be marked complete");
        assertFalse(approveButtons().get(0).isEnabled());
    }

    public void testInterruptedWaitRetiresTheMessageAsNotApproved() throws Exception {
        String path = trackedPath("interrupted.txt");
        session.registerNewFile(path);

        Thread[] holder = new Thread[1];
        CompletableFuture<String> result = runGate(
            () -> session.awaitReviewForPaths("git commit", List.of(path)), holder);
        waitUntil(() -> approveButtons().size() == 1, "the review message to appear");

        holder[0].interrupt();

        String error = result.get(WAIT_MS, TimeUnit.MILLISECONDS);
        assertNotNull(error);
        assertTrue("interrupt must surface as an error: " + error, error.startsWith("Error"));
        waitUntil(() -> markdown().contains("no longer pending"), "the message to be retired");
        assertFalse("must not claim approval", markdown().contains("Review complete"));
        assertFalse(approveButtons().get(0).isEnabled());
        assertTrue("the edits are still unreviewed", session.hasPendingIn(List.of(path)));
    }

    public void testRetriesWhileWaitingShareOneMessage() throws Exception {
        String path = trackedPath("retried.txt");
        session.registerNewFile(path);

        Thread[] first = new Thread[1];
        Thread[] second = new Thread[1];
        CompletableFuture<String> a = runGate(() -> session.awaitReviewForPaths("git commit", List.of(path)), first);
        waitUntil(() -> approveButtons().size() == 1, "the review message to appear");
        CompletableFuture<String> b = runGate(() -> session.awaitReviewForPaths("git commit", List.of(path)), second);
        waitUntil(() -> isBlocked(first[0]) && isBlocked(second[0]), "both calls to be waiting");
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

        assertEquals("a retry must not stack a second message", 1, approveButtons().size());

        approveButtons().get(0).doClick();

        assertNull(a.get(WAIT_MS, TimeUnit.MILLISECONDS));
        assertNull(b.get(WAIT_MS, TimeUnit.MILLISECONDS));
        waitUntil(() -> markdown().contains("Review complete"), "the message to be marked complete");
        assertEquals(1, approveButtons().size());
        assertFalse(approveButtons().get(0).isEnabled());
    }

    public void testMessageStaysLiveUntilTheLastWaitingCallLeaves() throws Exception {
        String path = trackedPath("last-waiter.txt");
        session.registerNewFile(path);

        Thread[] first = new Thread[1];
        Thread[] second = new Thread[1];
        CompletableFuture<String> a = runGate(() -> session.awaitReviewForPaths("git commit", List.of(path)), first);
        waitUntil(() -> approveButtons().size() == 1, "the review message to appear");
        CompletableFuture<String> b = runGate(() -> session.awaitReviewForPaths("git commit", List.of(path)), second);
        waitUntil(() -> isBlocked(first[0]) && isBlocked(second[0]), "both calls to be waiting");

        first[0].interrupt();
        a.get(WAIT_MS, TimeUnit.MILLISECONDS);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

        assertTrue("one call still waits, so the message must stay actionable",
            approveButtons().get(0).isEnabled());
        assertFalse("the message must not be retired yet", markdown().contains("no longer pending"));
        assertFalse(b.isDone());

        approveButtons().get(0).doClick();
        assertNull(b.get(WAIT_MS, TimeUnit.MILLISECONDS));
    }

    public void testNoMessageWhenNothingIsPending() throws Exception {
        String path = trackedPath("nothing-pending.txt");

        String result = session.awaitReviewForPaths("git commit", List.of(path));
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

        assertNull("no pending review means the gate does not block", result);
        assertTrue("and no message is added", approveButtons().isEmpty());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String trackedPath(String name) {
        String base = getProject().getBasePath();
        assertNotNull("test project must have a base path", base);
        return base + "/review-pending-" + name;
    }

    private CompletableFuture<String> runGate(java.util.function.Supplier<String> gate) {
        return runGate(gate, new Thread[1]);
    }

    private CompletableFuture<String> runGate(java.util.function.Supplier<String> gate, Thread[] holder) {
        CompletableFuture<String> result = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                result.complete(gate.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        }, "review-gate-test");
        thread.setDaemon(true);
        holder[0] = thread;
        threads.add(thread);
        thread.start();
        return result;
    }

    private static boolean isBlocked(Thread t) {
        Thread.State s = t.getState();
        return s == Thread.State.WAITING || s == Thread.State.TIMED_WAITING;
    }

    /**
     * Pumps the EDT, which delivers the gate's {@code invokeLater} UI updates, until the condition holds.
     */
    private static void waitUntil(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        fail("Timed out waiting for " + what);
    }

    private List<JButton> approveButtons() {
        List<JButton> found = new ArrayList<>();
        collectButtons(nativePanel.getComponent(), found);
        return found;
    }

    private static void collectButtons(Component c, List<JButton> out) {
        if (c instanceof JButton b && APPROVE.equals(b.getText())) out.add(b);
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) collectButtons(child, out);
        }
    }

    private String markdown() {
        StringBuilder sb = new StringBuilder();
        collectMarkdown(nativePanel.getComponent(), sb);
        return sb.toString();
    }

    private static void collectMarkdown(Component c, StringBuilder out) {
        if (c instanceof NativeMarkdownPane pane) out.append(pane.getRawText()).append('\n');
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) collectMarkdown(child, out);
        }
    }
}
