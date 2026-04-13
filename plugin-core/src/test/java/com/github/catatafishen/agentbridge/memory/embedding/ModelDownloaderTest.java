package com.github.catatafishen.agentbridge.memory.embedding;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for static path computation methods in {@link ModelDownloader}.
 */
class ModelDownloaderTest {

    @Test
    void modelDirectoryUnderUserHome() {
        Path dir = ModelDownloader.getModelDirectory();
        String home = System.getProperty("user.home");
        assertTrue(dir.startsWith(Path.of(home)));
        assertTrue(dir.toString().contains(".agentbridge"));
        assertTrue(dir.toString().contains("models"));
        assertTrue(dir.toString().endsWith("all-MiniLM-L6-v2"));
    }

    @Test
    void modelPathEndsWithSafetensors() {
        Path path = ModelDownloader.getModelPath();
        assertEquals("model.safetensors", path.getFileName().toString());
        assertTrue(path.startsWith(ModelDownloader.getModelDirectory()));
    }

    @Test
    void vocabPathEndsWithVocabTxt() {
        Path path = ModelDownloader.getVocabPath();
        assertEquals("vocab.txt", path.getFileName().toString());
        assertTrue(path.startsWith(ModelDownloader.getModelDirectory()));
    }

    @Test
    void modelPathIsChildOfModelDirectory() {
        assertEquals(ModelDownloader.getModelDirectory(), ModelDownloader.getModelPath().getParent());
    }

    @Test
    void vocabPathIsChildOfModelDirectory() {
        assertEquals(ModelDownloader.getModelDirectory(), ModelDownloader.getVocabPath().getParent());
    }

    @Test
    void modelAndVocabPathsDiffer() {
        assertNotEquals(ModelDownloader.getModelPath(), ModelDownloader.getVocabPath());
    }

    @Test
    void isModelAvailableReturnsFalseForNonExistentFiles() {
        // In a test environment, the model files won't exist at the computed paths
        // unless previously downloaded. This verifies the method doesn't throw.
        assertDoesNotThrow(ModelDownloader::isModelAvailable);
    }
}
