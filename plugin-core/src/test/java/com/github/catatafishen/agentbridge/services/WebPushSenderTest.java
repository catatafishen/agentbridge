package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for cryptographic utility methods in {@link WebPushSender}.
 * Covers VAPID key generation, serialization round-trips, DER/P1363
 * signature conversion, and byte-array helpers — all critical for
 * RFC 8291/8292 Web Push correctness.
 */
class WebPushSenderTest {

    // ── VAPID key pair generation & serialization ────────────────────────────

    @Test
    void generateVapidKeyPair_producesP256Keys() throws Exception {
        KeyPair kp = WebPushSender.generateVapidKeyPair();
        assertNotNull(kp);
        assertNotNull(kp.getPublic());
        assertNotNull(kp.getPrivate());
        assertEquals("EC", kp.getPublic().getAlgorithm());
        assertEquals("EC", kp.getPrivate().getAlgorithm());
    }

    @Test
    void serializeDeserializeRoundTrip_preservesKeyMaterial() throws Exception {
        KeyPair original = WebPushSender.generateVapidKeyPair();
        String[] serialized = WebPushSender.serializeKeyPair(original);

        assertEquals(2, serialized.length);
        assertNotNull(serialized[0]); // private key base64url
        assertNotNull(serialized[1]); // public key base64url

        KeyPair restored = WebPushSender.deserializeKeyPair(serialized[0], serialized[1]);
        assertNotNull(restored);

        // Verify key material matches
        ECPrivateKey origPriv = (ECPrivateKey) original.getPrivate();
        ECPrivateKey restoredPriv = (ECPrivateKey) restored.getPrivate();
        assertEquals(origPriv.getS(), restoredPriv.getS());

        ECPublicKey origPub = (ECPublicKey) original.getPublic();
        ECPublicKey restoredPub = (ECPublicKey) restored.getPublic();
        assertEquals(origPub.getW().getAffineX(), restoredPub.getW().getAffineX());
        assertEquals(origPub.getW().getAffineY(), restoredPub.getW().getAffineY());
    }

    @Test
    void deserializeKeyPair_returnsNullForNullInput() {
        assertNull(WebPushSender.deserializeKeyPair(null, null));
        assertNull(WebPushSender.deserializeKeyPair("abc", null));
        assertNull(WebPushSender.deserializeKeyPair(null, "abc"));
    }

    @Test
    void deserializeKeyPair_returnsNullForEmptyInput() {
        assertNull(WebPushSender.deserializeKeyPair("", ""));
        assertNull(WebPushSender.deserializeKeyPair("abc", ""));
    }

    @Test
    void deserializeKeyPair_returnsNullForGarbageInput() {
        assertNull(WebPushSender.deserializeKeyPair("not-real-key", "also-not-real"));
    }

    // ── encodePublicKeyUncompressed / decodePublicKey round-trip ─────────────

    @Test
    void publicKeyEncodeDecodeRoundTrip() throws Exception {
        KeyPair kp = WebPushSender.generateVapidKeyPair();
        ECPublicKey original = (ECPublicKey) kp.getPublic();

        byte[] encoded = invokeEncodePublicKeyUncompressed(original);
        assertEquals(65, encoded.length);
        assertEquals(0x04, encoded[0]); // uncompressed point marker

        ECPublicKey decoded = invokeDecodePublicKey(encoded);
        assertEquals(original.getW().getAffineX(), decoded.getW().getAffineX());
        assertEquals(original.getW().getAffineY(), decoded.getW().getAffineY());
    }

    @Test
    void decodePublicKey_rejectsWrongLength() {
        assertThrows(IllegalArgumentException.class, () -> invokeDecodePublicKey(new byte[32]));
    }

    @Test
    void decodePublicKey_rejectsWrongMarker() {
        byte[] bad = new byte[65];
        bad[0] = 0x02; // compressed marker, not 0x04
        assertThrows(IllegalArgumentException.class, () -> invokeDecodePublicKey(bad));
    }

    // ── toUnsignedBytes ──────────────────────────────────────────────────────

    @Test
    void toUnsignedBytes_padsShortValue() throws Exception {
        byte[] result = invokeToUnsignedBytes(BigInteger.ONE, 32);
        assertEquals(32, result.length);
        assertEquals(1, result[31]);
        // All leading bytes should be zero
        for (int i = 0; i < 31; i++) {
            assertEquals(0, result[i]);
        }
    }

