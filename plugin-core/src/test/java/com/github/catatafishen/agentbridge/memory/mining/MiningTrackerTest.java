package com.github.catatafishen.agentbridge.memory.mining;

import com.github.catatafishen.agentbridge.memory.mining.MiningTracker.MiningState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link MiningTracker} state machine.
 * Does not require IntelliJ platform — tests the state transitions only.
 */
class MiningTrackerTest {

    @Test
    void startsIdle() {
        var tracker = new TestTracker();
        assertEquals(MiningState.IDLE, tracker.getState());
    }

    @Test
    void startTurnMiningTransitionsFromIdle() {
        var tracker = new TestTracker();
        tracker.startTurnMining();
        assertEquals(MiningState.MINING_TURN, tracker.getState());
    }

    @Test
    void startTurnMiningIgnoredIfAlreadyMining() {
        var tracker = new TestTracker();
        tracker.startTurnMining();
        tracker.startTurnMining(); // second call should not change state
        assertEquals(MiningState.MINING_TURN, tracker.getState());
    }

    @Test
    void startBackfillOverridesCurrentState() {
        var tracker = new TestTracker();
        tracker.startTurnMining();
        tracker.startBackfill();
        assertEquals(MiningState.BACKFILLING, tracker.getState());
    }

    @Test
    void stopReturnsToIdle() {
        var tracker = new TestTracker();
        tracker.startTurnMining();
        tracker.stop();
        assertEquals(MiningState.IDLE, tracker.getState());
    }

    @Test
    void stopWhenIdleIsNoOp() {
        var tracker = new TestTracker();
        tracker.stop(); // no exception
        assertEquals(MiningState.IDLE, tracker.getState());
    }

    @Test
    void reportProgressDoesNothingWhenIdle() {
        var tracker = new TestTracker();
        tracker.reportProgress("should be ignored");
        assertEquals(MiningState.IDLE, tracker.getState());
    }

    @Test
    void listenerReceivesStateChanges() {
        var tracker = new TestTracker();
        List<MiningState> observed = new ArrayList<>();
        tracker.addTestListener((state, progress) -> observed.add(state));

        tracker.startTurnMining();
        tracker.stop();
        tracker.startBackfill();
        tracker.reportProgress("session 3 of 15");
        tracker.stop();

        assertEquals(List.of(
            MiningState.MINING_TURN,
            MiningState.IDLE,
            MiningState.BACKFILLING,
            MiningState.BACKFILLING, // reportProgress fires with current state
            MiningState.IDLE
        ), observed);
    }

    @Test
    void listenerReceivesProgressText() {
        var tracker = new TestTracker();
        List<String> texts = new ArrayList<>();
        tracker.addTestListener((state, progress) -> {
            if (progress != null) texts.add(progress);
        });

        tracker.startBackfill();
        tracker.reportProgress("session 1 of 10");
        tracker.reportProgress("session 2 of 10");
        tracker.stop();

        assertEquals(List.of("session 1 of 10", "session 2 of 10"), texts);
    }

    /**
     * Lightweight test double that bypasses MessageBus and project dependency.
     * Overrides {@link MiningTracker#fireChanged} to use a simple listener list.
     */
    @SuppressWarnings("NullableProblems") // null project is acceptable in this test double
    private static final class TestTracker extends MiningTracker {
        private final List<MiningTracker.Listener> listeners = new ArrayList<>();

        TestTracker() {
            super(null); // project not used by state logic
        }

        void addTestListener(MiningTracker.Listener listener) {
            listeners.add(listener);
        }

        @Override
        void fireChanged(MiningState newState, String progressText) {
            for (MiningTracker.Listener l : listeners) {
                l.miningStateChanged(newState, progressText);
            }
        }
    }
}
