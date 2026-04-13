package com.github.catatafishen.agentbridge.psi.tools.quality;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DialogInterceptor}: the {@code DialogInfo} record,
 * {@code collectComponents}, {@code selectOption}, and {@code clickConfirmButton}.
 *
 * <p>Swing components are created in headless mode. Tests avoid {@code Dialog}
 * construction (which requires a {@code Frame}).</p>
 */
class DialogInterceptorTest {

    @BeforeAll
    static void setHeadless() {
        System.setProperty("java.awt.headless", "true");
    }

    // ── DialogInfo ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DialogInfo")
    class DialogInfoTest {

        @Test
        @DisplayName("empty lists → isEmpty() is true")
        void emptyIsEmpty() {
            var info = new DialogInterceptor.DialogInfo(
                List.of(), List.of(), List.of(), List.of()
            );
            assertTrue(info.isEmpty());
        }

        @Test
        @DisplayName("non-empty radios → isEmpty() is false")
        void nonEmptyRadios() {
            var info = new DialogInterceptor.DialogInfo(
                List.of("Option A"), List.of(), List.of(), List.of()
            );
            assertFalse(info.isEmpty());
        }

        @Test
        @DisplayName("non-empty checkboxes → isEmpty() is false")
        void nonEmptyCheckboxes() {
            var info = new DialogInterceptor.DialogInfo(
                List.of(), List.of("Check A"), List.of(), List.of()
            );
            assertFalse(info.isEmpty());
        }

        @Test
        @DisplayName("non-empty textInputs → isEmpty() is false")
        void nonEmptyTextInputs() {
            var info = new DialogInterceptor.DialogInfo(
                List.of(), List.of(), List.of("input"), List.of()
            );
            assertFalse(info.isEmpty());
        }

        @Test
        @DisplayName("non-empty buttons → isEmpty() is false")
        void nonEmptyButtons() {
            var info = new DialogInterceptor.DialogInfo(
                List.of(), List.of(), List.of(), List.of("OK")
            );
            assertFalse(info.isEmpty());
        }

        @Test
        @DisplayName("record getters return correct values")
        void recordGetters() {
            var radios = List.of("R1");
            var checks = List.of("C1");
            var inputs = List.of("I1");
            var buttons = List.of("B1");
            var info = new DialogInterceptor.DialogInfo(radios, checks, inputs, buttons);

            assertEquals(radios, info.radioButtons());
            assertEquals(checks, info.checkBoxes());
            assertEquals(inputs, info.textInputs());
            assertEquals(buttons, info.buttons());
        }
    }

    // ── collectComponents ────────────────────────────────────────────────────

    @Nested
    @DisplayName("collectComponents")
    class CollectComponents {

        @Test
        @DisplayName("collects all component types from flat panel")
        void flatPanel() {
            JPanel panel = new JPanel();
            panel.add(new JRadioButton("Option A"));
            panel.add(new JCheckBox("Enable X"));
            panel.add(new JButton("OK"));
            JTextField tf = new JTextField("default");
            panel.add(tf);

            List<String> radios = new ArrayList<>();
            List<String> checks = new ArrayList<>();
            List<String> inputs = new ArrayList<>();
            List<String> buttons = new ArrayList<>();

            DialogInterceptor.collectComponents(panel, radios, checks, inputs, buttons);

            assertEquals(List.of("Option A"), radios);
            assertEquals(List.of("Enable X"), checks);
            assertEquals(List.of("OK"), buttons);
            assertEquals(List.of("default"), inputs);
        }

        @Test
        @DisplayName("discovers components in nested panels")
        void nestedPanels() {
            JPanel outer = new JPanel();
            JPanel inner = new JPanel();
            inner.add(new JRadioButton("Nested Radio"));
            inner.add(new JCheckBox("Nested Check"));
            outer.add(inner);

            List<String> radios = new ArrayList<>();
            List<String> checks = new ArrayList<>();
            List<String> inputs = new ArrayList<>();
            List<String> buttons = new ArrayList<>();

            DialogInterceptor.collectComponents(outer, radios, checks, inputs, buttons);

            assertEquals(List.of("Nested Radio"), radios);
            assertEquals(List.of("Nested Check"), checks);
        }

