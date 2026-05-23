package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingPopupServiceTest {

    private PendingPopupService service;
    private final AtomicReference<Instant> clock = new AtomicReference<>(Instant.now());

    @BeforeEach
    void setUp() {
        service = new PendingPopupService();
        service.setClockForTests(clock::get);
    }

    @AfterEach
    void tearDown() {
        service.clearForTests();
    }

    private PendingPopupService.Pending registerDefault() {
        return registerDefault("session-1");
    }

    private PendingPopupService.Pending registerDefault(String sessionKey) {
        return service.register(
            "apply_action",
            new JsonObject(),
            null,
            new ContextFingerprint("proj", "/file.java", 1L, "action1"),
            new PopupSnapshot("Title", List.of(
                new PopupChoice("val|0", 0, "val", null, true, false)
            ), PopupSnapshot.KIND_LIST_STEP),
            sessionKey,
            null
        );
    }

    @Nested
    class Register {
        @Test
        void returnsNonNullOnEmptySlot() {
            var rec = registerDefault();
            assertNotNull(rec);
            assertEquals("apply_action", rec.originalTool());
            assertEquals("session-1", rec.owningSessionKey());
            assertEquals(0, rec.unrelatedCallsSinceCreated());
        }

        @Test
        void rejectsWhenSlotAlreadyTaken() {
            registerDefault();
            var second = registerDefault("session-2");
            assertNull(second);
        }
    }

    @Nested
    class Peek {
        @Test
        void returnsNullOnEmptySlot() {
            assertNull(service.peek());
        }

        @Test
        void returnsCurrentPending() {
            var rec = registerDefault();
            var peeked = service.peek();
            assertNotNull(peeked);
            assertEquals(rec.id(), peeked.id());
        }

        @Test
        void autoClearsAfterMaxAge() {
            registerDefault();
            // Advance clock past MAX_AGE
            clock.set(clock.get().plus(PendingPopupService.MAX_AGE).plusSeconds(1));
            assertNull(service.peek());
        }

        @Test
        void doesNotClearBeforeMaxAge() {
            registerDefault();
            clock.set(clock.get().plus(PendingPopupService.MAX_AGE).minusSeconds(1));
            assertNotNull(service.peek());
        }
    }

    @Nested
    class Take {
        @Test
        void takesMatchingPopup() {
            var rec = registerDefault();
            var taken = service.take(rec.id());
            assertNotNull(taken);
            assertEquals(rec.id(), taken.id());
            // Slot is now empty
            assertNull(service.peek());
        }

        @Test
        void returnsNullForNonMatchingId() {
            registerDefault();
            assertNull(service.take("nonexistent-id"));
        }

        @Test
        void returnsNullOnEmptySlot() {
            assertNull(service.take("any-id"));
        }
    }

    @Nested
    class CancelAndClear {
        @Test
        void clearsWhenPopupIdMatches() {
            var rec = registerDefault();
            assertTrue(service.cancelAndClear(rec.id()));
            assertNull(service.peek());
        }

        @Test
        void doesNotClearWhenPopupIdMismatches() {
            registerDefault();
            assertFalse(service.cancelAndClear("wrong-id"));
            assertNotNull(service.peek());
        }

        @Test
        void clearsWithNullPopupId() {
            registerDefault();
            assertTrue(service.cancelAndClear(null));
            assertNull(service.peek());
        }

        @Test
        void returnsFalseOnEmptySlot() {
            assertFalse(service.cancelAndClear("any-id"));
        }
    }

    @Nested
    class RecordUnrelatedCall {
        @Test
        void foreignSessionDoesNotBurnBudget() {
            registerDefault("owner");
            for (int i = 0; i < PendingPopupService.MAX_UNRELATED_CALLS + 1; i++) {
                service.recordUnrelatedCall("foreign");
            }
            // Slot should still be alive
            assertNotNull(service.peek());
        }

        @Test
        void ownerSessionBurnsBudget() {
            registerDefault("owner");
            for (int i = 0; i < PendingPopupService.MAX_UNRELATED_CALLS - 1; i++) {
                assertNull(service.recordUnrelatedCall("owner"),
                    "Should not clear slot before reaching MAX_UNRELATED_CALLS");
            }
            // The MAX_UNRELATED_CALLS-th call should clear and return the pending
            var expired = service.recordUnrelatedCall("owner");
            assertNotNull(expired, "Should clear on the MAX_UNRELATED_CALLS-th call");
            assertNull(service.peek());
        }

        @Test
        void returnsNullOnEmptySlot() {
            assertNull(service.recordUnrelatedCall("session"));
        }
    }

    @Nested
    class NoteReplayDigest {
        @Test
        void updatesDigestForMatchingId() {
            var rec = registerDefault();
            assertNull(rec.previousReplayDigest());

            service.noteReplayDigest(rec.id(), "digest-abc");
            var peeked = service.peek();
            assertNotNull(peeked);
            assertEquals("digest-abc", peeked.previousReplayDigest());
        }

        @Test
        void doesNothingForMismatchedId() {
            registerDefault();
            service.noteReplayDigest("wrong-id", "digest-xyz");
            var peeked = service.peek();
            assertNotNull(peeked);
            assertNull(peeked.previousReplayDigest());
        }
    }
}
