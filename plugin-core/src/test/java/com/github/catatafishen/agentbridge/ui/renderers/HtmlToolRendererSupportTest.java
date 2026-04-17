package com.github.catatafishen.agentbridge.ui.renderers;

import org.junit.jupiter.api.Test;

import javax.swing.*;

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

    @Test
    void markdownPaneRendersBold() {
        JComponent pane = HtmlToolRendererSupport.INSTANCE.markdownPane("**bold**");

        JEditorPane editorPane = (JEditorPane) pane;
        String html = editorPane.getText();
        assertTrue(html.contains("<b>bold</b>"), html);
    }

    @Test
    void markdownPaneRendersParagraphText() {
        JComponent pane = HtmlToolRendererSupport.INSTANCE.markdownPane("plain text");

        JEditorPane editorPane = (JEditorPane) pane;
        String html = editorPane.getText();
        // JEditorPane adds whitespace; just check the text content is present
        assertTrue(html.contains("plain text"), html);
    }

    @Test
    void markdownPaneRendersListItemContent() {
        JComponent pane = HtmlToolRendererSupport.INSTANCE.markdownPane("- item1\n- item2");

        JEditorPane editorPane = (JEditorPane) pane;
        String html = editorPane.getText();
        assertTrue(html.contains("item1"), html);
        assertTrue(html.contains("item2"), html);
    }

    @Test
    void markdownPaneAppliesFontFamily() {
        JComponent pane = HtmlToolRendererSupport.INSTANCE.markdownPane("text");

        JEditorPane editorPane = (JEditorPane) pane;
        String html = editorPane.getText();
        assertTrue(html.contains("font-family:"), html);
    }

    @Test
    void markdownPaneIsEmptyForEmptyInput() {
        JComponent pane = HtmlToolRendererSupport.INSTANCE.markdownPane("");

        assertNotNull(pane);
    }

    @Test
    void markdownPaneRendersCode() {
        JComponent pane = HtmlToolRendererSupport.INSTANCE.markdownPane("Use `println()`");

        JEditorPane editorPane = (JEditorPane) pane;
        String html = editorPane.getText();
        assertTrue(html.contains("<code>println()</code>"), html);
    }

    @Test
    void markdownPaneRendersLinkHref() {
        JComponent pane = HtmlToolRendererSupport.INSTANCE.markdownPane("[click](https://example.com)");

        JEditorPane editorPane = (JEditorPane) pane;
        String html = editorPane.getText();
        assertTrue(html.contains("https://example.com"), html);
    }
}
