package com.github.catatafishen.agentbridge.ui;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class ChatThemeTest {

    private static final int SA_COLOR_COUNT = 8;

    // ── agentColorIndex ─────────────────────────────────────────────

    @Nested
    class AgentColorIndex {

        @Test
        void copilot_returns0() {
            assertEquals(0, ChatTheme.INSTANCE.agentColorIndex("copilot"));
        }

        @Test
        void claudeCli_returns1() {
            assertEquals(1, ChatTheme.INSTANCE.agentColorIndex("claude-cli"));
        }

        @Test
        void junie_returns2() {
            assertEquals(2, ChatTheme.INSTANCE.agentColorIndex("junie"));
        }

        @Test
        void kiro_returns3() {
            assertEquals(3, ChatTheme.INSTANCE.agentColorIndex("kiro"));
        }

        @Test
        void opencode_returns4() {
            assertEquals(4, ChatTheme.INSTANCE.agentColorIndex("opencode"));
        }

        @Test
        void customAgent_returnsValueInRange() {
            int idx = ChatTheme.INSTANCE.agentColorIndex("my-custom-agent");
            assertTrue(idx >= 0 && idx < SA_COLOR_COUNT,
                    "Expected index in [0, " + (SA_COLOR_COUNT - 1) + "] but got " + idx);
        }

        @Test
        void customAgent_isDeterministic() {
            int first = ChatTheme.INSTANCE.agentColorIndex("my-custom-agent");
            int second = ChatTheme.INSTANCE.agentColorIndex("my-custom-agent");
            assertEquals(first, second, "Same input must produce the same output");
        }

        @Test
        void variousCustomStrings_allInRange() {
            String[] inputs = {"alpha", "beta", "gamma", "delta", "epsilon",
                    "zeta", "eta", "theta", "iota", "kappa"};
            for (String input : inputs) {
                int idx = ChatTheme.INSTANCE.agentColorIndex(input);
                assertTrue(idx >= 0 && idx < SA_COLOR_COUNT,
                        "For '" + input + "': expected index in [0, " + (SA_COLOR_COUNT - 1) + "] but got " + idx);
            }
        }
    }

    // ── activeAgentCss ──────────────────────────────────────────────

    @Nested
    class ActiveAgentCss {

        @Test
        void copilot_cssUsesIndex0() {
            assertEquals(
                    "--active-agent:var(--sa-c0);--active-agent-a06:var(--sa-c0-a06);",
                    ChatTheme.INSTANCE.activeAgentCss("copilot"));
        }

        @Test
        void claudeCli_cssUsesIndex1() {
            assertEquals(
                    "--active-agent:var(--sa-c1);--active-agent-a06:var(--sa-c1-a06);",
                    ChatTheme.INSTANCE.activeAgentCss("claude-cli"));
        }

        @Test
        void junie_cssUsesIndex2() {
            assertEquals(
                    "--active-agent:var(--sa-c2);--active-agent-a06:var(--sa-c2-a06);",
                    ChatTheme.INSTANCE.activeAgentCss("junie"));
        }
    }

    // ── rgb (private) ───────────────────────────────────────────────

    @Nested
    class Rgb {

        private String invokeRgb(Color c) throws Exception {
            Object instance = ChatTheme.class.getDeclaredField("INSTANCE").get(null);
            Method rgbMethod = ChatTheme.class.getDeclaredMethod("rgb", Color.class);
            rgbMethod.setAccessible(true);
            return (String) rgbMethod.invoke(instance, c);
        }

        @Test
        void pureRed() throws Exception {
            assertEquals("rgb(255,0,0)", invokeRgb(new Color(255, 0, 0)));
        }

        @Test
        void mixedBlue() throws Exception {
            assertEquals("rgb(0,128,255)", invokeRgb(new Color(0, 128, 255)));
        }

        @Test
        void black() throws Exception {
            assertEquals("rgb(0,0,0)", invokeRgb(new Color(0, 0, 0)));
        }
    }

    // ── rgba (private) ──────────────────────────────────────────────

    @Nested
    class Rgba {

        private String invokeRgba(Color c, double a) throws Exception {
            Object instance = ChatTheme.class.getDeclaredField("INSTANCE").get(null);
            Method rgbaMethod = ChatTheme.class.getDeclaredMethod("rgba", Color.class, double.class);
            rgbaMethod.setAccessible(true);
            return (String) rgbaMethod.invoke(instance, c, a);
        }

        @Test
        void redWithHalfAlpha() throws Exception {
            assertEquals("rgba(255,0,0,0.5)", invokeRgba(new Color(255, 0, 0), 0.5));
        }

        @Test
        void mixedColorWithAlpha016() throws Exception {
            assertEquals("rgba(100,200,50,0.16)", invokeRgba(new Color(100, 200, 50), 0.16));
        }
    }
}