        @Test
        @DisplayName("blank-text components are skipped")
        void blankTextSkipped() {
            JPanel panel = new JPanel();
            panel.add(new JButton(""));
            panel.add(new JRadioButton("   "));
            panel.add(new JCheckBox(""));
            panel.add(new JButton("Valid"));

            List<String> radios = new ArrayList<>();
            List<String> checks = new ArrayList<>();
            List<String> inputs = new ArrayList<>();
            List<String> buttons = new ArrayList<>();

            DialogInterceptor.collectComponents(panel, radios, checks, inputs, buttons);

            assertTrue(radios.isEmpty());
            assertTrue(checks.isEmpty());
            assertEquals(List.of("Valid"), buttons);
        }
    }

    // ── selectOption ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("selectOption")
    class SelectOption {

        @Test
        @DisplayName("selects matching radio button (case-insensitive)")
        void selectsRadioButton() {
            JPanel panel = new JPanel();
            JRadioButton radioA = new JRadioButton("Option A");
            JRadioButton radioB = new JRadioButton("Option B");
            panel.add(radioA);
            panel.add(radioB);

            boolean result = DialogInterceptor.selectOption(panel, "option b");

            assertTrue(result);
            assertTrue(radioB.isSelected());
        }

        @Test
        @DisplayName("returns false for non-matching option")
        void nonMatching() {
            JPanel panel = new JPanel();
            panel.add(new JRadioButton("Option A"));
            panel.add(new JRadioButton("Option B"));

            boolean result = DialogInterceptor.selectOption(panel, "Option C");

            assertFalse(result);
        }

        @Test
        @DisplayName("works with JCheckBox")
        void worksWithCheckBox() {
            JPanel panel = new JPanel();
            JCheckBox cb = new JCheckBox("Enable Feature");
            panel.add(cb);

            boolean result = DialogInterceptor.selectOption(panel, "enable feature");

            assertTrue(result);
            assertTrue(cb.isSelected());
        }
    }

    // ── clickConfirmButton ───────────────────────────────────────────────────

    @Nested
    @DisplayName("clickConfirmButton")
    class ClickConfirmButton {

        @Test
        @DisplayName("clicks OK button")
        void clicksOk() {
            JPanel panel = new JPanel();
            JButton ok = new JButton("OK");
            JButton cancel = new JButton("Cancel");
            AtomicBoolean clicked = new AtomicBoolean(false);
            ok.addActionListener(e -> clicked.set(true));
            panel.add(ok);
            panel.add(cancel);

            DialogInterceptor.clickConfirmButton(panel);

            assertTrue(clicked.get());
        }

        @Test
        @DisplayName("recognizes 'Refactor' as confirm button")
        void refactorButton() {
            JPanel panel = new JPanel();
            JButton btn = new JButton("Refactor");
            AtomicBoolean clicked = new AtomicBoolean(false);
            btn.addActionListener(e -> clicked.set(true));
            panel.add(btn);

            DialogInterceptor.clickConfirmButton(panel);

            assertTrue(clicked.get());
        }

        @Test
        @DisplayName("recognizes 'Apply' as confirm button")
        void applyButton() {
            JPanel panel = new JPanel();
            JButton btn = new JButton("Apply");
            AtomicBoolean clicked = new AtomicBoolean(false);
            btn.addActionListener(e -> clicked.set(true));
            panel.add(btn);

            DialogInterceptor.clickConfirmButton(panel);

            assertTrue(clicked.get());
        }

        @Test
        @DisplayName("recognizes 'Run' as confirm button")
        void runButton() {
            JPanel panel = new JPanel();
            JButton btn = new JButton("Run");
            AtomicBoolean clicked = new AtomicBoolean(false);
            btn.addActionListener(e -> clicked.set(true));
            panel.add(btn);

            DialogInterceptor.clickConfirmButton(panel);

            assertTrue(clicked.get());
        }

        @Test
        @DisplayName("recognizes 'Yes' as confirm button")
        void yesButton() {
            JPanel panel = new JPanel();
            JButton btn = new JButton("Yes");
            AtomicBoolean clicked = new AtomicBoolean(false);
            btn.addActionListener(e -> clicked.set(true));
            panel.add(btn);

            DialogInterceptor.clickConfirmButton(panel);

            assertTrue(clicked.get());
        }

        @Test
        @DisplayName("disabled button is skipped")
        void disabledButtonSkipped() {
            JPanel panel = new JPanel();
            JButton ok = new JButton("OK");
            ok.setEnabled(false);
            AtomicBoolean clicked = new AtomicBoolean(false);
            ok.addActionListener(e -> clicked.set(true));
            panel.add(ok);

            DialogInterceptor.clickConfirmButton(panel);

            assertFalse(clicked.get());
        }
    }
}
