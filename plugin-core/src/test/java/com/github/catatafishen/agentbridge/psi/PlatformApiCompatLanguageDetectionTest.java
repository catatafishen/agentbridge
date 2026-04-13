package com.github.catatafishen.agentbridge.psi;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for the pure language-detection logic extracted from
 * {@link PlatformApiCompat}. These methods return language ID strings
 * (not {@code com.intellij.lang.Language} objects), so they can be
 * tested without the IntelliJ platform.
 */
class PlatformApiCompatLanguageDetectionTest {

    // ── resolveShebangInterpreter ─────────────────────────────

    @Nested
    class ResolveShebangInterpreter {

        @Test
        void returnsNullForNonShebang() {
            assertNull(PlatformApiCompat.resolveShebangInterpreter("no shebang here"));
        }

        @Test
        void returnsNullForEmptyString() {
            assertNull(PlatformApiCompat.resolveShebangInterpreter(""));
        }

        @Test
        void extractsFromEnvPython() {
            assertEquals("python3", PlatformApiCompat.resolveShebangInterpreter("#!/usr/bin/env python3\nprint('hi')"));
        }

        @Test
        void extractsFromEnvNode() {
            assertEquals("node", PlatformApiCompat.resolveShebangInterpreter("#!/usr/bin/env node\nconsole.log('hi')"));
        }

        @Test
        void extractsFromDirectPath() {
            assertEquals("bash", PlatformApiCompat.resolveShebangInterpreter("#!/bin/bash\necho hello"));
        }

        @Test
        void extractsFromUsrBinPerl() {
            assertEquals("perl", PlatformApiCompat.resolveShebangInterpreter("#!/usr/bin/perl\nuse strict;"));
        }

        @Test
        void extractsShWithArgs() {
            assertEquals("sh", PlatformApiCompat.resolveShebangInterpreter("#!/bin/sh -e\nset -x"));
        }

        @Test
        void extractsEnvWithArgs() {
            assertEquals("python", PlatformApiCompat.resolveShebangInterpreter("#!/usr/bin/env python -u\nprint()"));
        }

        @Test
        void extractsFromLocalBin() {
            assertEquals("ruby", PlatformApiCompat.resolveShebangInterpreter("#!/usr/local/bin/ruby\nputs 'hi'"));
        }

        @Test
        void shebangOnlyNoNewline() {
            assertEquals("bash", PlatformApiCompat.resolveShebangInterpreter("#!/bin/bash"));
        }
    }

    // ── resolveShebangLanguageId ──────────────────────────────

    @Nested
    class ResolveShebangLanguageId {

        @Test
        void returnsNullForNonShebang() {
            assertNull(PlatformApiCompat.resolveShebangLanguageId("just text"));
        }

        @ParameterizedTest
        @CsvSource({
            "'#!/usr/bin/env python3\n',Python",
            "'#!/usr/bin/env python\n',Python",
            "'#!/usr/bin/env node\n',JavaScript",
            "'#!/usr/bin/env deno\n',JavaScript",
            "'#!/usr/bin/env bun\n',JavaScript",
            "'#!/bin/bash\n',Shell Script",
            "'#!/bin/sh\n',Shell Script",
            "'#!/usr/bin/env zsh\n',Shell Script",
            "'#!/usr/bin/ruby\n',Ruby",
            "'#!/usr/bin/perl\n',Perl",
            "'#!/usr/bin/php\n',PHP",
            "'#!/usr/bin/env groovy\n',Groovy",
            "'#!/usr/bin/env lua\n',Lua",
            "'#!/usr/bin/env Rscript\n',R",
        })
        void mapsKnownInterpreters(String text, String expectedLangId) {
            assertEquals(expectedLangId, PlatformApiCompat.resolveShebangLanguageId(text));
        }

        @Test
        void returnsNullForUnknownInterpreter() {
            assertNull(PlatformApiCompat.resolveShebangLanguageId("#!/usr/bin/env unknown-lang\n"));
        }
    }

    // ── SHEBANG_LANG_MAP ─────────────────────────────────────

    @Nested
    class ShebangLangMap {

