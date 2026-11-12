package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileCategoryTest {

    private static JsonObject args(Object scope) {
        JsonObject o = new JsonObject();
        if (scope instanceof String s) o.addProperty(FileCategory.PARAM_SCOPE, s);
        if (scope instanceof String[] arr) {
            JsonArray a = new JsonArray();
            for (String s : arr) a.add(s);
            o.add(FileCategory.PARAM_SCOPE, a);
        }
        return o;
    }

    @Test
    void absentScopeDefaultsToProjectFilesExceptIde() {
        FileCategory.Selection s = FileCategory.parseScope(new JsonObject());
        assertNull(s.error());
        assertFalse(s.includes(FileCategory.IDE));
        assertFalse(s.includes(FileCategory.LIBRARIES));
        assertTrue(s.includes(FileCategory.SOURCES));
        assertTrue(s.includes(FileCategory.OTHER));
        assertTrue(s.includes(FileCategory.GENERATED_SOURCES));
    }

    @Test
    void emptyArrayFallsBackToDefault() {
        assertEquals(FileCategory.DEFAULT_SCOPE, FileCategory.parseScope(args(new String[0])).categories());
    }

    @Test
    void arrayFormSelectsOnlyNamedCategories() {
        Set<FileCategory> c = FileCategory.parseScope(args(new String[]{"sources", "ide"})).categories();
        assertEquals(EnumSet.of(FileCategory.SOURCES, FileCategory.IDE), c);
    }

    @Test
    void librariesOnlyIsNotProjectScope() {
        FileCategory.Selection s = FileCategory.parseScope(args(new String[]{"libraries"}));
        assertNull(s.error());
        assertTrue(s.includes(FileCategory.LIBRARIES));
        assertFalse(s.includesProject());
    }

    @Test
    void librariesWithProjectCategoryIncludesProject() {
        assertTrue(FileCategory.parseScope(args(new String[]{"libraries", "sources"})).includesProject());
    }

    @Test
    void excludedIsRejectedWithGuidanceInsteadOfBeingSearched() {
        FileCategory.Selection s = FileCategory.parseScope(args(new String[]{"sources", "excluded"}));
        assertNotNull(s.error());
        assertTrue(s.error().startsWith("Error: 'excluded' is not a scope category"));
        assertTrue(s.error().contains("run_command"));
        assertTrue(s.error().contains("mark_directory"));
    }

    @Test
    void commaSeparatedStringIsAcceptedForAgentsThatStringifyArrays() {
        Set<FileCategory> c = FileCategory.parseScope(args(" IDE , test_sources")).categories();
        assertEquals(EnumSet.of(FileCategory.IDE, FileCategory.TEST_SOURCES), c);
    }

    @Test
    void allSelectsEveryCategoryIncludingLibraries() {
        assertEquals(EnumSet.allOf(FileCategory.class),
            FileCategory.parseScope(args(new String[]{"sources", "all"})).categories());
    }

    @Test
    void allDoesNotSkipValidationOfLaterValues() {
        assertNotNull(FileCategory.parseScope(args(new String[]{"all", "excluded"})).error());
        assertNotNull(FileCategory.parseScope(args(new String[]{"all", "bogus"})).error());
    }

    @Test
    void malformedShapesAreRejectedNotDefaulted() {
        JsonObject nested = new JsonObject();
        JsonArray outer = new JsonArray();
        JsonArray inner = new JsonArray();
        inner.add("libraries");
        outer.add(inner);
        nested.add(FileCategory.PARAM_SCOPE, outer);
        assertNotNull(FileCategory.parseScope(nested).error());

        JsonObject object = new JsonObject();
        JsonObject value = new JsonObject();
        value.add("categories", new JsonArray());
        object.add(FileCategory.PARAM_SCOPE, value);
        assertNotNull(FileCategory.parseScope(object).error());

        JsonObject number = new JsonObject();
        number.addProperty(FileCategory.PARAM_SCOPE, 3);
        assertNotNull(FileCategory.parseScope(number).error());
    }

    @Test
    void oldSingleValueScopesAreUnknown() {
        FileCategory.Selection s = FileCategory.parseScope(args("production"));
        assertNotNull(s.error());
        assertTrue(s.error().startsWith("Error: unknown scope category 'production'"));
        assertTrue(s.error().contains("libraries"));
    }

    @Test
    void classifyPrecedenceIdeThenRootKindAndNeverLibraries() {
        assertEquals(FileCategory.IDE, FileCategory.classify(true, "sources"));
        assertEquals(FileCategory.TEST_RESOURCES, FileCategory.classify(false, "test_resources"));
        assertEquals(FileCategory.OTHER, FileCategory.classify(false, ""));
        assertEquals(FileCategory.OTHER, FileCategory.classify(false, "unknown_kind"));
        assertEquals(FileCategory.OTHER, FileCategory.classify(false, "libraries"));
    }

    @Test
    void skippedNoteNamesDefaultOptInsAndAlwaysExplainsExcluded() {
        String note = FileCategory.skippedNote(FileCategory.parseScope(new JsonObject()));
        assertTrue(note.contains("Not in scope: ide, libraries"));
        assertTrue(note.contains("Excluded"));
        String all = FileCategory.skippedNote(FileCategory.parseScope(args("all")));
        assertFalse(all.contains("Not in scope"));
        assertTrue(all.contains("Excluded"));
    }

    @Test
    void schemaIsStringArrayWithEnumIncludingLibrariesButNotExcluded() {
        JsonObject p = FileCategory.scopeSchema();
        assertEquals("array", p.get("type").getAsString());
        JsonArray e = p.getAsJsonObject("items").getAsJsonArray("enum");
        assertTrue(e.contains(new JsonPrimitive("all")));
        assertTrue(e.contains(new JsonPrimitive("libraries")));
        assertFalse(e.contains(new JsonPrimitive("excluded")));
    }
}
