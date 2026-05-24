package com.github.catatafishen.agentbridge.settings;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Project-level settings that control which diagnostics are surfaced to agents
 * by all diagnostic-emitting tools.
 *
 * <p>Agents benefit from focused, high-signal diagnostics. This filter keeps
 * spell-check noise and other low-priority inspections out of the agent's context
 * by default, while letting users tune exactly which severity levels and inspections
 * are visible. All tools that emit diagnostic data consult this filter.</p>
 */
@Service(Service.Level.PROJECT)
@State(name = "DiagnosticFilterSettings", storages = @Storage("agentbridge-diagnostic-filter.xml"))
public final class DiagnosticFilterSettings implements PersistentStateComponent<DiagnosticFilterSettings.State> {

    private State myState = new State();

    public static DiagnosticFilterSettings getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, DiagnosticFilterSettings.class);
    }

    /**
     * Returns {@code true} if the given highlight should be included in MCP tool output.
     *
     * <p>Combines severity and inspection-id filtering into a single call so all tools
     * apply the same policy without duplicating the two-step check.</p>
     */
    public boolean shouldInclude(@NotNull HighlightInfo h) {
        if (!isSeverityEnabled(h.getSeverity())) return false;
        String inspId = h.getInspectionToolId();
        return inspId == null || !isInspectionSuppressed(inspId);
    }

    /**
     * Returns {@code true} if diagnostics at the given severity level should be
     * included in MCP tool output according to the current settings.
     *
     * <p>Only highlights below INFORMATION level (e.g. {@code TEXT_ATTRIBUTES} formatting-only
     * entries) are unconditionally excluded — they carry no diagnostic message.</p>
     */
    public boolean isSeverityEnabled(@NotNull HighlightSeverity severity) {
        if (severity.myVal >= HighlightSeverity.ERROR.myVal) return myState.showErrors;
        if (severity.myVal >= HighlightSeverity.WARNING.myVal) return myState.showWarnings;
        if (severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal) return myState.showWeakWarnings;
        if (severity.myVal >= HighlightSeverity.INFORMATION.myVal) return myState.showInformation;
        return false;
    }

    /**
     * Returns {@code true} if diagnostics from the given inspection should be
     * suppressed from MCP tool output, regardless of severity.
     */
    public boolean isInspectionSuppressed(@NotNull String inspectionId) {
        return myState.suppressedInspectionIds.contains(inspectionId);
    }

    public boolean isShowErrors() {
        return myState.showErrors;
    }

    public void setShowErrors(boolean v) {
        myState.showErrors = v;
    }

    public boolean isShowWarnings() {
        return myState.showWarnings;
    }

    public void setShowWarnings(boolean v) {
        myState.showWarnings = v;
    }

    public boolean isShowWeakWarnings() {
        return myState.showWeakWarnings;
    }

    public void setShowWeakWarnings(boolean v) {
        myState.showWeakWarnings = v;
    }

    public boolean isShowInformation() {
        return myState.showInformation;
    }

    public void setShowInformation(boolean v) {
        myState.showInformation = v;
    }

    public @NotNull List<String> getSuppressedInspectionIds() {
        return Collections.unmodifiableList(myState.suppressedInspectionIds);
    }

    public void setSuppressedInspectionIds(@NotNull List<String> ids) {
        myState.suppressedInspectionIds = new ArrayList<>(ids);
    }

    @Override
    public @NotNull State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
    }

    public static final class State {

        private boolean showErrors = true;
        private boolean showWarnings = true;
        /**
         * Weak warnings enabled by default to match existing {@code get_highlights} behaviour.
         * Users with noisy codebases can uncheck this to reduce agent context.
         */
        private boolean showWeakWarnings = true;
        /**
         * Information-level highlights included by default. These can be useful context
         * (e.g. unused parameter hints, return value annotations). Users with very noisy
         * codebases can uncheck this.
         */
        private boolean showInformation = true;
        /**
         * SpellCheckingInspection suppressed by default — spell corrections are human-quality
         * work that creates noise for agents and crowds out real issues. Users who want agents
         * to fix typos can remove this entry.
         */
        private List<String> suppressedInspectionIds = new ArrayList<>(List.of("SpellCheckingInspection"));

        public boolean isShowErrors() {
            return showErrors;
        }

        public void setShowErrors(boolean v) {
            this.showErrors = v;
        }

        public boolean isShowWarnings() {
            return showWarnings;
        }

        public void setShowWarnings(boolean v) {
            this.showWarnings = v;
        }

        public boolean isShowWeakWarnings() {
            return showWeakWarnings;
        }

        public void setShowWeakWarnings(boolean v) {
            this.showWeakWarnings = v;
        }

        public boolean isShowInformation() {
            return showInformation;
        }

        public void setShowInformation(boolean v) {
            this.showInformation = v;
        }

        public List<String> getSuppressedInspectionIds() {
            return suppressedInspectionIds;
        }

        public void setSuppressedInspectionIds(List<String> ids) {
            this.suppressedInspectionIds = ids;
        }
    }
}
