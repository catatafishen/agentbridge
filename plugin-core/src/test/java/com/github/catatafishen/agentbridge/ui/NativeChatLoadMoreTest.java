package com.github.catatafishen.agentbridge.ui;

import com.github.catatafishen.agentbridge.bridge.EntryData;

import javax.swing.AbstractButton;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

/**
 * "Load more" inserts older history above the chat that is shown, keeping every prompt with its own reply, and
 * leaves a reply that is streaming meanwhile alone.
 */
public class NativeChatLoadMoreTest extends NativeMarkdownPaneTestBase {

    private final List<NativeChatPanel> panels = new ArrayList<>();

    @Override
    protected void tearDown() throws Exception {
        try {
            panels.forEach(NativeChatPanel::clear);
            panels.clear();
        } finally {
            super.tearDown();
        }
    }

    public void testLoadedHistoryReadsAsIfItHadBeenLoadedAtOnce() {
        List<EntryData> older = List.of(
            new EntryData.SessionSeparator(at(0), "OpenCode"),
            new EntryData.Prompt("first question", at(0)),
            new EntryData.ToolCall("Read the build file", null, "read", "plugins { java }", "completed"),
            new EntryData.Text("first answer", at(0)),
            new EntryData.TurnStats("t1", 3000),
            new EntryData.Prompt("second question", at(1)),
            new EntryData.Text("second answer", at(1)),
            new EntryData.TurnStats("t2", 4000));
        List<EntryData> newest = List.of(
            new EntryData.Prompt("third question", at(2)),
            new EntryData.Text("third answer", at(2)));
        NativeChatPanel allAtOnce = newPanel();
        List<EntryData> all = new ArrayList<>(older);
        all.addAll(newest);
        allAtOnce.appendEntries(all, -1);

        NativeChatPanel loadedLater = newPanel();
        loadedLater.appendEntries(newest, -1);
        loadedLater.showLoadMore(2);
        loadedLater.prependEntries(older);

        List<String> expected = new ArrayList<>();
        expected.add("button: ▲ Load 2 more messages");
        expected.addAll(rows(allAtOnce));
        assertEquals(expected, rows(loadedLater));
    }

    public void testLoadingHistoryWhileAReplyStreamsKeepsTheReplyInOneBubble() {
        NativeChatPanel panel = newPanel();
        panel.addPromptEntry("live question", null, null);
        panel.appendText("Partial ");
        panel.showLoadMore(1);

        panel.prependEntries(List.of(
            new EntryData.Prompt("old question", at(0)),
            new EntryData.Text("old answer", at(0))));
        panel.appendText("answer.");

        assertEquals(List.of("old question", "old answer", "live question", "Partial answer."), texts(panel));
    }

    /** After a tool completes, the reply's next text starts a new bubble; loading history must not undo that. */
    public void testLoadingHistoryAfterALiveToolCallKeepsTheNextTextInANewBubble() {
        NativeChatPanel panel = newPanel();
        panel.addPromptEntry("live question", null, null);
        panel.appendText("Before the tool.");
        panel.addToolCallEntry("live-tool", "Read the build file", null, "read", false);
        panel.updateToolCall("live-tool", "completed",
            new ChatPanelApi.ToolCallUpdate("plugins { java }", null, null, false, null, null, null));
        panel.showLoadMore(1);

        panel.prependEntries(List.of(
            new EntryData.Prompt("old question", at(0)),
            new EntryData.ToolCall("List files", null, "read", "src", "completed"),
            new EntryData.Text("old answer", at(0))));
        panel.appendText("After the tool.");

        assertEquals(List.of("old question", "old answer", "live question", "Before the tool.", "After the tool."),
            texts(panel));
    }

    private NativeChatPanel newPanel() {
        NativeChatPanel panel = new NativeChatPanel(getProject());
        panels.add(panel);
        return panel;
    }

    /** A timestamp {@code minute} minutes into the test conversation, so each turn shows its own. */
    private static String at(int minute) {
        return String.format("2026-11-26T10:%02d:00Z", minute);
    }

    /** Every bubble's text, label and button in the chat, top to bottom. */
    private static List<String> rows(NativeChatPanel panel) {
        List<String> out = new ArrayList<>();
        collect(((ChatPanelApi) panel).getComponent(), out, false);
        return out;
    }

    private static List<String> texts(NativeChatPanel panel) {
        List<String> out = new ArrayList<>();
        collect(((ChatPanelApi) panel).getComponent(), out, true);
        return out;
    }

    private static void collect(Component c, List<String> out, boolean textsOnly) {
        if (!c.isVisible()) return;
        if (c instanceof NativeMarkdownPane pane) {
            out.add(textsOnly ? pane.getRawText() : "text: " + pane.getRawText());
            return;
        }
        if (!textsOnly && c instanceof JLabel label && label.getText() != null && !label.getText().isBlank()) {
            out.add("label: " + label.getText());
        }
        if (!textsOnly && c instanceof AbstractButton button && button.getText() != null && !button.getText().isBlank()) {
            out.add("button: " + button.getText());
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) collect(child, out, textsOnly);
        }
    }
}
