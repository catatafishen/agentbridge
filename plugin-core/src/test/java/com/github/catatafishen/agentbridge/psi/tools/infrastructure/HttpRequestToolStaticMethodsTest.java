package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for static utility methods in {@link HttpRequestTool}.
 * Uses reflection since the methods are private.
 */
class HttpRequestToolStaticMethodsTest {

    // ── encodeFormData ──────────────────────────────────────

    @Test
    void encodeFormData_encodesKeyValuePairs() throws Exception {
        JsonObject form = new JsonObject();
        form.addProperty("username", "john");
        form.addProperty("password", "s3cret");

        String result = invokeEncodeFormData(form);
        assertTrue(result.contains("username=john"));
        assertTrue(result.contains("password=s3cret"));
        assertTrue(result.contains("&"), "Should join with &");
    }

    @Test
    void encodeFormData_urlEncodesSpecialChars() throws Exception {
        JsonObject form = new JsonObject();
        form.addProperty("q", "hello world");
        form.addProperty("url", "https://example.com/path?a=1&b=2");

        String result = invokeEncodeFormData(form);
        assertTrue(result.contains("q=hello+world") || result.contains("q=hello%20world"));
        assertFalse(result.contains("https://example.com"), "URL should be encoded");
    }

    @Test
    void encodeFormData_handlesEmptyObject() throws Exception {
        assertEquals("", invokeEncodeFormData(new JsonObject()));
    }

    @Test
    void encodeFormData_handlesSinglePair() throws Exception {
        JsonObject form = new JsonObject();
        form.addProperty("key", "value");
        assertEquals("key=value", invokeEncodeFormData(form));
    }

    // ── resolveBody ─────────────────────────────────────────

