package com.github.catatafishen.agentbridge.services.hooks;

import org.jetbrains.annotations.NotNull;

/**
 * Thrown by hook endpoint handlers when a request cannot be processed due to
 * a client error (malformed JSON, missing required field, etc.).
 *
 * <p>Caught by {@link AbstractHookHandler#handle} and converted to an HTTP error response.</p>
 */
class HookRequestException extends Exception {

    HookRequestException(@NotNull String message) {
        super(message);
    }

    HookRequestException(@NotNull String message, @NotNull Throwable cause) {
        super(message, cause);
    }
}
