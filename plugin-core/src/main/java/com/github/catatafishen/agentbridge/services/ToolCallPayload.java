package com.github.catatafishen.agentbridge.services;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ToolCallPayload {
    static final int SUMMARY_MAX_CHARS = 8_000;
    static final long MAX_FULL_PAYLOAD_FIELD_BYTES = 512L * 1024;

    private final @NotNull String summary;
    private final boolean truncated;
    private final long byteLength;
    private final @NotNull String sha256;
    private final @Nullable String retainedFullValue;
    private final @Nullable UnavailableReason unavailableReason;

    private record PayloadDigest(long bytes, @NotNull String sha256) {
    }

    enum UnavailableReason {
        FIELD_LIMIT("field_limit"), MEMORY_BUDGET("memory_budget");

        private final String wireValue;

        UnavailableReason(String wireValue) {
            this.wireValue = wireValue;
        }

        String wireValue() {
            return wireValue;
        }
    }

    private ToolCallPayload(
        @NotNull String summary,
        boolean truncated,
        long byteLength,
        @NotNull String sha256,
        @Nullable String retainedFullValue,
        @Nullable UnavailableReason unavailableReason
    ) {
        this.summary = summary;
        this.truncated = truncated;
        this.byteLength = byteLength;
        this.sha256 = sha256;
        this.retainedFullValue = retainedFullValue;
        this.unavailableReason = unavailableReason;
    }

    static @NotNull ToolCallPayload capture(@NotNull String value) {
        PayloadDigest digest = digestUtf8(value);
        boolean truncated = value.length() > SUMMARY_MAX_CHARS;
        String summary = truncated
            ? value.substring(0, SUMMARY_MAX_CHARS) + "\n[…truncated]"
            : value;
        if (!truncated) {
            return new ToolCallPayload(summary, false, digest.bytes(), digest.sha256(), null, null);
        }
        if (digest.bytes() > MAX_FULL_PAYLOAD_FIELD_BYTES) {
            return new ToolCallPayload(summary, true, digest.bytes(), digest.sha256(), null,
                UnavailableReason.FIELD_LIMIT);
        }
        return new ToolCallPayload(summary, true, digest.bytes(), digest.sha256(), value, null);
    }

    @NotNull String summary() {
        return summary;
    }

    boolean truncated() {
        return truncated;
    }

    long byteLength() {
        return byteLength;
    }

    @NotNull String sha256() {
        return sha256;
    }

    @Nullable UnavailableReason unavailableReason() {
        return unavailableReason;
    }

    @Nullable String completeValueOrNull() {
        return truncated ? retainedFullValue : summary;
    }

    boolean available() {
        return completeValueOrNull() != null;
    }

    long retainedBytes() {
        return retainedFullValue == null ? 0 : byteLength;
    }

    @NotNull ToolCallPayload evictForMemoryBudget() {
        return retainedFullValue == null ? this
            : new ToolCallPayload(summary, true, byteLength, sha256, null, UnavailableReason.MEMORY_BUDGET);
    }

    @Override
    public @NotNull String toString() {
        return "ToolCallPayload[truncated=" + truncated
            + ", byteLength=" + byteLength
            + ", sha256=" + sha256
            + ", unavailableReason=" + unavailableReason
            + ']';
    }

    private static @NotNull PayloadDigest digestUtf8(@NotNull String value) {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
        ByteBuffer buffer = ByteBuffer.allocate(8 * 1024);
        CharBuffer input = CharBuffer.wrap(value);
        MessageDigest digest = sha256Digest();
        long bytes = 0;

        try {
            while (true) {
                CoderResult result = encoder.encode(input, buffer, true);
                bytes += digestBuffer(buffer, digest);
                if (result.isUnderflow()) {
                    break;
                }
                if (!result.isOverflow()) {
                    result.throwException();
                }
            }
            while (true) {
                CoderResult result = encoder.flush(buffer);
                bytes += digestBuffer(buffer, digest);
                if (result.isUnderflow()) {
                    break;
                }
                if (!result.isOverflow()) {
                    result.throwException();
                }
            }
        } catch (CharacterCodingException exception) {
            throw new IllegalStateException("Unable to encode tool-call payload as UTF-8", exception);
        }

        return new PayloadDigest(bytes, HexFormat.of().formatHex(digest.digest()));
    }

    private static long digestBuffer(@NotNull ByteBuffer buffer, @NotNull MessageDigest digest) {
        buffer.flip();
        int bytes = buffer.remaining();
        digest.update(buffer);
        buffer.clear();
        return bytes;
    }

    private static @NotNull MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
