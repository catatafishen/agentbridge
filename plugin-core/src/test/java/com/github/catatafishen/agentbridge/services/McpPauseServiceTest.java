package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.services.McpPauseService.PauseState;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class McpPauseServiceTest {

    private McpPauseService service;

    @BeforeEach
    void setUp() {
        service = new McpPauseService();
    }

    @AfterEach
    void tearDown() {
        // Ensure no lingering pause state
        service.setPaused(false);
    }

    @Nested
    class InitialState {
        @Test
        void notPausedByDefault() {
            assertFalse(service.isPaused());
        }

        @Test
        void stateIsRunningByDefault() {
            assertEquals(PauseState.RUNNING, service.getPauseState());
        }
    }

    @Nested
    class SetPaused {
        @Test
        void requestingPauseSetsIsPaused() {
            service.setPaused(true);
            assertTrue(service.isPaused());
        }

        @Test
        void requestingPauseTransitionsToPending() {
            service.setPaused(true);
            assertEquals(PauseState.PENDING, service.getPauseState());
        }

        @Test
        void resumingClearsPause() {
            service.setPaused(true);
            service.setPaused(false);
            assertFalse(service.isPaused());
            assertEquals(PauseState.RUNNING, service.getPauseState());
        }
    }

    @Nested
    class AwaitResumeIfPaused {
        @Test
        void returnsImmediatelyWhenNotPaused() {
            // Should not block
            service.awaitResumeIfPaused();
            assertEquals(PauseState.RUNNING, service.getPauseState());
        }

        @Test
        void blocksUntilResumed() throws Exception {
            service.setPaused(true);

            var latch = new CountDownLatch(1);
            var pausedLatch = new CountDownLatch(1);

            // Use listener to detect PAUSED state deterministically (no sleep needed)
            service.addListener(state -> {
                if (state == PauseState.PAUSED) pausedLatch.countDown();
            });

            var thread = new Thread(() -> {
                service.awaitResumeIfPaused();
                latch.countDown();
            });
            thread.start();

            // Wait for the thread to enter the wait (listener fires on blockedCallCount increment)
            assertTrue(pausedLatch.await(2, TimeUnit.SECONDS), "Should transition to PAUSED");
            assertEquals(PauseState.PAUSED, service.getPauseState());

            // Resume
            service.setPaused(false);
            assertTrue(latch.await(2, TimeUnit.SECONDS), "Thread should have unblocked");

            thread.join(2000);
        }
    }

    @Nested
    class Listeners {
        @Test
        void listenerNotifiedOnPause() {
            List<PauseState> states = new ArrayList<>();
            service.addListener(states::add);

            service.setPaused(true);
            assertTrue(states.contains(PauseState.PENDING));
        }

        @Test
        void listenerNotifiedOnResume() {
            List<PauseState> states = new ArrayList<>();
            service.addListener(states::add);

            service.setPaused(true);
            service.setPaused(false);
            assertTrue(states.contains(PauseState.RUNNING));
        }

        @Test
        void removedListenerNotNotified() {
            List<PauseState> states = new ArrayList<>();
            McpPauseService.PauseListener listener = states::add;
            service.addListener(listener);
            service.removeListener(listener);

            service.setPaused(true);
            assertTrue(states.isEmpty());
        }
    }
}
