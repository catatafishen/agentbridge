package com.github.catatafishen.agentbridge.services;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("WebPushSender")
class WebPushSenderTest {

    // ── VAPID key generation ──────────────────────────────────────────────────

    @Nested
    @DisplayName("generateVapidKeyPair")
    class GenerateVapidKeyPair {

        @Test
        @DisplayName("returns a non-null EC key pair")
        void returnsNonNullEcKeyPair() throws Exception {
            KeyPair keyPair = WebPushSender.generateVapidKeyPair();

            assertNotNull(keyPair);
            assertNotNull(keyPair.getPublic());
            assertNotNull(keyPair.getPrivate());
        }

        @Test
        @DisplayName("public key is an EC key on the P-256 curve")
        void publicKeyIsEcP256() throws Exception {
            KeyPair keyPair = WebPushSender.generateVapidKeyPair();

            assertInstanceOf(ECPublicKey.class, keyPair.getPublic());
            assertEquals("EC", keyPair.getPublic().getAlgorithm());
        }

        @Test
        @DisplayName("private key is an EC private key")
        void privateKeyIsEc() throws Exception {
            KeyPair keyPair = WebPushSender.generateVapidKeyPair();

            assertInstanceOf(ECPrivateKey.class, keyPair.getPrivate());
            assertEquals("EC", keyPair.getPrivate().getAlgorithm());
        }

        @Test
        @DisplayName("each call generates a distinct key pair")
        void eachCallGeneratesDistinctKeyPair() throws Exception {
            KeyPair kp1 = WebPushSender.generateVapidKeyPair();
            KeyPair kp2 = WebPushSender.generateVapidKeyPair();

            assertFalse(
                java.util.Arrays.equals(kp1.getPublic().getEncoded(), kp2.getPublic().getEncoded()),
                "Two generated key pairs should have different public keys"
            );
        }
    }

    // ── serializeKeyPair ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("serializeKeyPair")
    class SerializeKeyPair {

        @Test
        @DisplayName("returns array of two non-empty base64url strings")
        void returnsTwoNonEmptyStrings() throws Exception {
            KeyPair kp = WebPushSender.generateVapidKeyPair();
            String[] serialized = WebPushSender.serializeKeyPair(kp);

            assertNotNull(serialized);
            assertEquals(2, serialized.length);
            assertNotNull(serialized[0], "Private key string should not be null");
            assertNotNull(serialized[1], "Public key string should not be null");
            assertFalse(serialized[0].isEmpty(), "Private key string should not be empty");
            assertFalse(serialized[1].isEmpty(), "Public key string should not be empty");
        }

        @Test
        @DisplayName("public key serializes to 87 base64url chars (65 uncompressed bytes)")
        void publicKeyIs87Chars() throws Exception {
            KeyPair kp = WebPushSender.generateVapidKeyPair();
            String[] serialized = WebPushSender.serializeKeyPair(kp);

            // 65 uncompressed EC point bytes → 87 base64url chars (no padding)
            assertEquals(87, serialized[1].length(),
                "Public key base64url should be 87 chars for 65 uncompressed bytes");
        }

        @Test
        @DisplayName("private key serializes to 43 base64url chars (32 scalar bytes)")
        void privateKeyIs43Chars() throws Exception {
            KeyPair kp = WebPushSender.generateVapidKeyPair();
            String[] serialized = WebPushSender.serializeKeyPair(kp);

            // 32 bytes encoded with base64url no-padding = 43 chars
            assertEquals(43, serialized[0].length(),
                "Private key base64url should be 43 chars for 32 scalar bytes");
        }

        @Test
        @DisplayName("output uses base64url alphabet (no +, /, or = padding)")
        void usesBase64UrlAlphabet() throws Exception {
            KeyPair kp = WebPushSender.generateVapidKeyPair();
            String[] serialized = WebPushSender.serializeKeyPair(kp);

            for (String s : serialized) {
                assertFalse(s.contains("+"), "Should use base64url, not base64: " + s);
                assertFalse(s.contains("/"), "Should use base64url, not base64: " + s);
                assertFalse(s.contains("="), "Should have no padding: " + s);
            }
        }

        @Test
        @DisplayName("public key starts with 0x04 (uncompressed point indicator)")
        void publicKeyStartsWithUncompressedMarker() throws Exception {
            KeyPair kp = WebPushSender.generateVapidKeyPair();
            String[] serialized = WebPushSender.serializeKeyPair(kp);

            byte[] pubBytes = Base64.getUrlDecoder().decode(serialized[1]);
            assertEquals(65, pubBytes.length, "Uncompressed public key should be 65 bytes");
            assertEquals(0x04, pubBytes[0] & 0xFF, "Uncompressed key should start with 0x04");
        }
    }

