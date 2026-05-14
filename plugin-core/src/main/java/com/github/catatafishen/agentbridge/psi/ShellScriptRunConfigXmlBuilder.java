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
     * @param scriptOptions      arguments passed to the script (may be {@code null})
     * @param interpreterPath    interpreter binary; defaults to {@code /bin/bash} when blank
     * @param interpreterOptions options for the interpreter (may be {@code null})
     * @param workingDir         working directory; defaults to {@code projectDir} when blank
     * @param executeInTerminal  {@code true} → embedded terminal, {@code false} → Run panel
     */
    public record ShellScriptConfig(
        String scriptPath,
        String scriptText,
        String scriptOptions,
        String interpreterPath,
        String interpreterOptions,
        String workingDir,
        boolean executeInTerminal
    ) {
    }

    /**
     * Returns {@code true} if {@code type} refers to a Shell Script run configuration.
     * Accepts {@code "Shell Script"}, {@code "sh"}, and the raw type ID
     * {@code "ShConfigurationType"} (all case-insensitive).
     */
    public static boolean isShellScriptType(String type) {
        if (type == null) return false;
        String lower = type.toLowerCase();
        return lower.equals("shell script") || lower.equals("sh")
            || lower.equalsIgnoreCase(TYPE_ID);
    }

    /**
     * Builds a {@code ShConfigurationType} run configuration XML block.
     *
     * @param name       run configuration name
     * @param config     script and execution parameters
     * @param projectDir absolute project base path used to emit {@code $PROJECT_DIR$} macros
     * @return XML string for writing to {@code .idea/runConfigurations/<name>.xml}
     */
    public static String build(String name, ShellScriptConfig config, String projectDir) {
        boolean executeScriptFile = config.scriptPath() != null && !config.scriptPath().isEmpty();

        String resolvedInterpreter = (config.interpreterPath() != null && !config.interpreterPath().isEmpty())
            ? config.interpreterPath() : DEFAULT_INTERPRETER;

        String fallbackWorkingDir = projectDir != null ? projectDir : "";
        String resolvedWorkingDir = (config.workingDir() != null && !config.workingDir().isEmpty())
            ? config.workingDir() : fallbackWorkingDir;

        String macroScriptPath = applyProjectDirMacro(
            config.scriptPath() != null ? config.scriptPath() : "", projectDir);
        String macroWorkingDir = applyProjectDirMacro(resolvedWorkingDir, projectDir);

        return "<component name=\"ProjectRunConfigurationManager\">\n"
            + "  <configuration default=\"false\" name=\"" + escapeXml(name) + "\""
            + " type=\"ShConfigurationType\">\n"
            + "    <option name=\"SCRIPT_TEXT\" value=\""
            + escapeXml(config.scriptText() != null ? config.scriptText() : "") + "\" />\n"
            + "    <option name=\"INDEPENDENT_SCRIPT_PATH\" value=\"true\" />\n"
            + "    <option name=\"SCRIPT_PATH\" value=\"" + escapeXml(macroScriptPath) + "\" />\n"
            + "    <option name=\"SCRIPT_OPTIONS\" value=\""
            + escapeXml(config.scriptOptions() != null ? config.scriptOptions() : "") + "\" />\n"
            + "    <option name=\"INDEPENDENT_SCRIPT_WORKING_DIRECTORY\" value=\"true\" />\n"
            + "    <option name=\"SCRIPT_WORKING_DIRECTORY\" value=\"" + escapeXml(macroWorkingDir) + "\" />\n"
            + "    <option name=\"INDEPENDENT_INTERPRETER_PATH\" value=\"true\" />\n"
            + "    <option name=\"INTERPRETER_PATH\" value=\"" + escapeXml(resolvedInterpreter) + "\" />\n"
            + "    <option name=\"INTERPRETER_OPTIONS\" value=\""
            + escapeXml(config.interpreterOptions() != null ? config.interpreterOptions() : "") + "\" />\n"
            + "    <option name=\"EXECUTE_IN_TERMINAL\" value=\"" + config.executeInTerminal() + "\" />\n"
            + "    <option name=\"EXECUTE_SCRIPT_FILE\" value=\"" + executeScriptFile + "\" />\n"
            + "    <method v=\"2\" />\n"
            + "  </configuration>\n"
            + "</component>";
    }

    /**
     * Replaces a leading {@code projectDir} prefix in {@code path} with {@code $PROJECT_DIR$}.
     * Returns {@code path} unchanged when either argument is null/empty, or when
     * {@code path} does not start with {@code projectDir}.
     */
    static String applyProjectDirMacro(String path, String projectDir) {
        if (path == null || path.isEmpty() || projectDir == null || projectDir.isEmpty()) {
            return path != null ? path : "";
        }
        if (path.startsWith(projectDir)) {
            return "$PROJECT_DIR$" + path.substring(projectDir.length());
        }
        return path;
    }

    /**
     * Escapes characters that are illegal inside an XML attribute value.
     */
    static String escapeXml(String value) {
        if (value == null) return "";
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }
}