        @Test
        void containsExpectedEntries() {
            var map = PlatformApiCompat.SHEBANG_LANG_MAP;
            assertEquals(14, map.size());
            assertEquals("Python", map.get("python"));
            assertEquals("Python", map.get("python3"));
            assertEquals("JavaScript", map.get("node"));
            assertEquals("JavaScript", map.get("deno"));
            assertEquals("JavaScript", map.get("bun"));
            assertEquals("Shell Script", map.get("bash"));
            assertEquals("Shell Script", map.get("sh"));
            assertEquals("Shell Script", map.get("zsh"));
            assertEquals("Ruby", map.get("ruby"));
            assertEquals("Perl", map.get("perl"));
            assertEquals("PHP", map.get("php"));
            assertEquals("Groovy", map.get("groovy"));
            assertEquals("Lua", map.get("lua"));
        }

        @Test
        void rscriptMapsToR() {
            assertEquals("R", PlatformApiCompat.SHEBANG_LANG_MAP.get("Rscript"));
        }

        @Test
        void doesNotContainUnexpectedKeys() {
            assertNull(PlatformApiCompat.SHEBANG_LANG_MAP.get("java"));
            assertNull(PlatformApiCompat.SHEBANG_LANG_MAP.get("gcc"));
        }
    }

    // ── detectMarkupLanguageId ────────────────────────────────

    @Nested
    class DetectMarkupLanguageId {

        @Test
        void detectsJsonObject() {
            assertEquals("JSON", PlatformApiCompat.detectMarkupLanguageId("{\"key\": \"value\"}", "{\"key\": \"value\"}"));
        }

        @Test
        void detectsJsonArray() {
            assertEquals("JSON", PlatformApiCompat.detectMarkupLanguageId("[\"item\"]", "[\"item\"]"));
        }

        @Test
        void requiresQuoteInFirst512ForJson() {
            assertNull(PlatformApiCompat.detectMarkupLanguageId("{no quotes}", "{no quotes}"));
        }

        @Test
        void detectsXmlDeclaration() {
            assertEquals("XML", PlatformApiCompat.detectMarkupLanguageId("<?xml version=\"1.0\"?>", "<?xml version=\"1.0\"?>"));
        }

        @Test
        void detectsDoctype() {
            assertEquals("XML", PlatformApiCompat.detectMarkupLanguageId("<!DOCTYPE svg>", "<!DOCTYPE svg>"));
        }

        @Test
        void detectsHtmlTag() {
            assertEquals("HTML", PlatformApiCompat.detectMarkupLanguageId("<html lang=\"en\">", "<html lang=\"en\">"));
        }

        @Test
        void detectsHtmlDoctype() {
            assertEquals("HTML", PlatformApiCompat.detectMarkupLanguageId("<!doctype html>", "<!doctype html>"));
        }

        @Test
        void detectsYamlFrontMatter() {
            assertEquals("yaml", PlatformApiCompat.detectMarkupLanguageId("---\ntitle: Test", "---\ntitle: Test"));
        }

        @Test
        void returnsNullForPlainText() {
            assertNull(PlatformApiCompat.detectMarkupLanguageId("Hello, world!", "Hello, world!"));
        }
    }

    // ── detectSqlOrJavaFamilyId ───────────────────────────────

    @Nested
    class DetectSqlOrJavaFamilyId {

        @ParameterizedTest
        @ValueSource(strings = {"SELECT ", "INSERT ", "CREATE TABLE", "ALTER TABLE", "DROP "})
        void detectsSqlKeywords(String prefix) {
            assertEquals("SQL", PlatformApiCompat.detectSqlOrJavaFamilyId(prefix + "foo", prefix.toLowerCase() + "foo"));
        }

        @Test
        void detectsJavaPackage() {
            String code = "package com.example; public class Foo {}";
            assertEquals("JAVA", PlatformApiCompat.detectSqlOrJavaFamilyId(code.toUpperCase(), code));
        }

        @Test
        void detectsKotlinByFun() {
            String code = "package com.example; fun main() {}";
            assertEquals("kotlin", PlatformApiCompat.detectSqlOrJavaFamilyId(code.toUpperCase(), code));
        }

        @Test
        void detectsKotlinByVal() {
            String code = "package com.example; val x = 42;";
            assertEquals("kotlin", PlatformApiCompat.detectSqlOrJavaFamilyId(code.toUpperCase(), code));
        }

        @Test
        void requiresSemicolonForPackageDetection() {
            String code = "package com.example";
            assertNull(PlatformApiCompat.detectSqlOrJavaFamilyId(code.toUpperCase(), code));
        }

        @Test
        void returnsNullForUnknown() {
            assertNull(PlatformApiCompat.detectSqlOrJavaFamilyId("UNKNOWN TEXT", "unknown text"));
        }
    }

    // ── detectScriptingLanguageId ─────────────────────────────