    // ── deserializeKeyPair ────────────────────────────────────────────────────

    @Nested
    @DisplayName("deserializeKeyPair")
    class DeserializeKeyPair {

        @Test
        @DisplayName("null private key returns null")
        void nullPrivKey_returnsNull() throws Exception {
            KeyPair kp = WebPushSender.generateVapidKeyPair();
            String[] s = WebPushSender.serializeKeyPair(kp);

            assertNull(WebPushSender.deserializeKeyPair(null, s[1]));
        }

        @Test
        @DisplayName("null public key returns null")
        void nullPubKey_returnsNull() throws Exception {
            KeyPair kp = WebPushSender.generateVapidKeyPair();
            String[] s = WebPushSender.serializeKeyPair(kp);

            assertNull(WebPushSender.deserializeKeyPair(s[0], null));
        }

        @Test
        @DisplayName("empty private key returns null")
        void emptyPrivKey_returnsNull() throws Exception {
            KeyPair kp = WebPushSender.generateVapidKeyPair();
            String[] s = WebPushSender.serializeKeyPair(kp);

            assertNull(WebPushSender.deserializeKeyPair("", s[1]));
        }

        @Test
        @DisplayName("empty public key returns null")
        void emptyPubKey_returnsNull() throws Exception {
            KeyPair kp = WebPushSender.generateVapidKeyPair();
            String[] s = WebPushSender.serializeKeyPair(kp);

            assertNull(WebPushSender.deserializeKeyPair(s[0], ""));
        }

        @Test
        @DisplayName("both null returns null")
        void bothNull_returnsNull() {
            assertNull(WebPushSender.deserializeKeyPair(null, null));
        }

        @Test
        @DisplayName("invalid base64 returns null (no exception)")
        void invalidBase64_returnsNull() {
            assertNull(WebPushSender.deserializeKeyPair("not-valid!!!", "also-not-valid!!!"));
        }

        @Test
        @DisplayName("round-trip: deserialised public key bytes match original")
        void roundTrip_publicKeyBytesMatch() throws Exception {
            KeyPair original = WebPushSender.generateVapidKeyPair();
            String[] serialized = WebPushSender.serializeKeyPair(original);

            KeyPair restored = WebPushSender.deserializeKeyPair(serialized[0], serialized[1]);

            assertNotNull(restored, "Restored key pair should not be null");
            // Compare via re-serialization to avoid DER encoding differences
            String[] reserialized = WebPushSender.serializeKeyPair(restored);
            assertEquals(serialized[1], reserialized[1], "Public key bytes should be identical after round-trip");
        }

        @Test
        @DisplayName("round-trip: private key scalar matches original")
        void roundTrip_privateKeyScalarMatches() throws Exception {
            KeyPair original = WebPushSender.generateVapidKeyPair();
            String[] serialized = WebPushSender.serializeKeyPair(original);

            KeyPair restored = WebPushSender.deserializeKeyPair(serialized[0], serialized[1]);

            assertNotNull(restored);
            ECPrivateKey origPriv = (ECPrivateKey) original.getPrivate();
            ECPrivateKey restPriv = (ECPrivateKey) restored.getPrivate();
            assertEquals(origPriv.getS(), restPriv.getS(), "Private key scalar S should be equal");
        }
    }

    // ── getVapidPublicKeyBase64 ───────────────────────────────────────────────

    @Nested
    @DisplayName("getVapidPublicKeyBase64")
    class GetVapidPublicKeyBase64 {

        @Test
        @DisplayName("returns 87 characters (65 uncompressed bytes, base64url no-padding)")
        void returns87Chars() throws Exception {
            KeyPair kp = WebPushSender.generateVapidKeyPair();
            WebPushSender sender = new WebPushSender(kp, null);

            assertEquals(87, sender.getVapidPublicKeyBase64().length());
        }

        @Test
        @DisplayName("matches the serialized public key from serializeKeyPair")
        void matchesSerializedPublicKey() throws Exception {
            KeyPair kp = WebPushSender.generateVapidKeyPair();
            WebPushSender sender = new WebPushSender(kp, null);
            String[] serialized = WebPushSender.serializeKeyPair(kp);

            assertEquals(serialized[1], sender.getVapidPublicKeyBase64());
        }

