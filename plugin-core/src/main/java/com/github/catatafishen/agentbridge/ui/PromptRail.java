package com.github.catatafishen.agentbridge.ui;

import com.intellij.openapi.util.text.HtmlBuilder;
import com.intellij.openapi.util.text.HtmlChunk;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A column between the chat and its scrollbar with a dot for each prompt: hover previews it, click scrolls to it.
 * It is also the chat's registry of prompt rows; permission decisions and ask-user answers are not prompts.
 */
public final class PromptRail extends JComponent {

    /** The rail replaces the chat's right margin, so it is wide enough to keep the dots clear of the chat. */
    private static final int RAIL_WIDTH = 20;
    private static final int DOT_SIZE = 6;
    private static final int MARKED_DOT_SIZE = 8;
    private static final int INSET = 10;
    private static final int MIN_GAP = 12;
    private static final int HIT_REACH = 6;
    private static final int PREVIEW_LINES = 3;
    private static final int PREVIEW_CHARS = 240;
    private static final int TOOLTIP_WRAP_CHARS = 60;
    private static final int TOOLTIP_WIDTH = 300;
    private static final int MIN_PROMPTS_SHOWN = 2;

    private record Entry(@NotNull String entryId, @NotNull JComponent row, @NotNull String text) {
    }

    /** An entry with its row's y in the chat content, for one paint or mouse event. */
    private record Placed(@NotNull Entry entry, int y) {
    }

    private final transient Map<String, Entry> entries = new LinkedHashMap<>();
    private final JViewport viewport;
    private final transient Supplier<Color> markColor;
    private boolean railEnabled;
    /** The entry id of the dot under the pointer, or null. An id, so prompts added meanwhile do not move it. */
    private @Nullable String hoveredId;

