#!/bin/sh
# _lib.sh — POSIX shell library for AgentBridge hook scripts.
# Source this at the top of every hook script:
#   . "${0%/*}/_lib.sh"
#
# Provides:
#   hook_read_payload       — reads and caches stdin JSON payload
#   hook_get <field>        — extract a top-level string from the payload
#   hook_get_arg <field>    — extract arguments.<field> (via HOOK_ARG_<field> env var)
#   hook_json_deny <reason> — emit deny decision JSON
#   hook_json_error <msg>   — emit pre-hook error JSON
#   hook_json_append <text> — emit append output JSON
#   hook_json_args <json>   — emit modified arguments JSON
#   hook_is_in_project <path>       — check if path is under AGENTBRIDGE_PROJECT_DIR
#   hook_is_in_source_root <path>   — check if path is under any AGENTBRIDGE_SOURCE_ROOTS
#   hook_escape_json <text>         — escape text for safe JSON embedding

# Cache stdin payload in a variable (can only read stdin once)
HOOK_PAYLOAD=""
hook_read_payload() {
    HOOK_PAYLOAD=$(cat)
}

# Extract a top-level string value from the JSON payload using sed.
# Limited to flat string values — does not handle nested objects.
hook_get() {
    printf '%s' "$HOOK_PAYLOAD" | sed -n 's/.*"'"$1"'"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1
}

# Extract an argument value. Prefers the HOOK_ARG_<field> env var injected
# by HookExecutor (fast, reliable). Falls back to sed extraction from payload.
hook_get_arg() {
    eval "val=\${HOOK_ARG_$1:-}"
    if [ -n "$val" ]; then
        printf '%s' "$val"
        return
    fi
    # Fallback: extract from argumentsJson (less reliable for complex values)
    printf '%s' "$HOOK_PAYLOAD" | sed -n 's/.*"arguments"[[:space:]]*:[[:space:]]*{[^}]*"'"$1"'"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1
}

# Escape a string for safe embedding in JSON values.
# Handles: backslash, double quote, newline, tab, carriage return.
hook_escape_json() {
    printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e ':a' -e 'N' -e '$!ba' -e 's/\n/\\n/g' -e 's/\t/\\t/g' -e 's/\r/\\r/g'
}

# Emit a deny decision (for PERMISSION hooks).
hook_json_deny() {
    _reason=$(hook_escape_json "$1")
    printf '{"decision":"deny","reason":"%s"}\n' "$_reason"
}

# Emit a pre-hook error (for PRE hooks — blocks tool execution).
hook_json_error() {
    _msg=$(hook_escape_json "$1")
    printf '{"error":"%s"}\n' "$_msg"
}

# Emit an append modifier (for SUCCESS/FAILURE hooks).
hook_json_append() {
    _text=$(hook_escape_json "$1")
    printf '{"append":"%s"}\n' "$_text"
}

# Emit modified arguments (for PRE hooks — merge semantics).
# Takes raw JSON object content, e.g.: hook_json_args '"author":"Bot <bot@example.com>"'
hook_json_args() {
    printf '{"arguments":{%s}}\n' "$1"
}

# Check if a path is inside the project directory.
hook_is_in_project() {
    case "$1" in
        "${AGENTBRIDGE_PROJECT_DIR}"|"${AGENTBRIDGE_PROJECT_DIR}/"*)
            return 0 ;;
        *)
            return 1 ;;
    esac
}

# Check if a path is under any source root listed in AGENTBRIDGE_SOURCE_ROOTS.
hook_is_in_source_root() {
    _path="$1"
    _IFS="$IFS"
    IFS='
'
    for _root in $AGENTBRIDGE_SOURCE_ROOTS; do
        case "$_path" in
            "${_root}"|"${_root}/"*)
                IFS="$_IFS"
                return 0 ;;
        esac
    done
    IFS="$_IFS"
    return 1
}

# Query the Hook API endpoint for dynamic IDE state.
# Usage: hook_query '{"action":"classify_path","path":"/some/file.java"}'
# Returns the JSON response on stdout. Requires curl and AGENTBRIDGE_MCP_PORT.
hook_query() {
    if [ -z "${AGENTBRIDGE_MCP_PORT:-}" ]; then
        printf '{"error":"AGENTBRIDGE_MCP_PORT not set"}\n'
        return 1
    fi
    curl -s -X POST "http://localhost:${AGENTBRIDGE_MCP_PORT}/hooks/query" \
        -H "Content-Type: application/json" \
        -d "$1" 2>/dev/null
}
