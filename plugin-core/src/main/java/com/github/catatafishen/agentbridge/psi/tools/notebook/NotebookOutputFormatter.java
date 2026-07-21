package com.github.catatafishen.agentbridge.psi.tools.notebook;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Pattern;

/**
 * Pure rendering of notebook cells and their outputs to compact, agent-readable text.
 *
 * <p>Cell outputs follow the nbformat output schema — {@code stream}, {@code execute_result},
 * {@code display_data}, {@code error}. Rich MIME bundles (images, HTML, JSON) are summarized
 * rather than dumped so responses stay token-efficient; {@code text/plain} (the console
 * representation Jupyter always includes) is shown verbatim within a per-output cap.
 *
 * <p>No IDE, filesystem, or threading dependencies — unit-testable in isolation.
 */
public final class NotebookOutputFormatter {

    /** Per-output character cap for {@code notebook_read_cell}. */
    public static final int DEFAULT_MAX_OUTPUT_CHARS = 4000;
    /** Source preview length for {@code notebook_list_cells}. */
    public static final int LIST_PREVIEW_CHARS = 80;

    private static final String TEXT_PLAIN = "text/plain";

    /**
     * ANSI CSI sequence such as {@code ESC[0;31m}. Built from {@code (char) 27} so the required
     * leading {@code ESC} (U+001B) is explicit and no raw control byte lives in the source — this
     * guarantees ordinary text such as {@code data[index]} is never matched and stripped.
     */
    private static final Pattern ANSI_CSI = Pattern.compile(((char) 27) + "\\[[0-9;?]*[A-Za-z]");

    private NotebookOutputFormatter() {
    }

    // ── list view ─────────────────────────────────────────────────────────────

    /**
     * One compact line per cell for {@code notebook_list_cells}, e.g.
     * {@code [2] code     #71137a7b  exec=3  out:text  | x = random.randint(0, N - 1) …}.
     */
    public static @NotNull String listLine(@NotNull JsonObject cell, int index) {
        String type = NotebookModel.cellType(cell);
        String id = NotebookModel.cellId(cell);
        Integer exec = NotebookModel.executionCount(cell);
        String source = NotebookModel.sourceOf(cell);

        StringBuilder sb = new StringBuilder();
        sb.append('[').append(index).append("] ").append(pad(type, 8));
        sb.append(" #").append(id != null ? id : "--------");
        if (NotebookModel.CODE.equals(type)) {
            sb.append("  exec=").append(exec != null ? exec : "·");
            String outSummary = shortOutputSummary(NotebookModel.outputsOf(cell));
            if (!outSummary.isEmpty()) {
                sb.append("  ").append(outSummary);
            }
        }
        sb.append("  | ").append(previewSource(source));
        return sb.toString();
    }

    private static @NotNull String previewSource(@NotNull String source) {
        String firstLine = source;
        int nl = source.indexOf('\n');
        boolean multiline = nl >= 0;
        if (multiline) {
            firstLine = source.substring(0, nl);
        }
        String preview = truncate(firstLine.strip(), LIST_PREVIEW_CHARS);
        if (multiline) {
            preview = preview + " …";
        }
        return preview.isEmpty() ? "(empty)" : preview;
    }

