package com.github.catatafishen.agentbridge.client.acp;

import com.github.catatafishen.agentbridge.model.SessionUpdate;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Parses OpenCode's own compaction marker.
 * <p>
 * To clients that do not advertise the standard ACP {@code session.compaction} capability, OpenCode
 * reports a compaction (including a manual {@code /compact}) as a {@code session_info_update}
 * carrying {@code _meta["opencode/compaction"] = {status, messageId, reason, error?}} instead of the
 * standard {@code compaction_update}; the summary is deliberately not sent as message text. Without
 * handling it a {@code /compact} turn looks empty (issue #1240). See OpenCode
 * {@code packages/cli/src/acp/compaction.ts}.
 */
final class OpenCodeCompactionMarker {

    static final String META_KEY = "opencode/compaction";

    private OpenCodeCompactionMarker() {
    }

    /**
     * @param update a normalized {@code session/update} payload
     * @return the compaction, or {@code null} when the update is not a {@code session_info_update}
     * carrying a recognisable marker (it is then parsed the standard way)
     */
    static @Nullable SessionUpdate.Compaction parse(@NotNull JsonObject update) {
        if (!"session_info_update".equals(string(update, "sessionUpdate"))) return null;
        JsonElement meta = update.get("_meta");
        if (meta == null || !meta.isJsonObject()) return null;
        JsonElement markerEl = meta.getAsJsonObject().get(META_KEY);
        if (markerEl == null || !markerEl.isJsonObject()) return null;
        JsonObject marker = markerEl.getAsJsonObject();

        SessionUpdate.CompactionStatus status = SessionUpdate.CompactionStatus.fromWire(string(marker, "status"));
        if (status == null) return null;
        boolean manual = "manual".equals(string(marker, "reason"));
        String error = status == SessionUpdate.CompactionStatus.FAILED ? errorMessage(marker.get("error")) : null;
        return new SessionUpdate.Compaction(status, manual, error);
    }

    private static @Nullable String errorMessage(@Nullable JsonElement error) {
        if (error == null) return null;
        if (error.isJsonObject()) return string(error.getAsJsonObject(), "message");
        return error.isJsonPrimitive() ? error.getAsString() : null;
    }

    private static @Nullable String string(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }
}
