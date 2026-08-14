package com.github.catatafishen.agentbridge.psi;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves run configuration names leniently so that pasting the decorated strings printed by
 * {@code list_run_configurations} (e.g. {@code "My Test [Gradle] (temporary)"}) back into
 * {@code run_configuration}, {@code debug_session_start}, {@code edit_run_configuration}, or
 * {@code delete_run_configuration} still works, and produces an actionable error when it doesn't.
 */
public final class RunConfigurationNameResolver {

    private static final String LIST_HINT = "Use list_run_configurations to see available configs.";

    // Matches "<name> [<type>]" with an optional trailing " (temporary)" — exactly the format
    // produced by RunConfigurationService#listRunConfigurations.
    private static final Pattern DECORATED_NAME_PATTERN =
        Pattern.compile("^(.+) \\[[^]\\[]+]( \\(temporary\\))?$");

    private RunConfigurationNameResolver() {
    }

    /**
     * Finds a run configuration by name, falling back to stripping a decorated
     * {@code list_run_configurations} suffix ({@code " [Type]"} / {@code " (temporary)"}) if the
     * exact name isn't found.
     */
    @Nullable
    public static RunnerAndConfigurationSettings findLenient(RunManager runManager, String name) {
        var settings = runManager.findConfigurationByName(name);
        if (settings != null) {
            return settings;
        }
        String stripped = stripDecoration(name);
        return stripped != null ? runManager.findConfigurationByName(stripped) : null;
    }

    /**
     * Returns the undecorated name if {@code name} matches the {@code "<name> [<type>] (temporary)"}
     * format produced by {@code list_run_configurations}, otherwise {@code null}.
     */
    @Nullable
    public static String stripDecoration(String name) {
        Matcher matcher = DECORATED_NAME_PATTERN.matcher(name);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /**
     * Builds an actionable "not found" error message, calling out when the passed-in name looks
     * like it was copied verbatim from {@code list_run_configurations} output rather than the
     * underlying configuration name.
     */
    public static String notFoundMessage(String name) {
        String stripped = stripDecoration(name);
        if (stripped != null) {
            return "Run configuration not found: '" + name + "'. This looks like a decorated "
                + "list_run_configurations entry (name + type/temporary suffix) rather than the plain "
                + "configuration name — '" + stripped + "' was also not found. " + LIST_HINT;
        }
        return "Run configuration not found: '" + name + "'. " + LIST_HINT;
    }
}
