package com.github.catatafishen.agentbridge.ui.renderers;

import org.junit.jupiter.api.Test;

import javax.swing.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebFetchRendererTest {

    @Test
    void renderOmitsHeaderLinesFromBodyWithoutBlankSeparator() {
        JPanel panel = (JPanel) WebFetchRenderer.INSTANCE.render("""
            URL: https://example.com
            Status: 200 OK
            # Body
            """);

        assertNotNull(panel);
        assertEquals(3, panel.getComponentCount());

        JEditorPane bodyPane = (JEditorPane) panel.getComponent(2);
        String html = bodyPane.getText();
        assertFalse(html.contains("URL:"), html);
        assertFalse(html.contains("Status:"), html);
        assertTrue(html.contains("Body"), html);
    }
}