        @Test
        @DisplayName("decodes to 65-byte uncompressed EC point starting with 0x04")
        void decodesTo65ByteUncompressedPoint() throws Exception {
            KeyPair kp = WebPushSender.generateVapidKeyPair();
            WebPushSender sender = new WebPushSender(kp, null);

            byte[] bytes = Base64.getUrlDecoder().decode(sender.getVapidPublicKeyBase64());
            assertEquals(65, bytes.length);
            assertEquals(0x04, bytes[0] & 0xFF, "Uncompressed point should start with 0x04");
        }
    }

    // ── subscription lifecycle ────────────────────────────────────────────────

    @Nested
    @DisplayName("subscription lifecycle")
    class SubscriptionLifecycle {

        @Test
        @DisplayName("new sender has no subscriptions")
        void newSender_hasNoSubscriptions() throws Exception {
            KeyPair kp = WebPushSender.generateVapidKeyPair();
            WebPushSender sender = new WebPushSender(kp, null);

            assertFalse(sender.hasSubscriptions());
        }

        @Test
        @DisplayName("addSubscription makes hasSubscriptions return true")
        void addSubscription_hasSubscriptionsTrue() throws Exception {
            KeyPair kp = WebPushSender.generateVapidKeyPair();
            WebPushSender sender = new WebPushSender(kp, null);

            sender.addSubscription(new WebPushSender.PushSubscription(
                "https://push.example.com/sub/1", "p256dhKey", "authKey"));

            assertTrue(sender.hasSubscriptions());
        }

        @Test
        @DisplayName("removeSubscription for only subscription makes hasSubscriptions return false")
        void removeLastSubscription_hasSubscriptionsFalse() throws Exception {
            KeyPair kp = WebPushSender.generateVapidKeyPair();
            WebPushSender sender = new WebPushSender(kp, null);

            sender.addSubscription(new WebPushSender.PushSubscription(
                "https://push.example.com/sub/1", "p256dhKey", "authKey"));
            sender.removeSubscription("https://push.example.com/sub/1");

            assertFalse(sender.hasSubscriptions());
        }

        @Test
        @DisplayName("removeSubscription for unknown endpoint is a no-op")
        void removeUnknownEndpoint_isNoOp() throws Exception {
            KeyPair kp = WebPushSender.generateVapidKeyPair();
            WebPushSender sender = new WebPushSender(kp, null);

            sender.addSubscription(new WebPushSender.PushSubscription(
                "https://push.example.com/sub/1", "p256dhKey", "authKey"));
            sender.removeSubscription("https://unknown.example.com/sub/99");

            assertTrue(sender.hasSubscriptions(), "Original subscription should still exist");
        }

        @Test
        @DisplayName("multiple subscriptions: removing one still leaves others")
        void multipleSubscriptions_removeOne_othersRemain() throws Exception {
            KeyPair kp = WebPushSender.generateVapidKeyPair();
            WebPushSender sender = new WebPushSender(kp, null);

            sender.addSubscription(new WebPushSender.PushSubscription("https://a.example.com/push", "p1", "a1"));
            sender.addSubscription(new WebPushSender.PushSubscription("https://b.example.com/push", "p2", "a2"));
            sender.addSubscription(new WebPushSender.PushSubscription("https://c.example.com/push", "p3", "a3"));

            sender.removeSubscription("https://b.example.com/push");

            assertTrue(sender.hasSubscriptions(), "Two remaining subscriptions should still be there");
        }

        @Test
        @DisplayName("multiple subscriptions: removing all leaves none")
        void multipleSubscriptions_removeAll_noneLeft() throws Exception {
            KeyPair kp = WebPushSender.generateVapidKeyPair();
            WebPushSender sender = new WebPushSender(kp, null);

            sender.addSubscription(new WebPushSender.PushSubscription("https://a.example.com/push", "p1", "a1"));
            sender.addSubscription(new WebPushSender.PushSubscription("https://b.example.com/push", "p2", "a2"));

            sender.removeSubscription("https://a.example.com/push");
            sender.removeSubscription("https://b.example.com/push");

            assertFalse(sender.hasSubscriptions());
        }

        @Test
        @DisplayName("addSubscription with same endpoint updates existing entry")
        void addSubscription_sameEndpoint_updatesEntry() throws Exception {
            KeyPair kp = WebPushSender.generateVapidKeyPair();
            WebPushSender sender = new WebPushSender(kp, null);

            sender.addSubscription(new WebPushSender.PushSubscription("https://push.example.com/sub", "old-p256dh", "old-auth"));
            sender.addSubscription(new WebPushSender.PushSubscription("https://push.example.com/sub", "new-p256dh", "new-auth"));

            // Still just one subscription (map keyed by endpoint)
            assertTrue(sender.hasSubscriptions());
        }
    }

    // ── loadSubscriptions from file ───────────────────────────────────────────

