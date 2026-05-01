package com.github.catatafishen.agentbridge.services;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implements RFC 8030 Web Push with RFC 8291 message encryption and RFC 8292 VAPID authentication.
 * All crypto uses Java's built-in providers — no external libraries needed.
 */
public final class WebPushSender {

    private static final Logger LOG = Logger.getInstance(WebPushSender.class);

    private static final int RECORD_SIZE = 4096;
    private static final String EC_CURVE = "secp256r1";
    private static final String HMAC_ALG = "HmacSHA256";
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

    /**
     * File path for persisting subscriptions across restarts.
     */
    private final Path subscriptionsFile;
    private final Gson gson = new Gson();

    public WebPushSender(@NotNull KeyPair vapidKeyPair, @Nullable Path subscriptionsFile) {
        this.vapidKeyPair = vapidKeyPair;
        this.vapidPublicKeyBytes = encodePublicKeyUncompressed((ECPublicKey) vapidKeyPair.getPublic());
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.subscriptionsFile = subscriptionsFile;
        loadSubscriptions();
    }

    // ── Subscription management ───────────────────────────────────────────────

    /**
     * Loads subscriptions from disk.
     */
    private void loadSubscriptions() {
        if (subscriptionsFile == null || !Files.exists(subscriptionsFile)) return;
        try {
            String json = Files.readString(subscriptionsFile);
            JsonArray array = com.google.gson.JsonParser.parseString(json).getAsJsonArray();
            for (var elem : array) {
                var obj = elem.getAsJsonObject();
                String endpoint = obj.get("endpoint").getAsString();
                String p256dh = obj.get("p256dh").getAsString();
                String auth = obj.get("auth").getAsString();
                subscriptions.put(endpoint, new PushSubscription(endpoint, p256dh, auth));
            }
            LOG.info("[WebPush] Loaded " + subscriptions.size() + " subscription(s) from disk");
        } catch (Exception e) {
            LOG.warn("[WebPush] Failed to load subscriptions: " + e.getMessage());
        }
    }

    /**
     * Saves subscriptions to disk.
     */
    private void saveSubscriptions() {
        if (subscriptionsFile == null) return;
        try {
            Files.createDirectories(subscriptionsFile.getParent());
            JsonArray array = new JsonArray();
            for (var sub : subscriptions.values()) {
                var obj = new com.google.gson.JsonObject();
                obj.addProperty("endpoint", sub.endpoint());
                obj.addProperty("p256dh", sub.p256dh());
                obj.addProperty("auth", sub.auth());
                array.add(obj);
            }
            Files.writeString(subscriptionsFile, gson.toJson(array), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            LOG.warn("[WebPush] Failed to save subscriptions: " + e.getMessage());
        }
    }

    /**
     * Registers or updates a push subscription.
     */
    public void addSubscription(@NotNull PushSubscription sub) {
        subscriptions.put(sub.endpoint(), sub);
        saveSubscriptions();
        LOG.info("[WebPush] Subscription registered: " + sub.endpoint());
    }

    /**
     * Removes a push subscription by endpoint.
     */
    public void removeSubscription(@NotNull String endpoint) {
        subscriptions.remove(endpoint);
        saveSubscriptions();
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
        if (subscriptions.isEmpty()) {
            LOG.debug("[WebPush] No subscriptions registered, skipping push");
            return;
        }
        LOG.debug("[WebPush] Sending to " + subscriptions.size() + " subscription(s)");
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
        kpg.initialize(new ECGenParameterSpec(EC_CURVE), new SecureRandom());
        KeyPair senderKeys = kpg.generateKeyPair();
        byte[] senderPublicKeyBytes = encodePublicKeyUncompressed((ECPublicKey) senderKeys.getPublic());
        ECPublicKey uaPublicKey = decodePublicKey(uaPublicKeyBytes);

        // ECDH shared secret
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(senderKeys.getPrivate());
        ka.doPhase(uaPublicKey, true);
        byte[] ecdhSecret = ka.generateSecret();

        // RFC 8291 key derivation: derive PRK_key from auth_secret and ecdh_secret via HMAC-SHA-256,
        // build key_info from the WebPush info label, uaPublicKey, and senderPublicKey,
        // then compute IKM via HKDF-Expand using key_info with an appended 0x01 byte.
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

        // Build aes128gcm content body: 16 bytes salt, 4 bytes record size, 1 byte key-id length, 65 bytes sender public key, then ciphertext
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
        Mac mac = Mac.getInstance(HMAC_ALG);
        mac.init(new SecretKeySpec(key, HMAC_ALG));
        return mac.doFinal(data);
    }

    private static byte[] hkdfExpand(byte[] prk, byte[] info, int length) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALG);
        mac.init(new SecretKeySpec(prk, HMAC_ALG));
        mac.update(info);
        byte[] t = mac.doFinal();
        return Arrays.copyOf(t, length);
    }

    private static byte[] encodePublicKeyUncompressed(ECPublicKey key) {
        return WebPushCryptoUtils.encodePublicKeyUncompressed(key);
    }

    private static ECPublicKey decodePublicKey(byte[] uncompressed) throws Exception {
        return WebPushCryptoUtils.decodePublicKey(uncompressed);
    }

    private static byte[] toUnsignedBytes(BigInteger n, int length) {
        return WebPushCryptoUtils.toUnsignedBytes(n, length);
    }

    /**
     * Converts DER-encoded ECDSA signature to raw IEEE P1363 format (r||s).
     */
    private static byte[] derToRawEcdsa(byte[] der, int componentLen) {
        return WebPushCryptoUtils.derToRawEcdsa(der, componentLen);
    }

    private static byte[] concat(byte[]... arrays) {
        return WebPushCryptoUtils.concat(arrays);
    }

    private static byte[] appendByte(byte[] arr, byte b) {
        return WebPushCryptoUtils.appendByte(arr, b);
    }

    /**
     * Normalises a base64 or base64url string (adds padding if needed, converts to url-safe chars).
     * Browser push subscriptions may use either variant.
     */
    private static String toBase64Url(String s) {
        return WebPushCryptoUtils.toBase64Url(s);
    }

    // ── VAPID key generation / serialisation ──────────────────────────────────

    /**
     * Generates a new P-256 VAPID key pair.
     */
    public static KeyPair generateVapidKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec(EC_CURVE), new SecureRandom());
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
            ap.init(new ECGenParameterSpec(EC_CURVE));
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
