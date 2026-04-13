package com.github.catatafishen.agentbridge.acp.client;

import com.github.catatafishen.agentbridge.agent.AbstractAgentClient.AgentMode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProjectAgentScannerTest {

    // ── stripYamlValue (private static) ─────────────────────────────────

    @Test
    void stripYamlValue_doubleQuoted() throws Exception {
        assertEquals("hello world", invokeStripYamlValue("\"hello world\""));
    }

    @Test
    void stripYamlValue_singleQuoted() throws Exception {
        assertEquals("hello world", invokeStripYamlValue("'hello world'"));
    }

    @Test
    void stripYamlValue_unquoted() throws Exception {
        assertEquals("hello world", invokeStripYamlValue("hello world"));
    }

    @Test
    void stripYamlValue_trailingWhitespace() throws Exception {
        assertEquals("value", invokeStripYamlValue("  value  "));
    }

    @Test
    void stripYamlValue_quotedWithWhitespace() throws Exception {
        assertEquals("value", invokeStripYamlValue("  \"value\"  "));
    }

    @Test
    void stripYamlValue_emptyString() throws Exception {
        assertEquals("", invokeStripYamlValue(""));
    }

    @Test
    void stripYamlValue_justQuotes() throws Exception {
        assertEquals("", invokeStripYamlValue("\"\""));
    }

    @Test
    void stripYamlValue_mismatchedQuotesNotStripped() throws Exception {
        assertEquals("\"hello'", invokeStripYamlValue("\"hello'"));
    }

    @Test
    void stripYamlValue_singleChar() throws Exception {
        assertEquals("x", invokeStripYamlValue("x"));
    }

    // ── parseAgentFile (private static) ────────────────────────────────

    @Nested
    class ParseAgentFile {

        @TempDir
        Path tempDir;

        @Test
        void fullFrontmatter_returnsNameAndDescription() throws Exception {
            Path file = writeAgent("---\nname: My Agent\ndescription: Does things\n---\nBody text");
            AgentMode mode = invokeParseAgentFile(file, "my-agent");

            assertNotNull(mode);
            assertEquals("my-agent", mode.slug());
            assertEquals("My Agent", mode.name());
            assertEquals("Does things", mode.description());
        }

        @Test
        void onlyName_descriptionIsNull() throws Exception {
            Path file = writeAgent("---\nname: Solo Name\n---\n# content");
            AgentMode mode = invokeParseAgentFile(file, "solo");

            assertNotNull(mode);
            assertEquals("solo", mode.slug());
            assertEquals("Solo Name", mode.name());
            assertNull(mode.description());
        }

        @Test
        void onlyDescription_nameFallsBackToSlug() throws Exception {
            Path file = writeAgent("---\ndescription: A helpful agent\n---\n");
            AgentMode mode = invokeParseAgentFile(file, "helper");

            assertNotNull(mode);
            assertEquals("helper", mode.slug());
            assertEquals("helper", mode.name());
            assertEquals("A helpful agent", mode.description());
        }

        @Test
        void emptyName_fallsBackToSlug() throws Exception {
            Path file = writeAgent("---\nname:\ndescription: Has desc\n---\n");
            AgentMode mode = invokeParseAgentFile(file, "fallback");

            assertNotNull(mode);
            assertEquals("fallback", mode.slug());
            assertEquals("fallback", mode.name());
            assertEquals("Has desc", mode.description());
        }

        @Test
        void noFrontmatter_returnsSlugAsNameAndNullDescription() throws Exception {
            Path file = writeAgent("Just a plain markdown file.\nNo frontmatter here.");
            AgentMode mode = invokeParseAgentFile(file, "plain");

            assertNotNull(mode);
            assertEquals("plain", mode.slug());
            assertEquals("plain", mode.name());
            assertNull(mode.description());
        }

        @Test
        void emptyFile_returnsSlugAsNameAndNullDescription() throws Exception {
            Path file = writeAgent("");
            AgentMode mode = invokeParseAgentFile(file, "empty");

            assertNotNull(mode);
            assertEquals("empty", mode.slug());
            assertEquals("empty", mode.name());
            assertNull(mode.description());
        }

        @Test
        void quotedYamlValues_quotesStripped() throws Exception {
            Path file = writeAgent("---\nname: \"Quoted Agent\"\ndescription: 'Single quoted'\n---\n");
            AgentMode mode = invokeParseAgentFile(file, "quoted");

            assertNotNull(mode);
            assertEquals("quoted", mode.slug());
            assertEquals("Quoted Agent", mode.name());
            assertEquals("Single quoted", mode.description());
        }

        @Test
        void malformedFrontmatter_noClosingDelimiter_stillExtractsFields() throws Exception {
            Path file = writeAgent("---\nname: Unclosed\ndescription: Still parsed\nsome other line");
            AgentMode mode = invokeParseAgentFile(file, "malformed");

            assertNotNull(mode);
            assertEquals("malformed", mode.slug());
            assertEquals("Unclosed", mode.name());
            assertEquals("Still parsed", mode.description());
        }

        // ── Helpers ─────────────────────────────────────────────────────

        private Path writeAgent(String content) throws Exception {
            Path file = tempDir.resolve("agent.md");
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        }
    }

    // ── Reflection helpers ──────────────────────────────────────────────

    private static String invokeStripYamlValue(String raw) throws Exception {
        Method m = ProjectAgentScanner.class.getDeclaredMethod("stripYamlValue", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, raw);
    }

    private static AgentMode invokeParseAgentFile(Path file, String slug) throws Exception {
        Method m = ProjectAgentScanner.class.getDeclaredMethod("parseAgentFile", Path.class, String.class);
        m.setAccessible(true);
        return (AgentMode) m.invoke(null, file, slug);
    }
}
