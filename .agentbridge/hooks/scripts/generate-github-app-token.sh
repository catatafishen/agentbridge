#!/bin/sh
# =============================================================================
# INTERNAL DEVELOPMENT HOOK — for AgentBridge plugin contributors only.
#
# This script is committed to the plugin repository as part of the project's
# OWN hook configuration (.agentbridge/hooks/), which governs how agents behave
# when working on the plugin's own codebase. It is NOT distributed to end users.
# End users receive a different set of hooks from plugin-core/src/main/resources/
# default-hooks/ (see DefaultHookProvisioner). This file is intentionally absent
# from that manifest.
#
# Purpose: helper used by enforce-gh-bot-identity.js and enforce-http-bot-identity.sh
# to generate a short-lived GitHub App installation access token. Requires a GitHub
# App PEM key and App ID configured in ~/.agentbridge/ or via env vars.
# =============================================================================
#
# generate-github-app-token.sh — Generate a GitHub App installation access token.
# See docs/BOT-IDENTITY-HOOKS.md for setup instructions (main repo vs fork, GitHub App vs PAT).
# These hooks are optional — safe to disable or delete locally.
#
# Requires: openssl, curl, base64 (all standard on macOS/Linux)
#
# Config (checked in order):
#   AGENTBRIDGE_APP_PEM  or  ~/.agentbridge/github-app.pem
#   AGENTBRIDGE_APP_ID   or  ~/.agentbridge/github-app-id
#
# Output: prints the installation access token to stdout.
# Exit 1 on failure with diagnostic on stderr.
set -e

AGENTBRIDGE_DIR="${HOME}/.agentbridge"
CACHE_FILE="${AGENTBRIDGE_DIR}/github-app-token-cache"
LOCK_DIR="${CACHE_FILE}.lock"
CACHE_TTL_SECONDS=3000

read_cached_token() {
    [ -r "$CACHE_FILE" ] || return 1
    cache_expires=$(sed -n '1p' "$CACHE_FILE")
    cache_token=$(sed -n '2p' "$CACHE_FILE")
    case "$cache_expires" in
        ''|*[!0-9]*) return 1 ;;
    esac
    [ "$(date +%s)" -lt "$cache_expires" ] && [ -n "$cache_token" ] || return 1
    printf '%s' "$cache_token"
}

if token=$(read_cached_token); then
    printf '%s' "$token"
    exit 0
fi

attempts=0
until mkdir "$LOCK_DIR" 2>/dev/null; do
    if token=$(read_cached_token); then
        printf '%s' "$token"
        exit 0
    fi
    attempts=$((attempts + 1))
    if [ "$attempts" -ge 15 ]; then
        echo "Error: Timed out waiting for GitHub App token cache" >&2
        exit 1
    fi
    sleep 1
done
trap 'rmdir "$LOCK_DIR"' EXIT HUP INT TERM

if token=$(read_cached_token); then
    printf '%s' "$token"
    exit 0
fi

# --- Resolve private key ---
pem_file="${AGENTBRIDGE_APP_PEM:-${AGENTBRIDGE_DIR}/github-app.pem}"
if [ ! -f "$pem_file" ]; then
    echo "Error: GitHub App private key not found at $pem_file" >&2
    exit 1
fi

# --- Resolve App ID ---
app_id="${AGENTBRIDGE_APP_ID:-}"
if [ -z "$app_id" ] && [ -f "${AGENTBRIDGE_DIR}/github-app-id" ]; then
    app_id=$(tr -d '[:space:]' < "${AGENTBRIDGE_DIR}/github-app-id")
fi
if [ -z "$app_id" ]; then
    echo "Error: GitHub App ID not configured (set AGENTBRIDGE_APP_ID or create ~/.agentbridge/github-app-id)" >&2
    exit 1
fi

# --- Base64url encode (POSIX-compatible) ---
b64url() {
    openssl base64 -A | tr '+/' '-_' | tr -d '='
}

# --- Build JWT ---
now=$(date +%s)
iat=$((now - 60))
exp=$((now + 300))

header=$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)
payload=$(printf '{"iss":"%s","iat":%d,"exp":%d}' "$app_id" "$iat" "$exp" | b64url)
unsigned="${header}.${payload}"

signature=$(printf '%s' "$unsigned" | openssl dgst -sha256 -sign "$pem_file" | b64url)
jwt="${unsigned}.${signature}"

# --- Get installation ID for this repo ---
# Try to detect repo from git remote
repo=""
if command -v git >/dev/null 2>&1; then
    remote_url=$(git remote get-url origin 2>/dev/null || true)
    case "$remote_url" in
        *github.com:*) repo=$(echo "$remote_url" | sed 's|.*github.com:||;s|\.git$||') ;;
        *github.com/*) repo=$(echo "$remote_url" | sed 's|.*github.com/||;s|\.git$||') ;;
    esac
fi

if [ -n "$repo" ]; then
    # Get installation for this specific repo
    install_resp=$(curl -s -H "Authorization: Bearer ${jwt}" \
        -H "Accept: application/vnd.github+json" \
        "https://api.github.com/repos/${repo}/installation" 2>/dev/null)
else
    # Fallback: get first installation
    install_resp=$(curl -s -H "Authorization: Bearer ${jwt}" \
        -H "Accept: application/vnd.github+json" \
        "https://api.github.com/app/installations" 2>/dev/null)
    # Extract first installation ID from array response
    install_resp=$(printf '%s' "$install_resp" | sed 's/\[//;s/\]//')
fi

install_id=$(printf '%s' "$install_resp" | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p' | head -1)

if [ -z "$install_id" ]; then
    echo "Error: Could not find installation ID. Response: $install_resp" >&2
    exit 1
fi

# --- Create installation access token ---
token_resp=$(curl -s -X POST \
    -H "Authorization: Bearer ${jwt}" \
    -H "Accept: application/vnd.github+json" \
    "https://api.github.com/app/installations/${install_id}/access_tokens" 2>/dev/null)

token=$(printf '%s' "$token_resp" | sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)

if [ -z "$token" ]; then
    echo "Error: Could not create installation token. Response: $token_resp" >&2
    exit 1
fi

umask 077
cache_temp="${CACHE_FILE}.$$"
printf '%s\n%s\n' "$(( $(date +%s) + CACHE_TTL_SECONDS ))" "$token" > "$cache_temp"
mv "$cache_temp" "$CACHE_FILE"
printf '%s' "$token"
