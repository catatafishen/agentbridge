package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for private static helper methods in {@link InteractWithModalTool}:
 * {@code doClick}, {@code collectButtons}, and {@code collectComponents}.
 * <p>
 * Uses reflection to access private methods and plain Swing components (headless-safe).
 */
@DisplayName("InteractWithModalTool static methods")
class InteractWithModalToolStaticMethodsTest {

    private static Method doClickMethod;
    private static Method collectButtonsMethod;
    private static Method collectComponentsMethod;

    @BeforeAll
    static void setUp() throws NoSuchMethodException {
        doClickMethod = InteractWithModalTool.class.getDeclaredMethod(
                "doClick", Container.class, String.class);
        doClickMethod.setAccessible(true);

        // The overload that returns List<String>
        collectButtonsMethod = InteractWithModalTool.class.getDeclaredMethod(
                "collectButtons", Container.class);
        collectButtonsMethod.setAccessible(true);

        collectComponentsMethod = InteractWithModalTool.class.getDeclaredMethod(
                "collectComponents", Container.class, List.class, List.class, List.class, List.class);
        collectComponentsMethod.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private static List<String> invokeCollectButtons(Container c) throws ReflectiveOperationException {
        return (List<String>) collectButtonsMethod.invoke(null, c);
    }

    private static boolean invokeDoClick(Container c, String text) throws ReflectiveOperationException {
        return (boolean) doClickMethod.invoke(null, c, text);
    }

    private static void invokeCollectComponents(Container c, List<String> labels,
                                                List<String> radios, List<String> checkboxes,
                                                List<String> buttons) throws ReflectiveOperationException {
        collectComponentsMethod.invoke(null, c, labels, radios, checkboxes, buttons);
    }

    // ── doClick ─────────────────────────────────────────────

    @Nested
    @DisplayName("doClick")
    class DoClick {

        @Test
        @DisplayName("clicks matching enabled button")
        void clicksMatchingButton() throws ReflectiveOperationException {
            JPanel panel = new JPanel();
            JButton btn = new JButton("OK");
            panel.add(btn);
            assertTrue(invokeDoClick(panel, "OK"));
        }

        @Test
        @DisplayName("returns false when no button matches")
        void noMatch() throws ReflectiveOperationException {
            JPanel panel = new JPanel();
            panel.add(new JButton("Cancel"));
            assertFalse(invokeDoClick(panel, "OK"));
        }

        @Test
        @DisplayName("skips button with null text without NPE")
        void nullTextSkipped() throws ReflectiveOperationException {
            JPanel panel = new JPanel();
            JButton nullBtn = new JButton((String) null);
            JButton okBtn = new JButton("OK");
            panel.add(nullBtn);
            panel.add(okBtn);
            // Should not throw, and should find OK
            assertTrue(invokeDoClick(panel, "OK"));
        }

        @Test
        @DisplayName("returns false for container with only null-text buttons")
        void allNullText() throws ReflectiveOperationException {
            JPanel panel = new JPanel();
            panel.add(new JButton((String) null));
            panel.add(new JButton((String) null));
            assertFalse(invokeDoClick(panel, "OK"));
        }

        @Test
        @DisplayName("match is case-insensitive")
        void caseInsensitiveMatch() throws ReflectiveOperationException {
            JPanel panel = new JPanel();
            panel.add(new JButton("Cancel"));
            assertTrue(invokeDoClick(panel, "cancel"));
        }

        @Test
        @DisplayName("recurses into nested containers")
        void recursesIntoNested() throws ReflectiveOperationException {
            JPanel outer = new JPanel();
            JPanel inner = new JPanel();
            inner.add(new JButton("Apply"));
            outer.add(inner);
            assertTrue(invokeDoClick(outer, "Apply"));
        }
    }

    // ── collectButtons ──────────────────────────────────────

    @Nested
    @DisplayName("collectButtons")
    class CollectButtons {

        @Test
        @DisplayName("collects enabled buttons with text")
        void collectsEnabledButtons() throws ReflectiveOperationException {
            JPanel panel = new JPanel();
            panel.add(new JButton("OK"));
            panel.add(new JButton("Cancel"));
            List<String> result = invokeCollectButtons(panel);
            assertEquals(List.of("OK", "Cancel"), result);
        }

        @Test
        @DisplayName("skips disabled buttons")
        void skipsDisabled() throws ReflectiveOperationException {
            JPanel panel = new JPanel();
            JButton disabled = new JButton("Disabled");
            disabled.setEnabled(false);
            panel.add(disabled);
            panel.add(new JButton("OK"));
            List<String> result = invokeCollectButtons(panel);
            assertEquals(List.of("OK"), result);
        }

        @Test
        @DisplayName("skips buttons with null text")
        void skipsNullText() throws ReflectiveOperationException {
            JPanel panel = new JPanel();
            panel.add(new JButton((String) null));
            panel.add(new JButton("OK"));
            List<String> result = invokeCollectButtons(panel);
            assertEquals(List.of("OK"), result);
        }

        @Test
        @DisplayName("skips buttons with blank text")
        void skipsBlankText() throws ReflectiveOperationException {
            JPanel panel = new JPanel();
            panel.add(new JButton("   "));
            panel.add(new JButton("OK"));
            List<String> result = invokeCollectButtons(panel);
            assertEquals(List.of("OK"), result);
        }

        @Test
        @DisplayName("empty container returns empty list")
        void emptyContainer() throws ReflectiveOperationException {
            List<String> result = invokeCollectButtons(new JPanel());
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("recurses into nested containers")
        void recursesNested() throws ReflectiveOperationException {
            JPanel outer = new JPanel();
            JPanel inner = new JPanel();
            inner.add(new JButton("Inner"));
            outer.add(new JButton("Outer"));
            outer.add(inner);
            List<String> result = invokeCollectButtons(outer);
            assertEquals(List.of("Outer", "Inner"), result);
        }
    }

    // ── collectComponents ───────────────────────────────────

    @Nested
    @DisplayName("collectComponents")
    class CollectComponents {

        @Test
        @DisplayName("sorts components by type")
        void sortsByType() throws ReflectiveOperationException {
            JPanel panel = new JPanel();
            panel.add(new JLabel("Label text"));
            panel.add(new JRadioButton("Radio"));
            panel.add(new JCheckBox("Check"));
            panel.add(new JButton("Btn"));

            List<String> labels = new ArrayList<>();
            List<String> radios = new ArrayList<>();
            List<String> checks = new ArrayList<>();
            List<String> btns = new ArrayList<>();
            invokeCollectComponents(panel, labels, radios, checks, btns);

            assertEquals(List.of("Label text"), labels);
            assertEquals(List.of("Radio"), radios);
            assertEquals(List.of("Check"), checks);
            assertEquals(List.of("Btn"), btns);
        }

        @Test
        @DisplayName("skips JRadioButton with null text")
        void skipsNullRadioButton() throws ReflectiveOperationException {
            JPanel panel = new JPanel();
            panel.add(new JRadioButton((String) null));
            panel.add(new JRadioButton("Valid"));

            List<String> radios = new ArrayList<>();
            invokeCollectComponents(panel, new ArrayList<>(), radios, new ArrayList<>(), new ArrayList<>());
            assertEquals(List.of("Valid"), radios);
        }

        @Test
        @DisplayName("skips JCheckBox with null text")
        void skipsNullCheckBox() throws ReflectiveOperationException {
            JPanel panel = new JPanel();
            panel.add(new JCheckBox((String) null));
            panel.add(new JCheckBox("Valid"));

            List<String> checks = new ArrayList<>();
            invokeCollectComponents(panel, new ArrayList<>(), new ArrayList<>(), checks, new ArrayList<>());
            assertEquals(List.of("Valid"), checks);
        }

        @Test
        @DisplayName("skips JButton with null text")
        void skipsNullButton() throws ReflectiveOperationException {
            JPanel panel = new JPanel();
            panel.add(new JButton((String) null));
            panel.add(new JButton("Valid"));

            List<String> btns = new ArrayList<>();
            invokeCollectComponents(panel, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), btns);
            assertEquals(List.of("Valid"), btns);
        }

        @Test
        @DisplayName("skips JLabel with null text")
        void skipsNullLabel() throws ReflectiveOperationException {
            JPanel panel = new JPanel();
            panel.add(new JLabel((String) null));
            panel.add(new JLabel("Valid"));

            List<String> labels = new ArrayList<>();
            invokeCollectComponents(panel, labels, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            assertEquals(List.of("Valid"), labels);
        }

        @Test
        @DisplayName("skips JLabel with icon")
        void skipsLabelWithIcon() throws ReflectiveOperationException {
            JPanel panel = new JPanel();
            JLabel iconLabel = new JLabel("Icon label");
            iconLabel.setIcon(UIManager.getIcon("FileView.fileIcon"));
            panel.add(iconLabel);
            panel.add(new JLabel("Text only"));

            List<String> labels = new ArrayList<>();
            invokeCollectComponents(panel, labels, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            assertEquals(List.of("Text only"), labels);
        }

        @Test
        @DisplayName("skips components with blank text")
        void skipsBlankText() throws ReflectiveOperationException {
            JPanel panel = new JPanel();
            panel.add(new JRadioButton("  "));
            panel.add(new JCheckBox("  "));
            panel.add(new JButton("  "));
            panel.add(new JLabel("  "));

            List<String> labels = new ArrayList<>();
            List<String> radios = new ArrayList<>();
            List<String> checks = new ArrayList<>();
            List<String> btns = new ArrayList<>();
            invokeCollectComponents(panel, labels, radios, checks, btns);

            assertTrue(labels.isEmpty());
            assertTrue(radios.isEmpty());
            assertTrue(checks.isEmpty());
            assertTrue(btns.isEmpty());
        }

        @Test
        @DisplayName("recurses into nested containers")
        void recursesNested() throws ReflectiveOperationException {
            JPanel outer = new JPanel();
            JPanel inner = new JPanel();
            inner.add(new JLabel("Nested"));
            outer.add(inner);

            List<String> labels = new ArrayList<>();
            invokeCollectComponents(outer, labels, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            assertEquals(List.of("Nested"), labels);
        }
    }
}
