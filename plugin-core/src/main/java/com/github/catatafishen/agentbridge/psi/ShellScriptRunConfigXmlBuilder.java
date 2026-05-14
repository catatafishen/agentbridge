package com.github.catatafishen.agentbridge.psi;

/**
 * Builds valid XML for IntelliJ Shell Script run configurations ({@code ShConfigurationType}).
 *
 * <p>The Shell Script plugin uses named XML options internally and exposes no stable public Java
 * API for programmatic configuration. This builder generates the exact XML structure IntelliJ
 * expects, making Shell Script config creation deterministic and unit-testable.
 *
 * <p>Generated XML is suitable for direct write to
 * {@code .idea/runConfigurations/<name>.xml}.</p>
 */
public final class ShellScriptRunConfigXmlBuilder {

    static final String TYPE_ID = "ShConfigurationType";
    static final String DEFAULT_INTERPRETER = "/bin/bash";

    private ShellScriptRunConfigXmlBuilder() {
    }

    /**
     * Parameters for a Shell Script run configuration.
     *
     * <p>Use {@code null} or empty string for optional fields to accept defaults.</p>
     *
     * @param scriptPath         path to script file; pass {@code null}/empty to use inline text
     * @param scriptText         inline script text (used when {@code scriptPath} is absent)
     * @param scriptOptions      arguments passed to the script
     * @param interpreterPath    path to the interpreter binary; defaults to {@code /bin/bash}
     * @param interpreterOptions extra flags for the interpreter
     * @param workingDirectory   working directory for the script; defaults to {@code $PROJECT_DIR$}
     * @param executeInTerminal  {@code true} to run in the integrated terminal
     */
    public record ShellScriptConfig(
        String scriptPath,
        String scriptText,
        String scriptOptions,
        String interpreterPath,
        String interpreterOptions,
        String workingDirectory,
        boolean executeInTerminal
    ) {
    }

    /**
     * Returns {@code true} when the given type string identifies a Shell Script configuration.
     * Matching is case-insensitive; recognises the canonical type id
     * {@code ShConfigurationType} as well as the display-name aliases
     * {@code shell script}, {@code shell_script}, and {@code sh}.
     */
    public static boolean isShellScriptType(String type) {
        if (type == null) return false;
        String lower = type.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("shconfigurationtype")
            || lower.equals("shell script")
            || lower.equals("shell_script")
            || lower.equals("sh");
    }

    /**
     * Builds the {@code <component>} XML expected by IntelliJ for a Shell Script run config.
     *
     * @param name        display name of the run configuration
     * @param config      script parameters
     * @param projectBase the project base path used to substitute {@code $PROJECT_DIR$} macros
     * @return valid XML string
     */
    public static String build(String name, ShellScriptConfig config, String projectBase) {
        boolean useFile = config.scriptPath() != null && !config.scriptPath().isBlank();
        String scriptText = useFile ? "" : (config.scriptText() != null ? config.scriptText() : "");
        String scriptPath = useFile ? applyProjectDirMacro(config.scriptPath(), projectBase) : "";
        String scriptOptions = config.scriptOptions() != null ? config.scriptOptions() : "";
        String interpreter = (config.interpreterPath() != null && !config.interpreterPath().isBlank())
            ? config.interpreterPath() : DEFAULT_INTERPRETER;
        String interpreterOptions = config.interpreterOptions() != null ? config.interpreterOptions() : "";
        String workDir = (config.workingDirectory() != null && !config.workingDirectory().isBlank())
            ? applyProjectDirMacro(config.workingDirectory(), projectBase) : "$PROJECT_DIR$";

        return "<component name=\"ProjectRunConfigurationManager\">\n"
            + "  <configuration default=\"false\" name=\"" + escapeXml(name) + "\" type=\"" + TYPE_ID + "\">\n"
            + "    <option name=\"SCRIPT_TEXT\" value=\"" + escapeXml(scriptText) + "\" />\n"
            + "    <option name=\"INDEPENDENT_SCRIPT_PATH\" value=\"true\" />\n"
            + "    <option name=\"SCRIPT_PATH\" value=\"" + escapeXml(scriptPath) + "\" />\n"
            + "    <option name=\"SCRIPT_OPTIONS\" value=\"" + escapeXml(scriptOptions) + "\" />\n"
            + "    <option name=\"INDEPENDENT_SCRIPT_WORKING_DIRECTORY\" value=\"true\" />\n"
            + "    <option name=\"SCRIPT_WORKING_DIRECTORY\" value=\"" + escapeXml(workDir) + "\" />\n"
            + "    <option name=\"INDEPENDENT_INTERPRETER_PATH\" value=\"true\" />\n"
            + "    <option name=\"INTERPRETER_PATH\" value=\"" + escapeXml(interpreter) + "\" />\n"
            + "    <option name=\"INTERPRETER_OPTIONS\" value=\"" + escapeXml(interpreterOptions) + "\" />\n"
            + "    <option name=\"EXECUTE_IN_TERMINAL\" value=\"" + config.executeInTerminal() + "\" />\n"
            + "    <option name=\"EXECUTE_SCRIPT_FILE\" value=\"" + useFile + "\" />\n"
            + "    <method v=\"2\" />\n"
            + "  </configuration>\n"
            + "</component>\n";
    }

    /**
     * Replaces the project base path prefix with {@code $PROJECT_DIR$} in the given path.
     * Returns an empty string for {@code null} or empty input.
     */
    static String applyProjectDirMacro(String path, String projectBase) {
        if (path == null || path.isBlank()) return "";
        if (projectBase == null || projectBase.isBlank()) return path;
        // Normalize to forward slashes so macro substitution works on all platforms.
        String normalizedPath = path.replace('\\', '/');
        String normalizedBase = projectBase.replace('\\', '/');
        if (normalizedPath.startsWith(normalizedBase + "/")) {
            return "$PROJECT_DIR$" + normalizedPath.substring(normalizedBase.length());
        }
        if (normalizedPath.equals(normalizedBase)) return "$PROJECT_DIR$";
        return normalizedPath;
    }

    /**
     * Escapes the five XML special characters in an attribute value or element text.
     * Returns an empty string for {@code null} input.
     */
    static String escapeXml(String value) {
        if (value == null) return "";
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("'", "&apos;");
    }
}
