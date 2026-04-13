package com.github.catatafishen.agentbridge.ui.renderers;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the pure parsing methods in {@link HttpRequestRenderer}.
 * These methods extract status codes, headers, and body from HTTP response strings
 * without requiring any Swing components.
 */
class HttpRequestRendererTest {

    // ── parseStatusLine ──────────────────────────────────────

    @Nested
    class ParseStatusLine {

        @Test
        void parsesNewFormat() {
            var info = HttpRequestRenderer.parseStatusLine("HTTP 200 (123ms)\n\nbody");
            assertNotNull(info);
            assertEquals(200, info.code());
            assertEquals("(123ms)", info.detail());
        }

        @Test
        void parsesOldFormatWithMessage() {
            var info = HttpRequestRenderer.parseStatusLine("HTTP 404 Not Found\n\n--- Body ---\nNo page");
            assertNotNull(info);
            assertEquals(404, info.code());
            assertEquals("Not Found", info.detail());
        }

        @Test
        void parsesServerError() {
            var info = HttpRequestRenderer.parseStatusLine("HTTP 500 Internal Server Error");
            assertNotNull(info);
            assertEquals(500, info.code());
            assertEquals("Internal Server Error", info.detail());
        }

        @Test
        void parsesRedirect() {
            var info = HttpRequestRenderer.parseStatusLine("HTTP 301 Moved Permanently\n\nLocation: /new");
            assertNotNull(info);
            assertEquals(301, info.code());
        }

        @Test
        void returnsNullForNonHttpLine() {
            assertNull(HttpRequestRenderer.parseStatusLine("Not an HTTP response"));
        }

        @Test
        void returnsNullForEmptyString() {
            assertNull(HttpRequestRenderer.parseStatusLine(""));
        }

        @Test
        void returnsNullForHttpWithoutSpace() {
            assertNull(HttpRequestRenderer.parseStatusLine("HTTP200"));
        }

        @Test
        void returnsNullForNonNumericCode() {
            assertNull(HttpRequestRenderer.parseStatusLine("HTTP abc OK"));
        }

        @Test
        void parsesCodeOnlyWithDetail() {
            var info = HttpRequestRenderer.parseStatusLine("HTTP 204 No Content");
            assertNotNull(info);
            assertEquals(204, info.code());
            assertEquals("No Content", info.detail());
        }
    }

    // ── parseHeaders ─────────────────────────────────────────

    @Nested
    class ParseHeaders {

        @Test
        void extractsHeadersBetweenMarkers() {
            String output = "HTTP 200 OK\n\n--- Headers ---\nContent-Type: application/json\nX-Custom: value\n\n--- Body ---\n{}";
            int headersStart = output.indexOf(HttpRequestRenderer.HEADERS_MARKER);
            int bodyStart = output.indexOf(HttpRequestRenderer.BODY_MARKER);
            List<String> headers = HttpRequestRenderer.parseHeaders(output, headersStart, bodyStart);
            assertEquals(2, headers.size());
            assertEquals("Content-Type: application/json", headers.get(0));
            assertEquals("X-Custom: value", headers.get(1));
        }

        @Test
        void returnsEmptyWhenNoHeadersMarker() {
            List<String> headers = HttpRequestRenderer.parseHeaders("HTTP 200 OK\n\n--- Body ---\n{}", -1, 20);
            assertTrue(headers.isEmpty());
        }

        @Test
        void extractsHeadersWithoutBody() {
            String output = "HTTP 200 OK\n\n--- Headers ---\nContent-Type: text/html\nCache-Control: no-cache";
            int headersStart = output.indexOf(HttpRequestRenderer.HEADERS_MARKER);
            List<String> headers = HttpRequestRenderer.parseHeaders(output, headersStart, -1);
            assertEquals(2, headers.size());
        }

        @Test
        void filtersBlankLines() {
            String output = "HTTP 200 OK\n\n--- Headers ---\n\nContent-Type: json\n\n--- Body ---\n{}";
            int headersStart = output.indexOf(HttpRequestRenderer.HEADERS_MARKER);
            int bodyStart = output.indexOf(HttpRequestRenderer.BODY_MARKER);
            List<String> headers = HttpRequestRenderer.parseHeaders(output, headersStart, bodyStart);
            assertEquals(1, headers.size());
        }
    }

