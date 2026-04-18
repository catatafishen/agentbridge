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
        public boolean showShortcutHints = true;
        public boolean smartPasteEnabled = true;
        public boolean softWrapsEnabled = true;
        public int smartPasteMinLines = DEFAULT_SMART_PASTE_MIN_LINES;
        public int smartPasteMinChars = DEFAULT_SMART_PASTE_MIN_CHARS;
        @NotNull
        public String fileSearchTrigger = "#";
        @NotNull
        public UnhandledNudgeMode unhandledNudgeMode = UnhandledNudgeMode.AUTO_SEND;
    }
}
