package com.github.catatafishen.agentbridge.psi.review;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link ChangeNavigator}. Exercises the prev/next search,
 * wrap-around, and enclosing-range lookup without spinning up a Project or VFS.
 */
class ChangeNavigatorTest {

    private static ChangeRange r(int startLine, int endLine, ChangeType type) {
        return new ChangeRange(startLine, endLine, type, startLine, Math.max(0, endLine - startLine));
    }

    private static NavigableMap<String, List<ChangeRange>> build() {
        NavigableMap<String, List<ChangeRange>> m = new TreeMap<>();
        m.put("/a.java", List.of(
            r(5, 6, ChangeType.MODIFIED),
            r(20, 22, ChangeType.ADDED)));
        m.put("/b.java", List.of(
            r(1, 2, ChangeType.MODIFIED),
            r(40, 41, ChangeType.MODIFIED)));
        m.put("/c.java", List.of(
            r(10, 11, ChangeType.ADDED)));
        return m;
    }

    @Test
    void findNext_empty_returnsEmpty() {
        assertTrue(ChangeNavigator.findNext(new TreeMap<>(), "/a.java", 0).isEmpty());
    }

    @Test
    void findNext_withinSameFile_picksNextRangeAfterCaret() {
        Optional<ChangeNavigator.Location> loc = ChangeNavigator.findNext(build(), "/a.java", 6);
        assertTrue(loc.isPresent());
        assertEquals("/a.java", loc.get().path());
        assertEquals(20, loc.get().range().startLine());
    }

    @Test
    void findNext_pastLastRangeInFile_advancesToNextFile() {
        Optional<ChangeNavigator.Location> loc = ChangeNavigator.findNext(build(), "/a.java", 100);
        assertTrue(loc.isPresent());
        assertEquals("/b.java", loc.get().path());
        assertEquals(1, loc.get().range().startLine());
    }

    @Test
    void findNext_pastLastFile_wrapsToFirst() {
        Optional<ChangeNavigator.Location> loc = ChangeNavigator.findNext(build(), "/c.java", 100);
        assertTrue(loc.isPresent());
        assertEquals("/a.java", loc.get().path());
        assertEquals(5, loc.get().range().startLine());
    }

    @Test
    void findNext_unknownPath_fallsBackToFirstEntry() {
        Optional<ChangeNavigator.Location> loc = ChangeNavigator.findNext(build(), "/unknown.java", 0);
        assertTrue(loc.isPresent());
        // unknown path has no ranges → falls through to higherEntry which returns null for paths
        // lexically after /unknown.java, so we wrap to the first entry overall.
        assertEquals("/a.java", loc.get().path());
    }

    @Test
    void findNext_nullPath_returnsFirstChange() {
        Optional<ChangeNavigator.Location> loc = ChangeNavigator.findNext(build(), null, 0);
        assertTrue(loc.isPresent());
        assertEquals("/a.java", loc.get().path());
        assertEquals(5, loc.get().range().startLine());
    }

    @Test
    void findPrevious_withinSameFile_picksPriorRangeBeforeCaret() {
        Optional<ChangeNavigator.Location> loc = ChangeNavigator.findPrevious(build(), "/b.java", 30);
        assertTrue(loc.isPresent());
        assertEquals("/b.java", loc.get().path());
        assertEquals(1, loc.get().range().startLine());
    }

    @Test
    void findPrevious_beforeFirstRangeInFile_goesToPriorFile() {
        Optional<ChangeNavigator.Location> loc = ChangeNavigator.findPrevious(build(), "/b.java", 0);
        assertTrue(loc.isPresent());
        assertEquals("/a.java", loc.get().path());
        // Last range of /a.java
        assertEquals(20, loc.get().range().startLine());
    }

    @Test
    void findPrevious_atFirstFileFirstRange_wrapsToLastFileLastRange() {
        Optional<ChangeNavigator.Location> loc = ChangeNavigator.findPrevious(build(), "/a.java", 0);
        assertTrue(loc.isPresent());
        assertEquals("/c.java", loc.get().path());
        assertEquals(10, loc.get().range().startLine());
    }

    @Test
    void findPrevious_empty_returnsEmpty() {
        assertTrue(ChangeNavigator.findPrevious(new TreeMap<>(), "/a.java", 0).isEmpty());
    }

    @Test
    void findEnclosing_lineInsideRange_returnsRange() {
        Optional<ChangeRange> r = ChangeNavigator.findEnclosing(build(), "/a.java", 21);
        assertTrue(r.isPresent());
        assertEquals(20, r.get().startLine());
        assertEquals(22, r.get().endLine());
    }

    @Test
    void findEnclosing_lineOutsideRange_returnsEmpty() {
        Optional<ChangeRange> r = ChangeNavigator.findEnclosing(build(), "/a.java", 50);
        assertFalse(r.isPresent());
    }

    @Test
    void findEnclosing_unknownPath_returnsEmpty() {
        Optional<ChangeRange> r = ChangeNavigator.findEnclosing(build(), "/nope.java", 0);
        assertFalse(r.isPresent());
    }

    @Test
    void findEnclosing_deletedRange_atStartLineReturnsIt() {
        NavigableMap<String, List<ChangeRange>> m = new TreeMap<>();
        m.put("/x.java", List.of(new ChangeRange(7, 7, ChangeType.DELETED, 5, 3)));
        Optional<ChangeRange> r = ChangeNavigator.findEnclosing(m, "/x.java", 7);
        assertTrue(r.isPresent());
        assertEquals(ChangeType.DELETED, r.get().type());
    }
}
