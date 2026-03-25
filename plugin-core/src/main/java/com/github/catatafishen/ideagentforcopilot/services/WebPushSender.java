package com.github.catatafishen.ideagentforcopilot.services;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Implements RFC 8030 Web Push with RFC 8291 message encryption and RFC 8292 VAPID authentication.
 * All crypto uses Java's built-in providers — no external libraries needed.
 */
public final class WebPushSender {

    private static final Logger LOG = Logger.getInstance(WebPushSender.class);

    private static final int RECORD_SIZE = 4096;
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();

    /**
     * In-memory push subscriptions, keyed by endpoint URL.
     */
    private final Map<String, PushSubscription> subscriptions = new ConcurrentHashMap<>();

    /**
     * VAPID key pair — loaded from settings or generated on first use.
     */
    private final KeyPair vapidKeyPair;

    /**
     * Uncompressed public key bytes for the VAPID public key (65 bytes: 0x04 || x || y).
     */
    private final byte[] vapidPublicKeyBytes;

    private final HttpClient http;

    public WebPushSender(@NotNull KeyPair vapidKeyPair) {
        this.vapidKeyPair = vapidKeyPair;
        this.vapidPublicKeyBytes = encodePublicKeyUncompressed((ECPublicKey) vapidKeyPair.getPublic());
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    // ── Subscription management ───────────────────────────────────────────────

    /**
     * Registers or updates a push subscription.
     */
    public void addSubscription(@NotNull PushSubscription sub) {
        subscriptions.put(sub.endpoint(), sub);
        LOG.info("[WebPush] Subscription registered: " + sub.endpoint());
    }

    /**
     * Removes a push subscription by endpoint.
     */
    public void removeSubscription(@NotNull String endpoint) {
        subscriptions.remove(endpoint);
    }

    public boolean hasSubscriptions() {
        return !subscriptions.isEmpty();
    }

    // ── VAPID public key ──────────────────────────────────────────────────────

    /**
     * Returns the VAPID public key as a base64url-encoded uncompressed P-256 point.
     */
    public String getVapidPublicKeyBase64() {
        return B64URL.encodeToString(vapidPublicKeyBytes);
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    /**
     * Sends a push message to all registered subscriptions asynchronously.
     *
     * @param payload small plaintext payload (e.g. event ID JSON)
     */
    public void sendToAll(@NotNull String payload) {
        if (subscriptions.isEmpty()) return;
        byte[] plaintext = payload.getBytes(StandardCharsets.UTF_8);
        for (PushSubscription sub : subscriptions.values()) {
            CompletableFuture.runAsync(() -> {
                try {
                    sendOne(sub, plaintext);
                } catch (Exception e) {
                    LOG.warn("[WebPush] Failed to send to " + sub.endpoint() + ": " + e.getMessage());
                    if (e.getMessage() != null && e.getMessage().contains("410")) {
                        // Gone — subscription expired
                        subscriptions.remove(sub.endpoint());
                        LOG.info("[WebPush] Removed expired subscription: " + sub.endpoint());
                    }
                }
            });
        }
    }

    // ── Core send logic ───────────────────────────────────────────────────────

    private void sendOne(@NotNull PushSubscription sub, byte[] plaintext) throws Exception {
        // Decode browser keys
        byte[] uaPublicKeyBytes = B64URL_DEC.decode(toBase64Url(sub.p256dh()));
        byte[] authSecret = B64URL_DEC.decode(toBase64Url(sub.auth()));

        // Generate ephemeral sender key pair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        KeyPair senderKeys = kpg.generateKeyPair();
        byte[] senderPublicKeyBytes = encodePublicKeyUncompressed((ECPublicKey) senderKeys.getPublic());
        ECPublicKey uaPublicKey = decodePublicKey(uaPublicKeyBytes);

        // ECDH shared secret
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(senderKeys.getPrivate());
        ka.doPhase(uaPublicKey, true);
        byte[] ecdhSecret = ka.generateSecret();

        // RFC 8291 key derivation
        //   PRK_key = HMAC-SHA-256(auth_secret, ecdh_secret)
        //   key_info = "WebPush: info\0" || uaPublicKey || senderPublicKey
        //   IKM = HKDF-Expand(PRK_key, key_info || 0x01, 32)
        byte[] prkKey = hmacSha256(authSecret, ecdhSecret);
        byte[] keyInfo = concat(
            "WebPush: info\0".getBytes(StandardCharsets.US_ASCII),
            uaPublicKeyBytes,
            senderPublicKeyBytes
        );
        byte[] ikm = hkdfExpand(prkKey, appendByte(keyInfo, (byte) 0x01), 32);

        // Salt + derive CEK and nonce
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] prk = hmacSha256(salt, ikm);
        byte[] cek = hkdfExpand(prk, appendByte("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), (byte) 0x01), 16);
        byte[] nonce = hkdfExpand(prk, appendByte("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), (byte) 0x01), 12);

        // Encrypt: plaintext + 0x02 (padding delimiter, no padding)
        byte[] padded = appendByte(plaintext, (byte) 0x02);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(padded);

        // Build aes128gcm content body: salt(16) || rs(4) || keyid_len(1) || sender_public_key(65) || ciphertext
        ByteBuffer body = ByteBuffer.allocate(16 + 4 + 1 + 65 + ciphertext.length);
        body.put(salt);
        body.putInt(RECORD_SIZE);
        body.put((byte) 65);
        body.put(senderPublicKeyBytes);
        body.put(ciphertext);
        byte[] bodyBytes = body.array();

        // VAPID JWT
        String vapidJwt = buildVapidJwt(sub.endpoint());
        String authHeader = "vapid t=" + vapidJwt + ",k=" + B64URL.encodeToString(vapidPublicKeyBytes);

        // HTTP POST to push endpoint
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(sub.endpoint()))
            .header("Content-Type", "application/octet-stream")
            .header("Content-Encoding", "aes128gcm")
            .header("Authorization", authHeader)
            .header("TTL", "86400")
            .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
            .timeout(Duration.ofSeconds(15))
            .build();
        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            LOG.debug("[WebPush] Delivered to " + sub.endpoint() + " (" + status + ")");
        } else {
            throw new RuntimeException("Push endpoint returned " + status + ": " + response.body());
        }
    }

    // ── VAPID JWT (RFC 8292) ──────────────────────────────────────────────────

    private String buildVapidJwt(@NotNull String endpoint) throws Exception {
        // audience = origin of the endpoint
        URI uri = URI.create(endpoint);
        String audience = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");

        long exp = Instant.now().getEpochSecond() + 3600;

        String header = B64URL.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = B64URL.encodeToString(
            ("{\"aud\":\"" + audience + "\",\"exp\":" + exp + ",\"sub\":\"mailto:agentbridge@localhost\"}").getBytes(StandardCharsets.UTF_8)
        );
        String sigInput = header + "." + payload;

        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(vapidKeyPair.getPrivate());
        sig.update(sigInput.getBytes(StandardCharsets.US_ASCII));
        byte[] derSig = sig.sign();

        // Java returns DER-encoded ECDSA signature; JWT requires IEEE P1363 (raw r||s, 32+32 bytes)
        byte[] rawSig = derToRawEcdsa(derSig, 32);
        return sigInput + "." + B64URL.encodeToString(rawSig);
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    private static byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] hkdfExpand(byte[] prk, byte[] info, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        byte[] t = mac.doFinal();
        return Arrays.copyOf(t, length);
    }

    private static byte[] encodePublicKeyUncompressed(ECPublicKey key) {
        ECPoint point = key.getW();
        byte[] x = toUnsignedBytes(point.getAffineX(), 32);
        byte[] y = toUnsignedBytes(point.getAffineY(), 32);
        byte[] result = new byte[65];
        result[0] = 0x04;
        System.arraycopy(x, 0, result, 1, 32);
        System.arraycopy(y, 0, result, 33, 32);
        return result;
    }

    private static ECPublicKey decodePublicKey(byte[] uncompressed) throws Exception {
        // Expect 65 bytes: 0x04 || x(32) || y(32)
        if (uncompressed.length != 65 || uncompressed[0] != 0x04) {
            throw new IllegalArgumentException("Expected 65-byte uncompressed P-256 key");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
        KeyFactory kf = KeyFactory.getInstance("EC");
        AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
        ap.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec spec = ap.getParameterSpec(ECParameterSpec.class);
        return (ECPublicKey) kf.generatePublic(new ECPublicKeySpec(new ECPoint(x, y), spec));
    }

    private static byte[] toUnsignedBytes(BigInteger n, int length) {
        byte[] bytes = n.toByteArray();
        if (bytes.length == length) return bytes;
        if (bytes.length > length) return Arrays.copyOfRange(bytes, bytes.length - length, bytes.length);
        byte[] padded = new byte[length];
        System.arraycopy(bytes, 0, padded, length - bytes.length, bytes.length);
        return padded;
    }

    /**
     * Converts DER-encoded ECDSA signature to raw IEEE P1363 format (r||s).
     */
    private static byte[] derToRawEcdsa(byte[] der, int componentLen) {
        // DER: 0x30 len 0x02 rLen r 0x02 sLen s
        int i = 2; // skip 0x30 and sequence length
        if (der[0] != 0x30) throw new IllegalArgumentException("Not a DER ECDSA signature");
        // skip r
        if (der[i++] != 0x02) throw new IllegalArgumentException("Missing r INTEGER tag");
        int rLen = der[i++] & 0xFF;
        byte[] r = Arrays.copyOfRange(der, i, i + rLen);
        i += rLen;
        // skip s
        if (der[i++] != 0x02) throw new IllegalArgumentException("Missing s INTEGER tag");
        int sLen = der[i++] & 0xFF;
        byte[] s = Arrays.copyOfRange(der, i, i + sLen);

        byte[] raw = new byte[componentLen * 2];
        // r may be 33 bytes (leading 0x00 for sign), pad/trim to componentLen
        copyUnsignedBig(r, raw, 0, componentLen);
        copyUnsignedBig(s, raw, componentLen, componentLen);
        return raw;
    }

    private static void copyUnsignedBig(byte[] src, byte[] dst, int dstOffset, int len) {
        // Strip leading zero byte (sign byte for positive BigInteger DER encoding)
        int srcStart = (src.length > len && src[0] == 0) ? 1 : 0;
        int srcLen = src.length - srcStart;
        int destStart = dstOffset + (len - srcLen);
        System.arraycopy(src, srcStart, dst, destStart, srcLen);
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }

    private static byte[] appendByte(byte[] arr, byte b) {
        byte[] result = Arrays.copyOf(arr, arr.length + 1);
        result[arr.length] = b;
        return result;
    }

    /**
     * Normalises a base64 or base64url string (adds padding if needed, converts to url-safe chars).
     * Browser push subscriptions may use either variant.
     */
    private static String toBase64Url(String s) {
        return s.replace('+', '-').replace('/', '_').replace("=", "");
    }

    // ── VAPID key generation / serialisation ──────────────────────────────────

    /**
     * Generates a new P-256 VAPID key pair.
     */
    public static KeyPair generateVapidKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        return kpg.generateKeyPair();
    }

    /**
     * Serialises a VAPID key pair to two base64url strings [privateKey, publicKey].
     */
    public static String[] serializeKeyPair(KeyPair kp) {
        ECPrivateKey priv = (ECPrivateKey) kp.getPrivate();
        byte[] privBytes = toUnsignedBytes(priv.getS(), 32);
        byte[] pubBytes = encodePublicKeyUncompressed((ECPublicKey) kp.getPublic());
        return new String[]{B64URL.encodeToString(privBytes), B64URL.encodeToString(pubBytes)};
    }

    /**
     * Restores a VAPID key pair from the two base64url strings returned by {@link #serializeKeyPair}.
     */
    @Nullable
    public static KeyPair deserializeKeyPair(@Nullable String privB64, @Nullable String pubB64) {
        if (privB64 == null || privB64.isEmpty() || pubB64 == null || pubB64.isEmpty()) return null;
        try {
            byte[] pubBytes = B64URL_DEC.decode(pubB64);
            ECPublicKey publicKey = decodePublicKey(pubBytes);

            byte[] privBytes = B64URL_DEC.decode(privB64);
            BigInteger s = new BigInteger(1, privBytes);
            KeyFactory kf = KeyFactory.getInstance("EC");
            AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
            ap.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec spec = ap.getParameterSpec(ECParameterSpec.class);
            ECPrivateKey privateKey = (ECPrivateKey) kf.generatePrivate(new ECPrivateKeySpec(s, spec));
            return new KeyPair(publicKey, privateKey);
        } catch (Exception e) {
            LOG.warn("[WebPush] Failed to deserialize VAPID key pair: " + e.getMessage());
            return null;
        }
    }

    // ── Subscription record ───────────────────────────────────────────────────

    public record PushSubscription(String endpoint, String p256dh, String auth) {
    }
}
