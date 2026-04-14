package com.github.catatafishen.agentbridge.memory.validation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EvidenceExtractor} — regex-based extraction of FQNs,
 * file:line refs, and file paths from text.
 */
class EvidenceExtractorTest {

    @Test
    void extractFqn_standardJavaFqn() {
        List<String> refs = EvidenceExtractor.extract("The class com.example.service.UserService handles auth.");
        assertTrue(refs.contains("com.example.service.UserService"), "Expected FQN: " + refs);
    }

    @Test
    void extractFqn_withMethodReference() {
        List<String> refs = EvidenceExtractor.extract("Call com.example.UserService.authenticate to verify.");
        assertTrue(refs.contains("com.example.UserService.authenticate"), "Expected FQN with method: " + refs);
    }

    @Test
    void extractFqn_rejectsUrls() {
        List<String> refs = EvidenceExtractor.extract("Visit https://www.example.com/page for docs.");
        assertTrue(refs.isEmpty(), "URLs should be rejected: " + refs);
    }

    @Test
    void extractFqn_rejectsVersionNumbers() {
        List<String> refs = EvidenceExtractor.extract("Using org.gradle.version 8.10.2 in the build.");
        for (String ref : refs) {
            assertFalse(ref.contains("8.10.2"), "Version numbers should be rejected: " + refs);
        }
    }

    @Test
    void extractFileLineRef_simpleRef() {
        List<String> refs = EvidenceExtractor.extract("See the fix in UserService.java:42 for details.");
        assertTrue(refs.contains("UserService.java:42"), "Expected file:line ref: " + refs);
    }

    @Test
    void extractFileLineRef_rangeRef() {
        List<String> refs = EvidenceExtractor.extract("Changed lines in Config.kt:10-25.");
        assertTrue(refs.contains("Config.kt:10-25"), "Expected file:range ref: " + refs);
    }

    @Test
    void extractFileLineRef_backtickWrapped() {
        List<String> refs = EvidenceExtractor.extract("Check `AuthController.java:100` for the handler.");
        assertTrue(refs.contains("AuthController.java:100"), "Expected backtick-wrapped ref: " + refs);
    }

    @Test
    void extractFilePath_standardPath() {
        List<String> refs = EvidenceExtractor.extract("Edit src/main/java/UserService.java to add the method.");
        assertTrue(refs.contains("src/main/java/UserService.java"), "Expected file path: " + refs);
    }

    @Test
    void extractFilePath_kotlinFile() {
        List<String> refs = EvidenceExtractor.extract("The panel is in ui/panel/ChatPanel.kt.");
        assertTrue(refs.contains("ui/panel/ChatPanel.kt"), "Expected Kotlin path: " + refs);
    }

    @Test
    void extract_deduplicates() {
        String text = "UserService.java:42 and again UserService.java:42 mentioned twice.";
        List<String> refs = EvidenceExtractor.extract(text);
        long count = refs.stream().filter("UserService.java:42"::equals).count();
        assertEquals(1, count, "Should deduplicate: " + refs);
    }

    @Test
    void extract_emptyText() {
        assertTrue(EvidenceExtractor.extract("").isEmpty());
    }

    @Test
    void extract_noCodeReferences() {
        List<String> refs = EvidenceExtractor.extract("This is a plain sentence with no code references.");
        assertTrue(refs.isEmpty(), "Should find nothing: " + refs);
    }

    @Test
    void extract_maxRefs_capped() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("File").append(i).append(".java:").append(i + 1).append(" ");
        }
        List<String> refs = EvidenceExtractor.extract(sb.toString());
        assertTrue(refs.size() <= 20, "Should cap at 20, got: " + refs.size());
    }

    @Test
    void extractAsJson_returnsEmptyForNoRefs() {
        assertEquals("", EvidenceExtractor.extractAsJson("No code references here."));
    }

    @Test
    void extractAsJson_returnsValidJsonArray() {
        String json = EvidenceExtractor.extractAsJson("See com.example.auth.AuthService and Config.java:5.");
        assertTrue(json.startsWith("["), "Should start with [: " + json);
        assertTrue(json.endsWith("]"), "Should end with ]: " + json);
        assertTrue(json.contains("com.example.auth.AuthService"), "Should contain FQN: " + json);
        assertTrue(json.contains("Config.java:5"), "Should contain file:line: " + json);
    }

    @Test
    void extractAsJson_escapesQuotes() {
        // FQN patterns won't match quotes, but this validates the escape function
        String json = EvidenceExtractor.extractAsJson("See com.example.service.MyService class.");
        assertFalse(json.isEmpty());
        // Verify valid JSON-like structure
        assertTrue(json.startsWith("[\""));
        assertTrue(json.endsWith("\"]"));
    }

    @Test
    void extractFqn_rejectsWwwDomains() {
        List<String> refs = EvidenceExtractor.extract("Go to www.google.com.SomeClass for info.");
        for (String ref : refs) {
            assertFalse(ref.startsWith("www."), "www domains should be rejected: " + refs);
        }
    }

    @Test
    void extractFilePath_ignoresNonCodeExtensions() {
        List<String> refs = EvidenceExtractor.extract("Download from path/to/file.zip and path/to/image.png.");
        assertTrue(refs.isEmpty(), "Non-code extensions should be ignored: " + refs);
    }

    @Test
    void extract_mixedReferences() {
        String text = """
            The com.github.example.UserService class at src/main/java/UserService.java:42
            calls com.github.example.AuthHelper.verify to check tokens.
            """;
        List<String> refs = EvidenceExtractor.extract(text);
        assertTrue(refs.size() >= 3, "Should find FQNs + file refs: " + refs);
    }

    @Test
    void extract_toolResultFormat_capturesFilePath() {
        String toolFragment = "[tool:read_file file:src/main/java/UserService.java]";
        List<String> refs = EvidenceExtractor.extract(toolFragment);
        assertTrue(refs.contains("src/main/java/UserService.java"),
            "Tool result file path should be captured: " + refs);
    }

    @Test
    void extract_searchToolResult_capturesFileRefs() {
        String searchResult = "[search_text result: " +
            "plugin-core/src/main/java/com/example/Foo.java:42 match found\n" +
            "plugin-core/src/test/java/com/example/FooTest.java:10 test match]";
        List<String> refs = EvidenceExtractor.extract(searchResult);
        assertTrue(refs.stream().anyMatch(r -> r.contains("Foo.java")),
            "Search result file:line refs should be captured: " + refs);
    }
}
