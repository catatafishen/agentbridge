package com.github.catatafishen.agentbridge.memory.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MemoryQuery} builder patterns and defaults.
 */
class MemoryQueryTest {

    @Test
    void semanticBuilderSetsQueryText() {
        MemoryQuery q = MemoryQuery.semantic("database migration").build();
        assertEquals("database migration", q.queryText());
        assertNull(q.queryEmbedding());
        assertEquals(MemoryQuery.DEFAULT_LIMIT, q.limit());
    }

    @Test
    void withEmbeddingBuilderSetsEmbedding() {
        float[] embedding = {0.1f, 0.2f, 0.3f};
        MemoryQuery q = MemoryQuery.withEmbedding(embedding).build();
        assertNull(q.queryText());
        assertArrayEquals(embedding, q.queryEmbedding());
    }

    @Test
    void filterBuilderSetsMetadata() {
        MemoryQuery q = MemoryQuery.filter()
            .wing("my-project")
            .room("architecture")
            .memoryType("decision")
            .agent("copilot")
            .limit(5)
            .build();

        assertNull(q.queryText());
        assertEquals("my-project", q.wing());
        assertEquals("architecture", q.room());
        assertEquals("decision", q.memoryType());
        assertEquals("copilot", q.agent());
        assertEquals(5, q.limit());
    }

    @Test
    void defaultLimitIsTen() {
        assertEquals(10, MemoryQuery.DEFAULT_LIMIT);
        MemoryQuery q = MemoryQuery.filter().build();
        assertEquals(10, q.limit());
    }

    @Test
    void semanticWithAllFilters() {
        float[] embedding = new float[384];
        MemoryQuery q = MemoryQuery.semantic("test query")
            .queryEmbedding(embedding)
            .wing("proj")
            .room("technical")
            .memoryType("problem")
            .agent("agent-1")
            .limit(20)
            .build();

        assertEquals("test query", q.queryText());
        assertNotNull(q.queryEmbedding());
        assertEquals(384, q.queryEmbedding().length);
        assertEquals("proj", q.wing());
        assertEquals("technical", q.room());
        assertEquals("problem", q.memoryType());
        assertEquals("agent-1", q.agent());
        assertEquals(20, q.limit());
    }

    @Test
    void nullFieldsAreAllowed() {
        MemoryQuery q = MemoryQuery.filter().build();
        assertNull(q.queryText());
        assertNull(q.queryEmbedding());
        assertNull(q.wing());
        assertNull(q.room());
        assertNull(q.memoryType());
        assertNull(q.agent());
    }
}
