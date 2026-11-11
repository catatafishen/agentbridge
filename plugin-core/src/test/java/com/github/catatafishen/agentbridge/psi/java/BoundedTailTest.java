package com.github.catatafishen.agentbridge.psi.java;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedTailTest {

    @Test
    void shortOutputIsKeptWhole() {
        BoundedTail tail = new BoundedTail(100);
        tail.append("hello ");
        tail.append("world");
        assertEquals("hello world", tail.text());
        assertFalse(tail.isTruncated());
    }

    @Test
    void keepsOnlyTheLastLimitCharacters() {
        BoundedTail tail = new BoundedTail(10);
        tail.append("HEAD");
        tail.append("x".repeat(50));
        tail.append("TAIL");
        assertEquals(10, tail.text().length());
        assertTrue(tail.text().endsWith("TAIL"));
        assertTrue(tail.isTruncated());
    }

    @Test
    void retainedMemoryStaysBoundedForManySmallChunks() {
        BoundedTail tail = new BoundedTail(100);
        for (int i = 0; i < 10_000; i++) {
            tail.append("line " + i + "\n");
        }
        assertEquals(100, tail.text().length());
        assertTrue(tail.text().endsWith("line 9999\n"));
        assertTrue(tail.isTruncated());
    }

    @Test
    void outputExactlyAtLimitIsNotTruncated() {
        BoundedTail tail = new BoundedTail(5);
        tail.append("12345");
        assertEquals("12345", tail.text());
        assertFalse(tail.isTruncated());
    }

    @Test
    void emptyTailHasNoText() {
        BoundedTail tail = new BoundedTail(5);
        assertEquals("", tail.text());
        assertFalse(tail.isTruncated());
    }

    @Test
    void nonPositiveLimitIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedTail(0));
    }
}
