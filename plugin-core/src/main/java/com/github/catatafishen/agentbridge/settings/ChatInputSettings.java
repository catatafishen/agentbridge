package com.github.catatafishen.agentbridge.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

/**
 * Application-level persistent settings for the chat input area.
 * Covers shortcut hint visibility, smart paste behaviour, and the
 * file-search trigger character.
 */
@Service(Service.Level.APP)
@State(name = "ChatInputSettings", storages = @Storage("ideAgentChatInput.xml"))
public final class ChatInputSettings implements PersistentStateComponent<ChatInputSettings.State> {

    public static final int DEFAULT_SMART_PASTE_MIN_LINES = 3;
    public static final int DEFAULT_SMART_PASTE_MIN_CHARS = 500;

    private State myState = new State();

    public static ChatInputSettings getInstance() {
        return ApplicationManager.getApplication().getService(ChatInputSettings.class);
    }

    // ── Shortcut hints ──────────────────────────────────────────────────────

    public boolean isShowShortcutHints() {
        return myState.showShortcutHints;
    }

    public void setShowShortcutHints(boolean show) {
        myState.showShortcutHints = show;
    }

    // ── Smart paste ─────────────────────────────────────────────────────────

    public boolean isSmartPasteEnabled() {
        return myState.smartPasteEnabled;
    }

    public void setSmartPasteEnabled(boolean enabled) {
        myState.smartPasteEnabled = enabled;
    }

    public int getSmartPasteMinLines() {
        return myState.smartPasteMinLines;
    }

    public void setSmartPasteMinLines(int lines) {
        myState.smartPasteMinLines = lines;
    }

    public int getSmartPasteMinChars() {
        return myState.smartPasteMinChars;
    }

    public void setSmartPasteMinChars(int chars) {
        myState.smartPasteMinChars = chars;
    }

    // ── Soft wraps ──────────────────────────────────────────────────────────

    public boolean isSoftWrapsEnabled() {
        return myState.softWrapsEnabled;
    }

    public void setSoftWrapsEnabled(boolean enabled) {
        myState.softWrapsEnabled = enabled;
    }

    // ── File search trigger ─────────────────────────────────────────────────

    @NotNull
    public String getFileSearchTrigger() {
        return myState.fileSearchTrigger;
    }

    public void setFileSearchTrigger(@NotNull String trigger) {
        myState.fileSearchTrigger = trigger;
    }

    // ── Unhandled nudge mode ────────────────────────────────────────────────

    /**
     * What happens to a queued nudge when the agent finishes its turn before the user
     * acted on it.
     *
     * <p>{@link #AUTO_SEND} (default) — fire the nudge as a brand-new prompt
     * automatically, preserving the historical behaviour. <br>
     * {@link #RESTORE_INTO_INPUT} — prepend the nudge text into the chat input area so
     * the user can edit it (and any text they were already typing stays appended).
     */
    public enum UnhandledNudgeMode {
        AUTO_SEND,
        RESTORE_INTO_INPUT
    }

    @NotNull
    public UnhandledNudgeMode getUnhandledNudgeMode() {
        return myState.unhandledNudgeMode == null ? UnhandledNudgeMode.AUTO_SEND : myState.unhandledNudgeMode;
    }

    public void setUnhandledNudgeMode(@NotNull UnhandledNudgeMode mode) {
        myState.unhandledNudgeMode = mode;
    }

    // ── Reprimand nudge mode ────────────────────────────────────────────────

    /**
     * Controls how the plugin handles auto-generated reprimand nudges (sent when the agent
     * calls a built-in tool instead of the MCP equivalent).
     *
     * <p>{@link #ENABLED} (default) — reprimand is shown as a pending nudge bubble and
     * injected into the next MCP tool result. <br>
     * {@link #SEND_SILENTLY} — reprimand is injected into the next MCP tool result but no
     * bubble is displayed in the chat UI. <br>
     * {@link #DISABLED} — the reprimand system is fully disabled; no nudge is shown or sent.
     */
    public enum ReprimandNudgeMode {
        ENABLED,
        SEND_SILENTLY,
        DISABLED
    }

