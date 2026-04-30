package com.github.catatafishen.agentbridge.memory.layers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for L0 ({@link IdentityLayer}).
 * Uses a {@code @TempDir} to simulate the resolved memory directory and
 * identity file.
 */
class IdentityLayerTest {

    private static final String WING = "test-project";

    @TempDir
    Path tempDir;

    @Test
    void layerIdAndDisplayName() {
        IdentityLayer layer = new IdentityLayer(tempDir);
        assertEquals("L0-identity", layer.layerId());
        assertEquals("Identity", layer.displayName());
    }

    @Test
    void noIdentityFileReturnsEmpty() {
        IdentityLayer layer = new IdentityLayer(tempDir);
        String result = layer.render(WING, null);
        assertEquals("", result);
    }

    @Test
    void emptyIdentityFileReturnsEmpty() throws IOException {
        writeIdentityFile("");
        IdentityLayer layer = new IdentityLayer(tempDir);
        String result = layer.render(WING, null);
        assertEquals("", result);
    }

    @Test
    void whitespaceOnlyIdentityFileReturnsEmpty() throws IOException {
        writeIdentityFile("   \n  \n   ");
        IdentityLayer layer = new IdentityLayer(tempDir);
        String result = layer.render(WING, null);
        assertEquals("", result);
    }

    @Test
    void validIdentityFileReturnsContent() throws IOException {
        String content = "This project is an IntelliJ plugin written in Java 21.\nThe team prefers conventional commits.";
        writeIdentityFile(content);
        IdentityLayer layer = new IdentityLayer(tempDir);
        String result = layer.render(WING, null);
        assertEquals("## Identity\n\n" + content, result);
    }

    @Test
    void identityContentIsStripped() throws IOException {
        writeIdentityFile("  Some identity facts with leading/trailing whitespace  \n  ");
        IdentityLayer layer = new IdentityLayer(tempDir);
        String result = layer.render(WING, null);
        assertTrue(result.startsWith("## Identity\n\n"));
        assertTrue(result.contains("Some identity facts"));
        // Verify leading/trailing whitespace was stripped
        assertEquals("## Identity\n\nSome identity facts with leading/trailing whitespace", result);
    }

    @Test
    void nullBasePathReturnsEmpty() {
        IdentityLayer layer = new IdentityLayer((Path) null);
        String result = layer.render(WING, null);
        assertEquals("", result);
    }

    @Test
    void queryParameterIsIgnored() throws IOException {
        String content = "Identity content here";
        writeIdentityFile(content);
        IdentityLayer layer = new IdentityLayer(tempDir);
        // Identity layer should ignore the query parameter
        String withQuery = layer.render(WING, "some query");
        String withoutQuery = layer.render(WING, null);
        assertEquals(withQuery, withoutQuery);
    }

    @Test
    void wingParameterIsIgnored() throws IOException {
        String content = "Identity for any wing";
        writeIdentityFile(content);
        IdentityLayer layer = new IdentityLayer(tempDir);
        // Identity layer returns the same content regardless of wing
        String wing1 = layer.render("project-a", null);
        String wing2 = layer.render("project-b", null);
        assertEquals(wing1, wing2);
    }

    private void writeIdentityFile(String content) throws IOException {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("identity.txt"), content, StandardCharsets.UTF_8);
    }
}
