#!/bin/sh
# Pre-hook for run_command and run_in_terminal: intercepts GitHub CLI commands
# that create content (PRs, comments, issues) and enforces bot identity.
#
# Detected patterns:
#   gh pr create|comment|review|edit|merge
#   gh issue create|comment|edit
#   gh discussion create|comment
#   gh release create
#
# Behaviour:
#   - AGENTBRIDGE_BOT_TOKEN set or ~/.agentbridge/bot-token exists:
#     injects GH_TOKEN into the command silently (merge semantics).
#   - Neither: BLOCKS the call with an actionable error.
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
    "gh pr create"*|"gh pr comment"*|"gh pr review"*|"gh pr edit"*|"gh pr merge"*) needs_bot=true ;;
    "gh issue create"*|"gh issue comment"*|"gh issue edit"*) needs_bot=true ;;
    "gh discussion create"*|"gh discussion comment"*) needs_bot=true ;;
    "gh release create"*) needs_bot=true ;;
    "gh api "*)
        case "$lcmd" in
            *"-x post"*|*"-x patch"*|*"-x put"*|*"-method post"*|*"-method patch"*|*"-method put"*)
                needs_bot=true ;;
        esac ;;
esac

if [ "$needs_bot" = "false" ]; then
    exit 0
fi

# Resolve bot token (env var takes precedence over file)
bot_token="${AGENTBRIDGE_BOT_TOKEN:-}"

if [ -z "$bot_token" ] && [ -f "${HOME}/.agentbridge/bot-token" ]; then
    bot_token=$(tr -d '[:space:]' < "${HOME}/.agentbridge/bot-token")
fi

if [ -n "$bot_token" ]; then
    # Escape the command for JSON embedding, then prefix with GH_TOKEN
    escaped_cmd=$(hook_escape_json "GH_TOKEN=${bot_token} ${command}")
    printf '{"arguments":{"command":"%s"}}\n' "$escaped_cmd"
else
    hook_json_error "Identity policy: this command would post GitHub content (PR, comment, issue, etc.) as the repository owner, not as the Copilot bot. STOP — do NOT retry using built-in bash, run_in_terminal, or any other bypass. Instead, tell the user: 'I cannot create GitHub content with bot identity because AGENTBRIDGE_BOT_TOKEN is not configured. Please set it to a bot PAT (see ~/.agentbridge/bot-token) or perform this action manually.'"
fi