    /** Very short output tag for the list view: {@code out:text}, {@code out:image}, {@code ERROR:NameError}, or "". */
    public static @NotNull String shortOutputSummary(@NotNull JsonArray outputs) {
        if (outputs.isEmpty()) {
            return "";
        }
        for (JsonElement el : outputs) {
            if (el.isJsonObject() && "error".equals(outputType(el.getAsJsonObject()))) {
                return "ERROR:" + stringField(el.getAsJsonObject(), "ename", "error");
            }
        }
        boolean hasImage = false;
        boolean hasText = false;
        for (JsonElement el : outputs) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            JsonObject data = o.has("data") && o.get("data").isJsonObject() ? o.getAsJsonObject("data") : null;
            if (data != null) {
                for (String key : data.keySet()) {
                    if (key.startsWith("image/")) {
                        hasImage = true;
                    } else if (!key.isEmpty()) {
                        hasText = true;
                    }
                }
            } else if ("stream".equals(outputType(o))) {
                hasText = true;
            }
        }
        if (hasImage) {
            return hasText ? "out:text+image" : "out:image";
        }
        return hasText ? "out:text" : "out:" + outputs.size();
    }

    // ── detail view ───────────────────────────────────────────────────────────

    /**
     * Full detail for {@code notebook_read_cell}: a header line, the source, and rendered outputs,
     * using the default per-output cap.
     */
    public static @NotNull String cellDetail(@NotNull JsonObject cell, int index, int totalCells) {
        return cellDetail(cell, index, totalCells, DEFAULT_MAX_OUTPUT_CHARS);
    }

    /**
     * Full detail for {@code notebook_read_cell}: a header line, the source, and rendered outputs,
     * capping each output's textual payload at {@code maxOutputChars}.
     */
    public static @NotNull String cellDetail(@NotNull JsonObject cell, int index, int totalCells,
                                             int maxOutputChars) {
        String type = NotebookModel.cellType(cell);
        String id = NotebookModel.cellId(cell);
        Integer exec = NotebookModel.executionCount(cell);

        StringBuilder sb = new StringBuilder();
        sb.append("Cell [").append(index).append('/').append(totalCells - 1).append("]  type=").append(type);
        if (id != null) {
            sb.append("  id=").append(id);
        }
        if (NotebookModel.CODE.equals(type)) {
            sb.append("  execution_count=").append(exec != null ? exec : "null");
        }
        sb.append("\n\n--- source ---\n");
        String source = NotebookModel.sourceOf(cell);
        sb.append(source.isEmpty() ? "(empty)" : source);

        if (NotebookModel.CODE.equals(type)) {
            JsonArray outputs = NotebookModel.outputsOf(cell);
            sb.append("\n\n--- outputs (").append(outputs.size()).append(") ---");
            if (outputs.isEmpty()) {
                sb.append("\n(none — cell has not produced output)");
            } else {
                sb.append('\n').append(renderOutputs(outputs, maxOutputChars));
            }
        }
        return sb.toString();
    }

    /** Renders an nbformat {@code outputs} array to text, capping each output's textual payload. */
    public static @NotNull String renderOutputs(@NotNull JsonArray outputs, int maxCharsPerOutput) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (JsonElement el : outputs) {
            if (!el.isJsonObject()) {
                continue;
            }
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(renderSingleOutput(el.getAsJsonObject(), maxCharsPerOutput));
            i++;
        }
        return sb.toString();
    }

    private static @NotNull String renderSingleOutput(@NotNull JsonObject output, int maxChars) {
        String type = outputType(output);
        return switch (type) {
            case "stream" -> {
                String name = stringField(output, "name", "stdout");
                String text = NotebookJson.linesToString(output.get("text"));
                yield "[stream:" + name + "]\n" + truncate(stripAnsi(text), maxChars);
            }
            case "error" -> {
                String ename = stringField(output, "ename", "Error");
                String evalue = stringField(output, "evalue", "");
                String tb = NotebookJson.linesToString(output.get("traceback"));
                String head = "[error] " + ename + (evalue.isEmpty() ? "" : ": " + evalue);
                yield tb.isEmpty() ? head : head + "\n" + truncate(stripAnsi(tb), maxChars);
            }
            case "execute_result", "display_data" -> renderDataBundle(output, type, maxChars);
            default -> "[" + type + "]";
        };
    }

    private static @NotNull String renderDataBundle(@NotNull JsonObject output, String type, int maxChars) {
        JsonObject data = output.has("data") && output.get("data").isJsonObject()
            ? output.getAsJsonObject("data") : new JsonObject();
        StringBuilder sb = new StringBuilder("[").append(type).append(']');
        String plain = data.has(TEXT_PLAIN) ? NotebookJson.linesToString(data.get(TEXT_PLAIN)) : "";
        if (!plain.isEmpty()) {
            sb.append('\n').append(truncate(plain, maxChars));
        }
        for (String mime : data.keySet()) {
            if (TEXT_PLAIN.equals(mime)) {
                continue;
            }
            sb.append("\n  <").append(mime).append(", ").append(approxSize(data.get(mime))).append('>');
        }
        return sb.toString();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static @NotNull String outputType(@NotNull JsonObject output) {
        return stringField(output, "output_type", "");
    }

    private static @NotNull String stringField(@NotNull JsonObject obj, @NotNull String key, @NotNull String fallback) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : fallback;
    }

    /** Human-readable size of a MIME payload (e.g. a base64 image or HTML blob). */
    static @NotNull String approxSize(@NotNull JsonElement value) {
        String s = NotebookJson.linesToString(value);
        long chars = s.length();
        // base64 payloads (images) decode to ~3/4 of their length; approximate the decoded size.
        long bytes = chars * 3 / 4;
        if (bytes < 1024) {
            return chars + " chars";
        }
        return (bytes / 1024) + " KB";
    }

    /**
     * Removes ANSI CSI escape sequences (e.g. {@code ESC[0;31m}) that IPython embeds in tracebacks
     * and colored stream text. The {@code ESC} byte is required, so ordinary text such as
     * {@code data[index]} is never touched.
     */
    static @NotNull String stripAnsi(@NotNull String text) {
        return ANSI_CSI.matcher(text).replaceAll("");
    }

    static @NotNull String truncate(@NotNull String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "\n… [truncated " + (s.length() - max) + " chars]";
    }

    private static @NotNull String pad(@NotNull String s, int width) {
        if (s.length() >= width) {
            return s;
        }
        return s + " ".repeat(width - s.length());
    }
}
