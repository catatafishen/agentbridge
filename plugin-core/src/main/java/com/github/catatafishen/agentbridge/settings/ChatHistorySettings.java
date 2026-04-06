package com.github.catatafishen.agentbridge.settings;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Persistent project-level settings for chat history limits.
 */
@Service(Service.Level.PROJECT)
@State(name = "ChatHistorySettings", storages = @Storage("chatHistory.xml"))
public final class ChatHistorySettings implements PersistentStateComponent<ChatHistorySettings.State> {

    public static final int DEFAULT_EVENT_LOG_SIZE = 600;
    public static final int DEFAULT_DOM_MESSAGE_LIMIT = 80;
    public static final int DEFAULT_RECENT_TURNS_ON_RESTORE = 5;
    public static final int DEFAULT_LOAD_MORE_BATCH_SIZE = 3;

    private State myState = new State();

    public static ChatHistorySettings getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, ChatHistorySettings.class);
    }

    public int getEventLogSize() {
        return myState.eventLogSize;
    }

    public void setEventLogSize(int size) {
        myState.eventLogSize = size;
    }

    public int getDomMessageLimit() {
        return myState.domMessageLimit;
    }

    public void setDomMessageLimit(int limit) {
        myState.domMessageLimit = limit;
    }

    public int getRecentTurnsOnRestore() {
        return myState.recentTurnsOnRestore;
    }

    public void setRecentTurnsOnRestore(int turns) {
        myState.recentTurnsOnRestore = turns;
    }

    public int getLoadMoreBatchSize() {
        return myState.loadMoreBatchSize;
    }

    public void setLoadMoreBatchSize(int size) {
        myState.loadMoreBatchSize = size;
    }

    @Override
    public @NotNull State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
    }

    public static class State {
        private int eventLogSize = DEFAULT_EVENT_LOG_SIZE;
        private int domMessageLimit = DEFAULT_DOM_MESSAGE_LIMIT;
        private int recentTurnsOnRestore = DEFAULT_RECENT_TURNS_ON_RESTORE;
        private int loadMoreBatchSize = DEFAULT_LOAD_MORE_BATCH_SIZE;

        public int getEventLogSize() {
            return eventLogSize;
        }

        public void setEventLogSize(int eventLogSize) {
            this.eventLogSize = eventLogSize;
        }

        public int getDomMessageLimit() {
            return domMessageLimit;
        }

        public void setDomMessageLimit(int domMessageLimit) {
            this.domMessageLimit = domMessageLimit;
        }

        public int getRecentTurnsOnRestore() {
            return recentTurnsOnRestore;
        }

        public void setRecentTurnsOnRestore(int recentTurnsOnRestore) {
            this.recentTurnsOnRestore = recentTurnsOnRestore;
        }

        public int getLoadMoreBatchSize() {
            return loadMoreBatchSize;
        }

        public void setLoadMoreBatchSize(int loadMoreBatchSize) {
            this.loadMoreBatchSize = loadMoreBatchSize;
        }
    }
}
