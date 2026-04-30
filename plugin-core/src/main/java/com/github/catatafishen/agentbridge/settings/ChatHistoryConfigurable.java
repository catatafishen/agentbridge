package com.github.catatafishen.agentbridge.settings;

import com.github.catatafishen.agentbridge.services.ActiveAgentManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.intellij.icons.AllIcons;
import com.intellij.ide.actions.RevealFileAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Project-level settings page for managing chat history files stored on disk.
 * Appears under Settings → AgentBridge → Other → Chat History.
 * <p>
 * Deletions happen immediately via toolbar actions (like JetBrains' own cache cleanup pages),
 * so {@link #isModified()} always returns {@code false} and {@link #apply()} is a no-op.
 */
public final class ChatHistoryConfigurable implements Configurable {

    public static final String ID = "com.github.catatafishen.agentbridge.chatHistory";

    private static final Logger LOG = Logger.getInstance(ChatHistoryConfigurable.class);

    private static final String AGENT_WORK_DIR = ".agent-work";
    private static final String CONVERSATIONS_DIR = "conversations";
    private static final String CURRENT_SESSION_FILE = "conversation.json";
    private static final String ARCHIVE_PREFIX = "conversation-";
    private static final String JSON_EXTENSION = ".json";

    private static final DateTimeFormatter TIMESTAMP_PARSER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");
    private static final DateTimeFormatter DISPLAY_FORMATTER =
        DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a");

    private static final String CURRENT_SESSION_LABEL = "Current Session";

    private static final long KB = 1024L;
    private static final long MB = KB * 1024L;

    private final Project project;

    private JBLabel summaryLabel;
    private JCheckBox injectHistoryCheckbox;
    private JBTable table;
    private ConversationTableModel tableModel;
    private JSpinner eventLogSizeSpinner;
    private JSpinner domMessageLimitSpinner;
    private JSpinner recentTurnsSpinner;
    private JSpinner loadMoreBatchSpinner;

    public ChatHistoryConfigurable(Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Chat History";
    }

    @Override
    public @NotNull JComponent createComponent() {
        summaryLabel = new JBLabel();
        summaryLabel.setBorder(JBUI.Borders.empty(0, 0, 4, 0));

        tableModel = new ConversationTableModel();
        table = new JBTable(tableModel);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getEmptyText().setText("Start a chat to create conversation history");
        table.getAccessibleContext().setAccessibleName("Conversation history files");

        table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(200));
        table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(80));
        table.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(80));
        table.getColumnModel().getColumn(3).setPreferredWidth(JBUI.scale(160));

        table.getColumnModel().getColumn(0).setCellRenderer(new ConversationNameRenderer());
        table.getColumnModel().getColumn(1).setCellRenderer(new MessageCountRenderer());

        table.getColumnModel().getColumn(2).setCellRenderer(new ColoredTableCellRenderer() {
            @Override
            protected void customizeCellRenderer(@NotNull JTable table, @Nullable Object value,
                                                 boolean selected, boolean hasFocus, int row, int column) {
                if (value != null) append(value.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                setTextAlign(SwingConstants.RIGHT);
            }
        });

        JPanel decorated = ToolbarDecorator.createDecorator(table)
            .disableAddAction()
            .disableUpDownActions()
            .setRemoveAction(b -> deleteSelectedConversations())
            .setRemoveActionUpdater(e -> {
                int[] rows = table.getSelectedRows();
                if (rows.length == 0) return false;
                for (int row : rows) {
                    if (tableModel.getEntryAt(row).isCurrentSession()) return false;
                }
                return true;
            })
            .addExtraAction(createDeleteAllArchivesAction())
            .addExtraAction(createRefreshAction())
            .addExtraAction(createRevealInFinderAction())
            .createPanel();

        injectHistoryCheckbox = new JCheckBox(
            "Inject prior conversation summary into new sessions");
        injectHistoryCheckbox.setToolTipText(
            "<html>When enabled, a compressed summary of recent conversation history is<br>"
                + "prepended to the first prompt of each new session.<br>"
                + "Useful as a fallback for clients without native session restore,<br>"
                + "but uses extra tokens. Disabled by default.</html>");
        injectHistoryCheckbox.setSelected(ActiveAgentManager.getInjectConversationHistory(project));

        ChatHistorySettings histSettings = ChatHistorySettings.getInstance(project);

        eventLogSizeSpinner = new JSpinner(new SpinnerNumberModel(
            histSettings.getEventLogSize(), 100, 10_000, 100));
        eventLogSizeSpinner.setToolTipText("Maximum number of JS events buffered for web/PWA clients");

        domMessageLimitSpinner = new JSpinner(new SpinnerNumberModel(
            histSettings.getDomMessageLimit(), 10, 1000, 10));
        domMessageLimitSpinner.setToolTipText("Maximum chat messages visible in the DOM before older ones are trimmed");

        recentTurnsSpinner = new JSpinner(new SpinnerNumberModel(
            histSettings.getRecentTurnsOnRestore(), 1, 100, 1));
        recentTurnsSpinner.setToolTipText("Number of recent turns loaded immediately when restoring a session");

        loadMoreBatchSpinner = new JSpinner(new SpinnerNumberModel(
            histSettings.getLoadMoreBatchSize(), 1, 50, 1));
        loadMoreBatchSpinner.setToolTipText("Number of turns loaded per 'Load More' click");

        JPanel limitsPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Web event log size:", eventLogSizeSpinner)
            .addLabeledComponent("DOM message limit:", domMessageLimitSpinner)
            .addLabeledComponent("Recent turns on restore:", recentTurnsSpinner)
            .addLabeledComponent("Load-more batch size:", loadMoreBatchSpinner)
            .getPanel();
        limitsPanel.setBorder(IdeBorderFactory.createTitledBorder("History Limits"));

        JPanel panel = FormBuilder.createFormBuilder()
            .addComponent(new JBLabel(
                "<html><body style='width: 420px'>Conversation files stored in this project's "
                    + "<code>.agent-work</code> directory.</body></html>"))
            .addComponent(summaryLabel)
            .addComponent(injectHistoryCheckbox)
            .addComponent(limitsPanel)
            .addComponentFillVertically(decorated, 4)
            .getPanel();
        panel.setBorder(JBUI.Borders.empty(8));

        // Load conversations in background after component creation to avoid blocking EDT
        ApplicationManager.getApplication().invokeLater(this::loadConversations);

        return panel;
    }

    @Override
    public boolean isModified() {
        if (injectHistoryCheckbox == null) return false;
        if (injectHistoryCheckbox.isSelected() != ActiveAgentManager.getInjectConversationHistory(project)) return true;

        ChatHistorySettings settings = ChatHistorySettings.getInstance(project);
        if (spinnerValue(eventLogSizeSpinner) != settings.getEventLogSize()) return true;
        if (spinnerValue(domMessageLimitSpinner) != settings.getDomMessageLimit()) return true;
        if (spinnerValue(recentTurnsSpinner) != settings.getRecentTurnsOnRestore()) return true;
        return spinnerValue(loadMoreBatchSpinner) != settings.getLoadMoreBatchSize();
    }

    @Override
    public void apply() {
        if (injectHistoryCheckbox != null) {
            ActiveAgentManager.setInjectConversationHistory(project, injectHistoryCheckbox.isSelected());
        }
        if (eventLogSizeSpinner != null) {
            ChatHistorySettings settings = ChatHistorySettings.getInstance(project);
            settings.setEventLogSize(spinnerValue(eventLogSizeSpinner));
            settings.setDomMessageLimit(spinnerValue(domMessageLimitSpinner));
            settings.setRecentTurnsOnRestore(spinnerValue(recentTurnsSpinner));
            settings.setLoadMoreBatchSize(spinnerValue(loadMoreBatchSpinner));
        }
    }

    @Override
    public void reset() {
        if (injectHistoryCheckbox != null) {
            injectHistoryCheckbox.setSelected(ActiveAgentManager.getInjectConversationHistory(project));
        }
        if (eventLogSizeSpinner != null) {
            ChatHistorySettings settings = ChatHistorySettings.getInstance(project);
            eventLogSizeSpinner.setValue(settings.getEventLogSize());
            domMessageLimitSpinner.setValue(settings.getDomMessageLimit());
            recentTurnsSpinner.setValue(settings.getRecentTurnsOnRestore());
            loadMoreBatchSpinner.setValue(settings.getLoadMoreBatchSize());
        }
        loadConversations();
    }

    @Override
    public void disposeUIResources() {
        summaryLabel = null;
        injectHistoryCheckbox = null;
        table = null;
        tableModel = null;
        eventLogSizeSpinner = null;
        domMessageLimitSpinner = null;
        recentTurnsSpinner = null;
        loadMoreBatchSpinner = null;
    }

    private static int spinnerValue(JSpinner spinner) {
        return ((Number) spinner.getValue()).intValue();
    }

    // ── Toolbar actions ──

    private AnAction createDeleteAllArchivesAction() {
        return new AnAction("Delete All Archives", "Delete all archived conversations (keeps current session)",
            AllIcons.Actions.GC) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                int result = Messages.showYesNoDialog(
                    project,
                    "Delete all archived conversations?\nThe current session will be kept.\n\nThis action cannot be undone.",
                    "Delete All Archives",
                    Messages.getWarningIcon());
                if (result != Messages.YES) return;

                List<ConversationEntry> entries = tableModel.getEntries();
                List<Path> toDelete = new ArrayList<>();
                for (ConversationEntry entry : entries) {
                    if (!entry.isCurrentSession()) {
                        toDelete.add(entry.path());
                    }
                }
                deleteFiles(toDelete);
                loadConversations();
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                boolean hasArchives = tableModel != null
                    && tableModel.getEntries().stream().anyMatch(entry -> !entry.isCurrentSession());
                e.getPresentation().setEnabled(hasArchives);
            }
        };
    }

    private AnAction createRefreshAction() {
        return new AnAction("Refresh", "Rescan conversation directory", AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                loadConversations();
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        };
    }

    private AnAction createRevealInFinderAction() {
        return new AnAction("Show in " + RevealFileAction.getFileManagerName(),
            "Open conversations directory in file manager",
            AllIcons.Actions.MenuOpen) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                String basePath = project.getBasePath();
                if (basePath == null) return;
                Path dir = Path.of(basePath, AGENT_WORK_DIR, CONVERSATIONS_DIR);
                if (!Files.isDirectory(dir)) {
                    dir = Path.of(basePath, AGENT_WORK_DIR);
                }
                RevealFileAction.openDirectory(dir.toFile());
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        };
    }

    private void deleteSelectedConversations() {
        int[] selectedRows = table.getSelectedRows();
        if (selectedRows.length == 0) return;

        String message = selectedRows.length == 1
            ? "Delete the selected conversation?\n\nThis action cannot be undone."
            : "Delete " + selectedRows.length + " selected conversations?\n\nThis action cannot be undone.";

        int result = Messages.showYesNoDialog(project, message, "Delete Conversations", Messages.getWarningIcon());
        if (result != Messages.YES) return;

        List<Path> toDelete = new ArrayList<>();
        for (int row : selectedRows) {
            toDelete.add(tableModel.getEntryAt(row).path());
        }
        deleteFiles(toDelete);
        loadConversations();
    }

    private void deleteFiles(List<Path> paths) {
        List<String> failures = new ArrayList<>();
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                LOG.warn("Failed to delete conversation file: " + path, e);
                failures.add(path.getFileName().toString());
            }
        }
        if (!failures.isEmpty()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("AgentBridge Notifications")
                .createNotification(
                    "Failed to delete " + failures.size() + " file(s): " + String.join(", ", failures),
                    NotificationType.WARNING)
                .notify(project);
        }
    }

    // ── Data loading ──

    private void loadConversations() {
        if (tableModel == null) return;

        table.getEmptyText().setText("Loading…");
        tableModel.setEntries(List.of());
        updateSummary(List.of());

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<ConversationEntry> entries = scanConversations();
            entries.sort(Comparator.comparing(ConversationEntry::dateMillis).reversed());

            ApplicationManager.getApplication().invokeLater(() -> {
                if (tableModel == null) return;
                tableModel.setEntries(entries);
                updateSummary(entries);
                table.getEmptyText().setText("No conversations found");
                table.getEmptyText().appendSecondaryText(
                    "Start a chat to create conversation history",
                    SimpleTextAttributes.GRAYED_ATTRIBUTES, null);
            });
        });
    }

    private List<ConversationEntry> scanConversations() {
        List<ConversationEntry> entries = new ArrayList<>();
        String basePath = project.getBasePath();
        if (basePath == null) return entries;

        Path agentWorkDir = Path.of(basePath, AGENT_WORK_DIR);
        Path currentSessionFile = agentWorkDir.resolve(CURRENT_SESSION_FILE);
        if (Files.isRegularFile(currentSessionFile)) {
            entries.add(buildEntry(currentSessionFile, true));
        }

        Path conversationsDir = agentWorkDir.resolve(CONVERSATIONS_DIR);
        if (Files.isDirectory(conversationsDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(conversationsDir,
                ARCHIVE_PREFIX + "*" + JSON_EXTENSION)) {
                for (Path file : stream) {
                    if (Files.isRegularFile(file)) {
                        entries.add(buildEntry(file, false));
                    }
                }
            } catch (IOException e) {
                LOG.warn("Failed to scan conversations directory: " + conversationsDir, e);
            }
        }

        return entries;
    }

    private static ConversationEntry buildEntry(Path file, boolean currentSession) {
        long size;
        long lastModifiedMillis;
        try {
            size = Files.size(file);
            lastModifiedMillis = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            LOG.warn("Failed to read file attributes: " + file, e);
            size = 0;
            lastModifiedMillis = 0;
        }

        int messageCount = countMessages(file);
        String displayName;
        long dateMillis;

        if (currentSession) {
            displayName = CURRENT_SESSION_LABEL;
            dateMillis = lastModifiedMillis;
        } else {
            String fileName = file.getFileName().toString();
            String timestamp = fileName
                .replace(ARCHIVE_PREFIX, "")
                .replace(JSON_EXTENSION, "");
            displayName = formatTimestamp(timestamp);
            dateMillis = parseTimestampMillis(timestamp, lastModifiedMillis);
        }

        return new ConversationEntry(file, displayName, messageCount, size, dateMillis, currentSession);
    }

    private static int countMessages(Path file) {
        return ConversationFileUtils.countMessages(file);
    }

    private static String formatTimestamp(String timestamp) {
        return ConversationFileUtils.formatTimestamp(timestamp);
    }

    private static long parseTimestampMillis(String timestamp, long fallback) {
        return ConversationFileUtils.parseTimestampMillis(timestamp, fallback);
    }

    private void updateSummary(List<ConversationEntry> entries) {
        if (summaryLabel == null) return;
        if (entries.isEmpty()) {
            summaryLabel.setText(" ");
            return;
        }
        long totalSize = 0;
        for (ConversationEntry entry : entries) {
            totalSize += entry.size();
        }
        int count = entries.size();
        String countText = count == 1 ? "1 conversation" : count + " conversations";
        summaryLabel.setText(countText + " using " + formatFileSize(totalSize));
    }

    // ── Formatting utilities ──

    static String formatFileSize(long bytes) {
        return ConversationFileUtils.formatFileSize(bytes);
    }

    static String formatDateMillis(long millis) {
        return ConversationFileUtils.formatDateMillis(millis);
    }

    // ── Data record ──

    record ConversationEntry(
        Path path,
        String displayName,
        int messageCount,
        long size,
        long dateMillis,
        boolean isCurrentSession
    ) {
    }

    // ── Table model ──

    private static final class ConversationTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Conversation", "Messages", "Size", "Date"};
        private final List<ConversationEntry> entries = new ArrayList<>();

        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ConversationEntry entry = entries.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> entry.displayName();
                case 1 -> entry.messageCount() >= 0 ? String.valueOf(entry.messageCount()) : "—";
                case 2 -> formatFileSize(entry.size());
                case 3 -> formatDateMillis(entry.dateMillis());
                default -> "";
            };
        }

        ConversationEntry getEntryAt(int row) {
            return entries.get(row);
        }

        List<ConversationEntry> getEntries() {
            return List.copyOf(entries);
        }

        void setEntries(List<ConversationEntry> newEntries) {
            entries.clear();
            entries.addAll(newEntries);
            fireTableDataChanged();
        }
    }

    // ── Cell renderers ──

    private static final class ConversationNameRenderer extends ColoredTableCellRenderer {
        @Override
        protected void customizeCellRenderer(
            @NotNull JTable table, @Nullable Object value,
            boolean selected, boolean hasFocus, int row, int column
        ) {
            if (value == null) return;
            ConversationTableModel model = (ConversationTableModel) table.getModel();
            ConversationEntry entry = model.getEntryAt(row);

            if (entry.isCurrentSession()) {
                setIcon(AllIcons.Actions.Execute);
                append(value.toString(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                append("  (active)", SimpleTextAttributes.GRAYED_ATTRIBUTES);
            } else {
                setIcon(AllIcons.Vcs.History);
                append(value.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
            }
        }
    }

    private static final class MessageCountRenderer extends ColoredTableCellRenderer {
        @Override
        protected void customizeCellRenderer(
            @NotNull JTable table, @Nullable Object value,
            boolean selected, boolean hasFocus, int row, int column
        ) {
            setTextAlign(SwingConstants.RIGHT);
            if (value == null) return;
            String text = value.toString();
            if ("—".equals(text)) {
                append(text, SimpleTextAttributes.GRAYED_ATTRIBUTES);
                setToolTipText("Unable to read message count from file");
            } else {
                append(text, SimpleTextAttributes.REGULAR_ATTRIBUTES);
                setToolTipText(null);
            }
        }
    }
}
