package com.github.catatafishen.ideagentforcopilot.acp.client;

import com.github.catatafishen.ideagentforcopilot.agent.AbstractAgentClient.AgentMode;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans project directories for user-defined Copilot agent {@code .md} files and
 * exposes them as {@link AgentMode} entries for the agent dropdown.
 * <p>
 * Scanned directories (relative to project root, in priority order):
 * <ol>
 *   <li>{@code .copilot/agents/}</li>
 *   <li>{@code .github/agents/}</li>
 * </ol>
 * If both directories contain a file with the same name, the {@code .copilot/agents/} version wins.
 */
final class ProjectAgentScanner {

    private static final Logger LOG = Logger.getInstance(ProjectAgentScanner.class);

    private static final String[] AGENT_DIRS = {".copilot/agents", ".github/agents"};

    private ProjectAgentScanner() {
    }

    /**
     * Scans for {@code .md} agent files in the project's agent directories and returns
     * an {@link AgentMode} for each one. Slugs that collide with {@code builtInSlugs}
     * are excluded so that built-in agents are never shadowed.
     *
     * @param projectBasePath root of the open project
     * @param builtInSlugs    slugs of the hardcoded built-in agents to exclude
     * @return discovered agents (may be empty, never null)
     */
    static @NotNull List<AgentMode> scanProjectAgents(@NotNull Path projectBasePath,
                                                      @NotNull Set<String> builtInSlugs) {
        Map<String, AgentMode> discovered = new LinkedHashMap<>();

        for (String relDir : AGENT_DIRS) {
            Path dir = projectBasePath.resolve(relDir);
            if (!Files.isDirectory(dir)) continue;

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.md")) {
                for (Path file : stream) {
                    String filename = file.getFileName().toString();
                    String slug = filename.substring(0, filename.length() - ".md".length());
                    if (builtInSlugs.contains(slug) || discovered.containsKey(slug)) continue;

                    AgentMode mode = parseAgentFile(file, slug);
                    if (mode != null) {
                        discovered.put(slug, mode);
                    }
                }
            } catch (IOException e) {
                LOG.warn("Failed to scan agent directory: " + dir, e);
            }
        }

        return new ArrayList<>(discovered.values());
    }

    /**
     * Copies all discovered project agent {@code .md} files into the global Copilot agents
     * directory ({@code ~/.copilot/agents/}) so the CLI can resolve them via {@code --agent <slug>}.
     * Built-in agent filenames are never overwritten.
     *
     * @param projectBasePath root of the open project
     * @param globalAgentsDir the global agents directory (e.g. {@code ~/.copilot/agents/})
     * @param builtInSlugs    slugs of the hardcoded built-in agents to skip
     */
    static void copyToGlobalAgentsDir(@NotNull Path projectBasePath,
                                      @NotNull Path globalAgentsDir,
                                      @NotNull Set<String> builtInSlugs) {
        for (String relDir : AGENT_DIRS) {
            Path dir = projectBasePath.resolve(relDir);
            if (!Files.isDirectory(dir)) continue;

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.md")) {
                for (Path file : stream) {
                    String filename = file.getFileName().toString();
                    String slug = filename.substring(0, filename.length() - ".md".length());
                    if (builtInSlugs.contains(slug)) continue;

                    Path dest = globalAgentsDir.resolve(filename);
                    try {
                        Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        LOG.warn("Failed to copy agent file to global dir: " + file + " → " + dest, e);
                    }
                }
            } catch (IOException e) {
                LOG.warn("Failed to scan agent directory for copy: " + dir, e);
            }
        }
    }

    /**
     * Parses a {@code .md} agent file's YAML frontmatter to extract {@code name} and {@code description}.
     * Returns {@code null} if the file has no valid frontmatter.
     */
    private static AgentMode parseAgentFile(@NotNull Path file, @NotNull String slug) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Failed to read agent file: " + file, e);
            return null;
        }

        if (lines.isEmpty() || !lines.get(0).trim().equals("---")) {
            return null;
        }

        String name = null;
        String description = null;

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.equals("---")) break;

            if (line.startsWith("name:")) {
                name = stripYamlValue(line.substring("name:".length()));
            } else if (line.startsWith("description:")) {
                description = stripYamlValue(line.substring("description:".length()));
            }
        }

        if (name == null || name.isEmpty()) {
            name = slug;
        }

        return new AgentMode(slug, name, description);
    }

    /**
     * Strips leading/trailing whitespace and surrounding quotes from a YAML scalar value.
     */
    private static @NotNull String stripYamlValue(@NotNull String raw) {
        String trimmed = raw.trim();
        if (trimmed.length() >= 2
                && ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
