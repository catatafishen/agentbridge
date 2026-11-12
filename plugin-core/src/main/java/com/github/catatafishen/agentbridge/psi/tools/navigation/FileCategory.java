package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Value of the {@code scope} parameter of the search and listing tools. Project files are categorised by how
 * their directory is marked in the project model; the source-root ids equal {@code mark_directory}'s
 * {@code type} values, so a directory marked {@code type=X} is selected by {@code scope=["X"]}.
 * {@link #LIBRARIES} covers dependency and JDK files, which are not project content.
 * Excluded directories have no category: the IDE does not index them, so the tools never read them.
 */
public enum FileCategory {
    SOURCES("sources"),
    TEST_SOURCES("test_sources"),
    RESOURCES("resources"),
    TEST_RESOURCES("test_resources"),
    GENERATED_SOURCES("generated_sources"),
    GENERATED_TEST_SOURCES("generated_test_sources"),
    /**
     * IDE project configuration (the project's {@code .idea} store, module files).
     */
    IDE("ide"),
    /**
     * Project content that is not under any marked root (README, build scripts, docs).
     */
    OTHER("other"),
    /**
     * Third-party dependencies and the JDK. Not project content and never matched by directory markings.
     */
    LIBRARIES("libraries");

    public static final String PARAM_SCOPE = "scope";
    public static final String SCOPE_ALL = "all";
    /**
     * Project files except IDE config; libraries are opt-in because they can dominate results.
     */
    public static final Set<FileCategory> DEFAULT_SCOPE =
        Collections.unmodifiableSet(EnumSet.complementOf(EnumSet.of(IDE, LIBRARIES)));

    private final String id;

    FileCategory(String id) {
        this.id = id;
    }

    public @NotNull String id() {
        return id;
    }

    public static @Nullable FileCategory fromId(@NotNull String id) {
        for (FileCategory c : values()) {
            if (c.id.equals(id)) return c;
        }
        return null;
    }

    /**
     * Category of a project file from {@code PlatformApiCompat.classifyFileSourceRoot} output. Precedence:
     * IDE config, then the marked root kind, then {@link #OTHER}. Never returns {@link #LIBRARIES}.
     */
    public static @NotNull FileCategory classify(boolean ideConfig, @NotNull String rootKind) {
        if (ideConfig) return IDE;
        FileCategory c = rootKind.isEmpty() || LIBRARIES.id.equals(rootKind) ? null : fromId(rootKind);
        return c != null ? c : OTHER;
    }

    /**
     * Result of parsing {@code scope}: either a category set or an error message.
     */
    public record Selection(@NotNull Set<FileCategory> categories, @Nullable String error) {
        public boolean includes(@NotNull FileCategory c) {
            return categories.contains(c);
        }

        /**
         * True when at least one project (non-library) category is selected.
         */
        public boolean includesProject() {
            return categories.stream().anyMatch(c -> c != LIBRARIES);
        }

        public @NotNull Set<FileCategory> skipped() {
            Set<FileCategory> s = EnumSet.allOf(FileCategory.class);
            s.removeAll(categories);
            return s;
        }
    }

    /**
     * Parses the {@code scope} argument. Accepts a JSON array of ids (the schema form), and also a single id
     * or a comma-separated string, since some agents send arrays as strings. Absent, null or empty input gives
     * {@link #DEFAULT_SCOPE}; {@code "all"} selects every category.
     */
    public static @NotNull Selection parseScope(@NotNull JsonObject args) {
        JsonElement el = args.get(PARAM_SCOPE);
        String shapeError = validateShape(el);
        if (shapeError != null) return new Selection(EnumSet.noneOf(FileCategory.class), shapeError);
        List<String> ids = readScopeIds(el);
        if (ids.isEmpty()) return new Selection(EnumSet.copyOf(DEFAULT_SCOPE), null);

        Set<FileCategory> out = EnumSet.noneOf(FileCategory.class);
        for (String id : ids) {
            if (EXCLUDED_ID.equals(id)) return new Selection(EnumSet.noneOf(FileCategory.class), EXCLUDED_ERROR);
            if (SCOPE_ALL.equals(id)) {
                out.addAll(EnumSet.allOf(FileCategory.class));
                continue;
            }
            FileCategory c = fromId(id);
            if (c == null) {
                return new Selection(EnumSet.noneOf(FileCategory.class),
                    "Error: unknown scope category '" + id + "'. Valid: " + validIds() + ", or 'all'.");
            }
            out.add(c);
        }
        return new Selection(out, null);
    }

    /**
     * Rejects shapes other than absent/null, a string, or an array of strings, so a malformed request
     * (e.g. a nested array or an object) is an error instead of silently falling back to the default scope.
     */
    private static @Nullable String validateShape(@Nullable JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        boolean ok = el.isJsonArray()
            ? el.getAsJsonArray().asList().stream()
            .allMatch(i -> i.isJsonPrimitive() && i.getAsJsonPrimitive().isString())
            : el.isJsonPrimitive() && el.getAsJsonPrimitive().isString();
        return ok ? null
            : "Error: 'scope' must be an array of category names, e.g. [\"sources\",\"test_sources\"]. Got: " + el;
    }

    /**
     * Normalized ids from a JSON array, a single string or a comma-separated string; empty when absent.
     */
    private static @NotNull List<String> readScopeIds(@Nullable JsonElement el) {
        List<String> raw = new ArrayList<>();
        if (el != null && el.isJsonArray()) {
            for (JsonElement item : el.getAsJsonArray()) raw.add(item.getAsString());
        } else if (el != null && el.isJsonPrimitive()) {
            raw.addAll(Arrays.asList(el.getAsString().split(",")));
        }
        return raw.stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList();
    }

    public static @NotNull String validIds() {
        return Arrays.stream(values()).map(FileCategory::id).collect(Collectors.joining(", "));
    }

    /**
     * Adds the {@code scope} property to a tool input schema and returns it.
     */
    public static @NotNull JsonObject withScopeParam(@NotNull JsonObject schema) {
        schema.getAsJsonObject("properties").add(PARAM_SCOPE, scopeSchema());
        return schema;
    }

    /**
     * JSON-schema property for {@code scope}: an array of enum strings.
     */
    public static @NotNull JsonObject scopeSchema() {
        JsonObject prop = new JsonObject();
        prop.addProperty("type", "array");
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        JsonArray enumValues = new JsonArray();
        for (FileCategory c : values()) enumValues.add(new JsonPrimitive(c.id));
        enumValues.add(new JsonPrimitive(SCOPE_ALL));
        items.add("enum", enumValues);
        prop.add("items", items);
        prop.addProperty("description", SCOPE_DESCRIPTION);
        return prop;
    }

    /**
     * mark_directory type for excluded directories; not a scope category.
     */
    public static final String EXCLUDED_ID = "excluded";

    /**
     * Shared wording: why excluded directories are invisible and what to do instead.
     */
    public static final String EXCLUDED_HINT =
        "Directories marked Excluded are not indexed by the IDE and are never searched or listed by IDE tools. "
            + "Use run_command to read them, or mark_directory type=unmark if they should be project content.";

    private static final String EXCLUDED_ERROR = "Error: 'excluded' is not a scope category. " + EXCLUDED_HINT;

    public static final String SCOPE_DESCRIPTION =
        "Which files to search. Project files are selected by how their directory is marked (same names as "
            + "mark_directory types); 'libraries' adds dependency and JDK files. Default: every project category "
            + "except 'ide'. Examples: [\"sources\"] for production code without tests, "
            + "[\"test_sources\",\"test_resources\"] for tests, [\"libraries\"] for dependencies only, "
            + "[\"all\"] for everything. 'ide' is IDE configuration (.idea); 'other' is project content not under "
            + "any marked root. Excluded directories are never included.";

    /**
     * Footer telling the agent which categories were not searched and that excluded directories never are.
     */
    public static @NotNull String skippedNote(@NotNull Selection selection) {
        Set<FileCategory> skipped = selection.skipped();
        String ids = skipped.stream().map(FileCategory::id).collect(Collectors.joining(", "));
        String categories = skipped.isEmpty() ? ""
            : "Not in scope: " + ids + " (pass scope=[\"all\"] to add them). ";
        return "\n(" + categories + EXCLUDED_HINT + ")";
    }
}
