package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link QualityTool#resolveColumn(Document, int, String, Integer)}.
 *
 * <p>Uses a Mockito mock of {@link Document} representing a three-line file:</p>
 * <pre>
 * line 1: "public class Foo {"   (chars 0–18, length 19 including newline)
 * line 2: "    int bar = 0;"     (chars 20–35)
 * line 3: "}"                    (char 37)
 * </pre>
 */
class QualityToolResolveColumnTest {

    private static final String LINE1 = "public class Foo {";
    private static final String LINE2 = "    int bar = 0;";
    private static final String LINE3 = "}";

    private Document doc;

    @BeforeEach
    void setUp() {
        doc = mock(Document.class);

        // Line 1 (0-indexed line 0): "public class Foo {"
        when(doc.getLineStartOffset(0)).thenReturn(0);
        when(doc.getLineEndOffset(0)).thenReturn(LINE1.length());
        when(doc.getText(new TextRange(0, LINE1.length()))).thenReturn(LINE1);

        // Line 2 (0-indexed line 1): "    int bar = 0;"
        int line2Start = LINE1.length() + 1; // after newline
        int line2End = line2Start + LINE2.length();
        when(doc.getLineStartOffset(1)).thenReturn(line2Start);
        when(doc.getLineEndOffset(1)).thenReturn(line2End);
        when(doc.getText(new TextRange(line2Start, line2End))).thenReturn(LINE2);

        // Line 3 (0-indexed line 2): "}"
        int line3Start = line2End + 1;
        int line3End = line3Start + LINE3.length();
        when(doc.getLineStartOffset(2)).thenReturn(line3Start);
        when(doc.getLineEndOffset(2)).thenReturn(line3End);
        when(doc.getText(new TextRange(line3Start, line3End))).thenReturn(LINE3);
    }

    @Test
    @DisplayName("symbol found returns indexOf(symbol) in line text")
    void symbolFound() {
        // "Foo" starts at index 13 in "public class Foo {"
        int col = QualityTool.resolveColumn(doc, 1, "Foo", null);
        assertEquals(LINE1.indexOf("Foo"), col);
    }

    static Stream<Arguments> resolveColumnFallbackCases() {
        return Stream.of(
            Arguments.of("symbol not found returns 0", 1, "Missing", null, 0),
            Arguments.of("no symbol and no column returns 0", 1, null, null, 0),
            Arguments.of("blank symbol treated as absent", 1, "  ", null, 0),
            Arguments.of("column=1 returns 0", 1, null, 1, 0),
            Arguments.of("explicit column returns column-1", 1, null, 5, 4)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resolveColumnFallbackCases")
    void resolveColumn_fallbackAndExplicit(String desc, int line, String symbol, Integer column, int expected) {
        int col = QualityTool.resolveColumn(doc, line, symbol, column);
        assertEquals(expected, col);
    }

    @Test
    @DisplayName("symbol takes priority over column")
    void symbolPriorityOverColumn() {
        int col = QualityTool.resolveColumn(doc, 1, "Foo", 5);
        assertEquals(LINE1.indexOf("Foo"), col);
    }

    @Test
    @DisplayName("symbol on line 2 is resolved correctly")
    void symbolOnLine2() {
        int col = QualityTool.resolveColumn(doc, 2, "bar", null);
        assertEquals(LINE2.indexOf("bar"), col);
    }
}
