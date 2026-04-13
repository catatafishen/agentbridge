package com.github.catatafishen.agentbridge.ui;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link BillingSnapshot} data class logic.
 */
class BillingSnapshotTest {

    @Nested
    class UsedComputation {

        @Test
        void usedIsEntitlementMinusRemaining() {
            var snap = new BillingSnapshot(300, 200, false, false, "2025-02-01");
            assertEquals(100, snap.getUsed());
        }

        @Test
        void usedIsZeroWhenNothingConsumed() {
            var snap = new BillingSnapshot(300, 300, false, false, "2025-02-01");
            assertEquals(0, snap.getUsed());
        }

        @Test
        void usedEqualsEntitlementWhenFullyConsumed() {
            var snap = new BillingSnapshot(300, 0, false, false, "2025-02-01");
            assertEquals(300, snap.getUsed());
        }

        @ParameterizedTest
        @CsvSource({
            "0,0,0",
            "100,50,50",
            "1000,999,1",
            "500,0,500",
        })
        void usedParameterized(int entitlement, int remaining, int expectedUsed) {
            var snap = new BillingSnapshot(entitlement, remaining, false, false, "");
            assertEquals(expectedUsed, snap.getUsed());
        }
    }

    @Nested
    class Equality {

        @Test
        void equalSnapshotsAreEqual() {
            var a = new BillingSnapshot(300, 200, false, true, "2025-02-01");
            var b = new BillingSnapshot(300, 200, false, true, "2025-02-01");
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        void differentEntitlementNotEqual() {
            var a = new BillingSnapshot(300, 200, false, false, "2025-02-01");
            var b = new BillingSnapshot(500, 200, false, false, "2025-02-01");
            assertNotEquals(a, b);
        }

        @Test
        void differentRemainingNotEqual() {
            var a = new BillingSnapshot(300, 200, false, false, "2025-02-01");
            var b = new BillingSnapshot(300, 100, false, false, "2025-02-01");
            assertNotEquals(a, b);
        }

        @Test
        void differentUnlimitedNotEqual() {
            var a = new BillingSnapshot(300, 200, false, false, "2025-02-01");
            var b = new BillingSnapshot(300, 200, true, false, "2025-02-01");
            assertNotEquals(a, b);
        }

        @Test
        void differentOverageNotEqual() {
            var a = new BillingSnapshot(300, 200, false, false, "2025-02-01");
            var b = new BillingSnapshot(300, 200, false, true, "2025-02-01");
            assertNotEquals(a, b);
        }

        @Test
        void differentResetDateNotEqual() {
            var a = new BillingSnapshot(300, 200, false, false, "2025-02-01");
            var b = new BillingSnapshot(300, 200, false, false, "2025-03-01");
            assertNotEquals(a, b);
        }
    }

    @Nested
    class ToStringOutput {

        @Test
        void includesAllFields() {
            var snap = new BillingSnapshot(300, 200, true, false, "2025-02-01");
            String s = snap.toString();
            assertTrue(s.contains("300"), "entitlement");
            assertTrue(s.contains("200"), "remaining");
            assertTrue(s.contains("true"), "unlimited");
            assertTrue(s.contains("false"), "overagePermitted");
            assertTrue(s.contains("2025-02-01"), "resetDate");
        }
    }

    @Nested
    class PropertyAccess {

        @Test
        void allPropertiesAccessible() {
            var snap = new BillingSnapshot(500, 123, true, true, "2025-06-15");
            assertEquals(500, snap.getEntitlement());
            assertEquals(123, snap.getRemaining());
            assertTrue(snap.getUnlimited());
            assertTrue(snap.getOveragePermitted());
            assertEquals("2025-06-15", snap.getResetDate());
        }
    }
}
