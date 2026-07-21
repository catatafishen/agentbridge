package com.github.catatafishen.agentbridge.psi.tools.notebook;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;

/**
 * A parsed, mutable Jupyter notebook backed by its nbformat JSON tree.
 *
 * <p>Cell operations mutate the wrapped {@link JsonObject} in place, preserving every field the
 * tools do not understand (kernel metadata, {@code ExecuteTime}, unknown output MIME bundles, …).
 * {@link #toJson()} re-serializes with the original file's conventions (see {@link NotebookJson}).
 *
 * <p>Pure logic only — no IDE, filesystem, or threading dependencies, so it is unit-testable
 * against real {@code .ipynb} fixtures.
 */
public final class NotebookModel {

    public static final String CODE = "code";
    public static final String MARKDOWN = "markdown";
    public static final String RAW = "raw";
    private static final Set<String> VALID_TYPES = Set.of(CODE, MARKDOWN, RAW);

    private static final String KEY_CELLS = "cells";
    private static final String KEY_CELL_TYPE = "cell_type";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_OUTPUTS = "outputs";
    private static final String KEY_EXECUTION_COUNT = "execution_count";
    private static final String KEY_METADATA = "metadata";
    private static final String KEY_ID = "id";

    private final JsonObject root;
    private final String lineEnding;
    private JsonArray cells;

    private NotebookModel(@NotNull JsonObject root, @NotNull String lineEnding) {
        this.root = root;
        this.lineEnding = lineEnding;
        JsonElement c = root.get(KEY_CELLS);
        if (c == null || !c.isJsonArray()) {
            throw new NotebookJson.NotebookParseException(
                "Notebook has no 'cells' array — not a valid .ipynb file");
        }
        this.cells = c.getAsJsonArray();
    }

    /** Parses raw {@code .ipynb} text, capturing its line ending for a faithful round-trip. */
    public static @NotNull NotebookModel parse(@NotNull String rawJson) {
        JsonObject root = NotebookJson.parse(rawJson);
        return new NotebookModel(root, NotebookJson.detectLineEnding(rawJson));
    }

    public int cellCount() {
        return cells.size();
    }

    /** Best-effort kernel name from {@code metadata.kernelspec} ({@code display_name} or {@code name}), or null. */
    public @Nullable String kernelName() {
        JsonElement md = root.get(KEY_METADATA);
        if (md == null || !md.isJsonObject()) {
            return null;
        }
        JsonElement ks = md.getAsJsonObject().get("kernelspec");
        if (ks == null || !ks.isJsonObject()) {
            return null;
        }
        JsonObject spec = ks.getAsJsonObject();
        for (String key : new String[]{"display_name", "name"}) {
            JsonElement v = spec.get(key);
            if (v != null && v.isJsonPrimitive()) {
                return v.getAsString();
            }
        }
        return null;
    }

    /**
     * Returns the cell object at a 0-based index.
     *
     * @throws NotebookException if the index is out of range
     */
    public @NotNull JsonObject cellAt(int index) {
        if (index < 0 || index >= cells.size()) {
            throw new NotebookException(
                "cell index " + index + " out of range (notebook has " + cells.size() + " cells, 0-based)");
        }
        JsonElement el = cells.get(index);
        if (!el.isJsonObject()) {
            throw new NotebookException("cell at index " + index + " is not a JSON object");
        }
        return el.getAsJsonObject();
    }

