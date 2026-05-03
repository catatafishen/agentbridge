#!/bin/sh
# Pre-hook for http_request: intercepts GitHub API calls that create/modify
# content and enforces bot identity via Authorization header.
#
# Silent fix: if AGENTBRIDGE_BOT_TOKEN is set, injects/replaces the auth header.
# Error: if no bot token is available, blocks the call with an actionable error.
#
# Detected patterns:
#   POST/PATCH/PUT to api.github.com (or github.com/api)
#
# Trigger: PRE
# Input:   JSON payload on stdin with arguments.url, arguments.method
# Output:  {"arguments":{"auth":"bearer <token>"}} or {"error":"..."}
. "${0%/*}/_lib.sh"
hook_read_payload

url=$(hook_get_arg url)
method=$(hook_get_arg method)
method=$(printf '%s' "${method:-GET}" | tr '[:lower:]' '[:upper:]')

# Only intercept write methods to GitHub API
is_github_write=false
case "$method" in
    POST|PATCH|PUT)
        case "$url" in
            *api.github.com*|*github.com/api*) is_github_write=true ;;
        esac ;;
esac

if [ "$is_github_write" = "false" ]; then
    exit 0
fi

# Resolve bot token
bot_token="${AGENTBRIDGE_BOT_TOKEN:-}"

if [ -z "$bot_token" ] && [ -f "${HOME}/.agentbridge/bot-token" ]; then
    bot_token=$(tr -d '[:space:]' < "${HOME}/.agentbridge/bot-token")
fi

if [ -n "$bot_token" ]; then
    escaped_token=$(hook_escape_json "$bot_token")
    printf '{"arguments":{"auth":"bearer %s"}}\n' "$escaped_token"
else
    hook_json_error "Identity policy: this HTTP request would call the GitHub API as the repository owner. Set AGENTBRIDGE_BOT_TOKEN or create ~/.agentbridge/bot-token with a bot PAT."
fi