    @Nested
    class DetectScriptingLanguageId {

        @Test
        void detectsPythonDefColon() {
            assertEquals("Python", PlatformApiCompat.detectScriptingLanguageId("def main():\n    pass"));
        }

        @Test
        void detectsPythonImportWithoutBraces() {
            assertEquals("Python", PlatformApiCompat.detectScriptingLanguageId("import os\nimport sys"));
        }

        @Test
        void doesNotDetectJsImport() {
            // "import { foo } from 'bar'" has braces → not Python
            assertNull(PlatformApiCompat.detectScriptingLanguageId("import { foo } from 'bar'"));
        }

        @Test
        void doesNotDetectJsFromImport() {
            // "import x from 'y'" has "from '" → not Python
            assertNull(PlatformApiCompat.detectScriptingLanguageId("import x from 'module'"));
        }

        @Test
        void detectsKotlinFunBrace() {
            assertEquals("kotlin", PlatformApiCompat.detectScriptingLanguageId("fun main() {\n    println(\"hi\")\n}"));
        }

        @Test
        void doesNotDetectFunWithSemicolonAsKotlin() {
            // "fun" + "{" + ";" → not Kotlin (could be Java/C)
            assertNull(PlatformApiCompat.detectScriptingLanguageId("fun main() { return; }"));
        }

        @Test
        void detectsGoPackageMain() {
            assertEquals("go", PlatformApiCompat.detectScriptingLanguageId("package main\n\nimport \"fmt\""));
        }

        @Test
        void detectsGoFunc() {
            assertEquals("go", PlatformApiCompat.detectScriptingLanguageId("func main() {\n\tfmt.Println(\"hi\")\n}"));
        }

        @Test
        void detectsRustFnLet() {
            assertEquals("Rust", PlatformApiCompat.detectScriptingLanguageId("fn main() {\n    let x = 5;\n}"));
        }

        @Test
        void detectsRustFnPub() {
            assertEquals("Rust", PlatformApiCompat.detectScriptingLanguageId("pub fn process() {\n}"));
        }

        @Test
        void returnsNullForUnknown() {
            assertNull(PlatformApiCompat.detectScriptingLanguageId("just plain text here"));
        }

        @Test
        void returnsNullForEmptyString() {
            assertNull(PlatformApiCompat.detectScriptingLanguageId(""));
        }
    }

    // ── detectLanguageIdViaPatterns (integration) ─────────────

    @Nested
    class DetectLanguageIdViaPatterns {

        @Test
        void detectsJsonFromFullContent() {
            assertEquals("JSON", PlatformApiCompat.detectLanguageIdViaPatterns("{\"name\": \"test\"}"));
        }

        @Test
        void detectsXmlFromFullContent() {
            assertEquals("XML", PlatformApiCompat.detectLanguageIdViaPatterns("<?xml version=\"1.0\"?>\n<root/>"));
        }

        @Test
        void detectsSqlFromFullContent() {
            assertEquals("SQL", PlatformApiCompat.detectLanguageIdViaPatterns("SELECT * FROM users WHERE id = 1"));
        }

        @Test
        void detectsPythonFromFullContent() {
            assertEquals("Python", PlatformApiCompat.detectLanguageIdViaPatterns("def hello():\n    print('hi')"));
        }

        @Test
        void detectsGoFromFullContent() {
            assertEquals("go", PlatformApiCompat.detectLanguageIdViaPatterns("package main\n\nfunc main() {}"));
        }

        @Test
        void detectsRustFromFullContent() {
            assertEquals("Rust", PlatformApiCompat.detectLanguageIdViaPatterns("fn main() {\n    let x = 42;\n}"));
        }

        @Test
        void returnsNullForUnknown() {
            assertNull(PlatformApiCompat.detectLanguageIdViaPatterns("Hello, this is just plain text."));
        }

        @Test
        void stripsLeadingWhitespace() {
            assertEquals("JSON", PlatformApiCompat.detectLanguageIdViaPatterns("   {\"key\": true}"));
        }

        @Test
        void markupPrioritizedOverPatterns() {
            // YAML front matter takes precedence even if content looks like Python
            assertEquals("yaml", PlatformApiCompat.detectLanguageIdViaPatterns("---\ndef foo():\n  pass"));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\n\n"})
        void handlesBlankishInput(String input) {
            if (input == null) return; // detectLanguageIdViaPatterns has @NotNull
            assertNull(PlatformApiCompat.detectLanguageIdViaPatterns(input));
        }
    }
}