    /** Returns the 0-based index of the cell with the given id, or {@code -1} if none. */
    public int indexOfId(@NotNull String id) {
        for (int i = 0; i < cells.size(); i++) {
            JsonElement el = cells.get(i);
            if (el.isJsonObject()) {
                String cid = cellId(el.getAsJsonObject());
                if (id.equals(cid)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Resolves a cell reference supplied as a 0-based {@code index}, a cell {@code id}, or both,
     * to a validated 0-based index.
     *
     * @throws NotebookException if neither is supplied, the index is out of range, or the id is
     *                           unknown
     */
    public int resolveIndex(@Nullable Integer index, @Nullable String id) {
        if (id != null && !id.isBlank()) {
            int byId = indexOfId(id);
            if (byId < 0) {
                throw new NotebookException("no cell with id '" + id + "' in this notebook");
            }
            if (index != null && index != byId) {
                throw new NotebookException(
                    "cell id '" + id + "' is at index " + byId + ", but index " + index + " was also given"
                        + " — pass only one of index/cell_id");
            }
            return byId;
        }
        if (index == null) {
            throw new NotebookException("either 'index' (0-based) or 'cell_id' is required");
        }
        if (index < 0 || index >= cells.size()) {
            throw new NotebookException(
                "cell index " + index + " out of range (notebook has " + cells.size() + " cells, 0-based)");
        }
        return index;
    }

    // ── Cell field accessors (static, null-safe) ──────────────────────────────

    public static @NotNull String cellType(@NotNull JsonObject cell) {
        JsonElement t = cell.get(KEY_CELL_TYPE);
        return t != null && t.isJsonPrimitive() ? t.getAsString() : CODE;
    }

    public static @Nullable String cellId(@NotNull JsonObject cell) {
        JsonElement id = cell.get(KEY_ID);
        return id != null && id.isJsonPrimitive() ? id.getAsString() : null;
    }

    /** The 1-based execution counter, or {@code null} if the cell has not been run (or is not code). */
    public static @Nullable Integer executionCount(@NotNull JsonObject cell) {
        JsonElement ec = cell.get(KEY_EXECUTION_COUNT);
        return ec != null && ec.isJsonPrimitive() && ec.getAsJsonPrimitive().isNumber()
            ? ec.getAsInt() : null;
    }

    public static @NotNull String sourceOf(@NotNull JsonObject cell) {
        return NotebookJson.linesToString(cell.get(KEY_SOURCE));
    }

    public static @NotNull JsonArray outputsOf(@NotNull JsonObject cell) {
        JsonElement o = cell.get(KEY_OUTPUTS);
        return o != null && o.isJsonArray() ? o.getAsJsonArray() : new JsonArray();
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    /**
     * Replaces a cell's source text. Existing outputs are left untouched (they become stale until
     * the cell is re-run), matching Jupyter's own edit behaviour.
     */
    public void setCellSource(int index, @NotNull String newSource) {
        cellAt(index).add(KEY_SOURCE, NotebookJson.stringToLines(newSource));
    }

    /**
     * Inserts a new cell of the given type at a 0-based position (clamped to {@code [0, cellCount]}).
     * Code cells are created with an empty {@code outputs} array and {@code null execution_count};
     * markdown/raw cells omit both, per nbformat. Returns the created cell (with its generated id).
     *
     * @throws NotebookException if {@code cellType} is not code/markdown/raw
     */
    public @NotNull JsonObject addCell(int atIndex, @NotNull String cellType, @NotNull String source) {
        if (!VALID_TYPES.contains(cellType)) {
            throw new NotebookException(invalidTypeMessage(cellType));
        }
        int pos = Math.clamp(atIndex, 0, cells.size());
        JsonObject cell = new JsonObject();
        cell.addProperty(KEY_CELL_TYPE, cellType);
        cell.add(KEY_METADATA, new JsonObject());
        cell.addProperty(KEY_ID, newCellId());
        cell.add(KEY_SOURCE, NotebookJson.stringToLines(source));
        if (CODE.equals(cellType)) {
            cell.add(KEY_EXECUTION_COUNT, JsonNull.INSTANCE);
            cell.add(KEY_OUTPUTS, new JsonArray());
        }
        insert(pos, cell);
        return cell;
    }

    /**
     * Removes the cell at a 0-based index and returns it.
     *
     * @throws NotebookException if the index is out of range
     */
    public @NotNull JsonObject deleteCell(int index) {
        JsonObject removed = cellAt(index);
        cells.remove(index);
        return removed;
    }

    /**
     * Moves the cell at {@code from} so that its final 0-based position is {@code to}.
     *
     * @throws NotebookException if either index is out of range
     */
    public void moveCell(int from, int to) {
        JsonObject cell = cellAt(from);
        if (to < 0 || to >= cells.size()) {
            throw new NotebookException(
                "target index " + to + " out of range (notebook has " + cells.size() + " cells, 0-based)");
        }
        cells.remove(from);
        insert(to, cell);
    }

    /**
     * Changes a cell's type. Converting to {@code code} adds an empty {@code outputs} array and a
     * {@code null execution_count}; converting to markdown/raw strips both (nbformat forbids them
     * on non-code cells). Source, id and metadata are preserved.
     *
     * @throws NotebookException if the index is out of range or {@code newType} is invalid
     */
    public void changeCellType(int index, @NotNull String newType) {
        if (!VALID_TYPES.contains(newType)) {
            throw new NotebookException(invalidTypeMessage(newType));
        }
        JsonObject cell = cellAt(index);
        cell.addProperty(KEY_CELL_TYPE, newType);
        if (CODE.equals(newType)) {
            if (!cell.has(KEY_EXECUTION_COUNT)) {
                cell.add(KEY_EXECUTION_COUNT, JsonNull.INSTANCE);
            }
            if (!cell.has(KEY_OUTPUTS)) {
                cell.add(KEY_OUTPUTS, new JsonArray());
            }
        } else {
            cell.remove(KEY_OUTPUTS);
            cell.remove(KEY_EXECUTION_COUNT);
        }
    }

    /** Serializes the (possibly mutated) notebook back to nbformat text. */
    public @NotNull String toJson() {
        return NotebookJson.serialize(root, lineEnding);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void insert(int index, @NotNull JsonObject cell) {
        JsonArray rebuilt = new JsonArray(cells.size() + 1);
        for (int i = 0; i < index; i++) {
            rebuilt.add(cells.get(i));
        }
        rebuilt.add(cell);
        for (int i = index; i < cells.size(); i++) {
            rebuilt.add(cells.get(i));
        }
        root.add(KEY_CELLS, rebuilt);
        cells = rebuilt;
    }

    private @NotNull String newCellId() {
        String candidate;
        do {
            candidate = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        } while (indexOfId(candidate) >= 0);
        return candidate;
    }

    private static @NotNull String invalidTypeMessage(@NotNull String type) {
        return "invalid cell type '" + type + "' — must be one of: " + CODE + ", " + MARKDOWN + ", " + RAW;
    }

    /** Thrown for invalid cell references or operations; the message is agent-facing. */
    public static final class NotebookException extends RuntimeException {
        public NotebookException(String message) {
            super(message);
        }
    }
}
