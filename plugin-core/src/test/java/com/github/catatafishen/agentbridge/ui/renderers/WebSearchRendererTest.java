package com.github.catatafishen.agentbridge.ui.renderers;

import org.junit.jupiter.api.Test;

import javax.swing.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WebSearchRendererTest {

    @Test
    void renderNormalizesStructuredResults() throws Exception {
        JPanel panel = (JPanel) WebSearchRenderer.INSTANCE.render(
            "{\"query\":\"find things\",\"results\":[{\"title\":\"Title ] name\",\"url\":\"https://example.com/path(with)\",\"snippet\":\"Line 1\\nLine 2\"}]}"
        );

        assertNotNull(panel);
        assertEquals(3, panel.getComponentCount());

        JPanel section = (JPanel) panel.getComponent(2);
        JPanel row = (JPanel) section.getComponent(0);
        Object titleComponent = row.getComponent(0);
        JLabel urlLabel = (JLabel) row.getComponent(1);
        JLabel snippetLabel = (JLabel) section.getComponent(1);

        assertEquals("HyperlinkLabel", titleComponent.getClass().getSimpleName());
        assertEquals("Title ] name", titleComponent.getClass().getMethod("getText").invoke(titleComponent));
        assertEquals("https://example.com/path(with)", urlLabel.getText());
        assertEquals("Line 1 Line 2", snippetLabel.getText());
    }
}
