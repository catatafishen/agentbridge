package com.github.catatafishen.agentbridge.memory.layers;

import com.github.catatafishen.agentbridge.memory.store.DrawerDocument;
import com.github.catatafishen.agentbridge.memory.store.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for L1 ({@link EssentialStoryLayer}).
 */
class EssentialStoryLayerTest {

    private static final String WING = "test-project";

    private MemoryStore store;

    @BeforeEach
    void setUp() {
        store = Mockito.mock(MemoryStore.class);
    }

    @Test
    void layerIdReturnsL1Essential() {
        EssentialStoryLayer layer = new EssentialStoryLayer(store);
        assertEquals("L1-essential", layer.layerId());
    }

    @Test
    void displayNameReturnsEssentialStory() {
        EssentialStoryLayer layer = new EssentialStoryLayer(store);
        assertEquals("Essential Story", layer.displayName());
    }

    @Test
    void renderWithEmptyDrawersReturnsEmpty() throws IOException {
        when(store.getTopDrawersDiverse(eq(WING), anyInt())).thenReturn(Collections.emptyList());

        EssentialStoryLayer layer = new EssentialStoryLayer(store);
        String result = layer.render(WING, null);

        assertEquals("", result);
    }

    @Test
    void renderWithDrawersFormatsMarkdown() throws IOException {
        DrawerDocument drawer = DrawerDocument.builder()
            .id("drawer1")
            .wing(WING)
            .room("codebase")
            .content("Refactored the parser module")
            .memoryType("decision")
            .filedAt(Instant.parse("2024-01-15T10:30:00Z"))
            .build();

        when(store.getTopDrawersDiverse(eq(WING), anyInt())).thenReturn(List.of(drawer));

        EssentialStoryLayer layer = new EssentialStoryLayer(store);
        String result = layer.render(WING, null);

        assertTrue(result.startsWith("## Essential Story\n\n"));
        assertTrue(result.contains("Recent memories for **test-project**:"));
        assertTrue(result.contains("[decision]"));
        assertTrue(result.contains("codebase:"));
        assertTrue(result.contains("Refactored the parser module"));
    }

    @Test
    void renderTruncatesContentOver200Chars() throws IOException {
        String longContent = "A".repeat(250);
        DrawerDocument drawer = DrawerDocument.builder()
            .id("drawer-long")
            .wing(WING)
            .room("general")
            .content(longContent)
            .memoryType("context")
            .filedAt(Instant.now())
            .build();

        when(store.getTopDrawersDiverse(eq(WING), anyInt())).thenReturn(List.of(drawer));

        EssentialStoryLayer layer = new EssentialStoryLayer(store);
        String result = layer.render(WING, null);

        // Content should be truncated to 200 chars + ellipsis
        assertFalse(result.contains(longContent));
        assertTrue(result.contains("A".repeat(200) + "…"));
    }

    @Test
    void renderIncludesRoomName() throws IOException {
        DrawerDocument drawer = DrawerDocument.builder()
            .id("drawer-room")
            .wing(WING)
            .room("debugging")
            .content("Found a null pointer issue")
            .memoryType("problem")
            .filedAt(Instant.now())
            .build();

        when(store.getTopDrawersDiverse(eq(WING), anyInt())).thenReturn(List.of(drawer));

        EssentialStoryLayer layer = new EssentialStoryLayer(store);
        String result = layer.render(WING, null);

        assertTrue(result.contains("debugging:"));
    }

    @Test
    void renderIgnoresQueryParameter() throws IOException {
        DrawerDocument drawer = DrawerDocument.builder()
            .id("drawer-q")
            .wing(WING)
            .room("workflow")
            .content("Set up CI pipeline")
            .memoryType("context")
            .filedAt(Instant.now())
            .build();

        when(store.getTopDrawersDiverse(eq(WING), anyInt())).thenReturn(List.of(drawer));

        EssentialStoryLayer layer = new EssentialStoryLayer(store);
        String withQuery = layer.render(WING, "some search query");
        String withoutQuery = layer.render(WING, null);

        assertEquals(withQuery, withoutQuery);
    }

    @Test
    void renderWhenStoreThrowsIOExceptionReturnsEmpty() throws IOException {
        when(store.getTopDrawersDiverse(anyString(), anyInt())).thenThrow(new IOException("disk failure"));

        EssentialStoryLayer layer = new EssentialStoryLayer(store);
        String result = layer.render(WING, null);

        assertEquals("", result);
    }

    @Test
    void customMaxDrawersLimitIsPassedToStore() throws IOException {
        int customLimit = 5;
        when(store.getTopDrawersDiverse(WING, customLimit)).thenReturn(Collections.emptyList());

        EssentialStoryLayer layer = new EssentialStoryLayer(store, customLimit);
        layer.render(WING, null);

        verify(store).getTopDrawersDiverse(WING, customLimit);
    }
}
