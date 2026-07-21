package com.github.catatafishen.agentbridge.psi.tools.notebook;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonWriter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;

/**
 * Pure helpers for reading and writing Jupyter {@code .ipynb} notebooks in the
 * <a href="https://nbformat.readthedocs.io/">nbformat</a> JSON representation.
 *
 * <p>The on-disk {@code .ipynb} file is the source of truth for cell outputs — the IntelliJ
 * notebook editor exposes only a transformed {@code #%%} script document that omits outputs
 * and stable cell ids. Notebook tools therefore operate on the raw JSON bytes rather than the
 * editor {@code Document}.
 *
 * <p>Serialization mirrors the conventions Jupyter/DataSpell write, so a parse → mutate one
 * field → serialize round-trip produces a minimal git diff:
 * <ul>
 *   <li>one-space indentation;</li>
 *   <li>{@code null} values preserved (e.g. {@code "execution_count": null});</li>
 *   <li>non-ASCII written verbatim (no {@code \\uXXXX} escaping);</li>
 *   <li>a single-line {@code source}/{@code text} stored as a JSON string, multi-line as an
 *       array of lines each terminated by {@code \n} (except the last), matching
 *       {@code str.splitlines(keepends=True)};</li>
 *   <li>the file's original structural line ending (LF or CRLF) preserved, plus a trailing
 *       newline.</li>
 * </ul>
 *
 * <p>All methods are pure and side-effect free — no IDE, filesystem, or threading dependencies.
 */
public final class NotebookJson {

    /**
     * Nulls must be serialized so {@code "execution_count": null} round-trips; HTML escaping is
     * disabled so characters such as {@code <}, {@code >} and {@code &} in source/output are not
     * rewritten to {@code \\uXXXX}. Indentation and line endings are applied by {@link #serialize}.
     */
    private static final Gson GSON = new GsonBuilder()
        .serializeNulls()
        .disableHtmlEscaping()
        .create();

    private NotebookJson() {
    }

    /**
     * Parses raw {@code .ipynb} text into a mutable JSON tree. Gson's {@code JsonObject} preserves
     * key insertion order, so unmodified cells serialize back byte-identically.
     *
     * @throws NotebookParseException if the text is not a JSON object
     */
    public static @NotNull JsonObject parse(@NotNull String rawJson) {
        try {
            JsonElement root = JsonParser.parseString(rawJson);
            if (!root.isJsonObject()) {
                throw new NotebookParseException("Notebook root is not a JSON object");
            }
            return root.getAsJsonObject();
        } catch (NotebookParseException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new NotebookParseException("Invalid notebook JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Detects the dominant structural line ending of the raw notebook text so it can be preserved
     * on write. Returns {@code "\r\n"} if the first line break is a CRLF, otherwise {@code "\n"}.
     */
    public static @NotNull String detectLineEnding(@NotNull String rawJson) {
        int nl = rawJson.indexOf('\n');
        if (nl > 0 && rawJson.charAt(nl - 1) == '\r') {
            return "\r\n";
        }
        return "\n";
    }

    /**
     * Serializes a notebook tree to text using Jupyter conventions (one-space indent, nulls kept,
     * non-ASCII verbatim), with the given structural line ending and a trailing newline.
     */
    public static @NotNull String serialize(@NotNull JsonObject root, @NotNull String lineEnding) {
        StringWriter sw = new StringWriter();
        try (JsonWriter jw = new JsonWriter(sw)) {
            jw.setIndent(" ");
            GSON.toJson(root, jw);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize notebook", e);
        }
        String out = sw.toString();
        if (!"\n".equals(lineEnding)) {
            // JsonWriter emits only '\n' as a structural break; in-string newlines are the escaped
            // two-char sequence "\\n", so a blanket replace touches structural breaks only.
            out = out.replace("\n", lineEnding);
        }
        return out + lineEnding;
    }

    /**
     * Reconstructs the full text of an nbformat {@code source}/{@code text} field, which may be a
     * single JSON string or an array of line fragments (each already carrying its own {@code \n}).
     * Returns {@code ""} for {@code null}/absent.
     */
    public static @NotNull String linesToString(@Nullable JsonElement field) {
        if (field == null || field.isJsonNull()) {
            return "";
        }
        if (field.isJsonPrimitive()) {
            return field.getAsString();
        }
        if (field.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement el : field.getAsJsonArray()) {
                if (el != null && !el.isJsonNull()) {
                    sb.append(el.getAsString());
                }
            }
            return sb.toString();
        }
        return "";
    }

    /**
     * Encodes text into the nbformat {@code source} shape: a plain JSON string when there is no
     * embedded newline, otherwise an array of lines split with {@code keepends} semantics (each
     * element retains its trailing {@code \n} except the last). Mirrors how Jupyter/DataSpell
     * store the field, keeping diffs minimal.
     */
    public static @NotNull JsonElement stringToLines(@NotNull String text) {
        if (text.indexOf('\n') < 0) {
            return new JsonPrimitive(text);
        }
        String[] parts = text.split("\n", -1);
        JsonArray arr = new JsonArray();
        for (int i = 0; i < parts.length; i++) {
            boolean last = i == parts.length - 1;
            if (last && parts[i].isEmpty()) {
                // A trailing '\n' already rides on the previous element — no empty tail entry.
                break;
            }
            arr.add(last ? parts[i] : parts[i] + "\n");
        }
        return arr;
    }

    /** Thrown when {@code .ipynb} text cannot be parsed as an nbformat notebook. */
    public static final class NotebookParseException extends RuntimeException {
        public NotebookParseException(String message) {
            super(message);
        }

        public NotebookParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
