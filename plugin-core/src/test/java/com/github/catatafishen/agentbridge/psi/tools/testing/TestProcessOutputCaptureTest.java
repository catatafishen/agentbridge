package com.github.catatafishen.agentbridge.psi.tools.testing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestProcessOutputCaptureTest {

    @Test
    void returnsAllCapturedOutputWhenWithinLimit() {
        TestProcessOutputCapture capture = new TestProcessOutputCapture();

        capture.append("first\n");
        capture.append("failure detail\n");

        assertEquals("first\nfailure detail\n", capture.content());
    }

    @Test
    void retainsFailureTailWhenOutputExceedsLimit() {
        TestProcessOutputCapture capture = new TestProcessOutputCapture();

        capture.append("x".repeat(20_000));
        capture.append("assertion failed at ExampleTest.java:42\n");

        String output = capture.content();
        assertTrue(output.startsWith("[Earlier process output omitted]\n"));
        assertTrue(output.endsWith("assertion failed at ExampleTest.java:42\n"));
        assertTrue(output.length() < 17_000);
    }
}
