package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.psi.MacroToolHandler;
import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.github.catatafishen.agentbridge.services.MacroToolSettings.MacroRegistration;
import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Manages the lifecycle of macro-based MCP tools. Reads {@link MacroToolSettings},
 * registers enabled macros in {@link PsiBridgeService}, and syncs when settings change.
 */
@Service(Service.Level.PROJECT)
public final class MacroToolRegistrar {

    private static final Logger LOG = Logger.getInstance(MacroToolRegistrar.class);

    private final Project project;
    private final Set<String> registeredToolIds = new HashSet<>();

    public MacroToolRegistrar(@NotNull Project project) {
        this.project = project;
    }

    @SuppressWarnings("java:S1905") // Cast needed: IDE doesn't resolve Project→ComponentManager supertype
    public static MacroToolRegistrar getInstance(@NotNull Project project) {
        return ((ComponentManager) project).getService(MacroToolRegistrar.class);
    }

    /**
     * Reads current settings and syncs tool registrations. Call on startup
     * and after settings are applied.
     */
    public void syncRegistrations() {
        PsiBridgeService bridge = PsiBridgeService.getInstance(project);
        MacroToolSettings settings = MacroToolSettings.getInstance(project);
        List<MacroRegistration> registrations = settings.getRegistrations();

        // Collect which tool IDs should be registered
        Set<String> desiredIds = new HashSet<>();
        for (MacroRegistration reg : registrations) {
            if (reg.isEnabled() && !reg.getToolName().isEmpty() && !reg.getMacroName().isEmpty()) {
                desiredIds.add(reg.getToolName());
            }
        }

        // Unregister tools that are no longer desired
        List<String> toRemove = new ArrayList<>();
        for (String id : registeredToolIds) {
            if (!desiredIds.contains(id)) {
                bridge.unregisterTool(id);
                toRemove.add(id);
                LOG.info("Unregistered macro tool: " + id);
            }
        }
        toRemove.forEach(registeredToolIds::remove);

        // Register new or updated tools
        for (MacroRegistration reg : registrations) {
            if (!reg.isEnabled() || reg.getToolName().isEmpty() || reg.getMacroName().isEmpty()) {
                continue;
            }
            bridge.registerTool(new MacroToolHandler(project, reg.getToolName(), reg.getMacroName()));
            registeredToolIds.add(reg.getToolName());
            LOG.info("Registered macro tool: " + reg.getToolName() + " → macro '" + reg.getMacroName() + "'");
        }
    }

    /**
     * Sanitizes a macro name into a valid MCP tool ID.
     * Converts to lowercase, replaces non-alphanumeric with underscores,
     * collapses multiple underscores, and adds "macro_" prefix.
     */
    public static String sanitizeToolName(String macroName) {
        String sanitized = macroName.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("(^_)|(_$)", "");
        if (sanitized.isEmpty()) {
            sanitized = "unnamed";
        }
        return "macro_" + sanitized;
    }
}