    @Test
    void toUnsignedBytes_trimsLongValue() throws Exception {
        // BigInteger with leading sign byte makes toByteArray() return 33 bytes
        byte[] bigBytes = new byte[33];
        bigBytes[0] = 0; // sign byte
        bigBytes[1] = (byte) 0xFF;
        Arrays.fill(bigBytes, 2, 33, (byte) 0xAA);
        BigInteger big = new BigInteger(1, bigBytes);

        byte[] result = invokeToUnsignedBytes(big, 32);
        assertEquals(32, result.length);
        assertEquals((byte) 0xFF, result[0]);
    }

    @Test
    void toUnsignedBytes_exactLengthPassesThrough() throws Exception {
        byte[] input = new byte[32];
        Arrays.fill(input, (byte) 0x42);
        BigInteger n = new BigInteger(1, input);
        byte[] result = invokeToUnsignedBytes(n, 32);
        assertEquals(32, result.length);
    }

    // ── derToRawEcdsa ────────────────────────────────────────────────────────

    @Test
    void derToRawEcdsa_convertsValidSignature() throws Exception {
        // Construct a minimal valid DER ECDSA signature:
        // 0x30 len 0x02 rLen r 0x02 sLen s
        byte[] r = new byte[32];
        Arrays.fill(r, (byte) 0x11);
        byte[] s = new byte[32];
        Arrays.fill(s, (byte) 0x22);

        byte[] der = buildDerSignature(r, s);
        byte[] raw = invokeDerToRawEcdsa(der, 32);

        assertEquals(64, raw.length);
        assertArrayEquals(r, Arrays.copyOfRange(raw, 0, 32));
        assertArrayEquals(s, Arrays.copyOfRange(raw, 32, 64));
    }

    @Test
    void derToRawEcdsa_handlesLeadingZeroByte() throws Exception {
        // When the high bit is set, DER adds a leading 0x00
        byte[] r = new byte[33];
        r[0] = 0x00;
        r[1] = (byte) 0x80;
        Arrays.fill(r, 2, 33, (byte) 0x11);

        byte[] s = new byte[32];
        Arrays.fill(s, (byte) 0x22);

        byte[] der = buildDerSignature(r, s);
        byte[] raw = invokeDerToRawEcdsa(der, 32);

        assertEquals(64, raw.length);
        assertEquals((byte) 0x80, raw[0]);
    }

    @Test
    void derToRawEcdsa_rejectsNonDerInput() {
        byte[] bad = {0x31, 0x04, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01}; // wrong tag
        assertThrows(Exception.class, () -> invokeDerToRawEcdsa(bad, 32));
    }

    // ── concat ───────────────────────────────────────────────────────────────

