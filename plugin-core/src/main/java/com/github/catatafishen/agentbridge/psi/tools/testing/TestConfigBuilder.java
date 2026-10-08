package com.github.catatafishen.agentbridge.psi.tools.testing;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure helpers for building test run configuration names, Gradle task prefixes,
 * FQN resolution, and parsing test targets from command strings.
 * No IDE dependencies — fully unit-testable.
 */
final class TestConfigBuilder {

    private TestConfigBuilder() {
    }

    /**
     * Builds a fully-qualified class name from package and simple name.
     */
    static String buildFqn(@Nullable String packageName, @NotNull String simpleName) {
        return packageName != null && !packageName.isEmpty() ? packageName + "." + simpleName : simpleName;
    }

    /**
     * Extracts the FQN from raw source text by parsing the {@code package} declaration via regex.
     * Fallback for when PSI reflection fails.
     */
    static String extractFqnFromSourceText(@NotNull String sourceText, @NotNull String simpleName) {
        var matcher = Pattern.compile("^package\\s+([\\w.]+)").matcher(sourceText);
        if (matcher.find()) {
            return matcher.group(1) + "." + simpleName;
        }
        return simpleName;
    }

    /**
     * Builds a JUnit run configuration name from the test class simple name and optional method.
     */
    static String buildJUnitConfigName(@NotNull String simpleName, @Nullable String testMethod) {
        return "Test: " + (testMethod != null ? simpleName + "." + testMethod : simpleName);
    }

    /**
     * Builds a pattern-based run configuration name from the glob target and match count.
     */
    static String buildPatternConfigName(@NotNull String target, int classCount) {
        return "Test: " + target + " (" + classCount + " classes)";
    }

    /**
     * Whether a test class matches a wildcard target. A target without a dot (e.g. {@code *Test}) is matched against
     * the simple class name; a package-qualified target (e.g. {@code com.example.Foo*}) is matched against the fully
     * qualified name, which Gradle's {@code --tests} filter and callers copying a class name both produce.
     *
     * @param fqn the fully qualified name, or {@code null} if it is not known yet
     */
    static boolean matchesTestTarget(@NotNull String simpleName, @Nullable String fqn, @NotNull String target,
                                     @Nullable Pattern compiledGlob) {
        if (target.isEmpty()) return true;
        if (!target.contains(".")) return !ToolUtils.doesNotMatchGlob(simpleName, target, compiledGlob);
        String qualified = fqn != null ? fqn : simpleName;
        return !ToolUtils.doesNotMatchGlob(qualified, target, compiledGlob);
    }

    /**
     * Builds a Gradle task prefix from the module name.
     * Returns an empty string for no module, or {@code ":module:"} for a named module.
     */
    static String buildGradleTaskPrefix(@NotNull String module) {
        return module.isEmpty() ? "" : ":" + module + ":";
    }

    /**
     * Normalises a Gradle {@code --tests} filter argument so it always includes a package qualifier.
     * <p>
     * Gradle's {@code --tests} filter requires a pattern in the form
     * {@code [package.]ClassName[.methodName]}.  A bare simple name such as {@code FormattingTest}
     * or a wildcard like {@code *Test} is silently rejected with
     * {@code "No tests found for given includes"} because Gradle interprets it as a root-package
     * class pattern, not an any-package wildcard.
     * <p>
     * Rules applied:
     * <ul>
     *   <li>No dot in target → prepend {@code *.}</li>
     *   <li>Has a dot but first segment starts with an uppercase letter → prepend {@code *.}</li>
     *   <li>Otherwise → return unchanged</li>
     * </ul>
     */
    static String buildGradleTestFilter(@NotNull String target) {
        if (!target.contains(".")) {
            return "*." + target;
        }
        char first = target.charAt(0);
        if (Character.isUpperCase(first)) {
            return "*." + target;
        }
        return target;
    }

    /**
     * Extracts the test filter value from a Gradle ({@code --tests <filter>}) or
     * Maven ({@code -Dtest=<filter>}) command string.
     *
     * @return the filter value, or {@code null} if no filter is present
     */
    @Nullable
    static String parseTestsFilterFromCommand(@NotNull String command) {
        var gradleMatcher = Pattern
            .compile("--tests\\s+[\"']?([^\"'\\s]+)[\"']?(?:\\s|$)")
            .matcher(command);
        if (gradleMatcher.find()) return gradleMatcher.group(1);

        var mavenMatcher = Pattern
            .compile("-Dtest=(\\S+)")
            .matcher(command);
        if (mavenMatcher.find()) return mavenMatcher.group(1);

        return null;
    }

    /**
     * Extracts the module name from a Gradle command string (e.g. {@code :plugin-core:test}).
     *
     * @return the module name, or empty string if not found
     */
    static @NotNull String parseModuleFromCommand(@NotNull String command) {
        Matcher m = Pattern.compile(
            "\\s:([a-z][a-z0-9._-]*):[a-z]",
            Pattern.CASE_INSENSITIVE).matcher(command);
        return m.find() ? m.group(1) : "";
    }

    /**
     * Extracts the task name from a Gradle command string.
     *
     * @return the task name, or {@code null} if not found
     */
    @Nullable
    static String parseTaskFromCommand(@NotNull String command) {
        var m = Pattern.compile(
            "gradlew?(?:\\.bat)?\\s+(?::[a-z][-a-z0-9._:]*:)?([a-z][a-z0-9]*+)(?:\\s|$)",
            Pattern.CASE_INSENSITIVE
        ).matcher(command);
        return m.find() ? m.group(1) : null;
    }
}
