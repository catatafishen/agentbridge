package com.github.catatafishen.agentbridge.psi.tools.navigation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure JUnit 5 unit tests for the private static
 * {@code matchesSizeAndDateFilters(long, long, long, long, long, long)} method
 * in {@link ListProjectFilesTool}.
 *
 * <p>No IntelliJ platform needed — the method is a stateless pure function.
 * Accessed via reflection because it is {@code private static}.
 */
@DisplayName("ListProjectFilesTool.matchesSizeAndDateFilters")
class ListProjectFilesToolStaticMethodsTest {

    private static final long NO_FILTER = -1;

    private static Method matchesSizeAndDateFilters;

    @BeforeAll
    static void setUp() throws Exception {
        matchesSizeAndDateFilters = ListProjectFilesTool.class.getDeclaredMethod(
                "matchesSizeAndDateFilters",
                long.class, long.class, long.class, long.class, long.class, long.class);
        matchesSizeAndDateFilters.setAccessible(true);
    }

    /** Convenience wrapper so test call-sites stay readable. */
    private static boolean invoke(long size, long ts,
                                  long minSize, long maxSize,
                                  long modifiedAfter, long modifiedBefore) throws Exception {
        return (boolean) matchesSizeAndDateFilters.invoke(null, size, ts, minSize, maxSize, modifiedAfter, modifiedBefore);
    }

    // ---- 1. No filters --------------------------------------------------------

    @Test
    @DisplayName("No filters (all -1) → returns true")
    void noFilters_returnsTrue() throws Exception {
        assertTrue(invoke(500, 1_000_000L, NO_FILTER, NO_FILTER, NO_FILTER, NO_FILTER));
    }

    // ---- 2–4. Size filters ----------------------------------------------------

    @Nested
    @DisplayName("minSize filter")
    class MinSizeFilter {

        @ParameterizedTest(name = "size={0}, minSize={1} → {2}")
        @CsvSource({
                "100, 100, true",   // exactly equal
                "99,  100, false",  // below threshold
                "101, 100, true"    // above threshold
        })
        void minSizeOnly(long size, long minSize, boolean expected) throws Exception {
            boolean result = invoke(size, 1_000_000L, minSize, NO_FILTER, NO_FILTER, NO_FILTER);
            if (expected) {
                assertTrue(result, "size=" + size + " should pass minSize=" + minSize);
            } else {
                assertFalse(result, "size=" + size + " should fail minSize=" + minSize);
            }
        }
    }

    @Nested
    @DisplayName("maxSize filter")
    class MaxSizeFilter {

        @ParameterizedTest(name = "size={0}, maxSize={1} → {2}")
        @CsvSource({
                "100, 100, true",   // exactly equal
                "99,  100, true",   // below threshold
                "101, 100, false"   // above threshold
        })
        void maxSizeOnly(long size, long maxSize, boolean expected) throws Exception {
            boolean result = invoke(size, 1_000_000L, NO_FILTER, maxSize, NO_FILTER, NO_FILTER);
            if (expected) {
                assertTrue(result, "size=" + size + " should pass maxSize=" + maxSize);
            } else {
                assertFalse(result, "size=" + size + " should fail maxSize=" + maxSize);
            }
        }
    }

    @Nested
    @DisplayName("minSize AND maxSize combined")
    class MinMaxSizeCombined {

        @ParameterizedTest(name = "size={0}, min={1}, max={2} → {3}")
        @CsvSource({
                "150, 100, 200, true",   // within range
                "50,  100, 200, false",  // below min
                "250, 100, 200, false"   // above max
        })
        void combinedSizeFilters(long size, long minSize, long maxSize, boolean expected) throws Exception {
            boolean result = invoke(size, 1_000_000L, minSize, maxSize, NO_FILTER, NO_FILTER);
            if (expected) {
                assertTrue(result, "size=" + size + " should pass [" + minSize + ", " + maxSize + "]");
            } else {
                assertFalse(result, "size=" + size + " should fail [" + minSize + ", " + maxSize + "]");
            }
        }
    }

    // ---- 5–7. Date filters ----------------------------------------------------

    @Nested
    @DisplayName("modifiedAfter filter")
    class ModifiedAfterFilter {

        @ParameterizedTest(name = "ts={0}, modifiedAfter={1} → {2}")
        @CsvSource({
                "5000, 5000, true",   // exactly equal
                "4999, 5000, false",  // before threshold
                "5001, 5000, true"    // after threshold
        })
        void modifiedAfterOnly(long ts, long modifiedAfter, boolean expected) throws Exception {
            boolean result = invoke(100, ts, NO_FILTER, NO_FILTER, modifiedAfter, NO_FILTER);
            if (expected) {
                assertTrue(result, "ts=" + ts + " should pass modifiedAfter=" + modifiedAfter);
            } else {
                assertFalse(result, "ts=" + ts + " should fail modifiedAfter=" + modifiedAfter);
            }
        }
    }