    @NotNull
    public ReprimandNudgeMode getReprimandNudgeMode() {
        return myState.reprimandNudgeMode == null ? ReprimandNudgeMode.ENABLED : myState.reprimandNudgeMode;
    }

    public void setReprimandNudgeMode(@NotNull ReprimandNudgeMode mode) {
        myState.reprimandNudgeMode = mode;
    }

    // ── Tool timeout ────────────────────────────────────────────────────────

    /**
     * When {@code false}, the timeout dialog is never shown; slow tool calls wait silently.
     * The user can set this to {@code false} via the dialog's "Never ask again" checkbox.
     */
    public boolean isToolTimeoutDialogEnabled() {
        return myState.toolTimeoutDialogEnabled;
    }

    public void setToolTimeoutDialogEnabled(boolean enabled) {
        myState.toolTimeoutDialogEnabled = enabled;
    }

    /**
     * Seconds to wait for a tool call before showing the "still running" dialog. Default: 60.
     */
    public int getToolTimeoutSeconds() {
        return myState.toolTimeoutSeconds;
    }

    public void setToolTimeoutSeconds(int seconds) {
        myState.toolTimeoutSeconds = seconds;
    }

    /**
     * Minutes for the first "wait longer" option in the tool timeout dialog. Default: 1.
     */
    public int getToolTimeoutExtension1Minutes() {
        return myState.toolTimeoutExtension1Minutes;
    }

    public void setToolTimeoutExtension1Minutes(int minutes) {
        myState.toolTimeoutExtension1Minutes = minutes;
    }

    /**
     * Minutes for the second "wait longer" option in the tool timeout dialog. Default: 5.
     */
    public int getToolTimeoutExtension2Minutes() {
        return myState.toolTimeoutExtension2Minutes;
    }

    public void setToolTimeoutExtension2Minutes(int minutes) {
        myState.toolTimeoutExtension2Minutes = minutes;
    }

    // ── Pause feature ───────────────────────────────────────────────────────

    /**
     * When true, the agent is automatically paused whenever the chat input area gains
     * keyboard focus, giving the user time to type a nudge without an in-flight tool call
     * racing ahead.
     */
    public boolean isPauseOnInputFocus() {
        return myState.pauseOnInputFocus;
    }

    public void setPauseOnInputFocus(boolean enabled) {
        myState.pauseOnInputFocus = enabled;
    }

    // ── PersistentStateComponent ────────────────────────────────────────────

    @Override
    public @NotNull State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
    }

    public static final class State {
        public boolean showShortcutHints = true; // NOSONAR - IntelliJ XmlSerializer persists public state fields directly.
        public boolean smartPasteEnabled = true; // NOSONAR - IntelliJ XmlSerializer persists public state fields directly.
        public boolean softWrapsEnabled = true; // NOSONAR - IntelliJ XmlSerializer persists public state fields directly.
        public int smartPasteMinLines = DEFAULT_SMART_PASTE_MIN_LINES; // NOSONAR - IntelliJ XmlSerializer persists public state fields directly.
        public int smartPasteMinChars = DEFAULT_SMART_PASTE_MIN_CHARS; // NOSONAR - IntelliJ XmlSerializer persists public state fields directly.
        @NotNull
        public String fileSearchTrigger = "#";
        @NotNull
        public UnhandledNudgeMode unhandledNudgeMode = UnhandledNudgeMode.AUTO_SEND;
        @NotNull
        public ReprimandNudgeMode reprimandNudgeMode = ReprimandNudgeMode.ENABLED; // NOSONAR - IntelliJ XmlSerializer persists public state fields directly.
        public boolean pauseOnInputFocus = false; // NOSONAR - IntelliJ XmlSerializer persists public state fields directly.
        public int toolTimeoutSeconds = 60; // NOSONAR - IntelliJ XmlSerializer persists public state fields directly.
        public int toolTimeoutExtension1Minutes = 1; // NOSONAR - IntelliJ XmlSerializer persists public state fields directly.
        public int toolTimeoutExtension2Minutes = 5; // NOSONAR - IntelliJ XmlSerializer persists public state fields directly.
        public boolean toolTimeoutDialogEnabled = true; // NOSONAR - IntelliJ XmlSerializer persists public state fields directly.
    }
}
