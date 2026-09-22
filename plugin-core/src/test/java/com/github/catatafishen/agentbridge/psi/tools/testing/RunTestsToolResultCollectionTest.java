package com.github.catatafishen.agentbridge.psi.tools.testing;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RunTestsToolResultCollectionTest {

    @Test
    void collectsPassFailErrorAndSkippedCountsFromTheResultsModel() throws Exception {
        Method collectResults = RunTestsTool.class.getDeclaredMethod("tryGetTestResults", Object.class);
        collectResults.setAccessible(true);

        String output = (String) collectResults.invoke(new RunTestsTool(null), new FakeConsole());

        assertTrue(output.startsWith("Test Results: 4 tests, 1 passed, 1 failed, 1 errors, 1 skipped"));
        assertTrue(output.contains("=== Test Details ==="));
        assertTrue(output.contains("PASSED passes"));
        assertTrue(output.contains("FAILED fails"));
    }

    public static final class FakeConsole {
        public FakeViewer getResultsViewer() {
            return new FakeViewer();
        }
    }

    public static final class FakeViewer {
        public List<FakeTest> getAllTests() {
            return List.of(
                new FakeTest("passes", true, false, false, false),
                new FakeTest("fails", false, true, false, false),
                new FakeTest("errors", false, true, false, true),
                new FakeTest("skips", false, false, true, false));
        }
    }

    public static final class FakeTest {
        private final String name;
        private final boolean passed;
        private final boolean defect;
        private final boolean ignored;
        private final boolean error;

        FakeTest(String name, boolean passed, boolean defect, boolean ignored, boolean error) {
            this.name = name;
            this.passed = passed;
            this.defect = defect;
            this.ignored = ignored;
            this.error = error;
        }

        public String getPresentableName() {
            return name;
        }

        public boolean isPassed() {
            return passed;
        }

        public boolean isDefect() {
            return defect;
        }

        public boolean isIgnored() {
            return ignored;
        }

        public boolean isError() {
            return error;
        }

        public List<FakeTest> getChildren() {
            return List.of();
        }

        public String getErrorMessage() {
            return defect ? "failure" : null;
        }

        public String getStacktrace() {
            return defect ? "trace" : null;
        }
    }
}
