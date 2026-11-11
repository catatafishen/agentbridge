package com.github.catatafishen.agentbridge.psi.tools.project;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildRequestTest {

    @Test
    void emptyArgumentsMeanIncrementalBuildOfWholeProject() {
        BuildRequest request = BuildRequest.parse(new JsonObject());
        assertNotNull(request);
        assertEquals("", request.moduleName());
        assertFalse(request.rebuild());
        assertTrue(request.files().isEmpty());
    }

    @Test
    void parsesModuleModeAndFiles() {
        JsonObject args = new JsonObject();
        args.addProperty("module", "plugin-core");
        args.addProperty("mode", "REBUILD");
        JsonArray files = new JsonArray();
        files.add("a/B.java");
        files.add("c/D.java");
        args.add("files", files);

        BuildRequest request = BuildRequest.parse(args);
        assertNotNull(request);
        assertEquals("plugin-core", request.moduleName());
        assertTrue(request.rebuild(), "mode is case-insensitive");
        assertEquals(List.of("a/B.java", "c/D.java"), request.files());
    }

    @Test
    void unknownModeIsAnIncrementalBuild() {
        JsonObject args = new JsonObject();
        args.addProperty("mode", "build");
        BuildRequest request = BuildRequest.parse(args);
        assertNotNull(request);
        assertFalse(request.rebuild());
    }

    @Test
    void nullModeIsRejected() {
        JsonObject args = new JsonObject();
        args.add("mode", JsonNull.INSTANCE);
        assertNull(BuildRequest.parse(args));
    }

    @Test
    void nullModuleIsRejected() {
        JsonObject args = new JsonObject();
        args.add("module", JsonNull.INSTANCE);
        assertNull(BuildRequest.parse(args));
    }

    @Test
    void nullFileEntryIsRejected() {
        JsonObject args = new JsonObject();
        JsonArray files = new JsonArray();
        files.add(JsonNull.INSTANCE);
        args.add("files", files);
        assertNull(BuildRequest.parse(args));
    }

    @Test
    void nonArrayFilesAreIgnored() {
        JsonObject args = new JsonObject();
        args.addProperty("files", "a/B.java");
        BuildRequest request = BuildRequest.parse(args);
        assertNotNull(request);
        assertTrue(request.files().isEmpty());
    }
}
