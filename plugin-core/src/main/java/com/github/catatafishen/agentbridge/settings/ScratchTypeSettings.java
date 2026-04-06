package com.github.catatafishen.agentbridge.settings;

import com.intellij.lang.Language;
import com.intellij.lang.LanguageUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Application-level settings for scratch file configuration:
 * <ul>
 *   <li>Which languages appear in the "New Scratch File" dropdown (enabled language IDs)</li>
 *   <li>Extra alias→extension mappings for the "Open in Scratch" code-block button</li>
 * </ul>
 * <p>
 * The primary language resolution uses IntelliJ's registered {@link Language} registry
 * (matching by ID and display name). Custom alias mappings serve as overrides for labels
 * the Language API cannot resolve on its own (e.g. "bash" → "sh", "golang" → "go").
 */
@Service(Service.Level.APP)
@State(name = "ScratchTypeSettings", storages = @Storage("ideAgentScratchTypes.xml"))
public final class ScratchTypeSettings implements PersistentStateComponent<ScratchTypeSettings.State> {

    private State myState = new State();

    public static ScratchTypeSettings getInstance() {
        return ApplicationManager.getApplication().getService(ScratchTypeSettings.class);
    }

    // ── Enabled languages (for the "New Scratch File" dropdown) ──

    public Set<String> getEnabledLanguageIds() {
        return myState.enabledLanguageIds;
    }

    public void setEnabledLanguageIds(Set<String> ids) {
        myState.enabledLanguageIds = new LinkedHashSet<>(ids);
    }

    /**
     * Returns the enabled languages that are actually installed in the IDE,
     * sorted by display name. Languages whose IDs are in the enabled set but
     * are not installed are silently excluded.
     */
    public List<Language> getEnabledLanguages() {
        Set<String> enabled = myState.enabledLanguageIds;
        return LanguageUtil.getFileLanguages().stream()
            .filter(lang -> enabled.contains(lang.getID()))
            .toList();
    }

    // ── Alias mappings (for "Open in Scratch" code-block resolution) ──

    public Map<String, String> getMappings() {
        return myState.mappings;
    }

    public void setMappings(Map<String, String> mappings) {
        myState.mappings = new LinkedHashMap<>(mappings);
    }

    /**
     * Resolves a language label (e.g. from a markdown code block) to a file
     * extension. Tries the IntelliJ Language registry first, then falls back
     * to custom alias mappings, and finally treats the input itself as an
     * extension.
     */
    public String resolve(String language) {
        if (language == null || language.isEmpty()) return "txt";

        String lower = language.toLowerCase();

        // 1. Try IntelliJ's Language registry (by ID, then by display name)
        String ext = resolveViaLanguageRegistry(lower);
        if (ext != null) return ext;

        // 2. Try custom alias overrides
        String mapped = myState.mappings.get(lower);
        if (mapped != null) return mapped;

        // 3. Fall back to using the input as the extension
        return lower;
    }

    static String resolveViaLanguageRegistry(String label) {
        for (Language lang : Language.getRegisteredLanguages()) {
            if (lang.getID().equalsIgnoreCase(label)
                || lang.getDisplayName().equalsIgnoreCase(label)) {
                LanguageFileType ft = lang.getAssociatedFileType();
                if (ft != null && !ft.getDefaultExtension().isEmpty()) {
                    return ft.getDefaultExtension();
                }
            }
        }
        return null;
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
        private Set<String> enabledLanguageIds = getDefaultEnabledIds();
        private Map<String, String> mappings = getDefaults();

        public Set<String> getEnabledLanguageIds() {
            return enabledLanguageIds;
        }

        public void setEnabledLanguageIds(Set<String> enabledLanguageIds) {
            this.enabledLanguageIds = enabledLanguageIds;
        }

        public Map<String, String> getMappings() {
            return mappings;
        }

        public void setMappings(Map<String, String> mappings) {
            this.mappings = mappings;
        }
    }

    /**
     * A curated set of commonly used language IDs shown in the dropdown by default.
     * Languages not installed in the current IDE are silently excluded at runtime.
     */
    public static Set<String> getDefaultEnabledIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add("JAVA");
        ids.add("kotlin");
        ids.add("Groovy");
        ids.add("Python");
        ids.add("JavaScript");
        ids.add("TypeScript");
        ids.add("TypeScript JSX");
        ids.add("Shell Script");
        ids.add("SQL");
        ids.add("JSON");
        ids.add("XML");
        ids.add("yaml");
        ids.add("TOML");
        ids.add("Properties");
        ids.add("HTML");
        ids.add("CSS");
        ids.add("Markdown");
        ids.add("TEXT");
        ids.add("go");
        ids.add("Rust");
        return ids;
    }

    /**
     * Only aliases that the Language registry cannot resolve on its own.
     * Common code-fence labels that don't match any Language ID or display name.
     */
    public static Map<String, String> getDefaults() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("bash", "sh");
        m.put("zsh", "sh");
        m.put("shell", "sh");
        m.put("golang", "go");
        m.put("c++", "cpp");
        m.put("yml", "yaml");
        m.put("kts", "kts");
        m.put("jsx", "jsx");
        m.put("tsx", "tsx");
        m.put("mjs", "js");
        m.put("mts", "ts");
        return m;
    }
}
