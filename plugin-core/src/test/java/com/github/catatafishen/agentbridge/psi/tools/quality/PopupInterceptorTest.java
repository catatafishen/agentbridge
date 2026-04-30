package com.github.catatafishen.agentbridge.psi.tools.quality;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure helpers in {@link PopupInterceptor}: {@code Result},
 * {@code firstVisibleLabel}, and {@code formatPopupBlockedError}.
 *
 * <p>The AWT-listener mechanics ({@code runDetectingPopups}, popup cancellation, and
 * window-open detection) require a live IDE platform and are not unit-testable in
 * headless mode; they are exercised by integration testing.</p>
 */
class PopupInterceptorTest {

    @BeforeAll
    static void setHeadless() {
        System.setProperty("java.awt.headless", "true");
    }

    // ── Result ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Result")
    class ResultTest {

        @Test
        @DisplayName("describe with empty titles → unidentified marker")
        void describeEmpty() {
            var r = new PopupInterceptor.Result(true, List.of(), true, null, false);
            assertEquals("(unidentified popup)", r.describe());
        }

        @Test
        @DisplayName("describe with one title → quoted title")
        void describeOne() {
            var r = new PopupInterceptor.Result(true, List.of("Choose Class"), true, null, false);
            assertEquals("'Choose Class'", r.describe());
        }

        @Test
        @DisplayName("describe with multiple titles → comma-joined quoted")
        void describeMany() {
            var r = new PopupInterceptor.Result(true, List.of("A", "B", "C"), false, null, false);
            assertEquals("'A', 'B', 'C'", r.describe());
        }

        @Test
        @DisplayName("popupTitles is exposed as immutable List")
        void titlesAccessor() {
            List<String> titles = List.of("X");
            var r = new PopupInterceptor.Result(true, titles, true, null, false);
            assertEquals(titles, r.popupTitles());
            assertTrue(r.popupWasOpened());
            assertTrue(r.cancelled());
        }
    }

    // ── firstVisibleLabel ────────────────────────────────────────────────────

    @Nested
    @DisplayName("firstVisibleLabel")
    class FirstVisibleLabelTest {

        @Test
        @DisplayName("returns text of first visible non-blank JLabel")
        void findsFirstLabel() {
            JPanel root = new JPanel();
            root.add(new JLabel("Choose class:"));
            root.add(new JLabel("ignored second"));
            assertEquals("Choose class:", PopupInterceptor.firstVisibleLabel(root));
        }

        @Test
        @DisplayName("descends into nested containers")
        void recursesIntoChildren() {
            JPanel root = new JPanel();
            JPanel inner = new JPanel();
            inner.add(new JLabel("nested"));
            root.add(inner);
            assertEquals("nested", PopupInterceptor.firstVisibleLabel(root));
        }

        @Test
        @DisplayName("skips blank labels")
        void skipsBlank() {
            JPanel root = new JPanel();
            root.add(new JLabel(""));
            root.add(new JLabel("   "));
            root.add(new JLabel("found"));
            assertEquals("found", PopupInterceptor.firstVisibleLabel(root));
        }

        @Test
        @DisplayName("skips invisible labels")
        void skipsInvisible() {
            JPanel root = new JPanel();
            JLabel hidden = new JLabel("hidden");
            hidden.setVisible(false);
            root.add(hidden);
            root.add(new JLabel("visible"));
            assertEquals("visible", PopupInterceptor.firstVisibleLabel(root));
        }

        @Test
        @DisplayName("empty container → null")
        void emptyContainer() {
            assertNull(PopupInterceptor.firstVisibleLabel(new JPanel()));
        }
    }

    // ── formatPopupBlockedError ──────────────────────────────────────────────

    @Nested
    @DisplayName("formatPopupBlockedError")
    class FormatErrorTest {

        @Test
        @DisplayName("starts with 'Error:' so MCP detects isError=true")
        void startsWithErrorPrefix() {
            var r = new PopupInterceptor.Result(true, List.of("X"), true, null, false);
            String msg = PopupInterceptor.formatPopupBlockedError("Import class 'Cell'", r);
            assertTrue(msg.startsWith("Error: "), "expected 'Error: ' prefix, got: " + msg);
        }

        @Test
        @DisplayName("includes the action name and popup description")
        void includesActionAndDescription() {
            var r = new PopupInterceptor.Result(true, List.of("Choose Class"), true, null, false);
            String msg = PopupInterceptor.formatPopupBlockedError("Import class 'Cell'", r);
            assertTrue(msg.contains("Import class 'Cell'"), msg);
            assertTrue(msg.contains("'Choose Class'"), msg);
        }

        @Test
        @DisplayName("when cancelled → confirms the popup was cancelled")
        void cancelledMessage() {
            var r = new PopupInterceptor.Result(true, List.of("X"), true, null, false);
            String msg = PopupInterceptor.formatPopupBlockedError("act", r);
            assertTrue(msg.contains("cancelled"), msg);
            assertFalse(msg.contains("may still be visible"), msg);
        }

        @Test
        @DisplayName("when not cancelled → warns that the popup may still be visible")
        void notCancelledMessage() {
            var r = new PopupInterceptor.Result(true, List.of("X"), false, null, false);
            String msg = PopupInterceptor.formatPopupBlockedError("act", r);
            assertTrue(msg.contains("may still be visible"), msg);
        }

        @Test
        @DisplayName("guides the agent to edit_text instead")
        void guidesToEditText() {
            var r = new PopupInterceptor.Result(true, List.of("X"), true, null, false);
            String msg = PopupInterceptor.formatPopupBlockedError("act", r);
            assertTrue(msg.contains("edit_text"), msg);
        }

        @Test
        @DisplayName("clarifies that popup choosers are NOT 'option' parameter selections")
        void clarifiesOptionDistinction() {
            var r = new PopupInterceptor.Result(true, List.of("X"), true, null, false);
            String msg = PopupInterceptor.formatPopupBlockedError("act", r);
            // We do NOT want the message to (mis)direct the agent to use the 'option' param,
            // since 'option' currently only handles dialog-radio selection, not popup choosers.
            assertTrue(msg.contains("not the same as") || msg.contains("cannot be selected via the 'option'"),
                "Expected message to clarify that popup choosers are not option-selectable. Got: " + msg);
        }
    }

    // ── Result construction sanity ────────────────────────────────────────────

    @Nested
    @DisplayName("Result has sensible defaults")
    class ResultDefaultsTest {

        @Test
        @DisplayName("no popup → popupWasOpened=false, no titles, not cancelled")
        void noPopup() {
            var r = new PopupInterceptor.Result(false, List.of(), false, null, false);
            assertFalse(r.popupWasOpened());
            assertNotNull(r.popupTitles());
            assertTrue(r.popupTitles().isEmpty());
        }
    }
}
