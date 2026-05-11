package com.github.catatafishen.agentbridge.ui.review;

import com.github.catatafishen.agentbridge.psi.review.AgentEditHighlighter;
import com.github.catatafishen.agentbridge.psi.review.AgentEditSession;
import com.github.catatafishen.agentbridge.psi.review.RevertReasonDialog;
import com.github.catatafishen.agentbridge.psi.review.ReviewItem;
import com.github.catatafishen.agentbridge.psi.review.ReviewSessionTopic;
import com.github.catatafishen.agentbridge.settings.McpServerSettings;
import com.github.catatafishen.agentbridge.ui.util.SidePanelFooter;
import com.github.catatafishen.agentbridge.ui.util.TimestampDisplayFormatter;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Side panel listing files the agent has touched in the current {@link AgentEditSession}.
 * <p>
 * Files are split into two sections — "Pending" and "Approved" — connected by a
 * {@link OnePixelSplitter}. Each section uses a {@link JBList} with a compact row
 * renderer showing file-type icon, status-colored filename, and animated diff counts.
 * Approve/unapprove/revert actions are available via right-click context menu.
 */
public final class DiffPanel extends JPanel implements Disposable {

    private static final String ACTION_OPEN_FILE = "openFile";
    private static final String ACTION_REMOVE_APPROVED = "removeApproved";

    private static final JBColor DIFF_GREEN = new JBColor(new Color(0, 128, 0), new Color(80, 200, 80));
    private static final JBColor DIFF_RED = new JBColor(new Color(200, 0, 0), new Color(255, 80, 80));

    private static final JBColor STATUS_ADDED = new JBColor(new Color(0x00, 0x61, 0x00), new Color(0x57, 0xAB, 0x5A));
    private static final JBColor STATUS_MODIFIED = new JBColor(new Color(0x08, 0x69, 0xDA), new Color(0x58, 0xA6, 0xFF));
    private static final JBColor STATUS_DELETED = new JBColor(new Color(0x6E, 0x77, 0x81), new Color(0x8B, 0x94, 0x9E));

    private static final JBColor APPROVED_BG = new JBColor(
        new Color(0, 120, 0, 90), new Color(80, 200, 80, 90));

    private final transient Project project;

    private final DefaultListModel<ReviewItem> pendingModel = new DefaultListModel<>();
    private final DefaultListModel<ReviewItem> approvedModel = new DefaultListModel<>();
    private final JBList<ReviewItem> pendingList;
    private final JBList<ReviewItem> approvedList;

    private final JBLabel pendingHeader;
    private final JBLabel approvedHeader;
    private final JBLabel diffTotalsLabel;

    private final transient ReviewDiffCountAnimator diffCountAnimator;
    private final Timer diffAnimationTimer;

    public DiffPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;

        diffCountAnimator = new ReviewDiffCountAnimator();

        pendingList = createReviewList(pendingModel);
        approvedList = createReviewList(approvedModel);
        configurePendingListActions();
        configureApprovedListActions();

        pendingHeader = createSectionHeader("Pending");
        approvedHeader = createSectionHeader("Approved");

        diffAnimationTimer = new Timer(33, e -> {
            long now = System.currentTimeMillis();
            pendingList.repaint();
            approvedList.repaint();
            if (!diffCountAnimator.hasActiveAnimations(now)) {
                ((Timer) e.getSource()).stop();
            }
        });
        diffAnimationTimer.setRepeats(true);

        OnePixelSplitter splitter = new OnePixelSplitter(true, 0.6f);
        splitter.setFirstComponent(createSectionPanel(pendingHeader, pendingList));
        splitter.setSecondComponent(createSectionPanel(approvedHeader, approvedList));

        ActionToolbar toolbar = createToolbar();
        toolbar.setTargetComponent(this);

        diffTotalsLabel = new JBLabel();
        diffTotalsLabel.setBorder(JBUI.Borders.emptyRight(8));
        JPanel toolbarFooter = SidePanelFooter.createToolbarFooter(toolbar, diffTotalsLabel);