    // ── parseBody ────────────────────────────────────────────

    @Nested
    class ParseBody {

        @Test
        void extractsBodyAfterMarker() {
            String output = "HTTP 200 OK\n\n--- Body ---\n{\"key\": \"value\"}";
            int bodyStart = output.indexOf(HttpRequestRenderer.BODY_MARKER);
            assertEquals("{\"key\": \"value\"}", HttpRequestRenderer.parseBody(output, bodyStart));
        }

        @Test
        void returnsEmptyWhenNoBodyMarker() {
            assertEquals("", HttpRequestRenderer.parseBody("HTTP 200 OK", -1));
        }

        @Test
        void trimsWhitespace() {
            String output = "HTTP 200 OK\n\n--- Body ---\n   \n  hello world  \n ";
            int bodyStart = output.indexOf(HttpRequestRenderer.BODY_MARKER);
            String body = HttpRequestRenderer.parseBody(output, bodyStart);
            assertEquals("hello world", body);
        }

        @Test
        void handlesEmptyBodyAfterMarker() {
            String output = "HTTP 200 OK\n\n--- Body ---\n   ";
            int bodyStart = output.indexOf(HttpRequestRenderer.BODY_MARKER);
            assertEquals("", HttpRequestRenderer.parseBody(output, bodyStart));
        }

        @Test
        void handlesMultilineBody() {
            String output = "HTTP 200 OK\n\n--- Body ---\nline1\nline2\nline3";
            int bodyStart = output.indexOf(HttpRequestRenderer.BODY_MARKER);
            String body = HttpRequestRenderer.parseBody(output, bodyStart);
            assertTrue(body.contains("line1"));
            assertTrue(body.contains("line3"));
        }
    }

    // ── StatusInfo record ────────────────────────────────────

    @Nested
    class StatusInfoRecord {

        @Test
        void holdsCodeAndDetail() {
            var info = new HttpRequestRenderer.StatusInfo(200, "OK");
            assertEquals(200, info.code());
            assertEquals("OK", info.detail());
        }

        @Test
        void equalsAndHashCode() {
            var a = new HttpRequestRenderer.StatusInfo(200, "OK");
            var b = new HttpRequestRenderer.StatusInfo(200, "OK");
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        void notEqualWithDifferentCode() {
            var a = new HttpRequestRenderer.StatusInfo(200, "OK");
            var b = new HttpRequestRenderer.StatusInfo(404, "OK");
            assertNotEquals(a, b);
        }
    }

    // ── Integration: full output parsing ─────────────────────

    @Nested
    class FullOutputParsing {

        @Test
        void parsesCompleteResponseWithHeadersAndBody() {
            String output = """
                HTTP 200 (45ms)

                --- Headers ---
                Content-Type: application/json
                X-Request-Id: abc123

                --- Body ---
                {"status": "ok"}
                """.stripIndent().trim();

            var status = HttpRequestRenderer.parseStatusLine(output);
            assertNotNull(status);
            assertEquals(200, status.code());

            int headersStart = output.indexOf(HttpRequestRenderer.HEADERS_MARKER);
            int bodyStart = output.indexOf(HttpRequestRenderer.BODY_MARKER);

            List<String> headers = HttpRequestRenderer.parseHeaders(output, headersStart, bodyStart);
            assertEquals(2, headers.size());

            String body = HttpRequestRenderer.parseBody(output, bodyStart);
            assertEquals("{\"status\": \"ok\"}", body);
        }

        @Test
        void parsesResponseWithBodyOnly() {
            String output = "HTTP 200 OK\n\n--- Body ---\nHello World";

            var status = HttpRequestRenderer.parseStatusLine(output);
            assertNotNull(status);

            int headersStart = output.indexOf(HttpRequestRenderer.HEADERS_MARKER);
            int bodyStart = output.indexOf(HttpRequestRenderer.BODY_MARKER);

            assertTrue(HttpRequestRenderer.parseHeaders(output, headersStart, bodyStart).isEmpty());
            assertEquals("Hello World", HttpRequestRenderer.parseBody(output, bodyStart));
        }
    }
}