    @Nested
    @DisplayName("modifiedBefore filter")
    class ModifiedBeforeFilter {

        @ParameterizedTest(name = "ts={0}, modifiedBefore={1} → {2}")
        @CsvSource({
                "5000, 5000, true",   // exactly equal
                "4999, 5000, true",   // before threshold
                "5001, 5000, false"   // after threshold
        })
        void modifiedBeforeOnly(long ts, long modifiedBefore, boolean expected) throws Exception {
            boolean result = invoke(100, ts, NO_FILTER, NO_FILTER, NO_FILTER, modifiedBefore);
            if (expected) {
                assertTrue(result, "ts=" + ts + " should pass modifiedBefore=" + modifiedBefore);
            } else {
                assertFalse(result, "ts=" + ts + " should fail modifiedBefore=" + modifiedBefore);
            }
        }
    }

    @Nested
    @DisplayName("modifiedAfter AND modifiedBefore combined")
    class ModifiedAfterAndBeforeCombined {

        @ParameterizedTest(name = "ts={0}, after={1}, before={2} → {3}")
        @CsvSource({
                "5000, 4000, 6000, true",   // in range
                "3000, 4000, 6000, false",  // before range
                "7000, 4000, 6000, false"   // after range
        })
        void combinedDateFilters(long ts, long modifiedAfter, long modifiedBefore, boolean expected) throws Exception {
            boolean result = invoke(100, ts, NO_FILTER, NO_FILTER, modifiedAfter, modifiedBefore);
            if (expected) {
                assertTrue(result, "ts=" + ts + " should pass [" + modifiedAfter + ", " + modifiedBefore + "]");
            } else {
                assertFalse(result, "ts=" + ts + " should fail [" + modifiedAfter + ", " + modifiedBefore + "]");
            }
        }
    }

    // ---- 8. All filters active simultaneously ---------------------------------

    @Nested
    @DisplayName("All filters active simultaneously")
    class AllFiltersActive {

        @Test
        @DisplayName("All conditions match → true")
        void allMatch() throws Exception {
            // size=150 in [100,200], ts=5000 in [4000,6000]
            assertTrue(invoke(150, 5000, 100, 200, 4000, 6000));
        }

        @Test
        @DisplayName("Only minSize fails → false")
        void minSizeFails() throws Exception {
            assertFalse(invoke(50, 5000, 100, 200, 4000, 6000));
        }

        @Test
        @DisplayName("Only maxSize fails → false")
        void maxSizeFails() throws Exception {
            assertFalse(invoke(250, 5000, 100, 200, 4000, 6000));
        }

        @Test
        @DisplayName("Only modifiedAfter fails → false")
        void modifiedAfterFails() throws Exception {
            assertFalse(invoke(150, 3000, 100, 200, 4000, 6000));
        }

        @Test
        @DisplayName("Only modifiedBefore fails → false")
        void modifiedBeforeFails() throws Exception {
            assertFalse(invoke(150, 7000, 100, 200, 4000, 6000));
        }
    }

    // ---- 9–10. Edge cases -----------------------------------------------------

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("size=0 with minSize=0 → true")
        void zeroSizeWithZeroMin() throws Exception {
            assertTrue(invoke(0, 1_000_000L, 0, NO_FILTER, NO_FILTER, NO_FILTER));
        }

        @Test
        @DisplayName("size=0 with maxSize=0 → true")
        void zeroSizeWithZeroMax() throws Exception {
            assertTrue(invoke(0, 1_000_000L, NO_FILTER, 0, NO_FILTER, NO_FILTER));
        }

        @Test
        @DisplayName("Very large timestamps pass when in range")
        void veryLargeTimestamps() throws Exception {
            long farFuture = Long.MAX_VALUE - 1;
            long afterTs = Long.MAX_VALUE - 100;
            long beforeTs = Long.MAX_VALUE;
            assertTrue(invoke(100, farFuture, NO_FILTER, NO_FILTER, afterTs, beforeTs));
        }

        @Test
        @DisplayName("Very large timestamps fail when out of range")
        void veryLargeTimestampOutOfRange() throws Exception {
            long farFuture = Long.MAX_VALUE;
            long beforeTs = Long.MAX_VALUE - 100;
            assertFalse(invoke(100, farFuture, NO_FILTER, NO_FILTER, NO_FILTER, beforeTs));
        }

        @Test
        @DisplayName("Very large size values")
        void veryLargeSizeValues() throws Exception {
            long hugeSize = Long.MAX_VALUE;
            assertTrue(invoke(hugeSize, 1000, hugeSize, NO_FILTER, NO_FILTER, NO_FILTER),
                    "size exactly equal to minSize=MAX_VALUE should pass");
            assertTrue(invoke(hugeSize, 1000, NO_FILTER, hugeSize, NO_FILTER, NO_FILTER),
                    "size exactly equal to maxSize=MAX_VALUE should pass");
        }
    }
}
