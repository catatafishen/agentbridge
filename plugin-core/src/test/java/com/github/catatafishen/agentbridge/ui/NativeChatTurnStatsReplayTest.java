package com.github.catatafishen.agentbridge.ui;

import com.github.catatafishen.agentbridge.bridge.EntryData;

import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

/**
 * A restored turn ends at its stats, as it did live: what the agent sent after its turn (a background
 * sub-agent's report) gets its own bubble instead of being glued to the turn's last reply.
 */
public class NativeChatTurnStatsReplayTest extends NativeMarkdownPaneTestBase {

    public void testTextAfterTheTurnStatsStartsANewBubble() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        JComponent root = ((ChatPanelApi) panel).getComponent();
        root.setSize(400, 800);

        panel.appendEntries(List.of(
            new EntryData.Prompt("Count them"),
            new EntryData.Text("I started a sub-agent."),
            new EntryData.TurnStats(
                "t1", 5000, 1000, 2000, 0.05, 3, 0, 0, "gpt-4", "1.5",
                0, 0, 0, 0.0, 0, 0, 0,
                "2026-01-01T10:05:00Z", "stats-1", List.of()),
            new EntryData.Text("DONE 24")
        ), -1);

        List<String> texts = new ArrayList<>();
        collectPaneTexts(root, texts);
        assertTrue("the turn's reply keeps its own bubble: " + texts, texts.contains("I started a sub-agent."));
        assertTrue("the message after the turn gets a new bubble: " + texts, texts.contains("DONE 24"));
    }

    private static void collectPaneTexts(Component c, List<String> out) {
        if (c instanceof NativeMarkdownPane pane) out.add(pane.getRawText());
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectPaneTexts(child, out);
            }
        }
    }
}
