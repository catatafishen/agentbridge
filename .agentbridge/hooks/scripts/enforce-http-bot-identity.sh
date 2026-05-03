#!/bin/sh
# Pre-hook for http_request: intercepts GitHub API calls that create/modify
# content and enforces bot identity via Authorization header.
#
# Token resolution (in order):
#   1. AGENTBRIDGE_BOT_TOKEN env var (static PAT)
#   2. ~/.agentbridge/bot-token file (static PAT)
#   3. GitHub App token via generate-github-app-token.sh (dynamic, preferred)
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
    POST|PATCH|PUT|DELETE)
        case "$url" in
            *api.github.com*|*github.com/api*) is_github_write=true ;;
        esac ;;
esac

if [ "$is_github_write" = "false" ]; then
    exit 0
fi

# Resolve bot token: static PAT → file → GitHub App
bot_token="${AGENTBRIDGE_BOT_TOKEN:-}"

if [ -z "$bot_token" ] && [ -f "${HOME}/.agentbridge/bot-token" ]; then
    bot_token=$(tr -d '[:space:]' < "${HOME}/.agentbridge/bot-token")
fi

if [ -z "$bot_token" ]; then
    script_dir="${0%/*}"
    if [ -x "${script_dir}/generate-github-app-token.sh" ]; then
        bot_token=$("${script_dir}/generate-github-app-token.sh" 2>/dev/null) || bot_token=""
    fi
fi

if [ -n "$bot_token" ]; then
    escaped_token=$(hook_escape_json "$bot_token")
    printf '{"arguments":{"auth":"bearer %s"}}\n' "$escaped_token"
else
    hook_json_error "Identity policy: this HTTP request would call the GitHub API as the repository owner. Configure one of: AGENTBRIDGE_BOT_TOKEN env var, ~/.agentbridge/bot-token file, or ~/.agentbridge/github-app.pem + github-app-id for GitHub App auth."
fi
