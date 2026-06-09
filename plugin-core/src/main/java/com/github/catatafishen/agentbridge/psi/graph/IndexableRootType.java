package com.github.catatafishen.agentbridge.psi.graph;

import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.EnumSet;
import java.util.Set;

/**
 * Directory types that can be included in the Knowledge Graph index.
 * Each maps to the string classification returned by
 * {@link com.github.catatafishen.agentbridge.psi.PlatformApiCompat#classifyFileSourceRoot}.
 * Colors match the IDE's "Mark Directory As" UI.
 */
public enum IndexableRootType {

    SOURCES("sources", "Sources",
        new JBColor(new Color(0x0032A0), new Color(0x4A86C8))),
    TEST_SOURCES("test_sources", "Test Sources",
        new JBColor(new Color(0x0A7B0A), new Color(0x499C54))),
    RESOURCES("resources", "Resources",
        new JBColor(new Color(0x7B1FA2), new Color(0xAB47BC))),
    TEST_RESOURCES("test_resources", "Test Resources",
        new JBColor(new Color(0x6A1B9A), new Color(0x9C27B0))),
    GENERATED_SOURCES("generated_sources", "Generated Sources",
        new JBColor(new Color(0x455A64), new Color(0x78909C)));

    private final String id;
    private final String displayName;
    private final Color color;

    IndexableRootType(@NotNull String id, @NotNull String displayName, @NotNull Color color) {
        this.id = id;
        this.displayName = displayName;
        this.color = color;
    }

    public @NotNull String id() {
        return id;
    }

    public @NotNull String displayName() {
        return displayName;
    }

    /**
     * IDE-standard color for this root type (matches "Mark Directory As" palette).
     */
    public @NotNull Color color() {
        return color;
    }

    /**
     * Default set of root types included in indexing (sources + test sources only).
     */
    public static @NotNull Set<IndexableRootType> defaults() {
        return EnumSet.of(SOURCES, TEST_SOURCES);
    }

    /**
     * Converts an enum set to the string IDs used for persistence.
     */
    public static @NotNull Set<String> toIds(@NotNull Set<IndexableRootType> types) {
        Set<String> ids = new java.util.LinkedHashSet<>();
        for (IndexableRootType t : types) ids.add(t.id);
        return ids;
    }

    /**
     * Parses persisted string IDs back to the enum set.
     * Unknown IDs are silently ignored (forward compatibility).
     */
    public static @NotNull Set<IndexableRootType> fromIds(@NotNull Set<String> ids) {
        EnumSet<IndexableRootType> result = EnumSet.noneOf(IndexableRootType.class);
        for (String id : ids) {
            IndexableRootType t = fromId(id);
            if (t != null) result.add(t);
        }
        return result;
    }

    public static @Nullable IndexableRootType fromId(@NotNull String id) {
        for (IndexableRootType type : values()) {
            if (type.id.equals(id)) return type;
        }
        return null;
    }
}
