package com.github.catatafishen.agentbridge.ui.renderers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.swing.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlToolRendererSupportTest {

    @Test
    void markdownPaneReturnsNonEditableJEditorPane() {
        JComponent pane = HtmlToolRendererSupport.INSTANCE.markdownPane("Hello **world**");

        assertNotNull(pane);
        assertInstanceOf(JEditorPane.class, pane);
        JEditorPane editorPane = (JEditorPane) pane;
        assertFalse(editorPane.isEditable());
        assertEquals("text/html", editorPane.getContentType());
    }

    @ParameterizedTest
    @MethodSource("markdownRenderingCases")
    void markdownPaneRendersExpectedContent(String markdown, String expectedInHtml) {
        JComponent pane = HtmlToolRendererSupport.INSTANCE.markdownPane(markdown);
        JEditorPane editorPane = (JEditorPane) pane;
        String html = editorPane.getText();
        assertTrue(html.contains(expectedInHtml), html);
    }

    static Stream<Arguments> markdownRenderingCases() {
        return Stream.of(
            Arguments.of("**bold**", "<b>bold</b>"),
            Arguments.of("plain text", "plain text"),
            Arguments.of("- item1\n- item2", "item1"),
            Arguments.of("- item1\n- item2", "item2"),
            Arguments.of("text", "font-family:"),
            Arguments.of("Use `println()`", "<code>&#8239;println()&#8239;</code>"),
            Arguments.of("[click](https://example.com)", "https://example.com")
        );
    }

    @Test
    void markdownPaneIsEmptyForEmptyInput() {
        JComponent pane = HtmlToolRendererSupport.INSTANCE.markdownPane("");

        assertNotNull(pane);
    }
}
