package com.github.catatafishen.agentbridge.agent.claude;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link InstructionsManager}.
 */
class InstructionsManagerTest {

    @TempDir
    Path projectRoot;

    // ── null / empty basePath guards ──────────────────────────────────────────

    @Test
    void doesNothingWhenBasePathIsNull() {
        // Must not throw
        InstructionsManager.ensureInstructions(null, "CLAUDE.md", "extra");
        assertTrue(true); // confirms no exception
    }

    @Test
    void doesNothingWhenTargetPathIsEmpty() {
        // Must not throw
        InstructionsManager.ensureInstructions(projectRoot.toString(), "", "extra");
        assertTrue(true); // confirms no exception
    }

    // ── creates file when target does not exist ───────────────────────────────

    @Test
    void createsTargetFileWithInstructionsWhenMissing() {
        InstructionsManager.ensureInstructions(projectRoot.toString(), "CLAUDE.md", "my-additional-instructions");

        Path target = projectRoot.resolve("CLAUDE.md");
        assertTrue(target.toFile().exists(), "target file must be created");

        String content = readFile(target);
        assertTrue(content.contains(InstructionsManager.INSTRUCTIONS_SENTINEL),
            "sentinel must be present in created file");
        assertTrue(content.contains("my-additional-instructions"),
            "additional instructions must be included");
    }

    @Test
    void createsNestedTargetFileWithMkdirs() {
        InstructionsManager.ensureInstructions(projectRoot.toString(), "some/nested/dir/CLAUDE.md", "");

        Path target = projectRoot.resolve("some/nested/dir/CLAUDE.md");
        assertTrue(target.toFile().exists(), "nested target file must be created");
    }

    // ── prepends to existing file ─────────────────────────────────────────────

    @Test
    void prependsInstructionsToExistingFileContent() throws IOException {
        Path target = projectRoot.resolve("CLAUDE.md");
        Files.writeString(target, "# My existing content\n\nSome user notes.", StandardCharsets.UTF_8);

        InstructionsManager.ensureInstructions(projectRoot.toString(), "CLAUDE.md", "");

        String content = readFile(target);
        assertTrue(content.contains(InstructionsManager.INSTRUCTIONS_SENTINEL),
            "sentinel must be prepended");
        assertTrue(content.contains("# My existing content"),
            "original content must be preserved");
        int sentinelPos = content.indexOf(InstructionsManager.INSTRUCTIONS_SENTINEL);
        int existingPos = content.indexOf("# My existing content");
        assertTrue(sentinelPos < existingPos,
            "sentinel must appear before original content");
    }

    // ── idempotence: does not duplicate if already prepended ──────────────────

    @Test
    void doesNotDuplicateInstructionsIfAlreadyPresent() {
        InstructionsManager.ensureInstructions(projectRoot.toString(), "CLAUDE.md", "");
        String afterFirst = readFile(projectRoot.resolve("CLAUDE.md"));

        InstructionsManager.ensureInstructions(projectRoot.toString(), "CLAUDE.md", "");
        String afterSecond = readFile(projectRoot.resolve("CLAUDE.md"));

        org.junit.jupiter.api.Assertions.assertEquals(afterFirst, afterSecond,
            "calling ensureInstructions twice must not duplicate content");
    }

    // ── additional instructions ───────────────────────────────────────────────

    @Test
    void blankAdditionalInstructionsNotAppended() {
        InstructionsManager.ensureInstructions(projectRoot.toString(), "CLAUDE.md", "   ");

        String content = readFile(projectRoot.resolve("CLAUDE.md"));
        assertFalse(content.endsWith("   "), "blank additional instructions must not be appended as-is");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