    /**
     * @param viewport    the chat's viewport; its view is the content the prompt rows live in
     * @param markColor   the color of the current prompt's dot, read at every paint so setting changes apply
     * @param onJump      called with the entry id of the clicked prompt
     * @param railEnabled whether the user wants the rail shown (it still hides with fewer than two prompts)
     */
    public PromptRail(@NotNull JViewport viewport, @NotNull Supplier<Color> markColor,
                      @NotNull Consumer<String> onJump, boolean railEnabled) {
        this.viewport = viewport;
        this.markColor = markColor;
        this.railEnabled = railEnabled;
        // Scrolling, resizing and growing content all change the viewport's state.
        viewport.addChangeListener(e -> repaint());
        // Registers the rail with the tooltip manager; the text comes from getToolTipText(MouseEvent).
        setToolTipText("");
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                Placed target = promptAt(e.getY());
                setHoveredId(target == null ? null : target.entry().entryId());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                setHoveredId(null);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                Placed target = promptAt(e.getY());
                if (target != null) onJump.accept(target.entry().entryId());
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        // The wheel over the rail scrolls the chat, as it does over the chat itself.
        addMouseWheelListener(e -> {
            Component scrollPane = viewport.getParent();
            if (scrollPane != null) scrollPane.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, scrollPane));
        });
        updateVisibility();
    }

    /**
     * Puts the rail in the strip that the viewport border keeps free between the chat and the scrollbar.
     *
     * @param scrollPane the scroll pane whose viewport this rail was created with
     */
    public void installIn(@NotNull JBScrollPane scrollPane) {
        scrollPane.setLayout(new JBScrollPane.Layout() {
            @Override
            public void layoutContainer(Container parent) {
                super.layoutContainer(parent);
                if (!isVisible()) return;
                Rectangle view = viewport.getBounds();
                setBounds(view.x + view.width, view.y, getPreferredSize().width, view.height);
            }
        });
        scrollPane.add(this);
        updateReservedSpace();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // A theme change can switch the scrollbar between drawn over the chat and beside it.
        if (getParent() != null) updateReservedSpace();
    }

    /** Registers a prompt row added to the chat, at any position (older history is inserted above). */
    public void addPrompt(@NotNull String entryId, @NotNull JComponent row, @NotNull String text) {
        entries.put(entryId, new Entry(entryId, row, text));
        updateVisibility();
        repaint();
    }

    /** The row of a registered prompt, or null when the chat has no such prompt. */
    public @Nullable JComponent rowFor(@NotNull String entryId) {
        Entry entry = entries.get(entryId);
        return entry == null ? null : entry.row();
    }

    /** How many registered prompts are in the chat, whether or not the rail is shown. */
    public int promptCount() {
        return placedPrompts().size();
    }

    /** Forgets every prompt, for when the chat is cleared. */
    public void clearPrompts() {
        entries.clear();
        setHoveredId(null);
        updateVisibility();
    }

    public boolean isRailEnabled() {
        return railEnabled;
    }

    public void setRailEnabled(boolean enabled) {
        railEnabled = enabled;
        if (!enabled) setHoveredId(null);
        updateVisibility();
    }

    private void updateVisibility() {
        boolean visible = railEnabled && promptCount() >= MIN_PROMPTS_SHOWN;
        if (visible == isVisible()) return;
        setVisible(visible);
        updateReservedSpace();
    }

    /** Keeps the strip beside the scrollbar as wide as the rail needs, or removes it while the rail is hidden. */
    private void updateReservedSpace() {
        if (!(getParent() instanceof JScrollPane scrollPane)) return;
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        boolean barOverlaysChat = bar != null && !bar.isOpaque();
        int width = PromptRailLayout.reservedWidth(isVisible(), getPreferredSize().width,
            barOverlaysChat, bar == null ? 0 : bar.getPreferredSize().width);
        scrollPane.setViewportBorder(width == 0 ? null : JBUI.Borders.emptyRight(width));
        // The strip already keeps the scrollbar off the chat, so JBViewport must not also inset the chat for it.
        if (bar != null) {
            bar.putClientProperty(JBScrollPane.IGNORE_SCROLLBAR_IN_INSETS, width > 0 && barOverlaysChat ? Boolean.TRUE : null);
        }
        scrollPane.revalidate();
        scrollPane.repaint();
    }

    /** The x, in the rail, of its dots' centers. */
    int dotCenterX() {
        JScrollBar bar = getParent() instanceof JScrollPane scrollPane ? scrollPane.getVerticalScrollBar() : null;
        return PromptRailLayout.dotCenterX(getWidth(), bar != null && !bar.isOpaque(), bar == null ? 0 : bar.getWidth());
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(JBUI.scale(RAIL_WIDTH), 0);
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(UIUtil.getPanelBackground());
        g.fillRect(0, 0, getWidth(), getHeight());
        List<Placed> prompts = placedPrompts();
        if (prompts.isEmpty()) return;
        int[] centers = centers(prompts);
        int current = currentIndex(prompts);
        int dotX = dotCenterX();
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (int i = 0; i < centers.length; i++) {
                boolean hovered = prompts.get(i).entry().entryId().equals(hoveredId);
                boolean marked = i == current || hovered;
                Color color;
                if (hovered) color = UIUtil.getLabelForeground();
                else if (i == current) color = markColor.get();
                else color = UIUtil.getContextHelpForeground();
                int size = JBUI.scale(marked ? MARKED_DOT_SIZE : DOT_SIZE);
                g2.setColor(color);
                g2.fillOval(dotX - size / 2, centers[i] - size / 2, size, size);
            }
        } finally {
            g2.dispose();
        }
    }

    @Override
    public @Nullable String getToolTipText(MouseEvent event) {
        Placed target = promptAt(event.getY());
        if (target == null) return null;
        String preview = PromptRailLayout.preview(target.entry().text(), PREVIEW_LINES, PREVIEW_CHARS);
        HtmlBuilder body = new HtmlBuilder();
        String[] lines = preview.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) body.br();
            body.append(lines[i]);
        }
        String hintColor = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground());
        body.br().append(HtmlChunk.span("color: " + hintColor).addText("Click to jump to this prompt"));
        HtmlChunk content = preview.length() > TOOLTIP_WRAP_CHARS
            ? body.wrapWith(HtmlChunk.div("width: " + JBUI.scale(TOOLTIP_WIDTH) + "px"))
            : body.toFragment();
        return new HtmlBuilder().append(content).wrapWithHtmlBody().toString();
    }

    private void setHoveredId(@Nullable String entryId) {
        if (Objects.equals(entryId, hoveredId)) return;
        hoveredId = entryId;
        setCursor(entryId != null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : null);
        repaint();
    }

    private @Nullable Placed promptAt(int y) {
        List<Placed> prompts = placedPrompts();
        int index = PromptRailLayout.hitTest(centers(prompts), y, JBUI.scale(HIT_REACH));
        return index < 0 ? null : prompts.get(index);
    }

    private int[] centers(@NotNull List<Placed> prompts) {
        Component view = viewport.getView();
        int contentHeight = view == null ? 0 : view.getHeight();
        return PromptRailLayout.dotCenters(ys(prompts), contentHeight, getHeight(),
            JBUI.scale(INSET), JBUI.scale(MIN_GAP));
    }

    private int currentIndex(@NotNull List<Placed> prompts) {
        Component view = viewport.getView();
        if (view == null) return -1;
        return PromptRailLayout.currentIndex(ys(prompts), viewport.getViewPosition().y,
            viewport.getExtentSize().height, view.getHeight());
    }

    private static int[] ys(@NotNull List<Placed> prompts) {
        return prompts.stream().mapToInt(Placed::y).toArray();
    }

    /** The registered prompts still in the chat, top to bottom, with their y in the content. */
    private @NotNull List<Placed> placedPrompts() {
        Component view = viewport.getView();
        List<Placed> placed = new ArrayList<>();
        if (view == null) return placed;
        for (Entry entry : entries.values()) {
            JComponent row = entry.row();
            if (!row.isVisible() || !SwingUtilities.isDescendingFrom(row, view)) continue;
            placed.add(new Placed(entry, SwingUtilities.convertPoint(row, 0, 0, view).y));
        }
        placed.sort(Comparator.comparingInt(Placed::y));
        return placed;
    }
}
