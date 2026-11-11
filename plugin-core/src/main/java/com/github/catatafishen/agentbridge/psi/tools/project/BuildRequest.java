package com.github.catatafishen.agentbridge.psi.tools.project;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsed arguments of a {@code build_project} call.
 *
 * @param moduleName module to build, or empty for the whole project
 * @param rebuild    true for a full rebuild instead of an incremental build
 * @param files      files to compile; when non-empty they override module and mode
 */
record BuildRequest(@NotNull String moduleName, boolean rebuild, @NotNull List<String> files) {

    static final String INVALID_ARGUMENTS_ERROR =
        "Error: Invalid arguments. 'module' and 'mode' must be strings and 'files' an array of strings"
            + " (null values are not allowed).";

    private static final String JSON_MODULE = "module";
    private static final String JSON_MODE = "mode";
    private static final String JSON_FILES = "files";

    /**
     * Parses the tool arguments.
     *
     * @return the request, or null if an argument has the wrong type (e.g. {@code mode: null}, {@code files: [null]})
     */
    static @Nullable BuildRequest parse(@NotNull JsonObject args) {
        try {
            String module = stringOrDefault(args, JSON_MODULE, "");
            boolean rebuild = "rebuild".equalsIgnoreCase(stringOrDefault(args, JSON_MODE, ""));
            return new BuildRequest(module, rebuild, parseFiles(args));
        } catch (UnsupportedOperationException | IllegalStateException | ClassCastException e) {
            return null;
        }
    }

    private static String stringOrDefault(JsonObject args, String key, String fallback) {
        return args.has(key) ? args.get(key).getAsString() : fallback;
    }

    private static List<String> parseFiles(JsonObject args) {
        List<String> files = new ArrayList<>();
        if (args.has(JSON_FILES) && args.get(JSON_FILES).isJsonArray()) {
            for (JsonElement element : args.getAsJsonArray(JSON_FILES)) {
                files.add(element.getAsString());
            }
        }
        return files;
    }
}
