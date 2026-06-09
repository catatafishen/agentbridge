package com.github.catatafishen.agentbridge.psi.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("IndexableRootType")
class IndexableRootTypeTest {

    @Nested
    @DisplayName("defaults")
    class Defaults {
        @Test
        void containsOnlySourcesAndTestSources() {
            Set<IndexableRootType> defaults = IndexableRootType.defaults();
            assertEquals(2, defaults.size());
            assertTrue(defaults.contains(IndexableRootType.SOURCES));
            assertTrue(defaults.contains(IndexableRootType.TEST_SOURCES));
            assertFalse(defaults.contains(IndexableRootType.RESOURCES));
            assertFalse(defaults.contains(IndexableRootType.TEST_RESOURCES));
            assertFalse(defaults.contains(IndexableRootType.GENERATED_SOURCES));
        }
    }

    @Nested
    @DisplayName("toIds and fromIds")
    class Serialization {
        @Test
        void roundTripsCorrectly() {
            Set<IndexableRootType> original = EnumSet.of(
                IndexableRootType.SOURCES, IndexableRootType.RESOURCES);
            Set<String> ids = IndexableRootType.toIds(original);

            assertEquals(Set.of("sources", "resources"), ids);

            Set<IndexableRootType> restored = IndexableRootType.fromIds(ids);
            assertEquals(original, restored);
        }

        @Test
        void unknownIdsAreIgnored() {
            Set<String> ids = Set.of("sources", "unknown_type", "future_thing");
            Set<IndexableRootType> result = IndexableRootType.fromIds(ids);
            assertEquals(EnumSet.of(IndexableRootType.SOURCES), result);
        }

        @Test
        void emptySetRoundTrips() {
            Set<String> ids = IndexableRootType.toIds(EnumSet.noneOf(IndexableRootType.class));
            assertTrue(ids.isEmpty());
            Set<IndexableRootType> result = IndexableRootType.fromIds(ids);
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("fromId")
    class FromId {
        @Test
        void resolvesKnownIds() {
            assertEquals(IndexableRootType.SOURCES, IndexableRootType.fromId("sources"));
            assertEquals(IndexableRootType.TEST_SOURCES, IndexableRootType.fromId("test_sources"));
            assertEquals(IndexableRootType.RESOURCES, IndexableRootType.fromId("resources"));
            assertEquals(IndexableRootType.TEST_RESOURCES, IndexableRootType.fromId("test_resources"));
            assertEquals(IndexableRootType.GENERATED_SOURCES, IndexableRootType.fromId("generated_sources"));
            assertEquals(IndexableRootType.GENERATED_TEST_SOURCES, IndexableRootType.fromId("generated_test_sources"));
        }

        @Test
        void returnsNullForUnknown() {
            assertNull(IndexableRootType.fromId("node_modules"));
            assertNull(IndexableRootType.fromId(""));
        }
    }

    @Nested
    @DisplayName("properties")
    class Properties {
        @Test
        void allTypesHaveNonNullColor() {
            for (IndexableRootType type : IndexableRootType.values()) {
                assertNotNull(type.color(), type.id() + " should have a color");
            }
        }

        @Test
        void allTypesHaveNonEmptyDisplayName() {
            for (IndexableRootType type : IndexableRootType.values()) {
                assertFalse(type.displayName().isEmpty(), type.id() + " should have a display name");
            }
        }

        @Test
        void idsMatchEnumConvention() {
            assertEquals("sources", IndexableRootType.SOURCES.id());
            assertEquals("test_sources", IndexableRootType.TEST_SOURCES.id());
            assertEquals("resources", IndexableRootType.RESOURCES.id());
            assertEquals("test_resources", IndexableRootType.TEST_RESOURCES.id());
            assertEquals("generated_sources", IndexableRootType.GENERATED_SOURCES.id());
            assertEquals("generated_test_sources", IndexableRootType.GENERATED_TEST_SOURCES.id());
        }
    }
}
