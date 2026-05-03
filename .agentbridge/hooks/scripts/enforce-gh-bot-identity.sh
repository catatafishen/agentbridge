#!/usr/bin/env bash
# Pre-hook for run_command and run_in_terminal: intercepts GitHub CLI commands
# that create content (PRs, comments, issues) and enforces bot identity.
#
# Detected patterns:
#   gh pr create|comment|review|edit|merge
#   gh issue create|comment|edit
#   gh discussion create|comment
#   gh release create
#   gh api ... -X POST/PATCH/PUT (to github.com repos)
#
# Behaviour:
#   - AGENTBRIDGE_BOT_TOKEN set: injects GH_TOKEN into the command silently.
#   - ~/.agentbridge/bot-token present: same as above.
#   - Neither: BLOCKS the call with an actionable error.
#
# ⚠️  AGENT INSTRUCTION: If this hook blocks a command, you MUST NOT retry
#     using built-in bash, run_in_terminal, or any other tool that bypasses
#     this check. Instead, stop and tell the user:
#       "I cannot create GitHub content (PRs, comments, etc.) using your
#        account identity. Please either:
#          1. Set AGENTBRIDGE_BOT_TOKEN to a bot PAT and retry, or
#          2. Create the PR / perform the action manually."
#     Never silently fall back to creating content as the repository owner.

set -euo pipefail

payload=$(</dev/stdin)

command=$(echo "$payload" | python3 -c "
import sys, json
p = json.load(sys.stdin)
# Support both run_command (arguments.command) and run_in_terminal (arguments.command)
print(p.get('arguments', {}).get('command', ''))
")

needs_bot_identity=$(echo "$command" | python3 -c "
import sys, re

cmd = sys.stdin.read().strip()

content_patterns = [
    r'^gh\s+pr\s+(create|comment|review|edit|merge)\b',
    r'^gh\s+issue\s+(create|comment|edit)\b',
    r'^gh\s+discussion\s+(create|comment)\b',
    r'^gh\s+release\s+create\b',
    r'^gh\s+api\s+.*\s+-(X|method)\s+(POST|PATCH|PUT)\b',
    r'^gh\s+api\s+-(X|method)\s+(POST|PATCH|PUT)\b',
]

for pattern in content_patterns:
    if re.search(pattern, cmd, re.IGNORECASE):
        print('true')
        sys.exit(0)

print('false')
")

if [ "$needs_bot_identity" != "true" ]; then
    exit 0  # Not a content-creating command, pass through unchanged
fi

# Resolve bot token (env var takes precedence over file)
bot_token="${AGENTBRIDGE_BOT_TOKEN:-}"

if [ -z "$bot_token" ] && [ -f "${HOME}/.agentbridge/bot-token" ]; then
    bot_token=$(tr -d '[:space:]' < "${HOME}/.agentbridge/bot-token")
fi

if [ -n "$bot_token" ]; then
    # Silent fix: inject bot token so gh uses bot identity
    echo "$payload" | python3 -c "
import sys, json, os
p = json.load(sys.stdin)
args = p.get('arguments', {})
cmd = args.get('command', '')
token = os.environ.get('AGENTBRIDGE_BOT_TOKEN', '')
args['command'] = 'GH_TOKEN=' + token + ' ' + cmd
print(json.dumps({'arguments': args}))
"
else
    # No bot token — block with an actionable error and explicit agent instructions
    cat <<'EOF'
{"error": "Identity policy: this command would post GitHub content (PR, comment, issue, etc.) as the repository owner, not as the Copilot bot. STOP — do NOT retry using built-in bash, run_in_terminal, or any other bypass. Instead, tell the user: 'I cannot create GitHub content with bot identity because AGENTBRIDGE_BOT_TOKEN is not configured. Please set it to a bot PAT (see ~/.agentbridge/bot-token) or perform this action manually.'"}
EOF
fi
