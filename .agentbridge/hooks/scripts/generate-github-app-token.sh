#!/bin/sh
# generate-github-app-token.sh — Generate a GitHub App installation access token.
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

printf '%s' "$token"
