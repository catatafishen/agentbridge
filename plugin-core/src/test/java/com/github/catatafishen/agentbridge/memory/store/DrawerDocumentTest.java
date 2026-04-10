package com.github.catatafishen.agentbridge.memory.store;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for {@link DrawerDocument} record, builder, and SearchResult.
 */
class DrawerDocumentTest {

    @Test
    void builderCreatesDocumentWithAllFields() {
        Instant now = Instant.now();
        DrawerDocument doc = DrawerDocument.builder()
            .id("drawer_myproject_general_abc123")
            .wing("myproject")
            .room("architecture")
            .content("We decided to use Java 21 for this project")
            .memoryType(DrawerDocument.TYPE_DECISION)
            .sourceSession("session-1")
            .sourceFile("session-1.jsonl")
            .agent("copilot")
            .filedAt(now)
            .addedBy(DrawerDocument.ADDED_BY_MINER)
            .sourceTurnIndex("5")
            .sourceCommits("abc1234,def5678")
            .build();

        assertEquals("drawer_myproject_general_abc123", doc.id());
        assertEquals("myproject", doc.wing());
        assertEquals("architecture", doc.room());
        assertEquals("We decided to use Java 21 for this project", doc.content());
        assertEquals(DrawerDocument.TYPE_DECISION, doc.memoryType());
        assertEquals("session-1", doc.sourceSession());
        assertEquals("session-1.jsonl", doc.sourceFile());
        assertEquals("copilot", doc.agent());
        assertEquals(now, doc.filedAt());
        assertEquals(DrawerDocument.ADDED_BY_MINER, doc.addedBy());
        assertEquals("5", doc.sourceTurnIndex());
        assertEquals("abc1234,def5678", doc.sourceCommits());
    }

    @Test
    void builderDefaultValues() {
        DrawerDocument doc = DrawerDocument.builder().build();

        assertEquals("", doc.id());
        assertEquals("", doc.wing());
        assertEquals("general", doc.room());
        assertEquals("", doc.content());
        assertEquals(DrawerDocument.TYPE_GENERAL, doc.memoryType());
        assertEquals("", doc.sourceSession());
        assertEquals("", doc.sourceFile());
        assertEquals("", doc.agent());
        assertNotNull(doc.filedAt());
        assertEquals(DrawerDocument.ADDED_BY_MINER, doc.addedBy());
        assertEquals("", doc.sourceTurnIndex());
        assertEquals("", doc.sourceCommits());
    }

    @Test
    void recordEqualityWorks() {
        Instant fixed = Instant.parse("2024-01-01T00:00:00Z");
        DrawerDocument a = DrawerDocument.builder()
            .id("test").wing("w").room("r").content("c")
            .memoryType("decision").filedAt(fixed).build();
        DrawerDocument b = DrawerDocument.builder()
            .id("test").wing("w").room("r").content("c")
            .memoryType("decision").filedAt(fixed).build();

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void searchResultHoldsDrawerAndScore() {
        DrawerDocument doc = DrawerDocument.builder()
            .id("test").content("hello").build();
        DrawerDocument.SearchResult result = new DrawerDocument.SearchResult(doc, 0.85f);

        assertSame(doc, result.drawer());
        assertEquals(0.85f, result.score(), 0.001);
    }

    @Test
    void typeConstantsAreDefined() {
        assertNotNull(DrawerDocument.TYPE_CONTEXT);
        assertNotNull(DrawerDocument.TYPE_DECISION);
        assertNotNull(DrawerDocument.TYPE_PROBLEM);
        assertNotNull(DrawerDocument.TYPE_SOLUTION);
        assertNotNull(DrawerDocument.TYPE_GENERAL);
    }

    @Test
    void roomConstantsAreDefined() {
        assertNotNull(DrawerDocument.ROOM_CODEBASE);
        assertNotNull(DrawerDocument.ROOM_DEBUGGING);
        assertNotNull(DrawerDocument.ROOM_WORKFLOW);
        assertNotNull(DrawerDocument.ROOM_DECISIONS);
        assertNotNull(DrawerDocument.ROOM_PREFERENCES);
        assertNotNull(DrawerDocument.ROOM_GENERAL);
    }

    @Test
    void addedByConstantsAreDefined() {
        assertEquals("miner", DrawerDocument.ADDED_BY_MINER);
        assertEquals("mcp", DrawerDocument.ADDED_BY_MCP);
    }

    @Test
    void maxContentLength() {
        assertEquals(100_000, DrawerDocument.MAX_CONTENT_LENGTH);
    }

    @Test
    void maxNameLength() {
        assertEquals(128, DrawerDocument.MAX_NAME_LENGTH);
    }
}