    @Nested
    @DisplayName("loadSubscriptions from file")
    class LoadSubscriptionsFromFile {

        @Test
        @DisplayName("loads subscriptions from JSON file on construction")
        void loadsSubscriptionsFromFile(@TempDir Path tempDir) throws Exception {
            Path subFile = tempDir.resolve("subscriptions.json");
            JsonArray array = new JsonArray();
            JsonObject sub = new JsonObject();
            sub.addProperty("endpoint", "https://push.example.com/sub/loaded");
            sub.addProperty("p256dh", "dGVzdA");
            sub.addProperty("auth", "YXV0aA");
            array.add(sub);
            Files.writeString(subFile, new Gson().toJson(array), StandardCharsets.UTF_8);

            KeyPair kp = WebPushSender.generateVapidKeyPair();
            WebPushSender sender = new WebPushSender(kp, subFile);

            assertTrue(sender.hasSubscriptions(), "Subscriptions should be loaded from file");
        }

        @Test
        @DisplayName("null file path skips loading and sender has no subscriptions")
        void nullFilePath_noSubscriptions() throws Exception {
            KeyPair kp = WebPushSender.generateVapidKeyPair();
            WebPushSender sender = new WebPushSender(kp, null);

            assertFalse(sender.hasSubscriptions());
        }

        @Test
        @DisplayName("non-existent file path skips loading gracefully")
        void nonExistentFile_noSubscriptions(@TempDir Path tempDir) throws Exception {
            Path missing = tempDir.resolve("does-not-exist.json");
            KeyPair kp = WebPushSender.generateVapidKeyPair();
            WebPushSender sender = new WebPushSender(kp, missing);

            assertFalse(sender.hasSubscriptions());
        }

        @Test
        @DisplayName("loads multiple subscriptions from file")
        void loadsMultipleSubscriptionsFromFile(@TempDir Path tempDir) throws Exception {
            Path subFile = tempDir.resolve("subscriptions.json");
            JsonArray array = new JsonArray();
            for (int i = 1; i <= 3; i++) {
                JsonObject sub = new JsonObject();
                sub.addProperty("endpoint", "https://push.example.com/sub/" + i);
                sub.addProperty("p256dh", "key" + i);
                sub.addProperty("auth", "auth" + i);
                array.add(sub);
            }
            Files.writeString(subFile, new Gson().toJson(array), StandardCharsets.UTF_8);

            KeyPair kp = WebPushSender.generateVapidKeyPair();
            WebPushSender sender = new WebPushSender(kp, subFile);

            assertTrue(sender.hasSubscriptions());
        }

        @Test
        @DisplayName("addSubscription persists to file; new instance reloads it")
        void addSubscription_persistsToFile(@TempDir Path tempDir) throws Exception {
            Path subFile = tempDir.resolve("subscriptions.json");
            KeyPair kp = WebPushSender.generateVapidKeyPair();

            WebPushSender sender1 = new WebPushSender(kp, subFile);
            sender1.addSubscription(new WebPushSender.PushSubscription(
                "https://push.example.com/persisted", "p256", "auth"));

            // A second instance loading the same file should see the subscription
            WebPushSender sender2 = new WebPushSender(kp, subFile);
            assertTrue(sender2.hasSubscriptions(), "Subscription should have been persisted and reloaded");
        }

        @Test
        @DisplayName("removeSubscription persists to file; new instance has no subscriptions")
        void removeSubscription_persistsToFile(@TempDir Path tempDir) throws Exception {
            Path subFile = tempDir.resolve("subscriptions.json");
            KeyPair kp = WebPushSender.generateVapidKeyPair();

            WebPushSender sender1 = new WebPushSender(kp, subFile);
            sender1.addSubscription(new WebPushSender.PushSubscription(
                "https://push.example.com/removable", "p256", "auth"));
            sender1.removeSubscription("https://push.example.com/removable");

            WebPushSender sender2 = new WebPushSender(kp, subFile);
            assertFalse(sender2.hasSubscriptions(), "Removed subscription should not be reloaded");
        }
    }

    // ── PushSubscription record ───────────────────────────────────────────────

    @Nested
    @DisplayName("PushSubscription record")
    class PushSubscriptionRecord {

        @Test
        @DisplayName("record accessors return the values passed to the constructor")
        void recordAccessors() {
            WebPushSender.PushSubscription sub = new WebPushSender.PushSubscription(
                "https://endpoint.example.com", "myP256dh", "myAuth");

            assertEquals("https://endpoint.example.com", sub.endpoint());
            assertEquals("myP256dh", sub.p256dh());
            assertEquals("myAuth", sub.auth());
        }
    }
}
