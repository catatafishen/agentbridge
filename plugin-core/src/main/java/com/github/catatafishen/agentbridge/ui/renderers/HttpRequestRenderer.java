package com.github.catatafishen.agentbridge.ui.renderers;

import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Renderer for http_request output.
 * Handles both formats:
 * <ul>
 *   <li>New: {@code HTTP {code} ({timing}ms)\n\n--- Body ---\n...}</li>
 *   <li>Old: {@code HTTP {code} {message}\n\n--- Headers ---\n...\n\n--- Body ---\n...}</li>
 * </ul>
 * Headers section only appears when {@code show_headers: true}.
 */
public final class HttpRequestRenderer implements ToolResultRenderer {

    public static final HttpRequestRenderer INSTANCE = new HttpRequestRenderer();

    private static final int MAX_BODY_LINES = 100;
    static final String HEADERS_MARKER = "--- Headers ---";
    static final String BODY_MARKER = "--- Body ---";

    private HttpRequestRenderer() {
    }

    @Override
    public @Nullable JComponent render(@NotNull String output) {
        StatusInfo status = parseStatusLine(output);
        if (status == null) return null;

        Color statusColor = statusColor(status.code);

        int headersStart = output.indexOf(HEADERS_MARKER);
        int bodyStart = output.indexOf(BODY_MARKER);

        List<String> headers = parseHeaders(output, headersStart, bodyStart);
        String body = parseBody(output, bodyStart);

        JPanel panel = ToolRenderers.INSTANCE.listPanel();
        panel.add(buildStatusRow(status, statusColor));
        if (!headers.isEmpty()) panel.add(renderHeaderSection(headers));
        if (!body.isEmpty()) panel.add(renderBodySection(body));

        return panel;
    }

    // ── Status line parsing ──────────────────────────────────

    record StatusInfo(int code, String detail) {
    }

    static @Nullable StatusInfo parseStatusLine(@NotNull String output) {
        String firstLine = output.lines().findFirst().orElse("");
        if (!firstLine.startsWith("HTTP ")) return null;

        String rest = firstLine.substring(5).trim();
        int spaceIdx = rest.indexOf(' ');
        if (spaceIdx < 0) return null;

        String codeStr = rest.substring(0, spaceIdx);
        try {
            int code = Integer.parseInt(codeStr);
            String detail = rest.substring(spaceIdx + 1).trim();
            return new StatusInfo(code, detail);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @NotNull Color statusColor(int code) {
        if (code >= 200 && code < 300) return ToolRenderers.INSTANCE.getSUCCESS_COLOR();
        if (code >= 300 && code < 400) return ToolRenderers.INSTANCE.getWARN_COLOR();
        return ToolRenderers.INSTANCE.getFAIL_COLOR();
    }

    // ── Component builders ───────────────────────────────────

    private static @NotNull JComponent buildStatusRow(@NotNull StatusInfo status, @NotNull Color color) {
        JPanel row = ToolRenderers.INSTANCE.rowPanel();
        JBLabel label = new JBLabel("HTTP " + status.code + " " + status.detail);
        label.setFont(UIUtil.getLabelFont().deriveFont(Font.BOLD));
        label.setForeground(color);
        row.add(label);
        return row;
    }

    static @NotNull List<String> parseHeaders(@NotNull String output, int headersStart, int bodyStart) {
        if (headersStart < 0) return List.of();
        int end = bodyStart >= 0 ? bodyStart : output.length();
        return output.substring(headersStart + HEADERS_MARKER.length(), end)
            .trim().lines()
            .filter(line -> !line.isBlank())
            .collect(Collectors.toList());
    }

    static @NotNull String parseBody(@NotNull String output, int bodyStart) {
        if (bodyStart < 0) return "";
        return output.substring(bodyStart + BODY_MARKER.length()).trim();
    }

    private static @NotNull JComponent renderHeaderSection(@NotNull List<String> headers) {
        JComponent section = ToolRenderers.INSTANCE.sectionPanel("Headers", headers.size(), 4);
        for (String h : headers) {
            JPanel row = ToolRenderers.INSTANCE.rowPanel();
            row.setBorder(JBUI.Borders.emptyLeft(8));
            int colonIdx = h.indexOf(':');
            if (colonIdx > 0) {
                row.add(ToolRenderers.INSTANCE.mutedLabel(h.substring(0, colonIdx).trim() + ":"));
                row.add(ToolRenderers.INSTANCE.monoLabel(h.substring(colonIdx + 1).trim()));
            } else {
                row.add(ToolRenderers.INSTANCE.monoLabel(h));
            }
            section.add(row);
        }
        return section;
    }

    private static @NotNull JComponent renderBodySection(@NotNull String body) {
        JPanel section = ToolRenderers.INSTANCE.listPanel();
        section.setBorder(JBUI.Borders.emptyTop(6));
        section.setAlignmentX(JComponent.LEFT_ALIGNMENT);

        JBLabel bodyLabel = new JBLabel("Body");
        bodyLabel.setFont(UIUtil.getLabelFont().deriveFont(Font.BOLD));
        bodyLabel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        section.add(bodyLabel);

        List<String> bodyLines = body.lines().collect(Collectors.toList());
        String truncatedBody = bodyLines.size() > MAX_BODY_LINES
            ? String.join("\n", bodyLines.subList(0, MAX_BODY_LINES))
            : body;
        section.add(ToolRenderers.INSTANCE.codeBlock(truncatedBody));

        if (bodyLines.size() > MAX_BODY_LINES) {
            ToolRenderers.INSTANCE.addTruncationIndicator(section, bodyLines.size() - MAX_BODY_LINES, "lines");
        }
        return section;
    }
}