    @Test
    void resolveBody_returnsBodyWhenPresent() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("body", "{\"data\":1}");
        assertEquals("{\"data\":1}", invokeResolveBody(args));
    }

    @Test
    void resolveBody_encodesFormDataWhenPresent() throws Exception {
        JsonObject args = new JsonObject();
        JsonObject form = new JsonObject();
        form.addProperty("k", "v");
        args.add("form_data", form);
        assertEquals("k=v", invokeResolveBody(args));
    }

    @Test
    void resolveBody_prefersBodyOverFormData() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("body", "raw body");
        JsonObject form = new JsonObject();
        form.addProperty("k", "v");
        args.add("form_data", form);

        assertEquals("raw body", invokeResolveBody(args), "body should take priority over form_data");
    }

    @Test
    void resolveBody_returnsNullWhenNeitherPresent() throws Exception {
        assertNull(invokeResolveBody(new JsonObject()));
    }

    // ── resolveBodyContentType ──────────────────────────────

    @Test
    void resolveBodyContentType_returnsFormEncodedForFormData() throws Exception {
        JsonObject args = new JsonObject();
        args.add("form_data", new JsonObject());
        assertEquals("application/x-www-form-urlencoded", invokeResolveBodyContentType(args));
    }

    @Test
    void resolveBodyContentType_returnsJsonForBody() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("body", "{}");
        assertEquals("application/json", invokeResolveBodyContentType(args));
    }

    @Test
    void resolveBodyContentType_returnsNullWhenNoBody() throws Exception {
        assertNull(invokeResolveBodyContentType(new JsonObject()));
    }

    // ── extractHost ─────────────────────────────────────────

    @Test
    void extractHost_extractsFromValidUrl() throws Exception {
        assertEquals("api.example.com", invokeExtractHost("https://api.example.com/v1/data"));
    }

    @Test
    void extractHost_truncatesInvalidUrl() throws Exception {
        String result = invokeExtractHost("not-a-url-at-all");
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void extractHost_handlesUrlWithPort() throws Exception {
        assertEquals("localhost", invokeExtractHost("http://localhost:8080/path"));
    }

    // ── truncateUrl ─────────────────────────────────────────

    @Test
    void truncateUrl_shortUrlUnchanged() throws Exception {
        assertEquals("http://example.com", invokeTruncateUrl("http://example.com"));
    }

    @Test
    void truncateUrl_longUrlTruncated() throws Exception {
        String longUrl = "https://api.example.com/very/long/path/that/exceeds/limit";
        String result = invokeTruncateUrl(longUrl);
        assertTrue(result.endsWith("..."));
        assertEquals(30, result.length());
    }

    @Test
    void truncateUrl_exactlyThirtyCharsNotTruncated() throws Exception {
        String url = "123456789012345678901234567890"; // 30 chars
        assertEquals(url, invokeTruncateUrl(url));
    }

    // ── applyAuth ────────────────────────────────────────────

    @Nested
    class ApplyAuthTest {

        @Test
        void bearerToken_setsAuthorizationHeader() throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("auth", "bearer my-token-123");
            HttpRequest request = buildRequestWithAuth(args);
            assertEquals("Bearer my-token-123",
                request.headers().firstValue("Authorization").orElse(null));
        }

        @Test
        void bearerToken_caseInsensitive() throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("auth", "BEARER MY-TOKEN");
            HttpRequest request = buildRequestWithAuth(args);
            assertEquals("Bearer MY-TOKEN",
                request.headers().firstValue("Authorization").orElse(null));
        }

        @Test
        void bearerToken_trimsWhitespace() throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("auth", "  bearer   spaced-token  ");
            HttpRequest request = buildRequestWithAuth(args);
            assertEquals("Bearer spaced-token",
                request.headers().firstValue("Authorization").orElse(null));
        }

        @Test
        void basicAuth_base64EncodesCredentials() throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("auth", "basic user:pass");
            HttpRequest request = buildRequestWithAuth(args);
            String expected = "Basic " + Base64.getEncoder()
                .encodeToString("user:pass".getBytes(StandardCharsets.UTF_8));
            assertEquals(expected,
                request.headers().firstValue("Authorization").orElse(null));
        }

        @Test
        void basicAuth_caseInsensitive() throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("auth", "BASIC admin:secret");
            HttpRequest request = buildRequestWithAuth(args);
            String expected = "Basic " + Base64.getEncoder()
                .encodeToString("admin:secret".getBytes(StandardCharsets.UTF_8));
            assertEquals(expected,
                request.headers().firstValue("Authorization").orElse(null));
        }

        @Test
        void basicAuth_handlesColonInPassword() throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("auth", "basic user:p@ss:word!");
            HttpRequest request = buildRequestWithAuth(args);
            String expected = "Basic " + Base64.getEncoder()
                .encodeToString("user:p@ss:word!".getBytes(StandardCharsets.UTF_8));
            assertEquals(expected,
                request.headers().firstValue("Authorization").orElse(null));
        }

        @Test
        void rawAuth_passedThrough() throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("auth", "Token abc123xyz");
            HttpRequest request = buildRequestWithAuth(args);
            assertEquals("Token abc123xyz",
                request.headers().firstValue("Authorization").orElse(null));
        }

        @Test
        void noAuth_noAuthorizationHeader() throws Exception {
            JsonObject args = new JsonObject();
            HttpRequest request = buildRequestWithAuth(args);
            assertTrue(request.headers().firstValue("Authorization").isEmpty());
        }

        private HttpRequest buildRequestWithAuth(JsonObject args) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://test.example.com"));
            invokeApplyAuth(builder, args);
            return builder.GET().build();
        }
    }

    // ── applyCookies ────────────────────────────────────────

    @Nested
    class ApplyCookiesTest {

        @Test
        void singleCookie() throws Exception {
            JsonObject args = new JsonObject();
            JsonObject cookies = new JsonObject();
            cookies.addProperty("session", "abc123");
            args.add("cookies", cookies);

            HttpRequest request = buildRequestWithCookies(args);
            assertEquals("session=abc123",
                request.headers().firstValue("Cookie").orElse(null));
        }

        @Test
        void multipleCookies_joinedWithSemicolon() throws Exception {
            JsonObject args = new JsonObject();
            JsonObject cookies = new JsonObject();
            cookies.addProperty("a", "1");
            cookies.addProperty("b", "2");
            args.add("cookies", cookies);

            HttpRequest request = buildRequestWithCookies(args);
            String cookie = request.headers().firstValue("Cookie").orElse("");
            assertTrue(cookie.contains("a=1"));
            assertTrue(cookie.contains("b=2"));
            assertTrue(cookie.contains("; "));
        }

        @Test
        void emptyCookiesObject_noCookieHeader() throws Exception {
            JsonObject args = new JsonObject();
            args.add("cookies", new JsonObject());

            HttpRequest request = buildRequestWithCookies(args);
            assertTrue(request.headers().firstValue("Cookie").isEmpty());
        }

        @Test
        void noCookiesParam_noCookieHeader() throws Exception {
            JsonObject args = new JsonObject();
            HttpRequest request = buildRequestWithCookies(args);
            assertTrue(request.headers().firstValue("Cookie").isEmpty());
        }

        @Test
        void cookiesNotJsonObject_noCookieHeader() throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("cookies", "not-an-object");

            HttpRequest request = buildRequestWithCookies(args);
            assertTrue(request.headers().firstValue("Cookie").isEmpty());
        }

        @Test
        void cookieWithSpecialCharacters() throws Exception {
            JsonObject args = new JsonObject();
            JsonObject cookies = new JsonObject();
            cookies.addProperty("token", "abc=def/ghi+jkl");
            args.add("cookies", cookies);

            HttpRequest request = buildRequestWithCookies(args);
            assertEquals("token=abc=def/ghi+jkl",
                request.headers().firstValue("Cookie").orElse(null));
        }

        private HttpRequest buildRequestWithCookies(JsonObject args) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://test.example.com"));
            invokeApplyCookies(builder, args);
            return builder.GET().build();
        }
    }

    // ── applyHeaders ────────────────────────────────────────

    @Nested
    class ApplyHeadersTest {

        @Test
        void appliesCustomHeaders() throws Exception {
            JsonObject args = new JsonObject();
            JsonObject headers = new JsonObject();
            headers.addProperty("X-Custom", "value1");
            headers.addProperty("Accept", "text/plain");
            args.add("headers", headers);

            HttpRequest request = buildRequestWithHeaders(args, null);
            assertEquals("value1", request.headers().firstValue("X-Custom").orElse(null));
            assertEquals("text/plain", request.headers().firstValue("Accept").orElse(null));
        }

        @Test
        void autoSetsContentType_whenNoExplicit() throws Exception {
            JsonObject args = new JsonObject();
            HttpRequest request = buildRequestWithHeaders(args, "application/json");
            assertEquals("application/json",
                request.headers().firstValue("Content-Type").orElse(null));
        }

        @Test
        void doesNotOverrideExplicitContentType() throws Exception {
            JsonObject args = new JsonObject();
            JsonObject headers = new JsonObject();
            headers.addProperty("Content-Type", "text/xml");
            args.add("headers", headers);

            HttpRequest request = buildRequestWithHeaders(args, "application/json");
            List<String> values = request.headers().allValues("Content-Type");
            assertTrue(values.contains("text/xml"));
            assertFalse(values.contains("application/json"),
                "Auto content-type should not be added when explicit Content-Type is present");
        }

        @Test
        void contentTypeCaseInsensitiveMatch() throws Exception {
            JsonObject args = new JsonObject();
            JsonObject headers = new JsonObject();
            headers.addProperty("content-type", "text/plain");
            args.add("headers", headers);

            HttpRequest request = buildRequestWithHeaders(args, "application/json");
            // Java HttpHeaders normalizes keys to lowercase
            List<String> values = request.headers().allValues("content-type");
            assertEquals(1, values.size(), "Should have exactly one content-type header");
            assertEquals("text/plain", values.get(0));
        }

        @Test
        void nullBodyContentType_noAutoContentType() throws Exception {
            JsonObject args = new JsonObject();
            HttpRequest request = buildRequestWithHeaders(args, null);
            assertTrue(request.headers().firstValue("Content-Type").isEmpty());
        }

        @Test
        void noHeadersParam_onlyAutoContentType() throws Exception {
            JsonObject args = new JsonObject();
            HttpRequest request = buildRequestWithHeaders(args, "application/x-www-form-urlencoded");
            assertEquals("application/x-www-form-urlencoded",
                request.headers().firstValue("Content-Type").orElse(null));
        }

        @Test
        void headersNotJsonObject_onlyAutoContentType() throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("headers", "not-an-object");

            HttpRequest request = buildRequestWithHeaders(args, "application/json");
            assertEquals("application/json",
                request.headers().firstValue("Content-Type").orElse(null));
        }

        private HttpRequest buildRequestWithHeaders(JsonObject args, String bodyContentType) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://test.example.com"));
            invokeApplyHeaders(builder, args, bodyContentType);
            return builder.GET().build();
        }
    }

    // ── encodeFormData (edge cases) ─────────────────────────

    @Nested
    class EncodeFormDataEdgeCasesTest {

        @Test
        void unicodeCharacters() throws Exception {
            JsonObject form = new JsonObject();
            form.addProperty("name", "日本語");
            String result = invokeEncodeFormData(form);
            assertTrue(result.startsWith("name="));
            assertFalse(result.contains("日本語"), "Unicode should be percent-encoded");
        }

        @Test
        void emptyStringValues() throws Exception {
            JsonObject form = new JsonObject();
            form.addProperty("key", "");
            assertEquals("key=", invokeEncodeFormData(form));
        }

        @Test
        void keysWithSpecialCharacters() throws Exception {
            JsonObject form = new JsonObject();
            form.addProperty("my key", "my value");
            String result = invokeEncodeFormData(form);
            assertFalse(result.contains(" "), "Spaces in keys should be encoded");
            assertTrue(result.contains("="));
        }

        @Test
        void encodesAmpersandInValues() throws Exception {
            JsonObject form = new JsonObject();
            form.addProperty("q", "a&b");
            String result = invokeEncodeFormData(form);
            assertFalse(result.contains("&b"), "Ampersand in value should be encoded");
            assertTrue(result.contains("q=a%26b") || result.contains("q=a&amp;b")
                || result.contains("%26"), "Ampersand should be percent-encoded");
        }

        @Test
        void encodesEqualsInValues() throws Exception {
            JsonObject form = new JsonObject();
            form.addProperty("expr", "a=b");
            String result = invokeEncodeFormData(form);
            assertTrue(result.startsWith("expr="));
            assertTrue(result.contains("%3D"), "Equals sign in value should be percent-encoded");
        }
    }

    // ── extractHost (edge cases) ────────────────────────────

    @Nested
    class ExtractHostEdgeCasesTest {

        @Test
        void ipAddressUrl() throws Exception {
            assertEquals("192.168.1.1", invokeExtractHost("http://192.168.1.1:8080/api"));
        }

        @Test
        void urlWithoutScheme_fallsBackToTruncate() throws Exception {
            // URI without scheme treats whole thing as path, host is null
            String result = invokeExtractHost("example.com/path");
            assertEquals("example.com/path", result);
        }

        @Test
        void veryLongInvalidUrl_truncated() throws Exception {
            String longUrl = "this is definitely not a valid URL and it is very long too";
            String result = invokeExtractHost(longUrl);
            assertTrue(result.endsWith("..."));
            assertEquals(30, result.length());
        }

        @Test
        void urlWithUserInfo() throws Exception {
            assertEquals("host.com", invokeExtractHost("http://user:pass@host.com/path"));
        }

        @Test
        void httpsUrl() throws Exception {
            assertEquals("secure.example.com", invokeExtractHost("https://secure.example.com:443/"));
        }

        @Test
        void urlWithFragment() throws Exception {
            assertEquals("example.com", invokeExtractHost("http://example.com/page#section"));
        }

        @Test
        void urlWithQueryString() throws Exception {
            assertEquals("api.example.com", invokeExtractHost("http://api.example.com?key=val"));
        }
    }

    // ── resolveBody (edge cases) ────────────────────────────

    @Nested
    class ResolveBodyEdgeCasesTest {

        @Test
        void formDataNotJsonObject_fallsToBody() throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("body", "raw text");
            args.addProperty("form_data", "not-json-object");
            assertEquals("raw text", invokeResolveBody(args));
        }

        @Test
        void formDataNotJsonObject_noBody_returnsNull() throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("form_data", "not-json-object");
            assertNull(invokeResolveBody(args));
        }

        @Test
        void emptyBodyString() throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("body", "");
            assertEquals("", invokeResolveBody(args));
        }
    }

    // ── resolveBodyContentType (edge cases) ─────────────────

    @Nested
    class ResolveBodyContentTypeEdgeCasesTest {

        @Test
        void formDataTakesPriorityOverBody() throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("body", "raw");
            args.add("form_data", new JsonObject());
            assertEquals("application/x-www-form-urlencoded", invokeResolveBodyContentType(args));
        }

        @Test
        void formDataNotJsonObject_fallsToBody() throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("form_data", "not-json-object");
            args.addProperty("body", "{}");
            assertEquals("application/json", invokeResolveBodyContentType(args));
        }

        @Test
        void formDataNotJsonObject_noBody_returnsNull() throws Exception {
            JsonObject args = new JsonObject();
            args.addProperty("form_data", "not-json-object");
            assertNull(invokeResolveBodyContentType(args));
        }
    }

    // ── truncateUrl (edge cases) ────────────────────────────

    @Nested
    class TruncateUrlEdgeCasesTest {

        @Test
        void thirtyOneChars_isTruncated() throws Exception {
            String url = "1234567890123456789012345678901"; // 31 chars
            String result = invokeTruncateUrl(url);
            assertTrue(result.endsWith("..."));
            assertEquals(30, result.length());
        }

        @Test
        void emptyString() throws Exception {
            assertEquals("", invokeTruncateUrl(""));
        }

        @Test
        void singleCharacter() throws Exception {
            assertEquals("x", invokeTruncateUrl("x"));
        }
    }

    // ── Reflection helpers ──────────────────────────────────

    private static String invokeEncodeFormData(JsonObject form) throws Exception {
        Method m = HttpRequestTool.class.getDeclaredMethod("encodeFormData", JsonObject.class);
        m.setAccessible(true);
        return (String) m.invoke(null, form);
    }

    private static String invokeResolveBody(JsonObject args) throws Exception {
        Method m = HttpRequestTool.class.getDeclaredMethod("resolveBody", JsonObject.class);
        m.setAccessible(true);
        return (String) m.invoke(null, args);
    }

    private static String invokeResolveBodyContentType(JsonObject args) throws Exception {
        Method m = HttpRequestTool.class.getDeclaredMethod("resolveBodyContentType", JsonObject.class);
        m.setAccessible(true);
        return (String) m.invoke(null, args);
    }

    private static String invokeExtractHost(String url) throws Exception {
        Method m = HttpRequestTool.class.getDeclaredMethod("extractHost", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, url);
    }

    private static String invokeTruncateUrl(String url) throws Exception {
        Method m = HttpRequestTool.class.getDeclaredMethod("truncateUrl", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, url);
    }

    private static void invokeApplyAuth(HttpRequest.Builder builder, JsonObject args) throws Exception {
        Method m = HttpRequestTool.class.getDeclaredMethod("applyAuth", HttpRequest.Builder.class, JsonObject.class);
        m.setAccessible(true);
        m.invoke(null, builder, args);
    }

    private static void invokeApplyCookies(HttpRequest.Builder builder, JsonObject args) throws Exception {
        Method m = HttpRequestTool.class.getDeclaredMethod("applyCookies", HttpRequest.Builder.class, JsonObject.class);
        m.setAccessible(true);
        m.invoke(null, builder, args);
    }

    private static void invokeApplyHeaders(HttpRequest.Builder builder, JsonObject args, String bodyContentType) throws Exception {
        Method m = HttpRequestTool.class.getDeclaredMethod("applyHeaders", HttpRequest.Builder.class, JsonObject.class, String.class);
        m.setAccessible(true);
        m.invoke(null, builder, args, bodyContentType);
    }
}
