#!/bin/sh
# Pre-hook for run_command and run_in_terminal: intercepts GitHub CLI commands
# that create content (PRs, comments, issues) and enforces bot identity.
#
# Detected patterns:
#   gh pr create|comment|review|edit|merge|close
#   gh issue create|comment|edit|close
#   gh discussion create|comment
#   gh release create
#
# Token resolution (in order):
#   1. AGENTBRIDGE_BOT_TOKEN env var (static PAT)
#   2. ~/.agentbridge/bot-token file (static PAT)
#   3. GitHub App token via generate-github-app-token.sh (dynamic, preferred)
#
# ⚠️ AGENT INSTRUCTION: If this hook blocks a command, you MUST NOT retry
#    using built-in bash, run_in_terminal, or any other tool that bypasses
#    this check. Instead, stop and tell the user the bot token is not configured.
#
# Trigger: PRE
# Input:   JSON payload on stdin with arguments.command
# Output:  {"arguments":{"command":"GH_TOKEN=... <original>"}} or {"error":"..."}
. "${0%/*}/_lib.sh"
hook_read_payload

command=$(hook_get_arg command)
lcmd=$(printf '%s' "$command" | tr '[:upper:]' '[:lower:]')

# Check if this is a content-creating gh command
needs_bot=false
case "$lcmd" in
    *"gh pr create"*|*"gh pr comment"*|*"gh pr review"*|*"gh pr edit"*|*"gh pr merge"*|*"gh pr close"*) needs_bot=true ;;
    *"gh issue create"*|*"gh issue comment"*|*"gh issue edit"*|*"gh issue close"*) needs_bot=true ;;
    *"gh discussion create"*|*"gh discussion comment"*) needs_bot=true ;;
    *"gh release create"*) needs_bot=true ;;
    *"gh api "*)
        case "$lcmd" in
            *"-x post"*|*"-x patch"*|*"-x put"*|*"-x delete"*|*"-method post"*|*"-method patch"*|*"-method put"*|*"-method delete"*)
                needs_bot=true ;;
        esac ;;
esac

if [ "$needs_bot" = "false" ]; then
    exit 0
fi

# Resolve bot token: static PAT → file → GitHub App
bot_token="${AGENTBRIDGE_BOT_TOKEN:-}"

if [ -z "$bot_token" ] && [ -f "${HOME}/.agentbridge/bot-token" ]; then
    bot_token=$(tr -d '[:space:]' < "${HOME}/.agentbridge/bot-token")
fi

if [ -z "$bot_token" ]; then
    # Try GitHub App token generation
    script_dir="${0%/*}"
    if [ -x "${script_dir}/generate-github-app-token.sh" ]; then
        bot_token=$("${script_dir}/generate-github-app-token.sh" 2>/dev/null) || bot_token=""
    fi
fi

if [ -n "$bot_token" ]; then
    # Use Python for reliable JSON construction — shell escaping is fragile with
    # multiline commands containing quotes, backslashes, and special characters.
    _BOT_TOKEN="$bot_token" python3 -c "
import sys, json, os
cmd = os.environ.get('HOOK_ARG_command', '')
token = os.environ.get('_BOT_TOKEN', '')
modified = 'export GH_TOKEN=' + token + '; ' + cmd
print(json.dumps({'arguments': {'command': modified}}))
"
else
    hook_json_error "Identity policy: this command would post GitHub content (PR, comment, issue, etc.) as the repository owner, not as the Copilot bot. STOP — do NOT retry using built-in bash, run_in_terminal, or any other bypass. Instead, tell the user: 'I cannot create GitHub content with bot identity because neither AGENTBRIDGE_BOT_TOKEN, ~/.agentbridge/bot-token, nor a GitHub App private key (~/.agentbridge/github-app.pem) is configured.'"
fi
