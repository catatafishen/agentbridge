package com.github.catatafishen.agentbridge.psi.java;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodeQualityJavaSupportTest {

    @Test
    void noLeadingWhitespace() {
        assertEquals("", CodeQualityJavaSupport.extractLeadingWhitespace("hello"));
    }

    @Test
    void leadingSpaces() {
        assertEquals("    ", CodeQualityJavaSupport.extractLeadingWhitespace("    hello"));
    }

    @Test
    void leadingTabs() {
        assertEquals("\t\t", CodeQualityJavaSupport.extractLeadingWhitespace("\t\thello"));
    }

    @Test
    void mixedTabsAndSpaces() {
        assertEquals("\t  \t", CodeQualityJavaSupport.extractLeadingWhitespace("\t  \thello"));
    }

    @Test
    void allWhitespace() {
        assertEquals("    ", CodeQualityJavaSupport.extractLeadingWhitespace("    "));
    }

    @Test
    void emptyString() {
        assertEquals("", CodeQualityJavaSupport.extractLeadingWhitespace(""));
    }

    @Test
    void singleSpace() {
        assertEquals(" ", CodeQualityJavaSupport.extractLeadingWhitespace(" x"));
    }

    @Test
    void singleTab() {
        assertEquals("\t", CodeQualityJavaSupport.extractLeadingWhitespace("\tx"));
    }

    @Test
    void leadingNewline_notIncluded() {
        assertEquals("", CodeQualityJavaSupport.extractLeadingWhitespace("\nhello"));
    }

    @Test
    void onlyTabs() {
        assertEquals("\t\t\t", CodeQualityJavaSupport.extractLeadingWhitespace("\t\t\t"));
    }

    @Test
    void whitespaceBeforeAnnotation() {
        assertEquals("    ", CodeQualityJavaSupport.extractLeadingWhitespace("    @SuppressWarnings(\"all\")"));
    }
}
