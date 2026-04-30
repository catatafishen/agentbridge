package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.ListPopupStep;
import com.intellij.ui.popup.list.ListPopupImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import javax.swing.*;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts a structural {@link PopupSnapshot} from a live {@link JBPopup}.
 *
 * <p><b>Why this exists — DO NOT REMOVE without reading
 * {@code .agent-work/popup-interaction-design-2026-04-30.md}.</b>
 *
 * <p>Strategy (in priority order):
 * <ol>
 *   <li><b>{@link ListPopupStep}</b> (preferred): cast popup to {@link ListPopupImpl}, get its
 *       {@code ListPopupStep<Object>} via {@link ListPopupImpl#getListStep()}, enumerate
 *       {@link ListPopupStep#getValues()}, format with
 *       {@link ListPopupStep#getTextFor(Object)}, check
 *       {@link ListPopupStep#isSelectable(Object)} and {@link ListPopupStep#hasSubstep(Object)}.
 *       This is structurally accurate and aligns with what the platform exposes for
 *       programmatic selection later via {@link ListPopupImpl#selectAndExecuteValue(Object)}.</li>
 *   <li><b>Raw {@link JList} fallback</b>: when the popup is not a {@code ListPopupImpl} (custom
 *       chooser, wizard step, etc.), walk the popup content for the first visible {@code JList},
 *       enumerate its {@code ListModel}, render each row with the cell renderer, and pull the
 *       first visible {@code JLabel}'s text. Marked {@code popupKind="list-fallback"} —
 *       structural metadata is best-effort.</li>
 *   <li><b>Tree</b>: when the popup contains a {@code JTree}, flatten the visible nodes with
 *       indentation up to depth 3.</li>
 *   <li><b>Unsupported</b>: otherwise, return an empty snapshot — caller falls back to the
 *       PR #363 cancel-and-error path.</li>
 * </ol>
 *
 * <p>All public methods are EDT-safe (called from the AWT {@code WINDOW_OPENED} listener
 * which fires on the EDT).
 */
public final class PopupContentExtractor {

    private static final Logger LOG = Logger.getInstance(PopupContentExtractor.class);
    private static final int MAX_CHOICES = 200;
    private static final int MAX_TREE_DEPTH = 3;

    private PopupContentExtractor() {
    }

    /**
     * Extracts a snapshot of {@code popup}. Never throws — returns an unsupported snapshot on
     * any failure so the listener can still cancel the popup safely.
     */
    @NotNull
    public static PopupSnapshot extract(@NotNull JBPopup popup) {
        String title = PopupInterceptor.extractPopupTitle(popup);
        try {
            if (popup instanceof ListPopupImpl listPopup) {
                ListPopupStep<Object> step = com.github.catatafishen.agentbridge.psi.PlatformApiCompat.getListStep(listPopup);
                if (step != null) {
                    List<PopupChoice> choices = extractFromStep(step);
                    if (!choices.isEmpty()) {
                        return new PopupSnapshot(title, choices, PopupSnapshot.KIND_LIST_STEP);
                    }
                }
            }
            JComponent content = popup.getContent();
            JList<?> list = findFirstList(content);
            if (list != null) {
                List<PopupChoice> choices = extractFromList(list);
                if (!choices.isEmpty()) {
                    return new PopupSnapshot(title, choices, PopupSnapshot.KIND_LIST_FALLBACK);
                }
            }
            javax.swing.JTree tree = findFirstTree(content);
            if (tree != null) {
                List<PopupChoice> choices = extractFromTree(tree);
                if (!choices.isEmpty()) {
                    return new PopupSnapshot(title, choices, PopupSnapshot.KIND_TREE);
                }
            }
        } catch (Exception | LinkageError t) {
            LOG.warn("PopupContentExtractor: extraction failed for popup '" + title + "'", t);
        }
        return new PopupSnapshot(title, List.of(), PopupSnapshot.KIND_UNSUPPORTED);
    }

    // ── Pure helpers (unit-testable with fakes) ───────────────────────

    /**
     * Pure logic over a {@link ListPopupStep} — directly testable with a fake step.
     */
    @VisibleForTesting
    @NotNull
    public static <T> List<PopupChoice> extractFromStep(@NotNull ListPopupStep<T> step) {
        List<? extends T> values = step.getValues();
        int limit = Math.min(values.size(), MAX_CHOICES);
        List<PopupChoice> result = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            T value = values.get(i);
            String text = safeText(safeCall(() -> step.getTextFor(value)));
            boolean selectable = Boolean.TRUE.equals(safeCall(() -> step.isSelectable(value)));
            boolean hasSub = Boolean.TRUE.equals(safeCall(() -> step.hasSubstep(value)));
            result.add(new PopupChoice(
                PopupChoice.buildValueId(text, i), i, text, null, selectable, hasSub
            ));
        }
        return result;
    }

    @NotNull
    private static List<PopupChoice> extractFromList(@NotNull JList<?> list) {
        ListModel<?> model = list.getModel();
        ListCellRenderer<Object> renderer = castRenderer(list);
        int size = Math.min(model.getSize(), MAX_CHOICES);
        List<PopupChoice> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Object value = model.getElementAt(i);
            String text = renderRowText(list, renderer, value, i);
            // Best-effort: assume selectable, no substep — fallback path lacks this metadata.
            result.add(new PopupChoice(
                PopupChoice.buildValueId(text, i), i, text, null, true, false
            ));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    @NotNull
    private static ListCellRenderer<Object> castRenderer(@NotNull JList<?> list) {
        return (ListCellRenderer<Object>) list.getCellRenderer();
    }

    @NotNull
    private static String renderRowText(@NotNull JList<?> list,
                                        @Nullable ListCellRenderer<Object> renderer,
                                        @Nullable Object value, int index) {
        if (renderer == null) {
            return value != null ? value.toString() : "(null)";
        }
        try {
            Component comp = renderer.getListCellRendererComponent(list, value, index, false, false);
            if (comp instanceof JLabel lbl && lbl.getText() != null && !lbl.getText().isBlank()) {
                return lbl.getText();
            }
            if (comp instanceof Container c) {
                String label = PopupInterceptor.firstVisibleLabel(c);
                if (label != null) return label;
            }
        } catch (Exception | LinkageError t) {
            LOG.debug("renderRowText failed at index " + index, t);
        }
        return value != null ? value.toString() : "(null)";
    }

    @NotNull
    private static List<PopupChoice> extractFromTree(@NotNull javax.swing.JTree tree) {
        TreeModel model = tree.getModel();
        if (model == null || model.getRoot() == null) return List.of();
        Object root = model.getRoot();
        List<PopupChoice> result = new ArrayList<>();
        TreePath rootPath = new TreePath(root);
        boolean rootVisible = tree.isRootVisible();
        if (rootVisible) {
            addTreeNode(model, root, 0, result);
        }
        appendChildren(model, root, rootPath, rootVisible ? 1 : 0, tree, result);
        return result;
    }

    private static void appendChildren(@NotNull TreeModel model, @NotNull Object parent,
                                       @NotNull TreePath parentPath, int depth,
                                       @NotNull javax.swing.JTree tree,
                                       @NotNull List<PopupChoice> out) {
        if (depth > MAX_TREE_DEPTH) return;
        int n = model.getChildCount(parent);
        for (int i = 0; i < n && out.size() < MAX_CHOICES; i++) {
            Object child = model.getChild(parent, i);
            TreePath path = parentPath.pathByAddingChild(child);
            addTreeNode(model, child, depth, out);
            if (tree.isExpanded(path)) {
                appendChildren(model, child, path, depth + 1, tree, out);
            }
        }
    }

    private static void addTreeNode(@NotNull TreeModel model, @NotNull Object node,
                                    int depth, @NotNull List<PopupChoice> out) {
        String label = nodeLabel(node);
        String indented = "  ".repeat(Math.min(depth, MAX_TREE_DEPTH)) + label;
        boolean leaf = model.isLeaf(node);
        out.add(new PopupChoice(
            PopupChoice.buildValueId(indented, out.size()),
            out.size(), indented, null, leaf, !leaf
        ));
    }

    @NotNull
    private static String nodeLabel(@NotNull Object node) {
        if (node instanceof javax.swing.tree.DefaultMutableTreeNode dmtn && dmtn.getUserObject() != null) {
            return dmtn.getUserObject().toString();
        }
        return node.toString();
    }

    @Nullable
    private static JList<?> findFirstList(@NotNull Container container) {
        for (Component c : container.getComponents()) {
            if (c instanceof JList<?> list && list.isVisible()) return list;
            if (c instanceof Container nested) {
                JList<?> r = findFirstList(nested);
                if (r != null) return r;
            }
        }
        return null;
    }

    @Nullable
    private static javax.swing.JTree findFirstTree(@NotNull Container container) {
        for (Component c : container.getComponents()) {
            if (c instanceof javax.swing.JTree tree && tree.isVisible()) return tree;
            if (c instanceof Container nested) {
                javax.swing.JTree r = findFirstTree(nested);
                if (r != null) return r;
            }
        }
        return null;
    }

    @NotNull
    private static String safeText(@Nullable String s) {
        return (s != null && !s.isBlank()) ? s : "(unnamed)";
    }

    @Nullable
    private static <T> T safeCall(@NotNull java.util.function.Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception | LinkageError t) {
            return null;
        }
    }
}