        add(splitter, BorderLayout.CENTER);
        add(toolbarFooter, BorderLayout.SOUTH);

        project.getMessageBus().connect(this).subscribe(
            ReviewSessionTopic.TOPIC,
            () -> ApplicationManager.getApplication().invokeLater(this::refresh));

        refresh();
    }

    @Override
    public void dispose() {
        diffAnimationTimer.stop();
        diffCountAnimator.clear();
    }

    public void refresh() {
        AgentEditSession session = AgentEditSession.getInstance(project);
        List<ReviewItem> allItems = session.getReviewItems();
        long now = System.currentTimeMillis();
        diffCountAnimator.sync(allItems, now);

        List<ReviewItem> pending = new ArrayList<>();
        List<ReviewItem> approved = new ArrayList<>();
        for (ReviewItem item : allItems) {
            if (item.approved()) approved.add(item);
            else pending.add(item);
        }

        String selectedPendingPath = selectedPath(pendingList);
        String selectedApprovedPath = selectedPath(approvedList);

        syncModel(pendingModel, pending);
        syncModel(approvedModel, approved);

        restoreSelection(pendingList, pendingModel, selectedPendingPath);
        restoreSelection(approvedList, approvedModel, selectedApprovedPath);

        pendingHeader.setText(sectionTitle("Pending", pending.size()));
        approvedHeader.setText(sectionTitle("Approved", approved.size()));

        updateDiffTotals(allItems);
        updateDiffAnimationTimer(now);

        revalidate();
        repaint();
    }

    private static @Nullable String selectedPath(@NotNull JBList<ReviewItem> list) {
        ReviewItem item = list.getSelectedValue();
        return item != null ? item.path() : null;
    }

    private static void syncModel(@NotNull DefaultListModel<ReviewItem> model,
                                  @NotNull List<ReviewItem> items) {
        model.clear();
        for (ReviewItem item : items) {
            model.addElement(item);
        }
    }

    private static void restoreSelection(@NotNull JBList<ReviewItem> list,
                                         @NotNull DefaultListModel<ReviewItem> model,
                                         @Nullable String path) {
        if (path == null) return;
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i).path().equals(path)) {
                list.setSelectedIndex(i);
                return;
            }
        }
    }

    private static @NotNull String sectionTitle(@NotNull String label, int count) {
        return count > 0 ? label + " (" + count + ")" : label;
    }

    private @NotNull JBList<ReviewItem> createReviewList(@NotNull DefaultListModel<ReviewItem> model) {
        JBList<ReviewItem> list = new JBList<>(model) {
            @Override
            public String getToolTipText(MouseEvent e) {
                ReviewItem item = itemAtPoint(this, e);
                if (item == null) return null;
                String tip = item.relativePath()
                    + (item.approved() ? " · Approved" : " · Pending review");
                if (item.lastEditedMillis() > 0) {
                    tip += " · " + TimestampDisplayFormatter.formatEpochMillis(item.lastEditedMillis());
                }
                return tip;
            }
        };
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        list.setCellRenderer(new FileRowRenderer());
        list.setExpandableItemsEnabled(false);
        list.getEmptyText().clear();
        ToolTipManager.sharedInstance().registerComponent(list);
        return list;
    }

    private static @NotNull JBLabel createSectionHeader(@NotNull String title) {
        JBLabel label = new JBLabel(title);
        label.setBorder(JBUI.Borders.empty(4, 8, 4, 8));
        label.setForeground(UIUtil.getLabelInfoForeground());
        label.setFont(UIUtil.getLabelFont(UIUtil.FontSize.SMALL).deriveFont(Font.BOLD));
        return label;
    }

    private static @NotNull JPanel createSectionPanel(@NotNull JBLabel header,
                                                      @NotNull JBList<ReviewItem> list) {
        JBScrollPane scrollPane = new JBScrollPane(list);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(header, BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private void configurePendingListActions() {
        pendingList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    ReviewItem item = itemAtPoint(pendingList, e);
                    if (item != null) navigateToFile(item);
                } else if (e.isPopupTrigger()) {
                    showPendingContextMenu(e);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) showPendingContextMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) showPendingContextMenu(e);
            }
        });

        InputMap im = pendingList.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap am = pendingList.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), ACTION_OPEN_FILE);
        am.put(ACTION_OPEN_FILE, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                ReviewItem item = pendingList.getSelectedValue();
                if (item != null) navigateToFile(item);
            }
        });
    }

    private void configureApprovedListActions() {
        approvedList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    ReviewItem item = itemAtPoint(approvedList, e);
                    if (item != null) navigateToFile(item);
                } else if (e.isPopupTrigger()) {
                    showApprovedContextMenu(e);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) showApprovedContextMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) showApprovedContextMenu(e);
            }
        });

        InputMap im = approvedList.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap am = approvedList.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), ACTION_OPEN_FILE);
        am.put(ACTION_OPEN_FILE, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                ReviewItem item = approvedList.getSelectedValue();
                if (item != null) navigateToFile(item);
            }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), ACTION_REMOVE_APPROVED);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), ACTION_REMOVE_APPROVED);
        am.put(ACTION_REMOVE_APPROVED, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                ReviewItem item = approvedList.getSelectedValue();
                if (item != null) {
                    AgentEditSession.getInstance(project).removeApproved(item.path());
                }
            }
        });
    }

    private void showPendingContextMenu(@NotNull MouseEvent e) {
        ReviewItem item = itemAtPoint(pendingList, e);
        if (item == null) return;

        JPopupMenu menu = new JPopupMenu();
        JMenuItem approveItem = new JMenuItem("Approve", AllIcons.Actions.Checked);
        approveItem.addActionListener(ev ->
            AgentEditSession.getInstance(project).acceptFile(item.path()));
        menu.add(approveItem);
        menu.addSeparator();
        JMenuItem revertItem = new JMenuItem("Revert…", AllIcons.Actions.Rollback);
        revertItem.addActionListener(ev -> showRevertDialog(item));
        menu.add(revertItem);
        menu.show(pendingList, e.getX(), e.getY());
    }

    private void showApprovedContextMenu(@NotNull MouseEvent e) {
        ReviewItem item = itemAtPoint(approvedList, e);
        if (item == null) return;

        JPopupMenu menu = new JPopupMenu();
        JMenuItem unapproveItem = new JMenuItem("Unapprove", AllIcons.Actions.Rollback);
        unapproveItem.addActionListener(ev ->
            AgentEditSession.getInstance(project).unapproveFile(item.path()));
        menu.add(unapproveItem);
        JMenuItem removeItem = new JMenuItem("Remove from list", AllIcons.Actions.Close);
        removeItem.addActionListener(ev ->
            AgentEditSession.getInstance(project).removeApproved(item.path()));
        menu.add(removeItem);
        menu.show(approvedList, e.getX(), e.getY());
    }

    private static @Nullable ReviewItem itemAtPoint(@NotNull JBList<ReviewItem> list,
                                                    @NotNull MouseEvent e) {
        int index = list.locationToIndex(e.getPoint());
        if (index < 0) return null;
        Rectangle bounds = list.getCellBounds(index, index);
        if (bounds == null || !bounds.contains(e.getPoint())) return null;
        return list.getModel().getElementAt(index);
    }

    private void navigateToFile(@NotNull ReviewItem item) {
        if (item.status() == ReviewItem.Status.DELETED) return;
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(item.path());
        if (vf == null) return;
        FileEditorManager.getInstance(project).openFile(vf, true);
        AgentEditHighlighter.getInstance(project).flashForNavigation(vf);
    }

    private void showRevertDialog(@NotNull ReviewItem item) {
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(item.path());
        if (vf == null) return;
        AgentEditSession session = AgentEditSession.getInstance(project);
        RevertReasonDialog dialog = new RevertReasonDialog(
            project, vf, item.relativePath(), session.isGateActive());
        if (!dialog.showAndGet()) return;
        AgentEditSession.RevertGateAction gateAction = switch (dialog.getResult()) {
            case CONTINUE_REVIEWING -> AgentEditSession.RevertGateAction.CONTINUE_REVIEWING;
            case SEND_NOW -> AgentEditSession.RevertGateAction.SEND_NOW;
            default -> AgentEditSession.RevertGateAction.DEFAULT;
        };
        session.revertFile(item.path(), dialog.getReason(), gateAction);
    }

    private void updateDiffAnimationTimer(long now) {
        if (diffCountAnimator.hasActiveAnimations(now)) {
            if (!diffAnimationTimer.isRunning()) diffAnimationTimer.start();
        } else {
            diffAnimationTimer.stop();
        }
    }

    private void updateDiffTotals(@NotNull List<ReviewItem> items) {
        int added = 0;
        int removed = 0;
        for (ReviewItem item : items) {
            added += item.linesAdded();
            removed += item.linesRemoved();
        }
        if (added == 0 && removed == 0) {
            diffTotalsLabel.setText("");
        } else {
            StringBuilder sb = new StringBuilder("<html><font size='-2'>");
            if (added > 0) sb.append(colorSpan(DIFF_GREEN, "+" + added));
            if (removed > 0) {
                if (added > 0) sb.append(" ");
                sb.append(colorSpan(DIFF_RED, "-" + removed));
            }
            sb.append("</font></html>");
            diffTotalsLabel.setText(sb.toString());
        }
    }

    private @NotNull ActionToolbar createToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new AutoApproveToggleAction(project));
        group.add(new AutoCleanOnNewPromptToggleAction(project));
        group.add(new ShowEditorHighlightsToggleAction(project));
        group.addSeparator();
        group.add(new DumbAwareAction("Clean Approved",
            "Remove all approved rows from the list", AllIcons.Actions.GC) {
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                AgentEditSession.getInstance(project).removeAllApproved();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(!approvedModel.isEmpty());
            }
        });
        return ActionManager.getInstance().createActionToolbar("ReviewChangesToolbar", group, true);
    }

    private static final class AutoApproveToggleAction extends ToggleAction
        implements com.intellij.openapi.actionSystem.ex.CustomComponentAction {
        private final Project project;

        AutoApproveToggleAction(@NotNull Project project) {
            super("Auto-Approve", "Apply agent edits without per-file approval", AllIcons.Actions.Checked);
            this.project = project;
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return McpServerSettings.getInstance(project).isAutoApproveAgentEdits();
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            McpServerSettings.getInstance(project).setAutoApproveAgentEdits(state);
            if (state) AgentEditSession.getInstance(project).onAutoApproveTurnedOn();
            project.getMessageBus().syncPublisher(ReviewSessionTopic.TOPIC).reviewStateChanged();
        }

        @Override
        public @NotNull JComponent createCustomComponent(
            @NotNull com.intellij.openapi.actionSystem.Presentation presentation,
            @NotNull String place) {
            return new com.intellij.openapi.actionSystem.impl.ActionButton(
                this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE) {
                @Override
                protected void paintButtonLook(Graphics g) {
                    if (isSelected()) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(APPROVED_BG);
                        int arc = JBUI.scale(4);
                        g2.fillRoundRect(2, 2, getWidth() - 4, getHeight() - 4, arc, arc);
                        g2.dispose();
                        Icon icon = presentation.getIcon();
                        if (icon != null) {
                            int x = (getWidth() - icon.getIconWidth()) / 2;
                            int y = (getHeight() - icon.getIconHeight()) / 2;
                            icon.paintIcon(this, g, x, y);
                        }
                    } else {
                        super.paintButtonLook(g);
                    }
                }
            };
        }
    }

    private static final class AutoCleanOnNewPromptToggleAction extends ToggleAction {
        private final Project project;

        AutoCleanOnNewPromptToggleAction(@NotNull Project project) {
            super("Auto-Clean on New Prompt",
                "Remove approved rows automatically when starting a new prompt",
                AllIcons.Actions.ClearCash);
            this.project = project;
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return McpServerSettings.getInstance(project).isAutoCleanReviewOnNewPrompt();
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            McpServerSettings.getInstance(project).setAutoCleanReviewOnNewPrompt(state);
        }
    }

    private static final class ShowEditorHighlightsToggleAction extends ToggleAction {
        private final Project project;

        ShowEditorHighlightsToggleAction(@NotNull Project project) {
            super("Show Editor Highlights",
                "Paint agent-edit background colors in the editor. Disable when git diff colors are sufficient",
                AllIcons.Actions.Highlighting);
            this.project = project;
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return McpServerSettings.getInstance(project).isShowEditorHighlights();
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            McpServerSettings.getInstance(project).setShowEditorHighlights(state);
            AgentEditHighlighter highlighter = AgentEditHighlighter.getInstance(project);
            if (state) {
                highlighter.refreshAll();
            } else {
                highlighter.clearAll();
            }
        }
    }

    /**
     * Compact row renderer: file-type icon + status-colored filename + animated diff counts.
     * Context menus handle approve/unapprove/revert actions, so no inline action buttons.
     */
    private final class FileRowRenderer extends JPanel implements ListCellRenderer<ReviewItem> {
        private final JLabel fileIconLabel = new JLabel();
        private final SimpleColoredComponent fileText = new SimpleColoredComponent();
        private final SimpleColoredComponent diffCountsText = new SimpleColoredComponent();

        FileRowRenderer() {
            setLayout(new BorderLayout());
            setBorder(JBUI.Borders.empty(2, 8, 2, 6));

            fileIconLabel.setBorder(JBUI.Borders.emptyRight(6));
            add(fileIconLabel, BorderLayout.WEST);

            fileText.setOpaque(false);
            fileText.setIpad(JBUI.emptyInsets());
            add(fileText, BorderLayout.CENTER);

            diffCountsText.setOpaque(false);
            diffCountsText.setIpad(JBUI.emptyInsets());
            diffCountsText.setBorder(JBUI.Borders.emptyLeft(8));
            add(diffCountsText, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(
            JList<? extends ReviewItem> jList, ReviewItem item,
            int index, boolean isSelected, boolean cellHasFocus) {

            Color bg = isSelected ? jList.getSelectionBackground() : jList.getBackground();
            Color fg = isSelected ? jList.getSelectionForeground() : jList.getForeground();
            setBackground(bg);
            setOpaque(true);

            Path p = Path.of(item.path());
            String fileName = p.getFileName() != null ? p.getFileName().toString() : item.path();
            Icon icon = com.intellij.openapi.fileTypes.FileTypeManager
                .getInstance().getFileTypeByFileName(fileName).getIcon();
            fileIconLabel.setIcon(icon != null ? icon : AllIcons.FileTypes.Text);

            fileText.clear();
            fileText.setFont(jList.getFont());
            Color fileColor = isSelected ? fg : switch (item.status()) {
                case ADDED -> STATUS_ADDED;
                case MODIFIED -> STATUS_MODIFIED;
                case DELETED -> STATUS_DELETED;
            };
            fileText.append(fileName, new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, fileColor));

            diffCountsText.clear();
            diffCountsText.setFont(jList.getFont());
            long now = System.currentTimeMillis();
            ReviewDiffCountAnimator.DiffCounts counts = diffCountAnimator.displayCounts(item, now);
            if (counts.added() > 0) {
                Color c = isSelected ? fg : DIFF_GREEN;
                diffCountsText.append("+" + counts.added(),
                    new SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, c));
            }
            if (counts.removed() > 0) {
                Color c = isSelected ? fg : DIFF_RED;
                String prefix = counts.added() > 0 ? " " : "";
                diffCountsText.append(prefix + "-" + counts.removed(),
                    new SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, c));
            }

            return this;
        }
    }

    private static @NotNull String colorHex(@NotNull Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static @NotNull String colorSpan(@NotNull Color c, @NotNull String text) {
        return "<font color='" + colorHex(c) + "'>" + text + "</font>";
    }
}
