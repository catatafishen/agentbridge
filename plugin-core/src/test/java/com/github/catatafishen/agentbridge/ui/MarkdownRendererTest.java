package com.github.catatafishen.agentbridge.ui;

import kotlin.Pair;
import kotlin.jvm.functions.Function1;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownRendererTest {

    // ── Helpers ──────────────────────────────────────────────────────────

    private static final Function1<String, Pair<String, Integer>> NO_FILE_REF = ref -> null;
    private static final Function1<String, String> NO_FILE_PATH = ref -> null;
    private static final Function1<String, Boolean> NO_GIT = sha -> false;

    /**
     * Render Markdown with no external resolution.
     */
    private String render(String markdown) {
        return MarkdownRenderer.INSTANCE.markdownToHtml(markdown, NO_FILE_REF, NO_FILE_PATH, NO_GIT);
    }

    /**
     * Render Markdown with a custom file-path resolver.
     */
    private String renderWithFilePath(String markdown, Function1<String, String> resolveFilePath) {
        return MarkdownRenderer.INSTANCE.markdownToHtml(markdown, NO_FILE_REF, resolveFilePath, NO_GIT);
    }

    /**
     * Render Markdown with a custom file-reference resolver.
     */
    private String renderWithFileRef(String markdown, Function1<String, Pair<String, Integer>> resolveFileRef) {
        return MarkdownRenderer.INSTANCE.markdownToHtml(markdown, resolveFileRef, NO_FILE_PATH, NO_GIT);
    }

    /**
     * Render Markdown with a git-commit checker.
     */
    private String renderWithGit(String markdown, Function1<String, Boolean> isGitCommit) {
        return MarkdownRenderer.INSTANCE.markdownToHtml(markdown, NO_FILE_REF, NO_FILE_PATH, isGitCommit);
    }

    /**
     * Render with all resolvers active.
     */
    private String renderFull(
        String markdown,
        Function1<String, Pair<String, Integer>> resolveFileRef,
        Function1<String, String> resolveFilePath,
        Function1<String, Boolean> isGitCommit
    ) {
        return MarkdownRenderer.INSTANCE.markdownToHtml(markdown, resolveFileRef, resolveFilePath, isGitCommit);
    }

    // ── Empty / Whitespace ──────────────────────────────────────────────

    @Nested
    class EmptyAndWhitespace {
        @Test
        void emptyInputReturnsEmptyOutput() {
            assertEquals("", render(""));
        }

        @Test
        void singleBlankLineProducesNoOutput() {
            // A single blank line is just an empty trimmed line — produces nothing
            assertEquals("", render(" "));
        }

        @Test
        void multipleBlankLinesProduceNoOutput() {
            assertEquals("", render("\n\n\n"));
        }
    }

    // ── Paragraphs ──────────────────────────────────────────────────────

    @Nested
    class Paragraphs {
        @Test
        void plainTextWrappedInParagraph() {
            String html = render("Hello world");
            assertEquals("<p>Hello world</p>", html);
        }

        @Test
        void twoLinesSeparatedByBlankLineProduceTwoParagraphs() {
            String html = render("First\n\nSecond");
            assertTrue(html.contains("<p>First</p>"));
            assertTrue(html.contains("<p>Second</p>"));
        }

        @Test
        void consecutivePlainLinesEachGetOwnParagraph() {
            String html = render("Line A\nLine B");
            assertTrue(html.contains("<p>Line A</p>"));
            assertTrue(html.contains("<p>Line B</p>"));
        }
    }

    // ── Headings ────────────────────────────────────────────────────────

    @Nested
    class Headings {
        @Test
        void h1MapsToH2() {
            // # heading → level 1 + 1 = h2
            String html = render("# Title");
            assertTrue(html.contains("<h2>Title</h2>"), html);
        }

        @Test
        void h2MapsToH3() {
            String html = render("## Subtitle");
            assertTrue(html.contains("<h3>Subtitle</h3>"), html);
        }

        @Test
        void h3MapsToH4() {
            String html = render("### Section");
            assertTrue(html.contains("<h4>Section</h4>"), html);
        }

        @Test
        void h4MapsToH5() {
            String html = render("#### Subsection");
            assertTrue(html.contains("<h5>Subsection</h5>"), html);
        }

        @Test
        void headingWithInlineCodeFormatsBoth() {
            String html = render("## Use `map()` here");
            assertTrue(html.contains("<h3>"), html);
            assertTrue(html.contains("<code>map()</code>"), html);
            assertTrue(html.contains("</h3>"), html);
        }

        @Test
        void headingWithBoldFormatsBoth() {
            String html = render("# **Important** note");
            assertTrue(html.contains("<h2>"), html);
            assertTrue(html.contains("<b>Important</b>"), html);
        }

        @Test
        void hashWithoutSpaceIsNotHeading() {
            // "#NoSpace" should not be treated as heading
            String html = render("#NoSpace");
            assertFalse(html.contains("<h"), html);
            assertTrue(html.contains("<p>"), html);
        }

        @Test
        void moreThanFourHashesIsNotHeading() {
            String html = render("##### Not a heading");
            assertFalse(html.contains("<h6>"), html);
            // Should be treated as plain text paragraph
            assertTrue(html.contains("<p>"), html);
        }
    }

    // ── Bold ────────────────────────────────────────────────────────────

    @Nested
    class Bold {
        @Test
        void doubleAsterisksCreateBold() {
            String html = render("This is **bold** text");
            assertTrue(html.contains("<b>bold</b>"), html);
        }

        @Test
        void boldAtStartOfLine() {
            String html = render("**Start** of line");
            assertTrue(html.contains("<b>Start</b>"), html);
        }

        @Test
        void boldAtEndOfLine() {
            String html = render("End of **line**");
            assertTrue(html.contains("<b>line</b>"), html);
        }

        @Test
        void multipleBoldInSameLine() {
            String html = render("**one** and **two**");
            assertTrue(html.contains("<b>one</b>"), html);
            assertTrue(html.contains("<b>two</b>"), html);
        }

        @Test
        void boldContainingInlineCode() {
            String html = render("**use `foo()`**");
            assertTrue(html.contains("<b>"), html);
            assertTrue(html.contains("<code>foo()</code>"), html);
            assertTrue(html.contains("</b>"), html);
        }
    }

    // ── Inline Code ─────────────────────────────────────────────────────

    @Nested
    class InlineCode {
        @Test
        void backtickCreatesCodeElement() {
            String html = render("Use `println`");
            assertTrue(html.contains("<code>println</code>"), html);
        }

        @Test
        void htmlInsideInlineCodeIsEscaped() {
            String html = render("Use `<script>alert(1)</script>`");
            assertTrue(html.contains("&lt;script&gt;"), html);
            assertFalse(html.contains("<script>"), html);
        }

        @Test
        void multipleInlineCodesInOneLine() {
            String html = render("Use `foo` and `bar`");
            assertTrue(html.contains("<code>foo</code>"), html);
            assertTrue(html.contains("<code>bar</code>"), html);
        }

        @Test
        void inlineCodeWithFileReferenceResolvesToLink() {
            String html = renderWithFileRef("`MyFile.kt`",
                ref -> "MyFile.kt".equals(ref) ? new Pair<>("/path/MyFile.kt", null) : null);
            assertTrue(html.contains("<a href='openfile:///path/MyFile.kt'>"), html);
            assertTrue(html.contains("<code>MyFile.kt</code>"), html);
        }

        @Test
        void inlineCodeWithFileReferenceAndLineNumber() {
            String html = renderWithFileRef("`MyFile.kt:42`",
                ref -> "MyFile.kt:42".equals(ref) ? new Pair<>("/path/MyFile.kt", 42) : null);
            assertTrue(html.contains("openfile:///path/MyFile.kt:42"), html);
        }

        @Test
        void inlineCodeWithGitShaResolvesToGitShowLink() {
            String html = renderWithGit("`abc1234`", sha -> "abc1234".equals(sha));
            assertTrue(html.contains("<a href='gitshow://abc1234'>"), html);
            assertTrue(html.contains("<code>abc1234</code>"), html);
        }

        @Test
        void inlineCodeWithNonMatchingGitShaDoesNotResolve() {
            String html = renderWithGit("`abc1234`", sha -> false);
            assertFalse(html.contains("gitshow://"), html);
            assertTrue(html.contains("<code>abc1234</code>"), html);
        }

        @Test
        void inlineCodeContentThatIsNotGitShaNotResolved() {
            // "hello" doesn't match GIT_SHA_REGEX (not all hex chars)
            String html = renderWithGit("`hello`", sha -> true);
            assertFalse(html.contains("gitshow://"), html);
            assertTrue(html.contains("<code>hello</code>"), html);
        }
    }

    // ── Code Fences ─────────────────────────────────────────────────────

    @Nested
    class CodeFences {
        @Test
        void simpleFencedCodeBlock() {
            String html = render("```\nfoo\nbar\n```");
            assertTrue(html.contains("<pre><code>"), html);
            assertTrue(html.contains("foo\n"), html);
            assertTrue(html.contains("bar\n"), html);
            assertTrue(html.contains("</code></pre>"), html);
        }

        @Test
        void fencedCodeBlockWithLanguage() {
            String html = render("```java\nSystem.out.println();\n```");
            assertTrue(html.contains("data-lang=\"java\""), html);
        }

        @Test
        void fencedCodeBlockLanguageIsLowercased() {
            String html = render("```Python\nprint()\n```");
            assertTrue(html.contains("data-lang=\"python\""), html);
        }

        @Test
        void htmlInsideCodeBlockIsEscaped() {
            String html = render("```\n<div>alert</div>\n```");
            assertTrue(html.contains("&lt;div&gt;"), html);
            assertFalse(html.contains("<div>"), html);
        }

        @Test
        void unclosedCodeFenceClosedAtEnd() {
            String html = render("```\nstill in code");
            assertTrue(html.contains("<pre><code>"), html);
            assertTrue(html.contains("still in code"), html);
            assertTrue(html.contains("</code></pre>"), html);
        }

        @Test
        void emptyCodeFence() {
            String html = render("```\n```");
            assertTrue(html.contains("<pre><code>"), html);
            assertTrue(html.contains("</code></pre>"), html);
        }

        @Test
        void codeFenceClosesOpenList() {
            String html = render("- item\n```\ncode\n```");
            // The list should be closed before the code block opens
            int ulClose = html.indexOf("</ul>");
            int preOpen = html.indexOf("<pre><code>");
            assertTrue(ulClose < preOpen, "List should close before code block: " + html);
        }
    }

    // ── Implicit Code Blocks ────────────────────────────────────────────

    @Nested
    class ImplicitCodeBlocks {
        @Test
        void lineStartingWithDoubleSlashCreatesCodeBlock() {
            String html = render("// this is a comment");
            assertTrue(html.contains("<pre><code>"), html);
            assertTrue(html.contains("// this is a comment"), html);
        }

        @Test
        void lineStartingWithSlashStarCreatesCodeBlock() {
            String html = render("/* block comment */");
            assertTrue(html.contains("<pre><code>"), html);
        }

        @Test
        void lineStartingWithStarSlashCreatesCodeBlock() {
            String html = render("*/ end comment");
            assertTrue(html.contains("<pre><code>"), html);
        }

        @Test
        void implicitCodeBlockClosedByBlankLine() {
            String html = render("// comment\n\nNormal text");
            assertTrue(html.contains("</code></pre>"), html);
            assertTrue(html.contains("<p>Normal text</p>"), html);
        }

        @Test
        void implicitCodeBlockClosedByHeading() {
            String html = render("// comment\n# Heading");
            assertTrue(html.contains("</code></pre>"), html);
            assertTrue(html.contains("<h2>Heading</h2>"), html);
        }

        @Test
        void implicitCodeBlockClosedByHorizontalRule() {
            String html = render("// comment\n---");
            assertTrue(html.contains("</code></pre>"), html);
            assertTrue(html.contains("<hr>"), html);
        }

        @Test
        void implicitCodeBlockClosedByBlockquote() {
            String html = render("// comment\n> quote");
            assertTrue(html.contains("</code></pre>"), html);
            assertTrue(html.contains("<blockquote>"), html);
        }

        @Test
        void consecutiveCodeLikeLinesStayInSameBlock() {
            String html = render("// line 1\n// line 2");
            // Should be one <pre><code> block, not two
            int firstPre = html.indexOf("<pre><code>");
            int lastPre = html.lastIndexOf("<pre><code>");
            assertEquals(firstPre, lastPre, "Should be a single code block: " + html);
        }

        @Test
        void nonCodeLineContinuesImplicitBlockIfNotMajorElement() {
            // A regular line inside implicit code block continues the block
            String html = render("// comment\nsome other stuff");
            // "some other stuff" should be inside the code block since it's not a major block element
            assertTrue(html.contains("<pre><code>"), html);
            assertTrue(html.contains("some other stuff"), html);
            // It should still be within the code block
            int codeOpen = html.indexOf("<pre><code>");
            int codeClose = html.indexOf("</code></pre>");
            int contentIdx = html.indexOf("some other stuff");
            assertTrue(contentIdx > codeOpen && contentIdx < codeClose,
                "Non-major-element line should remain in implicit code block: " + html);
        }

        @Test
        void implicitCodeBlockClosedAtEndOfInput() {
            String html = render("// trailing code");
            assertTrue(html.contains("</code></pre>"), html);
        }

        @Test
        void implicitCodeBlockEscapesHtml() {
            String html = render("// <script>alert('xss')</script>");
            assertTrue(html.contains("&lt;script&gt;"), html);
            assertFalse(html.contains("<script>"), html);
        }
    }

    // ── Horizontal Rules ────────────────────────────────────────────────

    @Nested
    class HorizontalRules {
        @Test
        void threeDashesCreateHr() {
            String html = render("---");
            assertTrue(html.contains("<hr>"), html);
        }

        @Test
        void manyDashesCreateHr() {
            String html = render("----------");
            assertTrue(html.contains("<hr>"), html);
        }

        @Test
        void threeAsterisksCreateHr() {
            String html = render("***");
            assertTrue(html.contains("<hr>"), html);
        }

        @Test
        void threeUnderscoresCreateHr() {
            String html = render("___");
            assertTrue(html.contains("<hr>"), html);
        }

        @Test
        void twoDashesDoNotCreateHr() {
            String html = render("--");
            assertFalse(html.contains("<hr>"), html);
        }

        @Test
        void hrClosesOpenList() {
            String html = render("- item\n---");
            assertTrue(html.contains("</ul>"), html);
            assertTrue(html.contains("<hr>"), html);
            int ulClose = html.indexOf("</ul>");
            int hr = html.indexOf("<hr>");
            assertTrue(ulClose < hr, "List should close before HR: " + html);
        }

        @Test
        void hrClosesOpenBlockquote() {
            String html = render("> quote\n---");
            assertTrue(html.contains("</blockquote>"), html);
            assertTrue(html.contains("<hr>"), html);
        }
    }

    // ── Blockquotes ─────────────────────────────────────────────────────

    @Nested
    class Blockquotes {
        @Test
        void simpleBlockquote() {
            String html = render("> This is a quote");
            assertTrue(html.contains("<blockquote>"), html);
            assertTrue(html.contains("<p>This is a quote</p>"), html);
            assertTrue(html.contains("</blockquote>"), html);
        }

        @Test
        void multiLineBlockquote() {
            String html = render("> Line 1\n> Line 2");
            assertTrue(html.contains("<blockquote>"), html);
            assertTrue(html.contains("<p>Line 1</p>"), html);
            assertTrue(html.contains("<p>Line 2</p>"), html);
        }

        @Test
        void emptyBlockquoteLine() {
            String html = render(">");
            assertTrue(html.contains("<blockquote>"), html);
            assertTrue(html.contains("</blockquote>"), html);
        }

        @Test
        void blockquoteClosedByNonQuoteLine() {
            String html = render("> quote\nNormal text");
            assertTrue(html.contains("</blockquote>"), html);
            assertTrue(html.contains("<p>Normal text</p>"), html);
        }

        @Test
        void blockquoteWithInlineFormatting() {
            String html = render("> Use **bold** and `code`");
            assertTrue(html.contains("<b>bold</b>"), html);
            assertTrue(html.contains("<code>code</code>"), html);
        }

        @Test
        void blockquoteClosedAtEndOfInput() {
            String html = render("> trailing quote");
            assertTrue(html.contains("</blockquote>"), html);
        }

        @Test
        void blockquoteClosesOpenList() {
            String html = render("- item\n> quote");
            int ulClose = html.indexOf("</ul>");
            int bqOpen = html.indexOf("<blockquote>");
            assertTrue(ulClose >= 0 && bqOpen >= 0, html);
            assertTrue(ulClose < bqOpen, "List should close before blockquote: " + html);
        }
    }

    // ── Unordered Lists ─────────────────────────────────────────────────

    @Nested
    class Lists {
        @Test
        void dashListItem() {
            String html = render("- Item one");
            assertTrue(html.contains("<ul>"), html);
            assertTrue(html.contains("<li>Item one</li>"), html);
            assertTrue(html.contains("</ul>"), html);
        }

        @Test
        void asteriskListItem() {
            String html = render("* Item one");
            assertTrue(html.contains("<ul>"), html);
            assertTrue(html.contains("<li>Item one</li>"), html);
        }

        @Test
        void multipleListItems() {
            String html = render("- One\n- Two\n- Three");
            assertTrue(html.contains("<li>One</li>"), html);
            assertTrue(html.contains("<li>Two</li>"), html);
            assertTrue(html.contains("<li>Three</li>"), html);
            // Should be one list
            assertEquals(html.indexOf("<ul>"), html.lastIndexOf("<ul>"),
                "Should be a single <ul>: " + html);
        }

        @Test
        void listWithInlineCode() {
            String html = render("- Use `foo()`");
            assertTrue(html.contains("<li>"), html);
            assertTrue(html.contains("<code>foo()</code>"), html);
        }

        @Test
        void listWithBold() {
            String html = render("- **Important** item");
            assertTrue(html.contains("<li>"), html);
            assertTrue(html.contains("<b>Important</b>"), html);
        }

        @Test
        void listClosedByNonListLine() {
            String html = render("- item\nParagraph");
            assertTrue(html.contains("</ul>"), html);
            assertTrue(html.contains("<p>Paragraph</p>"), html);
        }

        @Test
        void listClosedAtEndOfInput() {
            String html = render("- trailing item");
            assertTrue(html.contains("</ul>"), html);
        }

        @Test
        void listWithLinks() {
            String html = render("- [click](https://example.com)");
            assertTrue(html.contains("<li>"), html);
            assertTrue(html.contains("<a href='https://example.com'>click</a>"), html);
        }
    }

    // ── Tables ──────────────────────────────────────────────────────────

    @Nested
    class Tables {
        @Test
        void simpleTable() {
            String md = "| Name | Age |\n| --- | --- |\n| Alice | 30 |";
            String html = render(md);
            assertTrue(html.contains("<table>"), html);
            assertTrue(html.contains("<th>Name</th>"), html);
            assertTrue(html.contains("<th>Age</th>"), html);
            assertTrue(html.contains("<td>Alice</td>"), html);
            assertTrue(html.contains("<td>30</td>"), html);
            assertTrue(html.contains("</table>"), html);
        }

        @Test
        void tableSeparatorRowIsSkipped() {
            String md = "| H1 | H2 |\n| --- | --- |\n| D1 | D2 |";
            String html = render(md);
            // Separator row should not produce a <tr>
            // We should have exactly 2 <tr> (header + one data row)
            int count = countOccurrences(html, "<tr>");
            assertEquals(2, count, "Expected 2 rows (header + data): " + html);
        }

        @Test
        void tableWithInlineFormatting() {
            String md = "| **Bold** | `code` |\n|---|---|\n| normal | normal |";
            String html = render(md);
            assertTrue(html.contains("<b>Bold</b>"), html);
            assertTrue(html.contains("<code>code</code>"), html);
        }

        @Test
        void tableClosedByNonTableLine() {
            String md = "| H1 | H2 |\n|---|---|\n| D1 | D2 |\nParagraph after";
            String html = render(md);
            assertTrue(html.contains("</table>"), html);
            assertTrue(html.contains("<p>Paragraph after</p>"), html);
        }

        @Test
        void tableClosedAtEndOfInput() {
            String md = "| H1 | H2 |\n| --- | --- |\n| D1 | D2 |";
            String html = render(md);
            assertTrue(html.contains("</table>"), html);
        }

        @Test
        void tableClosesOpenList() {
            String md = "- item\n| H1 | H2 |\n|---|---|\n| D1 | D2 |";
            String html = render(md);
            int ulClose = html.indexOf("</ul>");
            int tableOpen = html.indexOf("<table>");
            assertTrue(ulClose < tableOpen, "List should close before table: " + html);
        }

        @Test
        void lineThatLooksLikeTableButHasTooFewPipesIsNotTable() {
            // Need at least 3 pipes to be a table row
            String html = render("| only two |");
            // Should not be treated as table since count of | is only 2
            assertFalse(html.contains("<table>"), html);
        }

        @Test
        void tableWithSeparatorOnlyDoesNotCrash() {
            String md = "| H1 | H2 |\n| :---: | :---: |";
            String html = render(md);
            assertTrue(html.contains("<table>"), html);
            assertTrue(html.contains("<th>"), html);
        }
    }

    // ── Links ───────────────────────────────────────────────────────────

    @Nested
    class Links {
        @Test
        void markdownLinkToHttpUrl() {
            String html = render("[OpenAI](https://openai.com)");
            assertTrue(html.contains("<a href='https://openai.com'>OpenAI</a>"), html);
        }

        @Test
        void markdownLinkToHttpsUrl() {
            String html = render("[Google](https://google.com)");
            assertTrue(html.contains("<a href='https://google.com'>Google</a>"), html);
        }

        @Test
        void bareUrlAutoLinked() {
            String html = render("Visit https://example.com for info");
            assertTrue(html.contains("<a href='https://example.com'>https://example.com</a>"), html);
        }

        @Test
        void bareHttpUrlAutoLinked() {
            String html = render("Visit http://example.com for info");
            assertTrue(html.contains("<a href='http://example.com'>http://example.com</a>"), html);
        }

        @Test
        void markdownLinkWithOpenFileProtocol() {
            String html = render("[file](openfile:///path/to/file.kt)");
            assertTrue(html.contains("<a href='openfile:///path/to/file.kt'>file</a>"), html);
        }

        @Test
        void markdownLinkWithGitShowProtocol() {
            String html = render("[commit](gitshow://abc1234)");
            assertTrue(html.contains("<a href='gitshow://abc1234'>commit</a>"), html);
        }

        @Test
        void markdownLinkResolvedAsFileReference() {
            String html = renderWithFileRef("[MyClass](MyClass.kt)",
                ref -> "MyClass.kt".equals(ref) ? new Pair<>("/src/MyClass.kt", null) : null);
            assertTrue(html.contains("openfile:///src/MyClass.kt"), html);
        }

        @Test
        void markdownLinkResolvedAsFileReferenceWithLine() {
            String html = renderWithFileRef("[MyClass](MyClass.kt:10)",
                ref -> "MyClass.kt:10".equals(ref) ? new Pair<>("/src/MyClass.kt", 10) : null);
            assertTrue(html.contains("openfile:///src/MyClass.kt:10"), html);
        }

        @Test
        void markdownLinkResolvedViaFilePath() {
            String html = renderWithFilePath("[file](file:///src/Main.kt)",
                ref -> "/src/Main.kt".equals(ref) ? "/resolved/Main.kt" : null);
            assertTrue(html.contains("openfile:///resolved/Main.kt"), html);
        }

        @Test
        void markdownLinkResolvedAsGitCommit() {
            String html = renderWithGit("[fix](abcdef1234)", sha -> "abcdef1234".equals(sha));
            assertTrue(html.contains("gitshow://abcdef1234"), html);
        }

        @Test
        void markdownLinkWithUnresolvableTargetRendersRawText() {
            String html = render("[label](not-a-url-or-file)");
            // Should fall through to the else branch and render as plain text
            assertTrue(html.contains("[label]"), html);
            assertTrue(html.contains("not-a-url-or-file"), html);
        }

        @Test
        void codexStyleAbsoluteFileMarkdownLinkResolvesToOpenFileAnchor() {
            String path = "/home/user/project/src/main/MyFile.kt";
            String html = renderWithFilePath("[" + path + "](" + path + ")",
                ref -> path.equals(ref) ? path : null);
            assertTrue(html.contains("<a href='openfile://" + path + "'>" + path + "</a>"), html);
        }

        @Test
        void multipleLinkTypesInOneLine() {
            String html = render("[a](https://a.com) and https://b.com");
            assertTrue(html.contains("<a href='https://a.com'>a</a>"), html);
            assertTrue(html.contains("<a href='https://b.com'>https://b.com</a>"), html);
        }
    }

    // ── File Path Detection ─────────────────────────────────────────────

    @Nested
    class FilePathDetection {
        @Test
        void absoluteFilePathResolvedInPlainText() {
            String html = renderWithFilePath("See /src/main/Foo.kt for details",
                ref -> "/src/main/Foo.kt".equals(ref) ? "/src/main/Foo.kt" : null);
            assertTrue(html.contains("<a href='openfile:///src/main/Foo.kt'>/src/main/Foo.kt</a>"), html);
        }

        @Test
        void relativeFilePathResolvedInPlainText() {
            String html = renderWithFilePath("See src/main/Foo.kt for details",
                ref -> "src/main/Foo.kt".equals(ref) ? "/abs/src/main/Foo.kt" : null);
            assertTrue(html.contains("openfile:///abs/src/main/Foo.kt"), html);
        }

        @Test
        void filePathWithLineNumber() {
            String html = renderWithFilePath("See /src/Foo.kt:42 for details",
                ref -> "/src/Foo.kt".equals(ref) ? "/src/Foo.kt" : null);
            assertTrue(html.contains("openfile:///src/Foo.kt:42"), html);
        }

        @Test
        void filePathNotResolvedStaysAsPlainText() {
            String html = renderWithFilePath("See /src/main/Foo.kt for details",
                ref -> null);
            assertFalse(html.contains("<a "), html);
            assertTrue(html.contains("/src/main/Foo.kt"), html);
        }

        @Test
        void dotRelativeFilePath() {
            String html = renderWithFilePath("Edit ./src/Foo.kt now",
                ref -> "./src/Foo.kt".equals(ref) ? "/abs/src/Foo.kt" : null);
            assertTrue(html.contains("openfile:///abs/src/Foo.kt"), html);
        }

        @Test
        void dotDotRelativeFilePath() {
            String html = renderWithFilePath("Check ../lib/Bar.java here",
                ref -> "../lib/Bar.java".equals(ref) ? "/abs/lib/Bar.java" : null);
            assertTrue(html.contains("openfile:///abs/lib/Bar.java"), html);
        }
    }

    // ── Git SHA Detection ───────────────────────────────────────────────

    @Nested
    class GitShaDetection {
        @Test
        void bareGitShaInTextResolvesToLink() {
            String html = renderWithGit("Fixed in abc1234 commit", sha -> "abc1234".equals(sha));
            assertTrue(html.contains("<a href='gitshow://abc1234'"), html);
            assertTrue(html.contains("class='git-commit-link'"), html);
        }

        @Test
        void bareGitShaNotResolvedStaysAsText() {
            String html = renderWithGit("Fixed in abc1234 commit", sha -> false);
            assertFalse(html.contains("gitshow://"), html);
            assertTrue(html.contains("abc1234"), html);
        }

        @Test
        void gitShaMaxLength() {
            // 12 chars is within BARE_GIT_SHA_REGEX range
            String sha = "abcdef123456";
            String html = renderWithGit("Commit " + sha, s -> sha.equals(s));
            assertTrue(html.contains("gitshow://" + sha), html);
        }

        @Test
        void thirteenCharHexNotMatchedByBareRegex() {
            // BARE_GIT_SHA_REGEX matches 7-12 chars only
            String sha = "abcdef1234567";
            String html = renderWithGit("Commit " + sha, s -> true);
            assertFalse(html.contains("gitshow://" + sha), html);
        }
    }

    // ── HTML Escaping / XSS Prevention ──────────────────────────────────

    @Nested
    class Escaping {
        @Test
        void anglesEscapedInParagraph() {
            String html = render("<b>not bold</b>");
            assertTrue(html.contains("&lt;b&gt;"), html);
            assertFalse(html.contains("<b>not bold</b>"), html);
        }

        @Test
        void ampersandEscaped() {
            String html = render("A & B");
            assertTrue(html.contains("A &amp; B"), html);
        }

        @Test
        void quotesEscaped() {
            String html = render("He said \"hello\"");
            assertTrue(html.contains("&quot;hello&quot;"), html);
        }

        @Test
        void scriptTagInParagraphEscaped() {
            String html = render("<script>alert('xss')</script>");
            assertFalse(html.contains("<script>"), html);
            assertTrue(html.contains("&lt;script&gt;"), html);
        }

        @Test
        void htmlInHeadingEscaped() {
            String html = render("# <img src=x onerror=alert(1)>");
            assertFalse(html.contains("<img"), html);
            assertTrue(html.contains("&lt;img"), html);
        }

        @Test
        void htmlInListItemEscaped() {
            String html = render("- <div>xss</div>");
            assertFalse(html.contains("<div>"), html);
            assertTrue(html.contains("&lt;div&gt;"), html);
        }

        @Test
        void htmlInBlockquoteEscaped() {
            String html = render("> <iframe src=evil>");
            assertFalse(html.contains("<iframe"), html);
            assertTrue(html.contains("&lt;iframe"), html);
        }

        @Test
        void htmlInCodeFenceEscaped() {
            String html = render("```\n<div onclick=\"evil()\">\n```");
            assertFalse(html.contains("<div"), html);
            assertTrue(html.contains("&lt;div"), html);
        }

        @Test
        void htmlInTableCellsEscaped() {
            String md = "| <script> | normal |\n|---|---|\n| data | data |";
            String html = render(md);
            assertFalse(html.contains("<script>"), html);
            assertTrue(html.contains("&lt;script&gt;"), html);
        }

        @Test
        void codeFenceLanguageIsEscaped() {
            String html = render("```<script>\ncode\n```");
            assertTrue(html.contains("data-lang=\"&lt;script&gt;\""), html);
            assertFalse(html.contains("data-lang=\"<script>\""), html);
        }
    }

    // ── Block Transitions & Closing ─────────────────────────────────────

    @Nested
    class BlockTransitions {
        @Test
        void listFollowedByTable() {
            String md = "- item\n| H1 | H2 |\n|---|---|\n| A | B |";
            String html = render(md);
            assertTrue(html.contains("</ul>"), html);
            assertTrue(html.contains("<table>"), html);
        }

        @Test
        void tableFollowedByList() {
            String md = "| H1 | H2 |\n|---|---|\n| D1 | D2 |\n- item";
            String html = render(md);
            assertTrue(html.contains("</table>"), html);
            assertTrue(html.contains("<ul>"), html);
        }

        @Test
        void headingClosesOpenBlockquote() {
            String md = "> quote\n# Heading";
            String html = render(md);
            int bqClose = html.indexOf("</blockquote>");
            int h2Open = html.indexOf("<h2>");
            assertTrue(bqClose < h2Open, "Blockquote should close before heading: " + html);
        }

        @Test
        void headingClosesOpenList() {
            String md = "- item\n# Heading";
            String html = render(md);
            int ulClose = html.indexOf("</ul>");
            int h2Open = html.indexOf("<h2>");
            assertTrue(ulClose < h2Open, "List should close before heading: " + html);
        }

        @Test
        void codeFenceClosesBlockquote() {
            // Code fence triggers closeListAndTable via handleCodeFence,
            // but blockquote is closed separately. Let's verify.
            String md = "> quote\n```\ncode\n```";
            String html = render(md);
            assertTrue(html.contains("</blockquote>"), html);
            assertTrue(html.contains("<pre><code>"), html);
        }

        @Test
        void multipleBlockTypesInSequence() {
            String md = "# Title\n\n- item1\n- item2\n\n> quote\n\n---\n\n| H1 | H2 |\n|---|---|\n| D1 | D2 |\n\n```\ncode\n```\n\nFinal paragraph";
            String html = render(md);
            assertTrue(html.contains("<h2>Title</h2>"), html);
            assertTrue(html.contains("<li>item1</li>"), html);
            assertTrue(html.contains("<li>item2</li>"), html);
            assertTrue(html.contains("<blockquote>"), html);
            assertTrue(html.contains("<hr>"), html);
            assertTrue(html.contains("<table>"), html);
            assertTrue(html.contains("<pre><code>"), html);
            assertTrue(html.contains("<p>Final paragraph</p>"), html);
        }

        @Test
        void allBlocksClosedAtEndOfInput() {
            // Open multiple blocks and ensure they're all closed
            // List still open at end
            String html1 = render("- item");
            assertTrue(html1.contains("</ul>"), html1);

            // Table still open at end
            String html2 = render("| H1 | H2 |\n|---|---|\n| D1 | D2 |");
            assertTrue(html2.contains("</table>"), html2);

            // Blockquote still open at end
            String html3 = render("> quote");
            assertTrue(html3.contains("</blockquote>"), html3);

            // Code fence still open at end
            String html4 = render("```\ncode");
            assertTrue(html4.contains("</code></pre>"), html4);
        }
    }

    // ── Combined / Complex Formatting ───────────────────────────────────

    @Nested
    class CombinedFormatting {
        @Test
        void boldInsideListItem() {
            String html = render("- This is **very** important");
            assertTrue(html.contains("<li>This is <b>very</b> important</li>"), html);
        }

        @Test
        void codeInsideListItem() {
            String html = render("- Run `npm install` first");
            assertTrue(html.contains("<li>Run <code>npm install</code> first</li>"), html);
        }

        @Test
        void linkInsideListItem() {
            String html = render("- Visit [docs](https://docs.com)");
            assertTrue(html.contains("<li>Visit <a href='https://docs.com'>docs</a></li>"), html);
        }

        @Test
        void boldInsideHeading() {
            String html = render("## The **key** concept");
            assertTrue(html.contains("<h3>The <b>key</b> concept</h3>"), html);
        }

        @Test
        void linkInsideHeading() {
            String html = render("# See [here](https://example.com)");
            assertTrue(html.contains("<h2>"), html);
            assertTrue(html.contains("<a href='https://example.com'>here</a>"), html);
        }

        @Test
        void boldInsideBlockquote() {
            String html = render("> This is **quoted bold**");
            assertTrue(html.contains("<blockquote>"), html);
            assertTrue(html.contains("<b>quoted bold</b>"), html);
        }

        @Test
        void inlineCodeInsideBlockquote() {
            String html = render("> Use `println()`");
            assertTrue(html.contains("<blockquote>"), html);
            assertTrue(html.contains("<code>println()</code>"), html);
        }

        @Test
        void boldAndCodeInSameParagraph() {
            String html = render("Use **bold** and `code` together");
            assertTrue(html.contains("<b>bold</b>"), html);
            assertTrue(html.contains("<code>code</code>"), html);
        }

        @Test
        void linkAndBoldInSameParagraph() {
            String html = render("Check **this** at [link](https://x.com)");
            assertTrue(html.contains("<b>this</b>"), html);
            assertTrue(html.contains("<a href='https://x.com'>link</a>"), html);
        }

        @Test
        void tableWithBoldAndCode() {
            String md = "| **Header** | `code` |\n|---|---|\n| **bold** | `val` |";
            String html = render(md);
            assertTrue(html.contains("<b>Header</b>"), html);
            assertTrue(html.contains("<code>code</code>"), html);
            assertTrue(html.contains("<b>bold</b>"), html);
            assertTrue(html.contains("<code>val</code>"), html);
        }

        @Test
        void fullDocumentRender() {
            String md = String.join("\n",
                "# Project Setup",
                "",
                "Follow these steps:",
                "",
                "- Install **Java 17**",
                "- Run `gradle build`",
                "- Check [docs](https://docs.example.com)",
                "",
                "> Note: this is a **warning**",
                "",
                "---",
                "",
                "## Code Example",
                "",
                "```kotlin",
                "fun main() {",
                "    println(\"Hello\")",
                "}",
                "```",
                "",
                "| Feature | Status |",
                "| --- | --- |",
                "| Build | Done |",
                "",
                "That's all!"
            );
            String html = render(md);
            assertTrue(html.contains("<h2>Project Setup</h2>"), html);
            assertTrue(html.contains("<li>Install <b>Java 17</b></li>"), html);
            assertTrue(html.contains("<li>Run <code>gradle build</code></li>"), html);
            assertTrue(html.contains("<a href='https://docs.example.com'>docs</a>"), html);
            assertTrue(html.contains("<blockquote>"), html);
            assertTrue(html.contains("<b>warning</b>"), html);
            assertTrue(html.contains("<hr>"), html);
            assertTrue(html.contains("<h3>Code Example</h3>"), html);
            assertTrue(html.contains("data-lang=\"kotlin\""), html);
            assertTrue(html.contains("<table>"), html);
            assertTrue(html.contains("<th>Feature</th>"), html);
            assertTrue(html.contains("<td>Build</td>"), html);
            assertTrue(html.contains("<p>That&#39;s all!</p>") || html.contains("<p>That&apos;s all!</p>") || html.contains("<p>That's all!</p>"), html);
        }
    }

    // ── MarkdownState Data Class ────────────────────────────────────────

    @Nested
    class MarkdownStateTests {
        @Test
        void defaultStateAllFalse() {
            MarkdownRenderer.MarkdownState state = new MarkdownRenderer.MarkdownState(
                false, false, true, false, false, false);
            assertFalse(state.getInCode());
            assertFalse(state.getInTable());
            assertTrue(state.getFirstTR());
            assertFalse(state.getInList());
            assertFalse(state.getInBlockquote());
            assertFalse(state.getInImplicitCode());
        }

        @Test
        void stateFieldsAreMutable() {
            MarkdownRenderer.MarkdownState state = new MarkdownRenderer.MarkdownState(
                false, false, true, false, false, false);
            state.setInCode(true);
            assertTrue(state.getInCode());
            state.setInTable(true);
            assertTrue(state.getInTable());
            state.setFirstTR(false);
            assertFalse(state.getFirstTR());
            state.setInList(true);
            assertTrue(state.getInList());
            state.setInBlockquote(true);
            assertTrue(state.getInBlockquote());
            state.setInImplicitCode(true);
            assertTrue(state.getInImplicitCode());
        }
    }

    // ── Full Resolution Integration ─────────────────────────────────────

    @Nested
    class FullResolutionIntegration {
        @Test
        void inlineCodeFileRefAndBarePathResolveBothWays() {
            String md = "See `Config.kt` and /src/main/Config.kt";
            String html = renderFull(md,
                ref -> "Config.kt".equals(ref) ? new Pair<>("/abs/Config.kt", null) : null,
                ref -> "/src/main/Config.kt".equals(ref) ? "/src/main/Config.kt" : null,
                sha -> false);
            assertTrue(html.contains("<a href='openfile:///abs/Config.kt'><code>Config.kt</code></a>"), html);
            assertTrue(html.contains("<a href='openfile:///src/main/Config.kt'>/src/main/Config.kt</a>"), html);
        }

        @Test
        void markdownLinkFallsThroughFromFileRefToFilePath() {
            // resolveFileReference returns null, but resolveFilePath resolves
            String html = renderFull("[config](file:///src/Config.kt)",
                ref -> null,
                ref -> "/src/Config.kt".equals(ref) ? "/resolved/Config.kt" : null,
                sha -> false);
            assertTrue(html.contains("openfile:///resolved/Config.kt"), html);
        }

        @Test
        void markdownLinkFallsThroughToGitCommit() {
            String sha = "abcdef12";
            String html = renderFull("[fix](" + sha + ")",
                ref -> null,
                ref -> null,
                s -> sha.equals(s));
            assertTrue(html.contains("gitshow://" + sha), html);
        }
    }

    // ── Edge Cases ──────────────────────────────────────────────────────

    @Nested
    class EdgeCases {
        @Test
        void singleAsteriskNotBold() {
            // * is a list item prefix with space, but without space it's just text
            String html = render("Use *italics* maybe");
            // The renderer doesn't handle single asterisk italics
            // so it should be plain text in a paragraph
            assertTrue(html.contains("<p>"), html);
        }

        @Test
        void emptyCodeFenceLanguage() {
            String html = render("```\ncode\n```");
            assertTrue(html.contains("<pre><code>"), html);
            assertFalse(html.contains("data-lang"), html);
        }

        @Test
        void codeInsideFenceNotProcessedAsMarkdown() {
            String html = render("```\n# Not a heading\n**Not bold**\n- Not a list\n```");
            // Inside code fence, everything is escaped and literal
            assertFalse(html.contains("<h"), html.replaceAll("<pre><code[^>]*>", ""));
            assertFalse(html.contains("<b>"), html);
            assertFalse(html.contains("<li>"), html);
        }

        @Test
        void consecutiveCodeFences() {
            String html = render("```\nblock1\n```\n```\nblock2\n```");
            // Should produce two separate code blocks
            int firstClose = html.indexOf("</code></pre>");
            int secondOpen = html.indexOf("<pre><code>", firstClose);
            assertTrue(secondOpen > firstClose, "Should have two separate code blocks: " + html);
        }

        @Test
        void tableWithOnlyHeaderNoData() {
            String md = "| H1 | H2 |";
            String html = render(md);
            assertTrue(html.contains("<table>"), html);
            assertTrue(html.contains("<th>H1</th>"), html);
            assertTrue(html.contains("</table>"), html);
        }

        @Test
        void whitespaceTrimmedForBlockElements() {
            // Lines are trimmed before processing
            String html = render("   # Heading   ");
            assertTrue(html.contains("<h2>Heading</h2>"), html);
        }

        @Test
        void specialCharactersInInlineCode() {
            String html = render("Use `a < b && c > d`");
            assertTrue(html.contains("<code>a &lt; b &amp;&amp; c &gt; d</code>"), html);
        }

        @Test
        void emptyListItemContentTrimsToJustDash() {
            // "- " trims to "-" which doesn't match "- " prefix, so it becomes a paragraph
            String html = render("- ");
            assertTrue(html.contains("<p>"), html);
        }

        @Test
        void hrAfterDashListDoesNotConflict() {
            // "---" is both HR and potentially a list item start ambiguity
            String html = render("Text\n\n---\n\nMore text");
            assertTrue(html.contains("<hr>"), html);
        }

        @Test
        void multipleHorizontalRulesInARow() {
            String html = render("---\n***\n___");
            assertEquals(3, countOccurrences(html, "<hr>"), html);
        }

        @Test
        void emptyBlockquoteContent() {
            String html = render("> ");
            assertTrue(html.contains("<blockquote>"), html);
            assertTrue(html.contains("</blockquote>"), html);
        }
    }

    @Nested
    class XmlTagPreprocessing {
        @Test
        void thinkTagRendersAsThinkingBlock() {
            String html = render("Before\n<think>Step 1\nStep 2</think>\nAfter");
            assertTrue(html.contains("<thinking-block><div class=\"thinking-content\">Step 1<br>Step 2</div></thinking-block>"), html);
            assertTrue(html.contains("<p>Before</p>"), html);
            assertTrue(html.contains("<p>After</p>"), html);
        }

        @Test
        void thinkingTagRendersAsThinkingBlock() {
            String html = render("<thinking>Reasoning</thinking>");
            assertTrue(html.contains("<thinking-block><div class=\"thinking-content\">Reasoning</div></thinking-block>"), html);
        }

        @Test
        void unclosedThinkTagFallsBackToEscapedText() {
            String html = render("<think>\nunfinished");
            assertTrue(html.contains("&lt;think&gt;"), html);
            assertFalse(html.contains("<thinking-block>"), html);
        }

        @Test
        void wrapperTagsAreStrippedFromStandaloneLines() {
            String html = render(String.join("\n",
                "<task_result>",
                "<commentary>",
                "Result body",
                "</commentary>",
                "</task_result>",
                "<example>",
                "Example body",
                "</example>",
                "<code>",
                "Code wrapper body",
                "</code>"
            ));
            assertFalse(html.contains("task_result"), html);
            assertFalse(html.contains("commentary"), html);
            assertFalse(html.contains("example"), html);
            assertFalse(html.contains("&lt;code&gt;"), html);
            assertTrue(html.contains("<p>Result body</p>"), html);
            assertTrue(html.contains("<p>Example body</p>"), html);
            assertTrue(html.contains("<p>Code wrapper body</p>"), html);
        }

        @Test
        void thinkTagInsideFencedCodeIsPreserved() {
            String html = render(String.join("\n",
                "```md",
                "<think>Reasoning</think>",
                "<task_result>",
                "```"
            ));
            assertTrue(html.contains("&lt;think&gt;Reasoning&lt;/think&gt;"), html);
            assertTrue(html.contains("&lt;task_result&gt;"), html);
            assertFalse(html.contains("<thinking-block>"), html);
        }

        @Test
        void tagsInsideImplicitCodeArePreserved() {
            String html = render(String.join("\n",
                "// start",
                "<think>Reasoning</think>",
                "<task_result>"
            ));
            assertTrue(html.contains("&lt;think&gt;Reasoning&lt;/think&gt;"), html);
            assertTrue(html.contains("&lt;task_result&gt;"), html);
            assertFalse(html.contains("<thinking-block>"), html);
        }

        @Test
        void emptyThinkTagUsesFallbackText() {
            String html = render("<think>\r\n\r\n</think>");
            assertTrue(html.contains("No reasoning returned"), html);
        }
    }

    // ── Utility ─────────────────────────────────────────────────────────

    private static int countOccurrences(String str, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