    @Test
    void concat_mergesMultipleArrays() throws Exception {
        byte[] a = {1, 2};
        byte[] b = {3, 4, 5};
        byte[] c = {6};
        byte[] result = invokeConcat(a, b, c);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, result);
    }

    @Test
    void concat_handlesEmptyArrays() throws Exception {
        byte[] result = invokeConcat(new byte[0], new byte[]{1}, new byte[0]);
        assertArrayEquals(new byte[]{1}, result);
    }

    // ── appendByte ───────────────────────────────────────────────────────────

    @Test
    void appendByte_addsToEnd() throws Exception {
        byte[] result = invokeAppendByte(new byte[]{1, 2, 3}, (byte) 4);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, result);
    }

    @Test
    void appendByte_worksOnEmptyArray() throws Exception {
        byte[] result = invokeAppendByte(new byte[0], (byte) 42);
        assertArrayEquals(new byte[]{42}, result);
    }

    // ── toBase64Url ──────────────────────────────────────────────────────────

    @Test
    void toBase64Url_convertsStandardToUrlSafe() throws Exception {
        assertEquals("a-b_c", invokeToBase64Url("a+b/c"));
    }

    @Test
    void toBase64Url_stripsPadding() throws Exception {
        assertEquals("abc", invokeToBase64Url("abc==="));
    }

    @Test
    void toBase64Url_leavesUrlSafeUnchanged() throws Exception {
        assertEquals("already-safe_chars", invokeToBase64Url("already-safe_chars"));
    }

    // ── hmacSha256 ───────────────────────────────────────────────────────────

    @Test
    void hmacSha256_producesConsistentOutput() throws Exception {
        byte[] key = "secret".getBytes();
        byte[] data = "message".getBytes();
        byte[] hash1 = invokeHmacSha256(key, data);
        byte[] hash2 = invokeHmacSha256(key, data);
        assertEquals(32, hash1.length); // SHA-256 produces 32 bytes
        assertArrayEquals(hash1, hash2); // deterministic
    }

    @Test
    void hmacSha256_differentKeysProduceDifferentResults() throws Exception {
        byte[] data = "message".getBytes();
        byte[] hash1 = invokeHmacSha256("key1".getBytes(), data);
        byte[] hash2 = invokeHmacSha256("key2".getBytes(), data);
        assertFalse(Arrays.equals(hash1, hash2));
    }

    private static void assertFalse(boolean condition) {
        org.junit.jupiter.api.Assertions.assertFalse(condition);
    }

    // ── Integration: VAPID JWT ───────────────────────────────────────────────

    @Test
    void serializeKeyPair_producesBase64UrlStrings() throws Exception {
        KeyPair kp = WebPushSender.generateVapidKeyPair();
        String[] serialized = WebPushSender.serializeKeyPair(kp);

        // base64url should not contain +, /, or = characters
        for (String part : serialized) {
            assertTrue(part.matches("[A-Za-z0-9_-]+"), "Should be base64url: " + part);
        }
    }

    // ── Reflection helpers ───────────────────────────────────────────────────

    private static byte[] invokeEncodePublicKeyUncompressed(ECPublicKey key) throws Exception {
        Method m = WebPushSender.class.getDeclaredMethod("encodePublicKeyUncompressed", ECPublicKey.class);
        m.setAccessible(true);
        return (byte[]) m.invoke(null, key);
    }

    private static ECPublicKey invokeDecodePublicKey(byte[] data) throws Exception {
        Method m = WebPushSender.class.getDeclaredMethod("decodePublicKey", byte[].class);
        m.setAccessible(true);
        try {
            return (ECPublicKey) m.invoke(null, (Object) data);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Exception ex) throw ex;
            throw e;
        }
    }

    private static byte[] invokeToUnsignedBytes(BigInteger n, int length) throws Exception {
        Method m = WebPushSender.class.getDeclaredMethod("toUnsignedBytes", BigInteger.class, int.class);
        m.setAccessible(true);
        return (byte[]) m.invoke(null, n, length);
    }

    private static byte[] invokeDerToRawEcdsa(byte[] der, int componentLen) throws Exception {
        Method m = WebPushSender.class.getDeclaredMethod("derToRawEcdsa", byte[].class, int.class);
        m.setAccessible(true);
        try {
            return (byte[]) m.invoke(null, der, componentLen);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Exception ex) throw ex;
            throw e;
        }
    }

    private static byte[] invokeConcat(byte[]... arrays) throws Exception {
        Method m = WebPushSender.class.getDeclaredMethod("concat", byte[][].class);
        m.setAccessible(true);
        return (byte[]) m.invoke(null, (Object) arrays);
    }

    private static byte[] invokeAppendByte(byte[] arr, byte b) throws Exception {
        Method m = WebPushSender.class.getDeclaredMethod("appendByte", byte[].class, byte.class);
        m.setAccessible(true);
        return (byte[]) m.invoke(null, arr, b);
    }

    private static String invokeToBase64Url(String s) throws Exception {
        Method m = WebPushSender.class.getDeclaredMethod("toBase64Url", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, s);
    }

    private static byte[] invokeHmacSha256(byte[] key, byte[] data) throws Exception {
        Method m = WebPushSender.class.getDeclaredMethod("hmacSha256", byte[].class, byte[].class);
        m.setAccessible(true);
        return (byte[]) m.invoke(null, key, data);
    }

    /**
     * Builds a minimal DER-encoded ECDSA signature from raw r and s components.
     */
    private static byte[] buildDerSignature(byte[] r, byte[] s) {
        // 0x30 totalLen 0x02 rLen r 0x02 sLen s
        int totalLen = 2 + r.length + 2 + s.length;
        byte[] der = new byte[2 + totalLen];
        int i = 0;
        der[i++] = 0x30;
        der[i++] = (byte) totalLen;
        der[i++] = 0x02;
        der[i++] = (byte) r.length;
        System.arraycopy(r, 0, der, i, r.length);
        i += r.length;
        der[i++] = 0x02;
        der[i++] = (byte) s.length;
        System.arraycopy(s, 0, der, i, s.length);
        return der;
    }
}
