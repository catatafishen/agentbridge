package com.github.catatafishen.agentbridge.ui.renderers;

import kotlin.text.MatchResult;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests regex patterns in {@link TerminalOutputRenderer}.
 */
class TerminalOutputRendererTest {

    private static final TerminalOutputRenderer R = TerminalOutputRenderer.INSTANCE;

    @Nested
    class TabHeaderPattern {

        @Test
        void matchesTabHeader() {
            MatchResult match = R.getTAB_HEADER().find("Tab: My Terminal", 0);

            assertNotNull(match);
            assertEquals("My Terminal", match.getGroupValues().get(1));
        }

        @Test
        void matchesTabWithSpaces() {
            MatchResult match = R.getTAB_HEADER().find("Tab: Build Output Tab", 0);

            assertNotNull(match);
            assertEquals("Build Output Tab", match.getGroupValues().get(1));
        }

        @Test
        void doesNotMatchWithoutPrefix() {
            assertNull(R.getTAB_HEADER().find("My Terminal", 0));
        }
    }

    @Nested
    class TotalLengthPattern {

        @Test
        void matchesTotalLength() {
            MatchResult match = R.getTOTAL_LENGTH().find("Total length: 1234 chars", 0);

            assertNotNull(match);
            assertEquals("1234", match.getGroupValues().get(1));
        }

        @Test
        void matchesLargeCount() {
            MatchResult match = R.getTOTAL_LENGTH().find("Total length: 999999 chars", 0);

            assertNotNull(match);
            assertEquals("999999", match.getGroupValues().get(1));
        }

        @Test
        void doesNotMatchWithoutChars() {
            assertNull(R.getTOTAL_LENGTH().find("Total length: 1234", 0));
        }
    }

    @Nested
    class TerminalOutputPattern {

        @Test
        void matchesTerminalOutput() {
            MatchResult match = R.getTERMINAL_OUTPUT().find("Terminal 'agent' output:", 0);

            assertNotNull(match);
            assertEquals("agent", match.getGroupValues().get(1));
        }

        @Test
        void matchesTabNameWithSpaces() {
            MatchResult match = R.getTERMINAL_OUTPUT().find("Terminal 'my tab' output:", 0);

            assertNotNull(match);
            assertEquals("my tab", match.getGroupValues().get(1));
        }

        @Test
        void doesNotMatchWithoutQuotes() {
            assertNull(R.getTERMINAL_OUTPUT().find("Terminal agent output:", 0));
        }
    }

    @Nested
    class TerminalRunningPattern {

        @Test
        void matchesRunningCommand() {
            MatchResult match = R.getTERMINAL_RUNNING().find(
                "Running in terminal 'agent': ls -la", 0);

            assertNotNull(match);
            assertEquals("agent", match.getGroupValues().get(1));
            assertEquals("ls -la", match.getGroupValues().get(2));
        }

        @Test
        void matchesComplexCommand() {
            MatchResult match = R.getTERMINAL_RUNNING().find(
                "Running in terminal 'build': ./gradlew build --info", 0);

            assertNotNull(match);
            assertEquals("build", match.getGroupValues().get(1));
            assertEquals("./gradlew build --info", match.getGroupValues().get(2));
        }

        @Test
        void doesNotMatchSentPattern() {
            assertNull(R.getTERMINAL_RUNNING().find("Sent to terminal 'agent': hello", 0));
        }
    }

    @Nested
    class TerminalSentPattern {

        @Test
        void matchesSentInput() {
            MatchResult match = R.getTERMINAL_SENT().find(
                "Sent to terminal 'agent': hello", 0);

            assertNotNull(match);
            assertEquals("agent", match.getGroupValues().get(1));
            assertEquals("hello", match.getGroupValues().get(2));
        }

        @Test
        void matchesSentWithSpecialChars() {
            MatchResult match = R.getTERMINAL_SENT().find(
                "Sent to terminal 'my-tab': {enter}", 0);

            assertNotNull(match);
            assertEquals("my-tab", match.getGroupValues().get(1));
            assertEquals("{enter}", match.getGroupValues().get(2));
        }

        @Test
        void doesNotMatchRunningPattern() {
            assertNull(R.getTERMINAL_SENT().find("Running in terminal 'agent': ls", 0));
        }
    }
}
